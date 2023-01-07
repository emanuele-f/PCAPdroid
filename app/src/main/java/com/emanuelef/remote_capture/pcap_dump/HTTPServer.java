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
 * Copyright 2020-22 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.pcap_dump;

import android.content.Context;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.interfaces.PcapDumper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/*
 * A simple HTTP server which allows clients to download the PCAP dump over HTTP.
 */
public class HTTPServer implements PcapDumper, Runnable {
    private static final String TAG = "HTTPServer";
    private static final String PCAP_MIME = "application/vnd.tcpdump.pcap";
    private static final String PCAPNG_MIME = "application/x-pcapng";
    public static final int MAX_CLIENTS = 8;
    private ServerSocket mSocket;
    private boolean mRunning;
    private Thread mThread;
    private final int mPort;
    private final boolean mPcapngFormat;
    private final String mMimeType;
    private final Context mContext;

    // Shared state, must be synchronized
    private final ArrayList<ClientHandler> mClients = new ArrayList<>();

    public HTTPServer(Context context, int port, boolean pcapng_format) {
        mPort = port;
        mContext = context;
        mPcapngFormat = pcapng_format;
        mMimeType = pcapng_format ? PCAPNG_MIME : PCAP_MIME;
    }

    private static class ChunkedOutputStream extends FilterOutputStream {
        public ChunkedOutputStream(OutputStream out) throws IOException {
            super(out);
        }

        @Override
        public void write(byte[] data) throws IOException {
            // Chunked transfer coding
            // https://datatracker.ietf.org/doc/html/rfc2616#section-3.6.1
            out.write(String.format("%x\r\n", data.length).getBytes());
            out.write(data);
            out.write("\r\n".getBytes());
            out.flush();
        }

        public void finish() throws IOException {
            // Chunked transfer termination
            out.write("0\r\n\r\n".getBytes());
        }
    }

    /* Handles a single HTTP client. The normal workflow is:
     *  1. if isReadyForData then sendChunk
     *  2. if isClosed then remove this client
     *
     * No need for synchronization because sendChunk is only called when the runnable has terminated
     * (see isReadyForData).
     */
    private static class ClientHandler implements Runnable {
        static final int INPUT_BUFSIZE = 1024;
        Socket mSocket;
        final InputStream mInputStream;
        final OutputStream mOutputStream;
        final String mFname;
        final String mMimeType;
        ChunkedOutputStream mChunkedOutputStream;
        boolean mHasError;
        boolean mReadyForData;
        boolean mHeaderSent;
        boolean mIsClosed;

        public ClientHandler(Socket socket, String mimeType, String fname) throws IOException {
            mSocket = socket;
            mFname = fname;
            mInputStream = mSocket.getInputStream();
            mOutputStream = mSocket.getOutputStream();
            mMimeType = mimeType;
        }

        private void close(String error) {
            if(isClosed())
                return;

            if(error != null) {
                Log.i(TAG, "Client error: " + error);
                mHasError = true;
            } else if (mReadyForData) {
                try {
                    // Terminate the chunked stream
                    mChunkedOutputStream.finish();
                } catch (IOException ignored) {}
            }

            Utils.safeClose(mChunkedOutputStream);
            Utils.safeClose(mOutputStream);
            Utils.safeClose(mInputStream);
            Utils.safeClose(mSocket);
            mIsClosed = true;
        }

        public void stop() {
            // if running, will trigger a IOException
            Utils.safeClose(mSocket);
        }

