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
 * Copyright 2022-24 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.LruCache;
import android.util.SparseArray;

import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.interfaces.MitmListener;
import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.PayloadChunk;
import com.emanuelef.remote_capture.model.PayloadChunk.ChunkType;
import com.emanuelef.remote_capture.model.Prefs;
import com.pcapdroid.mitm.MitmAPI;

import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

/* A receiver for the mitm addon messages.
 *
 * The mitm addon sends TCP messages via a socket, containing an header and the plaintext.
 *
 * The header is an ASCII string in the following format:
 *   "timestamp:port:msg_type:msg_length\n"
 * - timestamp: milliseconds timestamp for the message
 * - port: the TCP local port used by the SOCKS5 client
 * - msg_type: type of message, see parseMsgType for possible values
 * - msg_length: the message length in bytes
 *
 * The raw message data follows the header.
 */
public class MitmReceiver implements Runnable, ConnectionsListener, MitmListener {
    private static final String TAG = "MitmReceiver";
    public static final int TLS_DECRYPTION_PROXY_PORT = 7780;
    private Thread mThread;
    private final ConnectionsRegister mReg;
    private final Context mContext;
    private final MitmAddon mAddon;
    private final MitmAPI.MitmConfig mConfig;
    private final boolean mPcapngFormat;
    private static final MutableLiveData<Status> proxyStatus = new MutableLiveData<>(Status.NOT_STARTED);
    private ParcelFileDescriptor mSocketFd;
    private BufferedOutputStream mKeylog;

    // Shared state
    private final LruCache<Integer, Integer> mPortToConnId = new LruCache<>(64);
    private final SparseArray<ArrayList<PendingMessage>> mPendingMessages = new SparseArray<>();

    private enum MsgType {
        UNKNOWN,
        RUNNING,
        TLS_ERROR,
        HTTP_ERROR,
        HTTP_REQUEST,
        HTTP_REPLY,
        TCP_CLIENT_MSG,
        TCP_SERVER_MSG,
        TCP_ERROR,
        WEBSOCKET_CLIENT_MSG,
        WEBSOCKET_SERVER_MSG,
        DATA_TRUNCATED,
        MASTER_SECRET,
        LOG,
        JS_INJECTED
    }

    private static class PendingMessage {
        MsgType type;
        byte[] msg;
        int port;
        long pendingSince;
        long when;

        PendingMessage(MsgType _type, byte[] _msg, int _port, long _now) {
            type = _type;
            msg = _msg;
            port = _port;
            pendingSince = SystemClock.elapsedRealtime();
            when = _now;
        }
    }

    public enum Status {
        NOT_STARTED,
        STARTING,
        START_ERROR,
        RUNNING
    }

    public MitmReceiver(Context ctx, CaptureSettings settings, String proxyAuth) {
        mContext = ctx;
        mReg = CaptureService.requireConnsRegister();
        mAddon = new MitmAddon(mContext, this);
        mPcapngFormat = settings.pcapng_format;

        mConfig = new MitmAPI.MitmConfig();
        mConfig.proxyPort = TLS_DECRYPTION_PROXY_PORT;
        mConfig.proxyAuth = proxyAuth;
        mConfig.dumpMasterSecrets = (CaptureService.getDumpMode() != Prefs.DumpMode.NONE);
        mConfig.additionalOptions = settings.mitmproxy_opts;
        mConfig.shortPayload = !settings.full_payload;

        /* upstream certificate verification is disabled because the app does not provide a way to let the user
           accept a given cert. Moreover, it provides a workaround for a bug with HTTPS proxies described in
           https://github.com/mitmproxy/mitmproxy/issues/5109 */
        mConfig.sslInsecure = true;

        // root capture uses transparent mode (redirection via iptables)
        mConfig.transparentMode = settings.root_capture;

        //noinspection ResultOfMethodCallIgnored
        getKeylogFilePath(mContext).delete();
    }

    public static File getKeylogFilePath(Context ctx) {
        return new File(ctx.getCacheDir(), "SSLKEYLOG.txt");
    }

