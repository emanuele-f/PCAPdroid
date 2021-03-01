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
 * Copyright 2020 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.model.VPNStats;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

public class CaptureService extends VpnService implements Runnable {
    private static final String TAG = "CaptureService";
    private static final String VpnSessionName = "PCAPdroid VPN";
    private static final String NOTIFY_CHAN_VPNSERVICE = "vpnservice";
    private static final int NOTIFY_ID_VPNSERVICE = 1;
    private ParcelFileDescriptor mParcelFileDescriptor = null;
    private Handler mHandler;
    private Thread mThread;
    private String vpn_ipv4;
    private String vpn_dns;
    private String public_dns;
    private String collector_address;
    private String tls_proxy_address;
    private Prefs.DumpMode dump_mode;
    private boolean tls_decryption_enabled;
    private int collector_port;
    private int http_server_port;
    private int tls_proxy_port;
    private long last_bytes;
    private int last_connections;
    private static CaptureService INSTANCE;
    private String app_filter;
    private HTTPServer mHttpServer;
    private OutputStream mOutputStream;
    private ConnectionsRegister conn_reg;
    private Uri mPcapUri;
    private boolean mFirstStreamWrite;

    /* The maximum connections to log into the ConnectionsRegister. Older connections are dropped.
     * Max Estimated max memory usage: less than 2 MB. */
    public static final int CONNECTIONS_LOG_SIZE = 4192;

    /* The IP address of the virtual network interface */
    public static final String VPN_IP_ADDRESS = "10.215.173.1";

    /* The DNS server IP address to use to internally analyze the DNS requests.
     * It must be in the same subnet of the VPN network interface.
     * After the analysis, requests will be routed to the primary DNS server. */
    public static final String VPN_VIRTUAL_DNS_SERVER = "10.215.173.2";

    public static final String ACTION_TRAFFIC_STATS_UPDATE = "traffic_stats_update";
    public static final String ACTION_STATS_DUMP = "stats_dump";
    public static final String TRAFFIC_STATS_UPDATE_SENT_BYTES = "sent_bytes";
    public static final String TRAFFIC_STATS_UPDATE_RCVD_BYTES = "rcvd_bytes";
    public static final String TRAFFIC_STATS_UPDATE_SENT_PKTS = "sent_pkts";
    public static final String TRAFFIC_STATS_UPDATE_RCVD_PKTS = "rcvd_pkts";

    public static final String ACTION_SERVICE_STATUS = "service_status";
    public static final String SERVICE_STATUS_KEY = "status";
    public static final String SERVICE_STATUS_STARTED = "started";
    public static final String SERVICE_STATUS_STOPPED = "stopped";

    static {
        /* Load native library */
        System.loadLibrary("vpnproxy-jni");
    }

    @Override
    public void onCreate() {
        Log.d(CaptureService.TAG, "onCreate");
        INSTANCE = this;
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Context app_ctx = getApplicationContext();

        mHandler = new Handler(Looper.getMainLooper());

        if (intent == null) {
            Log.d(CaptureService.TAG, "NULL intent onStartCommand");
            return super.onStartCommand(null, flags, startId);
        }

        Log.d(CaptureService.TAG, "onStartCommand");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        Bundle settings = intent.getBundleExtra("settings");

        if (settings == null) {
            Log.e(CaptureService.TAG, "NULL settings");
            return super.onStartCommand(null, flags, startId);
        }

        // Retrieve Configuration
        public_dns = Utils.getDnsServer(app_ctx);
        vpn_dns = VPN_VIRTUAL_DNS_SERVER;
        vpn_ipv4 = VPN_IP_ADDRESS;
        app_filter = settings.getString(Prefs.PREF_APP_FILTER);

        collector_address = Prefs.getCollectorIp(prefs);
        collector_port = Prefs.getCollectorPort(prefs);
        http_server_port = Prefs.getHttpServerPort(prefs);
        tls_decryption_enabled = Prefs.getTlsDecryptionEnabled(prefs);
        tls_proxy_address = Prefs.getTlsProxyAddress(prefs);
        tls_proxy_port = Prefs.getTlsProxyPort(prefs);
        dump_mode = Prefs.getDumpMode(prefs);
        last_bytes = 0;
        last_connections = 0;

        conn_reg = new ConnectionsRegister(CONNECTIONS_LOG_SIZE);

        if(dump_mode != Prefs.DumpMode.HTTP_SERVER)
            mHttpServer = null;
        mOutputStream = null;
        mPcapUri = null;

        if(dump_mode == Prefs.DumpMode.HTTP_SERVER) {
            if (mHttpServer == null)
                mHttpServer = new HTTPServer(app_ctx, http_server_port);

            try {
                mHttpServer.startConnections();
            } catch (IOException e) {
                Log.e(CaptureService.TAG, "Could not start the HTTP server");
                e.printStackTrace();
            }
        } else if(dump_mode == Prefs.DumpMode.PCAP_FILE) {
            String path = settings.getString(Prefs.PREF_PCAP_URI);

            if(path != null) {
                mPcapUri = Uri.parse(path);

                try {
                    mOutputStream = getContentResolver().openOutputStream(mPcapUri);
                    mFirstStreamWrite = true;
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                }
            }

            if(mOutputStream == null) {
                Utils.showToast(this, R.string.cannot_write_pcap_file);
                return super.onStartCommand(intent, flags, startId);
            }
        }

        Log.i(TAG, "Using DNS server " + public_dns);

        // VPN
        /* In order to see the DNS packets into the VPN we must set an internal address as the DNS
         * server. */
        Builder builder = new Builder()
                .addAddress(vpn_ipv4, 30) // using a random IP as an address is needed
                .addRoute("0.0.0.0", 1)
                .addRoute("128.0.0.0", 1)
                .addDnsServer(vpn_dns);

        if(app_filter != null) {
            Log.d(TAG, "Setting app filter: " + app_filter);

            try {
                builder.addAllowedApplication(app_filter);
            } catch (PackageManager.NameNotFoundException e) {
                String msg = String.format(getResources().getString(R.string.app_not_found), app_filter);
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                return super.onStartCommand(intent, flags, startId);
            }
        }

        try {
            mParcelFileDescriptor = builder.setSession(CaptureService.VpnSessionName).establish();
        } catch (IllegalArgumentException | IllegalStateException e) {
            Utils.showToast(this, R.string.vpn_setup_failed);
            return super.onStartCommand(intent, flags, startId);
        }

        // Stop the previous session by interrupting the thread.
        if (mThread != null) {
            mThread.interrupt();
        }

        // Start a new session by creating a new thread.
        mThread = new Thread(this, "CaptureService Thread");
        mThread.start();

        setupNotifications();
        startForeground(NOTIFY_ID_VPNSERVICE, getNotification());

        return START_STICKY;
        //return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onRevoke() {
        Log.d(CaptureService.TAG, "onRevoke");
        stop();

        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        Log.d(CaptureService.TAG, "onDestroy");
        stop();
        INSTANCE = null;

        if(mThread != null) {
            mThread.interrupt();
        }
        if(mHttpServer != null)
            mHttpServer.stop();

        super.onDestroy();
    }

