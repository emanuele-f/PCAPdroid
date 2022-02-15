/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2022 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LruCache;

import com.emanuelef.remote_capture.activities.MitmSetupWizard;
import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.interfaces.MitmListener;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;

import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

/* An experimental receiver for the TLS decryption plaintext.
 *
 * A mitmproxy plugin sends TCP messages on port 5750, containing an header and the plaintext.
 *
 * The header is an ASCII string in the following format:
 *   "port:payload_type:payload_length\n"
 * - port: the TCP local port used by the SOCKS5 client
 * - payload_type: http_req|http_rep|ws_climsg|ws_srvmsg
 * - payload_length: the payload length in bytes:
 *
 * The raw payload data follows the header.
 */
public class MitmReceiver implements Runnable, ConnectionsListener, MitmListener {
    private static final String TAG = "MitmReceiver";
    public static final int MAX_PLAINTEXT_LENGTH = 1024; // sync with pcapdroid.h
    public static final int TLS_DECRYPTION_PROXY_PORT = 7780;
    private Thread mThread;
    private final ConnectionsRegister mReg;
    private final Context mContext;
    private final MitmAddon mAddon;
    private ParcelFileDescriptor mSocketFd;

    // Shared state
    private final LruCache<Integer, Integer> mPortToConnId = new LruCache<>(64);

    public MitmReceiver(Context ctx) {
        mContext = ctx;
        mReg = CaptureService.requireConnsRegister();
        mAddon = new MitmAddon(mContext, this);
    }

    public boolean start() throws IOException {
        Log.d(TAG, "starting");

        Intent intent = new Intent();
        intent.setComponent(new ComponentName(MitmAddon.PACKAGE_NAME, MitmAddon.MITM_SERVICE));

        if(!mAddon.connect(Context.BIND_IMPORTANT)) {
            Utils.showToastLong(mContext, R.string.mitm_start_failed);
            return false;
        }

        mReg.addListener(this);
        return true;
    }

    public void stop() throws IOException {
        Log.d(TAG, "stopping");

        mReg.removeListener(this);

        ParcelFileDescriptor fd = mSocketFd;
        mSocketFd = null;
        Utils.safeClose(fd); // possibly wake mThread

        while((mThread != null) && (mThread.isAlive())) {
            try {
                Log.d(TAG, "Joining receiver thread...");
                mThread.join();
            } catch (InterruptedException ignored) {}
        }
        mThread = null;

        mAddon.disconnect();

        Log.d(TAG, "stop done");
    }

    @Override
    public void run() {
        Log.d(TAG, "Receiving data...");

        try(DataInputStream istream = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(mSocketFd))) {
            while(mAddon.isConnected()) {
                String payload_type;
                int port;
                int payload_len;

                // Read the header
                String header = istream.readLine();
                if(header == null)
                    break;

                StringTokenizer tk = new StringTokenizer(header);
                //Log.d(TAG, "[HEADER] " + header);

                try {
                    // port:payload_type:payload_length\n
                    String tk_port = tk.nextToken(":");
                    payload_type = tk.nextToken();
                    String tk_len = tk.nextToken();

                    port = Integer.parseInt(tk_port);
                    payload_len = Integer.parseInt(tk_len);
                } catch (NoSuchElementException | NumberFormatException e) {
                    Log.w(TAG, "Invalid header");
                    return;
                }

                if((payload_len <= 0) || (payload_len > 1048576)) { /* max 1 MB */
                    Log.w(TAG, "Bad payload length: " + payload_len);
                    return;
                }

                if(payload_type.equals("http_req")) {
                    byte[] payload = new byte[payload_len];
                    istream.readFully(payload);

                    //Log.d(TAG, "HTTP_REQUEST [" + payload_len + "]");

                    ConnectionDescriptor conn = getConnByLocalPort(port);
                    if((conn != null) && (conn.l7proto.equals("TLS")) && (conn.request_plaintext.isEmpty())) {
                        // NOTE: we are accessing conn concurrently, however request_plaintext is
                        // never set inline for encrypted flows.
                        conn.request_plaintext = getPlaintextString(payload);
                    }
                } else
                    istream.skipBytes(payload_len); // ignore for now
            }
        } catch (IOException e) {
            if(mSocketFd != null) // ignore termination
                e.printStackTrace();
        }

        Log.d(TAG, "End receiving data");
    }

    @Override
    public void connectionsChanges(int num_connetions) {}
    @Override
    public void connectionsRemoved(int start, ConnectionDescriptor[] conns) {}
    @Override
    public void connectionsUpdated(int[] positions) {}

    @Override
    public void connectionsAdded(int start, ConnectionDescriptor[] conns) {
        synchronized(mPortToConnId) {
            // Save the latest port->ID mapping
            for(ConnectionDescriptor conn: conns)
                mPortToConnId.put(conn.local_port, conn.incr_id);
        }
    }

    @Override
    public void onMitmServiceConnect() {
        // when connected, verify that the certificate is installed before starting the proxy.
        // will continue on onMitmGetCaCertificateResult.
        if(!mAddon.requestCaCertificate())
            mAddon.disconnect();
    }

    @Override
    public void onMitmGetCaCertificateResult(@Nullable String ca_pem) {
        if(!Utils.isCAInstalled(ca_pem)) {
            // The certificate has been uninstalled from the system
            Utils.showToastLong(mContext, R.string.cert_reinstall_required);
            MitmAddon.setDecryptionSetupDone(mContext, false);
            CaptureService.stopService();
            return;
        }

        // Certificate installation verified, start the proxy
        mSocketFd = mAddon.startProxy(TLS_DECRYPTION_PROXY_PORT);
        if(mSocketFd == null) {
            mAddon.disconnect();
            return;
        }

        if(mThread != null)
            mThread.interrupt();

        mThread = new Thread(MitmReceiver.this);
        mThread.start();
    }

    @Override
    public void onMitmServiceDisconnect() {
        Utils.safeClose(mSocketFd);
        mSocketFd = null;

        // Stop the capture if running
        CaptureService.stopService();
    }

    ConnectionDescriptor getConnByLocalPort(int local_port) {
        Integer conn_id;

        synchronized(mPortToConnId) {
            conn_id = mPortToConnId.get(local_port);
        }
        if(conn_id == null)
            return null;

        ConnectionDescriptor conn = mReg.getConnById(conn_id);
        if((conn == null) || (conn.local_port != local_port))
            return null;

        // success
        return conn;
    }

    // sync with pcapdroid.c
    private boolean is_plaintext(byte c) {
        return ((c >= 32) && (c <= 126)) || (c == '\r') || (c == '\n') || (c == '\t');
    }

    private String getPlaintextString(byte []bytes) {
        int i = 0;
        int limit = Math.min(bytes.length, MAX_PLAINTEXT_LENGTH);

        while(i < limit) {
            if(!is_plaintext(bytes[i]))
                break;
            i++;
        }

        return new String(bytes, 0, i, StandardCharsets.US_ASCII);
    }
}