    public boolean start() throws IOException {
        Log.d(TAG, "starting");
        proxyStatus.postValue(Status.STARTING);

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

        // send explicit stop message, as the addon may not be waked when the fd is closed
        mAddon.stopProxy();

        // on some devices, calling close on the socket is not enough to stop the thread,
        // the service must be unbound
        mAddon.disconnect();

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
        if(mSocketFd == null) {
            Log.e(TAG, "Null socket, abort");
            proxyStatus.postValue(Status.NOT_STARTED);
            return;
        }

        Log.i(TAG, "Receiving data...");
        try(DataInputStream istream = new DataInputStream(new ParcelFileDescriptor.AutoCloseInputStream(mSocketFd))) {
            while(mAddon.isConnected()) {
                String msg_type;
                int port;
                int msg_len;
                long tstamp;

                // Read the header
                @SuppressWarnings("deprecation")
                String header = istream.readLine();

                if(header == null) {
                    // received when the addon is stopped
                    CaptureService.stopService();
                    break;
                }

                StringTokenizer tk = new StringTokenizer(header);
                //Log.d(TAG, "[HEADER] " + header);

                try {
                    // timestamp:port:msg_type:msg_length\n
                    String tk_tstamp = tk.nextToken(":");
                    String tk_port = tk.nextToken();
                    msg_type = tk.nextToken();
                    String tk_len = tk.nextToken();

                    tstamp = Long.parseLong(tk_tstamp);
                    port = Integer.parseInt(tk_port);
                    msg_len = Integer.parseInt(tk_len);
                } catch (NoSuchElementException | NumberFormatException e) {
                    CaptureService.requireInstance().reportError("[BUG] Invalid header received from the mitm plugin");
                    CaptureService.stopService();
                    break;
                }

                if((msg_len < 0) || (msg_len > 67108864)) { /* max 64 MB */
                    Log.w(TAG, "Ignoring bad message length: " + msg_len);
                    istream.skipBytes(msg_len);
                    continue;
                }

                MsgType type = parseMsgType(msg_type);
                //Log.d(TAG, "MSG." + type.name() + "[" + msg_len + " B]: port=" + port);

                byte[] msg;
                try {
                    msg = new byte[msg_len];
                } catch (OutOfMemoryError ignored) {
                    Log.w(TAG, "Ignoring message causing OOM (length: " + msg_len + ")");
                    istream.skipBytes(msg_len);
                    continue;
                }
                istream.readFully(msg);

                if(type == MsgType.MASTER_SECRET)
                    logMasterSecret(msg);
                else if(type == MsgType.LOG) {
                    handleLog(msg);
                } else if(type == MsgType.RUNNING) {
                    Log.i(TAG, "MITM proxy is running");
                    proxyStatus.postValue(Status.RUNNING);
                } else {
                    ConnectionDescriptor conn = getConnByLocalPort(port);
                    //Log.d(TAG, "MSG." + type.name() + "[" + msg_len + " B]: port=" + port + ", match=" + (conn != null));

                    if(conn != null)
                        handleMessage(conn, type, msg, tstamp);
                    else
                        // We may receive a message before seeing the connection in connectionsAdded
                        addPendingMessage(new PendingMessage(type, msg, port, tstamp));
                }
            }
        } catch (IOException e) {
            if(mSocketFd != null) // ignore termination
                e.printStackTrace();
        } finally {
            Utils.safeClose(mKeylog);
            mKeylog = null;
        }

        if(proxyStatus.getValue() == Status.STARTING)
            proxyStatus.postValue(Status.START_ERROR);
        else
            proxyStatus.postValue(Status.NOT_STARTED);

        Log.i(TAG, "End receiving data");
    }

    private boolean isSent(MsgType type) {
        switch (type) {
            case HTTP_REQUEST:
            case TCP_CLIENT_MSG:
            case WEBSOCKET_CLIENT_MSG:
                return true;
            default:
                return false;
        }
    }

    private ChunkType getChunkType(MsgType type) {
        switch (type) {
            case HTTP_REQUEST:
            case HTTP_REPLY:
                return ChunkType.HTTP;
            case WEBSOCKET_CLIENT_MSG:
            case WEBSOCKET_SERVER_MSG:
                return ChunkType.WEBSOCKET;
            default:
                return ChunkType.RAW;
        }
    }

    private void handleMessage(ConnectionDescriptor conn, MsgType type, byte[] message, long tstamp) {
        // NOTE: we are possibly accessing the conn concurrently
        if((type == MsgType.TLS_ERROR) || (type == MsgType.HTTP_ERROR) || (type == MsgType.TCP_ERROR)) {
            conn.decryption_error = new String(message, StandardCharsets.US_ASCII);

            // see ConnectionDescriptor.processUpdate
            if(conn.status == ConnectionDescriptor.CONN_STATUS_CLOSED)
                conn.status = ConnectionDescriptor.CONN_STATUS_CLIENT_ERROR;
        } else if(type == MsgType.DATA_TRUNCATED) {
            conn.setPayloadTruncatedByAddon();
        } else if(type == MsgType.JS_INJECTED) {
            conn.js_injected_scripts = new String(message, StandardCharsets.US_ASCII);
        } else
            conn.addPayloadChunkMitm(new PayloadChunk(message, getChunkType(type), isSent(type), tstamp));
    }

