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

import android.util.Log;
import android.util.LruCache;

import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
public class PlaintextReceiver implements Runnable, ConnectionsListener {
    private static final String TAG = "PlaintextReceiver";
    public static final int MAX_PLAINTEXT_LENGTH = 1024; // sync with pcapdroid.h
    private ServerSocket mSocket;
    private Socket mClient;
    private Thread mThread;
    private boolean mRunning;
    private final ConnectionsRegister mReg;

    // Shared state
    private final LruCache<Integer, Integer> mPortToConnId = new LruCache<>(64);

    public PlaintextReceiver() {
        mReg = CaptureService.requireConnsRegister();
    }

    public void start() throws IOException {
        mSocket = new ServerSocket();
        mSocket.setReuseAddress(true);
        mSocket.bind(new InetSocketAddress(5750), 1);

        mRunning = true;
        mThread = new Thread(this);
        mThread.start();

        mReg.addListener(this);
    }

    public void stop() throws IOException {
        mRunning = false;
        mReg.removeListener(this);

        // Possibly generate a socket exception on the thread
        mSocket.close();
        if(mClient != null)
            mClient.close();

        while((mThread != null) && (mThread.isAlive())) {
            try {
                Log.d(TAG, "Joining receiver thread...");
                mThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Joining receiver thread failed");
            }
        }
    }

    @Override
    public void run() {
        while(mRunning) {
            try {
                mClient = mSocket.accept();
                Log.d(TAG, String.format("Client: %s:%d", mClient.getInetAddress().getHostAddress(), mClient.getPort()));
                handleClient();
            } catch (IOException e) {
                if(!mRunning)
                    Log.d(TAG, "Got termination request");
                else
                    Log.d(TAG, e.getLocalizedMessage());
            }
        }

        try {
            if(mClient != null)
                mClient.close();
        } catch (IOException ignored) {}
        mClient = null;
    }

    private void handleClient() throws IOException {
        try(DataInputStream istream = new DataInputStream(mClient.getInputStream())) {
            while(mRunning) {
                String payload_type;
                int port;
                int payload_len;

                // Read the header
                String header = istream.readLine();
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
        synchronized(mPortToConnId) {
            // Save the latest port->ID mapping
            for(ConnectionDescriptor conn: conns)
                mPortToConnId.put(conn.local_port, conn.incr_id);
        }
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
