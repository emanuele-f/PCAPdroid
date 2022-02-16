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
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import android.util.SparseArray;

import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.interfaces.MitmListener;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;

import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private final SparseArray<PendingPayload> mPendingPayloads = new SparseArray<>();

    private enum PayloadType {
        UNKNOWN,
        TLS_ERROR,
        HTTP_REQUEST,
        HTTP_REPLY,
        TCP_CLIENT_MSG,
        TCP_SERVER_MSG,
        WEBSOCKET_CLIENT_MSG,
        WEBSOCKET_SERVER_MSG,
    }

    private static class PendingPayload {
        PayloadType pType;
        byte[] payload;
        int port;
        long pendingSince;

        PendingPayload(PayloadType _pType, byte[] _payload, int _port) {
            pType = _pType;
            payload = _payload;
            port = _port;
            pendingSince = SystemClock.uptimeMillis();
        }
    }

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

        // on some devices, calling close on the socket is not enough to stop the thread,
        // the service must be unbound
        mAddon.disconnect();

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

                PayloadType pType = parsePayloadType(payload_type);

                if((pType == PayloadType.HTTP_REQUEST)
                        || (pType == PayloadType.TCP_CLIENT_MSG)
                        || (pType == PayloadType.TLS_ERROR)) {
                    byte[] payload = new byte[payload_len];
                    istream.readFully(payload);

                    ConnectionDescriptor conn = getConnByLocalPort(port);
                    //Log.d(TAG, "PAYLOAD." + pType.name() + "[" + payload_len + " B]: port=" + port + ", match=" + (conn != null));

                    if(conn != null)
                        handlePayload(conn, pType, payload);
                    else
                        // We may receive a payload before seeing the connection in connectionsAdded
                        addPendingPayload(new PendingPayload(pType, payload, port));
                } else
                    istream.skipBytes(payload_len); // ignore for now
            }
        } catch (IOException e) {
            if(mSocketFd != null) // ignore termination
                e.printStackTrace();
        }

        Log.d(TAG, "End receiving data");
    }

    private void handlePayload(ConnectionDescriptor conn, PayloadType pType, byte[] payload) {
        // NOTE: we are possibly accessing the conn concurrently
        if(((pType == PayloadType.HTTP_REQUEST) || (pType == PayloadType.TCP_CLIENT_MSG)) && conn.request_plaintext.isEmpty())
            conn.request_plaintext = getPlaintextString(payload);
        else if(pType == PayloadType.TLS_ERROR) {
            conn.tls_error = new String(payload, StandardCharsets.US_ASCII);

            // see ConnectionDescriptor.processUpdate
            if(conn.status == ConnectionDescriptor.CONN_STATUS_CLOSED)
                conn.status = ConnectionDescriptor.CONN_STATUS_CLIENT_ERROR;
        }
    }

    private synchronized void addPendingPayload(PendingPayload pending) {
        // Purge unresolved connections (should not happen, just in case)
        if(mPendingPayloads.size() > 32) {
            long now = SystemClock.uptimeMillis();

            for(int i=mPendingPayloads.size()-1; i>=0; i--) {
                PendingPayload pp = mPendingPayloads.valueAt(i);

                if((now - pp.pendingSince) > 5000 /* 5 sec */) {
                    Log.d(TAG, "Dropping old payload: " + pp.pType.name());
                    mPendingPayloads.remove(pp.port);
                }
            }
        }

        // TODO: concatenate the payload to the existing entry
        if(mPendingPayloads.indexOfKey(pending.port) < 0)
            mPendingPayloads.put(pending.port, pending);
    }

    private static PayloadType parsePayloadType(String str) {
        switch (str) {
            case "tls_err":
                return PayloadType.TLS_ERROR;
            case "http_req":
                return PayloadType.HTTP_REQUEST;
            case "http_rep":
                return PayloadType.HTTP_REPLY;
            case "tcp_climsg":
                return PayloadType.TCP_CLIENT_MSG;
            case "tcp_srvmsg":
                return PayloadType.TCP_SERVER_MSG;
            case "ws_climsg":
                return PayloadType.WEBSOCKET_CLIENT_MSG;
            case "ws_srvmsg":
                return PayloadType.WEBSOCKET_SERVER_MSG;
            default:
                return PayloadType.UNKNOWN;
        }
    }

    @Override
    public void connectionsChanges(int num_connetions) {}
    @Override
    public void connectionsRemoved(int start, ConnectionDescriptor[] conns) {}
    @Override
    public void connectionsUpdated(int[] positions) {}

    @Override
    public void connectionsAdded(int start, ConnectionDescriptor[] conns) {
        synchronized(this) {
            // Save the latest port->ID mapping
            for(ConnectionDescriptor conn: conns) {
                //Log.d(TAG, "[+] port " + conn.local_port);
                mPortToConnId.put(conn.local_port, conn.incr_id);

                // Check if the payload has already been received
                int pending_idx = mPendingPayloads.indexOfKey(conn.local_port);
                if(pending_idx >= 0) {
                    PendingPayload pending = mPendingPayloads.valueAt(pending_idx);
                    mPendingPayloads.removeAt(pending_idx);

                    //Log.d(TAG, "(pending) PAYLOAD." + pending.pType.name() + "[" + pending.payload.length + " B]: port=" + pending.port);

                    handlePayload(conn, pending.pType, pending.payload);
                }
            }
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
        // Stop the capture if running, CaptureService will call MitmReceiver::stop
        CaptureService.stopService();
    }

    ConnectionDescriptor getConnByLocalPort(int local_port) {
        Integer conn_id;

        synchronized(this) {
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