    private synchronized void addPendingMessage(PendingMessage pending) {
        // Purge unresolved connections (should not happen, just in case)
        if(mPendingMessages.size() > 32) {
            long now = SystemClock.elapsedRealtime();

            for(int i = mPendingMessages.size()-1; i>=0; i--) {
                ArrayList<PendingMessage> pp = mPendingMessages.valueAt(i);

                if((now - pp.get(0).pendingSince) > 5000 /* 5 sec */) {
                    Log.w(TAG, "Dropping " + pp.size() + " old messages");
                    mPendingMessages.remove(mPendingMessages.keyAt(i));
                }
            }
        }

        int idx = mPendingMessages.indexOfKey(pending.port);
        ArrayList<PendingMessage> pp;

        if(idx < 0) {
            pp = new ArrayList<>();
            mPendingMessages.put(pending.port, pp);
        } else
            pp = mPendingMessages.valueAt(idx);

        pp.add(pending);
    }

    private static MsgType parseMsgType(String str) {
        switch (str) {
            case "running":
                return MsgType.RUNNING;
            case "tls_err":
                return MsgType.TLS_ERROR;
            case "http_err":
                return MsgType.HTTP_ERROR;
            case "http_req":
                return MsgType.HTTP_REQUEST;
            case "http_rep":
                return MsgType.HTTP_REPLY;
            case "tcp_climsg":
                return MsgType.TCP_CLIENT_MSG;
            case "tcp_srvmsg":
                return MsgType.TCP_SERVER_MSG;
            case "tcp_err":
                return MsgType.TCP_ERROR;
            case "ws_climsg":
                return MsgType.WEBSOCKET_CLIENT_MSG;
            case "ws_srvmsg":
                return MsgType.WEBSOCKET_SERVER_MSG;
            case "trunc":
                return MsgType.DATA_TRUNCATED;
            case "secret":
                return MsgType.MASTER_SECRET;
            case "log":
                return MsgType.LOG;
            case "js_inject":
                return MsgType.JS_INJECTED;
            default:
                return MsgType.UNKNOWN;
        }
    }

    private void logMasterSecret(byte[] master_secret) throws IOException {
        if(mPcapngFormat)
            CaptureService.dumpMasterSecret(master_secret);
        else {
            if(mKeylog == null)
                mKeylog = new BufferedOutputStream(
                        mContext.getContentResolver().openOutputStream(
                                Uri.fromFile(getKeylogFilePath(mContext)), "rwt"));

            mKeylog.write(master_secret);
            mKeylog.write(0xa);
        }
    }

    private void handleLog(byte[] message) {
        try {
            String msg = new String(message, StandardCharsets.US_ASCII);

            // format: 1:message
            if (msg.length() < 3)
                return;

            int lvl = Integer.parseInt(msg.substring(0, 1));
            Log.level(Log.MITMADDON_LOGGER, lvl, msg.substring(2));
        } catch (NumberFormatException ignored) {}
    }

    public Status getProxyStatus() {
        return proxyStatus.getValue();
    }

    public static void observeStatus(LifecycleOwner lifecycleOwner, Observer<Status> observer) {
        proxyStatus.observe(lifecycleOwner, observer);
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

                // Check if the message has already been received
                int pending_idx = mPendingMessages.indexOfKey(conn.local_port);
                if(pending_idx >= 0) {
                    ArrayList<PendingMessage> pp = mPendingMessages.valueAt(pending_idx);
                    mPendingMessages.removeAt(pending_idx);

                    for(PendingMessage pending: pp) {
                        //Log.d(TAG, "(pending) MSG." + pending.type.name() + "[" + pending.message.length + " B]: port=" + pending.port);
                        handleMessage(conn, pending.type, pending.msg, pending.when);
                    }
                }
            }
        }
    }

    @Override
    public void onMitmServiceConnect() {
        // Ensure that no other instance is running
        mAddon.stopProxy();

        // when connected, verify that the certificate is installed before starting the proxy.
        // will continue on onMitmGetCaCertificateResult.
        if(!mAddon.requestCaCertificate())
            mAddon.disconnect();
    }

    @Override
    public void onMitmGetCaCertificateResult(@Nullable String ca_pem) {
        if(!MitmAddon.isCAInstallationSkipped(mContext) && !Utils.isCAInstalled(ca_pem)) {
            // The certificate has been uninstalled from the system
            Utils.showToastLong(mContext, R.string.cert_reinstall_required);
            MitmAddon.setDecryptionSetupDone(mContext, false);
            CaptureService.stopService();
            return;
        }

        // Certificate installation verified, start the proxy
        mSocketFd = mAddon.startProxy(mConfig);
        if(mSocketFd == null) {
            mAddon.disconnect();
            return;
        }

        if (MitmAddon.isDozeEnabled(mContext)) {
            Utils.showToastLong(mContext, R.string.mitm_doze_notice);
            mAddon.disableDoze();
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
}
