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
 * Copyright 2020-21 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.activities.ConnectionsActivity;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.fragments.ConnectionsFragment;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.Blacklists;
import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.ConnectionUpdate;
import com.emanuelef.remote_capture.model.FilterDescriptor;
import com.emanuelef.remote_capture.model.MatchList;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.model.VPNStats;
import com.emanuelef.remote_capture.pcap_dump.FileDumper;
import com.emanuelef.remote_capture.pcap_dump.HTTPServer;
import com.emanuelef.remote_capture.interfaces.PcapDumper;
import com.emanuelef.remote_capture.pcap_dump.UDPDumper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.LinkedBlockingDeque;

public class CaptureService extends VpnService implements Runnable {
    private static final String TAG = "CaptureService";
    private static final String VpnSessionName = "PCAPdroid VPN";
    private static final String NOTIFY_CHAN_VPNSERVICE = "VPNService";
    private static final String NOTIFY_CHAN_BLACKLISTED = "Blacklisted";
    private static final int NOTIFY_ID_VPNSERVICE = 1;
    private static CaptureService INSTANCE;
    private ParcelFileDescriptor mParcelFileDescriptor;
    private CaptureSettings mSettings;
    private Handler mHandler;
    private Thread mCaptureThread;
    private Thread mBlacklistsUpdateThread;
    private Thread mConnUpdateThread;
    private final LinkedBlockingDeque<Pair<ConnectionDescriptor[], ConnectionUpdate[]>> mPendingUpdates = new LinkedBlockingDeque<>(16);
    private String vpn_ipv4;
    private String vpn_dns;
    private String dns_server;
    private long last_bytes;
    private int last_connections;
    private int app_filter_uid;
    private PcapDumper mDumper;
    private ConnectionsRegister conn_reg;
    private Uri mPcapUri;
    private NotificationCompat.Builder mStatusBuilder;
    private NotificationCompat.Builder mBlacklistedBuilder;
    private long mMonitoredNetwork;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private AppsResolver appsResolver;
    private boolean mMalwareDetectionEnabled;
    private Blacklists mBlacklists;

    /* The maximum connections to log into the ConnectionsRegister. Older connections are dropped.
     * Max Estimated max memory usage: less than 4 MB. */
    public static final int CONNECTIONS_LOG_SIZE = 8192;

    public static final String FALLBACK_DNS_SERVER = "8.8.8.8";
    public static final String IPV6_DNS_SERVER = "2001:4860:4860::8888";

    /* The IP address of the virtual network interface */
    public static final String VPN_IP_ADDRESS = "10.215.173.1";
    public static final String VPN_IP6_ADDRESS = "fd00:2:fd00:1:fd00:1:fd00:1";

    /* The DNS server IP address to use to internally analyze the DNS requests.
     * It must be in the same subnet of the VPN network interface.
     * After the analysis, requests will be routed to the primary DNS server. */
    public static final String VPN_VIRTUAL_DNS_SERVER = "10.215.173.2";

    public static final String ACTION_STATS_DUMP = "stats_dump";
    public static final String ACTION_SERVICE_STATUS = "service_status";
    public static final String SERVICE_STATUS_KEY = "status";
    public static final String SERVICE_STATUS_STARTED = "started";
    public static final String SERVICE_STATUS_STOPPED = "stopped";