    private void setupNotifications() {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        NotificationChannel chan = new NotificationChannel(NOTIFY_CHAN_VPNSERVICE,
                NOTIFY_CHAN_VPNSERVICE, NotificationManager.IMPORTANCE_LOW); // low: no sound
        nm.createNotificationChannel(chan);
    }

    private Notification getNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

        String msg = String.format(getString(R.string.notification_msg),
                Utils.formatBytes(last_bytes), Utils.formatNumber(this, last_connections));

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFY_CHAN_VPNSERVICE)
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setContentIntent(pi)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentTitle(getResources().getString(R.string.capture_running))
                .setContentText(msg)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW); // see IMPORTANCE_LOW

        return builder.build();
    }

    private void updateNotification() {
        Notification notification = getNotification();
        NotificationManagerCompat.from(this).notify(NOTIFY_ID_VPNSERVICE, notification);
    }

    private void stop() {
        stopPacketLoop();

        while((mThread != null) && (mThread.isAlive())) {
            try {
                Log.d(TAG, "Joining native thread...");
                mThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Joining native thread failed");
            }
        }

        mThread = null;

        if(mParcelFileDescriptor != null) {
            try {
                mParcelFileDescriptor.close();
            } catch (IOException e) {
                Toast.makeText(this, "Stopping VPN failed", Toast.LENGTH_SHORT).show();
            }
            mParcelFileDescriptor = null;
        }

        if(mHttpServer != null)
            mHttpServer.endConnections();
        // NOTE: do not destroy the mHttpServer, let it terminate the active connections

        if(mOutputStream != null) {
            try {
                mOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        mPcapUri = null;

        stopForeground(true /* remove notification */);
    }

    /* Check if the VPN service was launched */
    public static boolean isServiceActive() {
        return((INSTANCE != null) &&
                (INSTANCE.mParcelFileDescriptor != null));
    }

    public static String getAppFilter() {
        return((INSTANCE != null) ? INSTANCE.app_filter : null);
    }

    public static Uri getPcapUri() {
        return ((INSTANCE != null) ? INSTANCE.mPcapUri : null);
    }

    public static long getBytes() {
        return((INSTANCE != null) ? INSTANCE.last_bytes : 0);
    }

    public static String getCollectorAddress() {
        return((INSTANCE != null) ? INSTANCE.collector_address : "");
    }

    public static int getCollectorPort() {
        return((INSTANCE != null) ? INSTANCE.collector_port : 0);
    }

    public static int getHTTPServerPort() {
        return((INSTANCE != null) ? INSTANCE.http_server_port : 0);
    }

    public static Prefs.DumpMode getDumpMode() {
        return((INSTANCE != null) ? INSTANCE.dump_mode : Prefs.DumpMode.NONE);
    }

    public static String getDNSServer() {
        return((INSTANCE != null) ? INSTANCE.getPublicDns() : "");
    }

    /* Stop a running VPN service */
    public static void stopService() {
        if (INSTANCE != null)
            INSTANCE.stop();
    }

    public static ConnectionsRegister getConnsRegister() {
        return((INSTANCE != null) ? INSTANCE.conn_reg : null);
    }

    @Override
    public void run() {
        if(mParcelFileDescriptor != null) {
            int fd = mParcelFileDescriptor.getFd();
            int fd_setsize = getFdSetSize();

            if((fd > 0) && (fd < fd_setsize)) {
                Log.d(TAG, "VPN fd: " + fd + " - FD_SETSIZE: " + fd_setsize);
                runPacketLoop(fd, this, Build.VERSION.SDK_INT);
            } else
                Log.e(TAG, "Invalid VPN fd: " + fd);
        }
    }

    /* The following methods are called from native code */

    public String getVpnIPv4() {
        return(vpn_ipv4);
    }

    public String getVpnDns() {
        return(vpn_dns);
    }

    public String getPublicDns() {
        return(public_dns);
    }

    public String getPcapCollectorAddress() {
        return(collector_address);
    }

    public int getPcapCollectorPort() {
        return(collector_port);
    }

    public String getTlsProxyAddress() {  return(tls_proxy_address);  }

    public int getTlsDecryptionEnabled() { return tls_decryption_enabled ? 1 : 0; }

    public int getTlsProxyPort() {  return(tls_proxy_port);  }

    // returns 1 if dumpPcapData should be called
    public int dumpPcapToJava() {
        return(((dump_mode == Prefs.DumpMode.HTTP_SERVER) || (dump_mode == Prefs.DumpMode.PCAP_FILE)) ? 1 : 0);
    }

    public int dumpPcapToUdp() {
        return((dump_mode == Prefs.DumpMode.UDP_EXPORTER) ? 1 : 0);
    }

    // from NetGuard
    @TargetApi(Build.VERSION_CODES.Q)
    public int getUidQ(int version, int protocol, String saddr, int sport, String daddr, int dport) {
        if (protocol != 6 /* TCP */ && protocol != 17 /* UDP */)
            return -1;

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null)
            return -1;

        InetSocketAddress local = new InetSocketAddress(saddr, sport);
        InetSocketAddress remote = new InetSocketAddress(daddr, dport);

        Log.i(TAG, "Get uid local=" + local + " remote=" + remote);
        int uid = cm.getConnectionOwnerUid(protocol, local, remote);
        Log.i(TAG, "Get uid=" + uid);
        return uid;
    }

    public void sendCaptureStats(long sent_bytes, long rcvd_bytes, int sent_pkts, int rcvd_pkts) {
        Intent intent = new Intent(ACTION_TRAFFIC_STATS_UPDATE);

        intent.putExtra(TRAFFIC_STATS_UPDATE_SENT_BYTES, sent_bytes);
        intent.putExtra(TRAFFIC_STATS_UPDATE_RCVD_BYTES, rcvd_bytes);
        intent.putExtra(TRAFFIC_STATS_UPDATE_SENT_PKTS, sent_pkts);
        intent.putExtra(TRAFFIC_STATS_UPDATE_RCVD_PKTS, rcvd_pkts);

        last_bytes = sent_bytes + rcvd_bytes;

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public void sendConnectionsDump(ConnectionDescriptor[] new_conns, ConnectionDescriptor[] conns_updates) {
        // synchronize the conn_reg to ensure that newConnections and connectionsUpdates run atomically
        // thus preventing the ConnectionsAdapter from interleaving other operations
        synchronized (conn_reg) {
            if(new_conns.length > 0)
                conn_reg.newConnections(new_conns);

            if(conns_updates.length > 0)
                conn_reg.connectionsUpdates(conns_updates);
            }
    }

    public void sendStatsDump(VPNStats stats) {
        Log.d(TAG, "sendStatsDump");

        Bundle bundle = new Bundle();
        bundle.putSerializable("value", stats);
        Intent intent = new Intent(ACTION_STATS_DUMP);
        intent.putExtras(bundle);

        last_connections = stats.tot_conns;
        mHandler.post(this::updateNotification);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void sendServiceStatus(String cur_status) {
        Intent intent = new Intent(ACTION_SERVICE_STATUS);
        intent.putExtra(SERVICE_STATUS_KEY, cur_status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public String getApplicationByUid(int uid) {
        return(getPackageManager().getNameForUid(uid));
    }

    /* Exports a PCAP data chunk */
    public void dumpPcapData(byte[] data) {
        if(mHttpServer != null)
            mHttpServer.pushData(data);
        else if(mOutputStream != null) {
            try {
                if(mFirstStreamWrite) {
                    mOutputStream.write(Utils.hexStringToByteArray(Utils.PCAP_HEADER));
                    mFirstStreamWrite = false;
                }

                mOutputStream.write(data);
            } catch (IOException e) {
                e.printStackTrace();
                reportError(e.getLocalizedMessage());
                stopPacketLoop();
            }
        }
    }

    public void reportError(String msg) {
        mHandler.post(() -> {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    public static native void runPacketLoop(int fd, CaptureService vpn, int sdk);
    public static native void stopPacketLoop();
    public static native void askStatsDump();
    public static native int getFdSetSize();
}