        @Override
        public void run() {
            byte[] buf = new byte[INPUT_BUFSIZE];
            int sofar = 0;
            int req_size = 0;

            try {
                while(req_size <= 0) {
                    sofar += mInputStream.read(buf, sofar, buf.length - sofar);
                    req_size = Utils.getEndOfHTTPHeaders(buf);
                }

                Log.d(TAG, "Request headers end at " + req_size);
                //Log.d(TAG, "Req: " + new String(buf, 0, req_size, StandardCharsets.UTF_8));

                try(BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(buf, 0, req_size)))) {
                    String line = reader.readLine();
                    if(line == null) {
                        close("Bad request");
                        return;
                    }

                    StringTokenizer tk = new StringTokenizer(line);
                    String method = tk.nextToken();
                    String url = tk.nextToken();

                    if(!method.equals("GET")) {
                        close("Bad request method");
                        return;
                    }

                    if(url.equals("/")) {
                        redirectToPcap();
                        close(null);
                    } else {
                        Log.d(TAG, "URL: " + url);

                        // NOTE: compressing with gzip is almost useless as most HTTP data is already
                        // gzip-compressed
                        mOutputStream.write(("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: " + mMimeType + "\r\n" +
                                "Connection: close\r\n" +
                                "Transfer-Encoding: chunked\r\n" +
                                "\r\n"
                        ).getBytes());
                        mOutputStream.flush();

                        Log.d(TAG, "Ready for data");
                        mChunkedOutputStream = new ChunkedOutputStream(mOutputStream);
                        mReadyForData = true;
                    }
                }
            } catch (IOException | NoSuchElementException e) {
                close(e.getLocalizedMessage());
            }
        }

        /* Sends a 302 redirect to allow saving the PCAP file with a specific name */
        private void redirectToPcap() throws IOException {
            Log.d(TAG, "Redirecting to PCAP: " + mFname);

            mOutputStream.write(("HTTP/1.1 302 Found\r\n" +
                    "Location: /" + mFname + "\r\n" +
                    "\r\n"
            ).getBytes());
        }

        // Returns true if the client socket is closed
        public boolean isClosed() {
            return mIsClosed;
        }

        public boolean isReadyForData() {
            return mReadyForData;
        }

        // Send a chunk of data
        public void sendChunk(byte []data) {
            try {
                if(!mHeaderSent) {
                    mChunkedOutputStream.write(CaptureService.getPcapHeader());
                    mHeaderSent = true;
                }

                //Log.d(TAG, "+CHUNK [" + data.length + "]");
                mChunkedOutputStream.write(data);
            } catch (IOException e) {
                close(e.getLocalizedMessage());
            }
        }
    }

    @Override
    public void startDumper() throws IOException {
        mSocket = new ServerSocket();
        mSocket.setReuseAddress(true);
        mSocket.bind(new InetSocketAddress(mPort));

        mRunning = true;
        mThread = new Thread(this);
        mThread.start();
    }

    @Override
    public void run() {
        // NOTE: threads only handle the initial client communication.
        // After isReadyForData, clients are handled in dumpData.
        ExecutorService pool = Executors.newFixedThreadPool(MAX_CLIENTS);

        while(mRunning) {
            try {
                Socket client = mSocket.accept();

                synchronized(this) {
                    if(mClients.size() >= MAX_CLIENTS) {
                        Log.w(TAG, "Clients limit reached");
                        Utils.safeClose(client);
                        continue;
                    }
                }

                Log.i(TAG, "New client: " + client.getInetAddress().getHostAddress() + ":" + client.getPort());
                ClientHandler handler = new ClientHandler(client, mMimeType, Utils.getUniquePcapFileName(mContext, mPcapngFormat));

                try {
                    // will fail if pool is full
                    pool.submit(handler);

                    synchronized(this) {
                      mClients.add(handler);
                    }
                } catch (RejectedExecutionException e) {
                    Log.w(TAG, e.getLocalizedMessage());
                    Utils.safeClose(client);
                }
            } catch (IOException e) {
                if(!mRunning)
                    Log.d(TAG, "Got termination request");
                else
                    Log.d(TAG, e.getLocalizedMessage());
            }
        }

        Utils.safeClose(mSocket);

        // Terminate the running clients threads
        pool.shutdown();
        synchronized(this) {
            // Possibly wake clients blocked on read
            for(ClientHandler client: mClients) {
                if(!client.isReadyForData())
                    client.stop();
            }
        }
        while(true) {
            try {
                if(pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS))
                    break;
            } catch (InterruptedException ignored) {}
        }

        // Close the clients
        synchronized(this) {
            for(ClientHandler client: mClients) {
                if(!client.isClosed())
                    client.close(null);
            }

            mClients.clear();
        }
    }

    @Override
    public void stopDumper() throws IOException {
        mRunning = false;

        // Generate a socket exception
        mSocket.close();

        while((mThread != null) && (mThread.isAlive())) {
            try {
                Log.d(TAG, "Joining HTTP thread...");
                mThread.join();
            } catch (InterruptedException ignored) {}
        }
    }

    @Override
    public String getBpf() {
        return "not (host " + Utils.getLocalIPAddress(mContext) + " and tcp port " + mPort + ")";
    }

    @Override
    public void dumpData(byte[] data) throws IOException {
        synchronized(this) {
            Iterator<ClientHandler> it = mClients.iterator();

            while(it.hasNext()) {
                ClientHandler client = it.next();

                if(client.isReadyForData())
                    client.sendChunk(data);

                if(client.isClosed()) {
                    it.remove();
                    Log.d(TAG, "Client closed, active clients: " + mClients.size());
                }
            }
        }
    }
}