    static {
        /* Load native library */
        System.loadLibrary("vpnproxy-jni");
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base.createConfigurationContext(Utils.getLocalizedConfig(base)));
    }

    @Override
    public void onCreate() {
        Log.d(CaptureService.TAG, "onCreate");
        INSTANCE = this;
        appsResolver = new AppsResolver(this);

        super.onCreate();
    }

    private int abortStart() {
        // NOTE: startForeground must be called before stopSelf, otherwise an exception will occur
        setupNotifications();
        startForeground(NOTIFY_ID_VPNSERVICE, getStatusNotification());

        stopSelf();
        sendServiceStatus(SERVICE_STATUS_STOPPED);
        return START_STICKY;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        mHandler = new Handler(Looper.getMainLooper());

        if (intent == null) {
            Log.d(CaptureService.TAG, "NULL intent onStartCommand");
            return abortStart();
        }

        Log.d(CaptureService.TAG, "onStartCommand");
        mSettings = (CaptureSettings) intent.getSerializableExtra("settings");

        if (mSettings == null) {
            Log.e(CaptureService.TAG, "Missing capture settings");
            return abortStart();
        }

        // Retrieve DNS server
        dns_server = FALLBACK_DNS_SERVER;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Service.CONNECTIVITY_SERVICE);
            Network net = cm.getActiveNetwork();

            if(net != null) {
                dns_server = Utils.getDnsServer(cm, net);

                if(dns_server == null)
                    dns_server = FALLBACK_DNS_SERVER;
                else {
                    // If the network goes offline we roll back to the fallback DNS server to
                    // avoid possibly using a private IP DNS server not reachable anymore
                    mMonitoredNetwork = net.getNetworkHandle();
                    registerNetworkCallbacks();
                }
            }
        }

        vpn_dns = VPN_VIRTUAL_DNS_SERVER;
        vpn_ipv4 = VPN_IP_ADDRESS;
        last_bytes = 0;
        last_connections = 0;
        conn_reg = new ConnectionsRegister(this, CONNECTIONS_LOG_SIZE);
        mPcapUri = null;
        mDumper = null;

        // Possibly allocate the dumper
        if(mSettings.dump_mode == Prefs.DumpMode.HTTP_SERVER)
            mDumper = new HTTPServer(this, mSettings.http_server_port);
        else if(mSettings.dump_mode == Prefs.DumpMode.PCAP_FILE) {
            if(mSettings.pcap_uri != null) {
                mPcapUri = Uri.parse(mSettings.pcap_uri);
                mDumper = new FileDumper(this, mPcapUri);
            }
        } else if(mSettings.dump_mode == Prefs.DumpMode.UDP_EXPORTER) {
            InetAddress addr;

            try {
                addr = InetAddress.getByName(mSettings.collector_address);
            } catch (UnknownHostException e) {
                reportError(e.getLocalizedMessage());
                e.printStackTrace();
                return abortStart();
            }

            mDumper = new UDPDumper(new InetSocketAddress(addr, mSettings.collector_port));
        }

        if(mDumper != null) {
            try {
                mDumper.startDumper();
            } catch (IOException | SecurityException e) {
                reportError(e.getLocalizedMessage());
                e.printStackTrace();
                mDumper = null;
                return abortStart();
            }
        }

        if ((mSettings.app_filter != null) && (!mSettings.app_filter.isEmpty())) {
            try {
                app_filter_uid = getPackageManager().getApplicationInfo(mSettings.app_filter, 0).uid;
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
                app_filter_uid = -1;
            }
        } else
            app_filter_uid = -1;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mMalwareDetectionEnabled = Prefs.isMalwareDetectionEnabled(this, prefs);

        if(!mSettings.root_capture) {
            Log.i(TAG, "Using DNS server " + dns_server);

            // VPN
            /* In order to see the DNS packets into the VPN we must set an internal address as the DNS
             * server. */
            Builder builder = new Builder()
                    .addAddress(vpn_ipv4, 30) // using a random IP as an address is needed
                    .addRoute("0.0.0.0", 1)
                    .addRoute("128.0.0.0", 1)
                    .addDnsServer(vpn_dns);

            if (mSettings.ipv6_enabled) {
                builder.addAddress(VPN_IP6_ADDRESS, 128);

                // Route unicast IPv6 addresses
                builder.addRoute("2000::", 3);

                try {
                    builder.addDnsServer(InetAddress.getByName(IPV6_DNS_SERVER));
                } catch (UnknownHostException e) {
                    Log.w(TAG, "Could not set IPv6 DNS server");
                }
            }

            if ((mSettings.app_filter != null) && (!mSettings.app_filter.isEmpty())) {
                Log.d(TAG, "Setting app filter: " + mSettings.app_filter);

                try {
                    // NOTE: the API requires a package name, however it is converted to a UID
                    // (see Vpn.java addUserToRanges). This means that vpn routing happens on a UID basis,
                    // not on a package-name basis!
                    builder.addAllowedApplication(mSettings.app_filter);
                } catch (PackageManager.NameNotFoundException e) {
                    String msg = String.format(getResources().getString(R.string.app_not_found), mSettings.app_filter);
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
                    return abortStart();
                }
            }

            try {
                mParcelFileDescriptor = builder.setSession(CaptureService.VpnSessionName).establish();
            } catch (IllegalArgumentException | IllegalStateException e) {
                Utils.showToast(this, R.string.vpn_setup_failed);
                return abortStart();
            }
        }

        // Stop the previous session by interrupting the thread.
        if (mCaptureThread != null) {
            mCaptureThread.interrupt();
        }

        mBlacklists = PCAPdroid.getInstance().getBlacklists();
        if(mMalwareDetectionEnabled && !mBlacklists.needsUpdate())
            reloadBlacklists();
        checkBlacklistsUpdates();

        mConnUpdateThread = new Thread(this::connUpdateWork, "UpdateListener");
        mConnUpdateThread.start();

        // Start the native capture thread
        mCaptureThread = new Thread(this, "PacketCapture");
        mCaptureThread.start();

        setupNotifications();
        startForeground(NOTIFY_ID_VPNSERVICE, getStatusNotification());
        return START_STICKY;
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

        if(mCaptureThread != null)
            mCaptureThread.interrupt();
        if(mBlacklistsUpdateThread != null)
            mBlacklistsUpdateThread.interrupt();
        if(mDumper != null) {
            try {
                mDumper.stopDumper();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mDumper = null;
        }

        appsResolver = null;
        super.onDestroy();
    }

    private void setupNotifications() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            // VPN running notification channel
            NotificationChannel chan = new NotificationChannel(NOTIFY_CHAN_VPNSERVICE,
                    NOTIFY_CHAN_VPNSERVICE, NotificationManager.IMPORTANCE_LOW); // low: no sound
            nm.createNotificationChannel(chan);

            // Blacklisted connection notification channel
            chan = new NotificationChannel(NOTIFY_CHAN_BLACKLISTED,
                    NOTIFY_CHAN_BLACKLISTED, NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(chan);
        }

        // Status notification builder
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), Utils.getIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));
        mStatusBuilder = new NotificationCompat.Builder(this, NOTIFY_CHAN_VPNSERVICE)
                .setSmallIcon(R.drawable.ic_logo)
                .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
                .setContentIntent(pi)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentTitle(getResources().getString(R.string.capture_running))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_LOW); // see IMPORTANCE_LOW

        // Blacklisted notification builder
        mBlacklistedBuilder = new NotificationCompat.Builder(this, NOTIFY_CHAN_BLACKLISTED)
                .setSmallIcon(R.drawable.ic_skull)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setPriority(NotificationCompat.PRIORITY_HIGH); // see IMPORTANCE_HIGH
    }

    private Notification getStatusNotification() {
        String msg = String.format(getString(R.string.notification_msg),
                Utils.formatBytes(last_bytes), Utils.formatNumber(this, last_connections));

        mStatusBuilder.setContentText(msg);

        return mStatusBuilder.build();
    }

    private void updateNotification() {
        Notification notification = getStatusNotification();
        NotificationManagerCompat.from(this).notify(NOTIFY_ID_VPNSERVICE, notification);
    }

    public void notifyBlacklistedConnection(ConnectionDescriptor conn) {
        int uid = conn.uid;

        AppDescriptor app = appsResolver.get(conn.uid, 0);
        assert app != null;

        FilterDescriptor filter = new FilterDescriptor();
        filter.onlyBlacklisted = true;

        Intent intent = new Intent(this, ConnectionsActivity.class)
                .putExtra(ConnectionsFragment.FILTER_EXTRA, filter)
                .putExtra(ConnectionsFragment.QUERY_EXTRA, app.getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                intent, Utils.getIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));

        String rule_label;
        if(conn.isBlacklistedHost())
            rule_label = MatchList.getRuleLabel(this, MatchList.RuleType.HOST, conn.info);
        else
            rule_label = MatchList.getRuleLabel(this, MatchList.RuleType.IP, conn.dst_ip);

        mBlacklistedBuilder
                .setContentIntent(pi)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(String.format(getResources().getString(R.string.malicious_connection_app), app.getName()))
                .setContentText(rule_label);
        Notification notification = mBlacklistedBuilder.build();

        // Use the UID as the notification ID to group alerts from the same app
        mHandler.post(() -> NotificationManagerCompat.from(this).notify(uid, notification));
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void registerNetworkCallbacks() {
        if(mNetworkCallback != null)
            return;

        ConnectivityManager cm = (ConnectivityManager) getSystemService(Service.CONNECTIVITY_SERVICE);
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(@NonNull Network network) {
                Log.d(TAG, "onLost " + network);

                if(network.getNetworkHandle() == mMonitoredNetwork) {
                    Log.d(TAG, "Main network " + network + " lost, using fallback DNS " + FALLBACK_DNS_SERVER);
                    dns_server = FALLBACK_DNS_SERVER;
                    mMonitoredNetwork = 0;
                    unregisterNetworkCallbacks();

                    // change native
                    setDnsServer(dns_server);
                }
            }
        };

        cm.registerNetworkCallback(
                new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                mNetworkCallback);
    }

    private void unregisterNetworkCallbacks() {
        if(mNetworkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Service.CONNECTIVITY_SERVICE);

            try {
                cm.unregisterNetworkCallback(mNetworkCallback);
            } catch(IllegalArgumentException e) {
                Log.w(TAG, "unregisterNetworkCallback failed: " + e);
            }

            mNetworkCallback = null;
        }
    }

    private void stop() {
        stopPacketLoop();
        mPendingUpdates.push(new Pair<>(null, null)); // signal termination to the mConnUpdateThread

        while((mCaptureThread != null) && (mCaptureThread.isAlive())) {
            try {
                Log.d(TAG, "Joining native thread...");
                mCaptureThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Joining native thread failed");
            }
        }
        mCaptureThread = null;

        while((mConnUpdateThread != null) && (mConnUpdateThread.isAlive())) {
            try {
                Log.d(TAG, "Joining conn update thread...");
                mConnUpdateThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "Joining conn update thread failed");
            }
        }
        mConnUpdateThread = null;

        if(mParcelFileDescriptor != null) {
            try {
                mParcelFileDescriptor.close();
            } catch (IOException e) {
                Toast.makeText(this, "Stopping VPN failed", Toast.LENGTH_SHORT).show();
            }
            mParcelFileDescriptor = null;
        }

        if(mDumper != null) {
            try {
                mDumper.stopDumper();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mDumper = null;
        }

        mPcapUri = null;
        mPendingUpdates.clear();
        unregisterNetworkCallbacks();

        stopForeground(true /* remove notification */);
    }

    private void stopThread() {
        mCaptureThread = null;
        stop();
    }

    /* Check if the VPN service was launched */
    public static boolean isServiceActive() {
        return((INSTANCE != null) &&
                (INSTANCE.mCaptureThread != null));
    }

    private void checkBlacklistsUpdates() {
        if(!mMalwareDetectionEnabled || (mBlacklistsUpdateThread != null))
            return;

        if(mBlacklists.needsUpdate()) {
            mBlacklistsUpdateThread = new Thread(this::updateBlacklistsWork, "Blacklists Update");
            mBlacklistsUpdateThread.start();
        }
    }

    private void updateBlacklistsWork() {
        mBlacklists.update();
        reloadBlacklists();
        mBlacklistsUpdateThread = null;
    }

    public static String getAppFilter() {
        return((INSTANCE != null) ? INSTANCE.mSettings.app_filter : null);
    }

    public static Uri getPcapUri() {
        return ((INSTANCE != null) ? INSTANCE.mPcapUri : null);
    }

    public static long getBytes() {
        return((INSTANCE != null) ? INSTANCE.last_bytes : 0);
    }

    public static String getCollectorAddress() {
        return((INSTANCE != null) ? INSTANCE.mSettings.collector_address : "");
    }

    public static int getCollectorPort() {
        return((INSTANCE != null) ? INSTANCE.mSettings.collector_port : 0);
    }

    public static int getHTTPServerPort() {
        return((INSTANCE != null) ? INSTANCE.mSettings.http_server_port : 0);
    }

    public static Prefs.DumpMode getDumpMode() {
        return((INSTANCE != null) ? INSTANCE.mSettings.dump_mode : Prefs.DumpMode.NONE);
    }

    public static String getDNSServer() {
        return((INSTANCE != null) ? INSTANCE.getDnsServer() : "");
    }

    /* Stop a running VPN service */
    public static void stopService() {
        if (INSTANCE != null)
            INSTANCE.stop();
    }

    public static @NonNull CaptureService requireInstance() {
        CaptureService inst = INSTANCE;
        assert(inst != null);
        return(inst);
    }

    public static @Nullable ConnectionsRegister getConnsRegister() {
        return((INSTANCE != null) ? INSTANCE.conn_reg : null);
    }

    public static @NonNull ConnectionsRegister requireConnsRegister() {
        ConnectionsRegister reg = getConnsRegister();

        assert(reg != null);

        return reg;
    }

    public static boolean isCapturingAsRoot() {
        return((INSTANCE != null) &&
                (INSTANCE.isRootCapture() == 1));
    }

    // Inside the mCaptureThread
    @Override
    public void run() {
        if(mSettings.root_capture) {
            runPacketLoop(-1, this, Build.VERSION.SDK_INT);
        } else {
            if(mParcelFileDescriptor != null) {
                int fd = mParcelFileDescriptor.getFd();
                int fd_setsize = getFdSetSize();

                if((fd > 0) && (fd < fd_setsize)) {
                    Log.d(TAG, "VPN fd: " + fd + " - FD_SETSIZE: " + fd_setsize);
                    runPacketLoop(fd, this, Build.VERSION.SDK_INT);
                } else {
                    Log.e(TAG, "Invalid VPN fd: " + fd);
                    stopThread();
                }
            }
        }

        if(mMalwareDetectionEnabled)
            mBlacklists.save();
    }

    private void connUpdateWork() {
        try {
            while(true) {
                Pair<ConnectionDescriptor[], ConnectionUpdate[]> item = mPendingUpdates.take();
                if(item.first == null) // termination request
                    break;

                ConnectionDescriptor[] new_conns = item.first;
                ConnectionUpdate[] conns_updates = item.second;

                // synchronize the conn_reg to ensure that newConnections and connectionsUpdates run atomically
                // thus preventing the ConnectionsAdapter from interleaving other operations
                synchronized (conn_reg) {
                    if(new_conns.length > 0)
                        conn_reg.newConnections(new_conns);

                    if(conns_updates.length > 0)
                        conn_reg.connectionsUpdates(conns_updates);
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /* The following methods are called from native code */

    public String getVpnIPv4() {
        return(vpn_ipv4);
    }

    public String getVpnDns() {
        return(vpn_dns);
    }

    public String getDnsServer() {
        return(dns_server);
    }

    public String getIpv6DnsServer() { return(IPV6_DNS_SERVER); }

    public String getSocks5ProxyAddress() {  return(mSettings.socks5_proxy_address);  }

    public int getSocks5Enabled() { return mSettings.socks5_enabled ? 1 : 0; }

    public int getSocks5ProxyPort() {  return(mSettings.socks5_proxy_port);  }

    public int getIPv6Enabled() { return(mSettings.ipv6_enabled ? 1 : 0); }

    public int isRootCapture() { return(mSettings.root_capture ? 1 : 0); }

    public int malwareDetectionEnabled() { return(mMalwareDetectionEnabled ? 1 : 0); }

    public int addPcapdroidTrailer() { return(mSettings.pcapdroid_trailer ? 1 : 0); }

    public int getAppFilterUid() { return(app_filter_uid); }

    public String getCaptureInterface() { return(mSettings.capture_interface); }

    public int getOwnAppUid() {
        AppDescriptor app = AppsResolver.resolve(getPackageManager(), BuildConfig.APPLICATION_ID, 0);

        if(app != null)
            return app.getUid();

        return Utils.UID_NO_FILTER;
    }

    // returns 1 if dumpPcapData should be called
    public int pcapDumpEnabled() {
        return((mSettings.dump_mode != Prefs.DumpMode.NONE) ? 1 : 0);
    }

    public String getPcapDumperBpf() { return((mDumper != null) ? mDumper.getBpf() : ""); }

    @Override
    public boolean protect(int socket) {
        // Do not call protect in root mode
        if(mSettings.root_capture)
            return true;

        return super.protect(socket);
    }

    // from NetGuard
    @TargetApi(Build.VERSION_CODES.Q)
    public int getUidQ(int version, int protocol, String saddr, int sport, String daddr, int dport) {
        if (protocol != 6 /* TCP */ && protocol != 17 /* UDP */)
            return Utils.UID_UNKNOWN;

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null)
            return Utils.UID_UNKNOWN;

        InetSocketAddress local = new InetSocketAddress(saddr, sport);
        InetSocketAddress remote = new InetSocketAddress(daddr, dport);

        Log.d(TAG, "Get uid local=" + local + " remote=" + remote);
        return cm.getConnectionOwnerUid(protocol, local, remote);
    }

    public void updateConnections(ConnectionDescriptor[] new_conns, ConnectionUpdate[] conns_updates) {
        checkBlacklistsUpdates();

        // Put the update into a queue to avoid performing much work on the capture thread.
        // This will be processed by mConnUpdateThread.
        mPendingUpdates.push(new Pair<>(new_conns, conns_updates));
    }

    public void sendStatsDump(VPNStats stats) {
        //Log.d(TAG, "sendStatsDump");

        Bundle bundle = new Bundle();
        bundle.putSerializable("value", stats);
        Intent intent = new Intent(ACTION_STATS_DUMP);
        intent.putExtras(bundle);

        last_bytes = stats.bytes_sent + stats.bytes_rcvd;
        last_connections = stats.tot_conns;
        mHandler.post(this::updateNotification);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // also called from native
    private void sendServiceStatus(String cur_status) {
        Intent intent = new Intent(ACTION_SERVICE_STATUS);
        intent.putExtra(SERVICE_STATUS_KEY, cur_status);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    public String getApplicationByUid(int uid) {
        AppDescriptor dsc = appsResolver.get(uid, 0);

        if(dsc == null)
            return "";

        return dsc.getName();
    }

    /* Exports a PCAP data chunk */
    public void dumpPcapData(byte[] data) {
        if(mDumper != null) {
            try {
                mDumper.dumpData(data);
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

    public String getWorkingDir() {
        return getCacheDir().getAbsolutePath();
    }
    public String getPersistentDir() { return getFilesDir().getAbsolutePath(); }

    public String getLibprogPath(String prog_name) {
        // executable binaries are stored into the /lib folder of the app
        String dir = getApplicationInfo().nativeLibraryDir;
        return(dir + "/lib" + prog_name + ".so");
    }

    public void notifyBlacklistsLoaded(Blacklists.NativeBlacklistStatus[] loaded_blacklists) {
        mBlacklists.onNativeLoaded(loaded_blacklists);
    }

    public static native void runPacketLoop(int fd, CaptureService vpn, int sdk);
    public static native void stopPacketLoop();
    public static native void askStatsDump();
    public static native int getFdSetSize();
    public static native void setDnsServer(String server);
    public static native byte[] getPcapHeader();
    public static native void reloadBlacklists();
}
