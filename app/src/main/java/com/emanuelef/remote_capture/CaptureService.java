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
import android.net.LinkProperties;
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
import android.util.SparseArray;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.activities.CaptureCtrl;
import com.emanuelef.remote_capture.activities.ConnectionsActivity;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.fragments.ConnectionsFragment;
import com.emanuelef.remote_capture.interfaces.SslkeylogDumpListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.BlacklistDescriptor;
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
import com.pcapdroid.mitm.MitmAPI;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.concurrent.LinkedBlockingDeque;

public class CaptureService extends VpnService implements Runnable {
    private static final String TAG = "CaptureService";
    private static final String VpnSessionName = "PCAPdroid VPN";
    private static final String NOTIFY_CHAN_VPNSERVICE = "VPNService";
    private static final String NOTIFY_CHAN_BLACKLISTED = "Blacklisted";
    private static final int NOTIFY_ID_VPNSERVICE = 1;
    private static CaptureService INSTANCE;
    private ParcelFileDescriptor mParcelFileDescriptor;
    private boolean mIsAlwaysOnVPN;
    private SharedPreferences mPrefs;
    private CaptureSettings mSettings;
    private Billing mBilling;
    private Handler mHandler;
    private Thread mCaptureThread;
    private Thread mBlacklistsUpdateThread;
    private Thread mConnUpdateThread;
    private Thread mDumperThread;
    private MitmReceiver mMitmReceiver;
    private final LinkedBlockingDeque<Pair<ConnectionDescriptor[], ConnectionUpdate[]>> mPendingUpdates = new LinkedBlockingDeque<>(32);
    private LinkedBlockingDeque<byte[]> mDumpQueue;
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
    private boolean mBlacklistsUpdateRequested;
    private boolean mBlockPrivateDns;
    private boolean mDnsEncrypted;
    private boolean mStrictDnsNoticeShown;
    private boolean mQueueFull;
    private Blacklists mBlacklists;
    private MatchList mBlocklist;
    private MatchList mWhitelist;
    private SparseArray<String> mIfIndexToName;
    private boolean mSocks5Enabled;
    private String mSocks5Address;
    private int mSocks5Port;
    private String mSocks5Auth;

    /* The maximum connections to log into the ConnectionsRegister. Older connections are dropped.
     * Max estimated memory usage: less than 4 MB (+8 MB with payload mode minimal). */
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
        try {
            System.loadLibrary("capture");
        } catch (UnsatisfiedLinkError e) {
            // This should only happen while running tests
            //e.printStackTrace();
        }
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base.createConfigurationContext(Utils.getLocalizedConfig(base)));
    }

    @Override
    public void onCreate() {
        Log.d(CaptureService.TAG, "onCreate");
        appsResolver = new AppsResolver(this);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mSettings = new CaptureSettings(mPrefs); // initialize to prevent NULL pointer exceptions in methods (e.g. isRootCapture)

        INSTANCE = this;
        super.onCreate();
    }

    private int abortStart() {
        stopService();
        sendServiceStatus(SERVICE_STATUS_STOPPED);
        return START_NOT_STICKY;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        // startForeground must always be called since the Service is being started with
        // ContextCompat.startForegroundService.
        // NOTE: since Android 12, startForeground cannot be called when the app is in background
        // (unless invoked via an Intent).
        setupNotifications();
        startForeground(NOTIFY_ID_VPNSERVICE, getStatusNotification());

        // NOTE: onStartCommand may be called when the capture is already running, e.g. if the user
        // turns on the always-on VPN while the capture is running in root mode
        if(mCaptureThread != null) {
            // Restarting the capture requires calling stopAndJoinThreads, which is blocking.
            // Choosing not to support this right now.
            Log.e(TAG, "Restarting the capture is not supported");
            return abortStart();
        }

        mHandler = new Handler(Looper.getMainLooper());
        mBilling = Billing.newInstance(this);

        Log.d(CaptureService.TAG, "onStartCommand");

        // NOTE: a null intent may be delivered due to START_STICKY
        // It can be simulated by starting the capture, putting PCAPdroid in the background and then running:
        //  adb shell ps | grep remote_capture | awk '{print $2}' | xargs adb shell run-as com.emanuelef.remote_capture.debug kill
        CaptureSettings settings = (CaptureSettings) ((intent == null) ? null : intent.getSerializableExtra("settings"));
        if(settings == null) {
            // Use the settings from mPrefs

            // An Intent without extras is delivered in case of always on VPN
            // https://developer.android.com/guide/topics/connectivity/vpn#always-on
            mIsAlwaysOnVPN = (intent != null);

            Log.d(CaptureService.TAG, "Missing capture settings, using SharedPrefs");
            if(mIsAlwaysOnVPN)
                mSettings.root_capture = false;
        } else {
            // Use the provided settings
            mSettings = settings;
            mIsAlwaysOnVPN = false;
        }

        // Retrieve DNS server
        dns_server = FALLBACK_DNS_SERVER;
        mBlockPrivateDns = false;
        mStrictDnsNoticeShown = false;
        mDnsEncrypted = false;

        // Map network interfaces
        mIfIndexToName = new SparseArray<>();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while(ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();

                Log.d(TAG, "ifidx " + iface.getIndex() + " -> " + iface.getName());
                mIfIndexToName.put(iface.getIndex(), iface.getName());
            }
        } catch (SocketException ignored) {}

        if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Service.CONNECTIVITY_SERVICE);
            Network net = cm.getActiveNetwork();

            if(net != null) {
                handleLinkProperties(cm.getLinkProperties(net));

                dns_server = Utils.getDnsServer(cm, net);
                if(dns_server == null)
                    dns_server = FALLBACK_DNS_SERVER;
                else {
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
        mDumpQueue = null;
        mPendingUpdates.clear();

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
            // Max memory usage = (JAVA_PCAP_BUFFER_SIZE * 64) = 32 MB
            mDumpQueue = new LinkedBlockingDeque<>(64);

            try {
                mDumper.startDumper();
            } catch (IOException | SecurityException e) {
                reportError(e.getLocalizedMessage());
                e.printStackTrace();
                mDumper = null;
                return abortStart();
            }
        }

        mSocks5Address = "";
        mSocks5Enabled = mSettings.socks5_enabled || mSettings.tls_decryption;
        if(mSocks5Enabled) {
            if(mSettings.tls_decryption) {
                // Built-in decryption
                mSocks5Address = "127.0.0.1";
                mSocks5Port = MitmReceiver.TLS_DECRYPTION_PROXY_PORT;
                mSocks5Auth = Utils.genRandomString(8) + ":" + Utils.genRandomString(8);

                mMitmReceiver = new MitmReceiver(this, mSocks5Auth);
                try {
                    if(!mMitmReceiver.start())
                        return abortStart();
                } catch (IOException e) {
                    e.printStackTrace();
                    return abortStart();
                }
            } else {
                // SOCKS5 proxy
                mSocks5Address = mSettings.socks5_proxy_address;
                mSocks5Port = mSettings.socks5_proxy_port;
                mSocks5Auth = null;
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

        mMalwareDetectionEnabled = Prefs.isMalwareDetectionEnabled(this, mPrefs);

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

            if(mSettings.tls_decryption) {
                // Exclude the mitm addon traffic in case system-wide decryption is performed
                try {
                    builder.addDisallowedApplication(MitmAPI.PACKAGE_NAME);
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
            }

            try {
                mParcelFileDescriptor = builder.setSession(CaptureService.VpnSessionName).establish();
            } catch (IllegalArgumentException | IllegalStateException e) {
                Utils.showToast(this, R.string.vpn_setup_failed);
                return abortStart();
            }
        }

        mWhitelist = PCAPdroid.getInstance().getMalwareWhitelist();
        mBlacklists = PCAPdroid.getInstance().getBlacklists();
        if(mMalwareDetectionEnabled && !mBlacklists.needsUpdate())
            reloadBlacklists();
        checkBlacklistsUpdates();

        mBlocklist = PCAPdroid.getInstance().getBlocklist();

        mConnUpdateThread = new Thread(this::connUpdateWork, "UpdateListener");
        mConnUpdateThread.start();

        if(mDumper != null) {
            mDumperThread = new Thread(this::dumpWork, "DumperThread");
            mDumperThread.start();
        }

        // Start the native capture thread
        mQueueFull = false;
        mCaptureThread = new Thread(this, "PacketCapture");
        mCaptureThread.start();

        // If the service is killed (e.g. due to low memory), then restart it with a NULL intent
        return START_STICKY;
    }

    @Override
    public void onRevoke() {
        Log.d(CaptureService.TAG, "onRevoke");
        stopService();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        Log.d(CaptureService.TAG, "onDestroy");

        // Do not nullify INSTANCE to allow its settings and the connections register to be accessible
        // after the capture is stopped
        //INSTANCE = null;

        if(mCaptureThread != null)
            mCaptureThread.interrupt();
        if(mBlacklistsUpdateThread != null)
            mBlacklistsUpdateThread.interrupt();

        unregisterNetworkCallbacks();

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

                // If the network goes offline we roll back to the fallback DNS server to
                // avoid possibly using a private IP DNS server not reachable anymore
                if(network.getNetworkHandle() == mMonitoredNetwork) {
                    Log.d(TAG, "Main network " + network + " lost, using fallback DNS " + FALLBACK_DNS_SERVER);
                    dns_server = FALLBACK_DNS_SERVER;
                    mMonitoredNetwork = 0;
                    unregisterNetworkCallbacks();

                    // change native
                    setDnsServer(dns_server);
                }
            }

            @RequiresApi(api = Build.VERSION_CODES.O)
            @Override
            public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties linkProperties) {
                Log.d(TAG, "onLinkPropertiesChanged " + network);

                if(network.getNetworkHandle() == mMonitoredNetwork)
                    handleLinkProperties(linkProperties);
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

    private void handleLinkProperties(LinkProperties linkProperties) {
        if(linkProperties == null)
            return;

        if(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            boolean strict_mode = (linkProperties.getPrivateDnsServerName() != null);
            boolean opportunistic_mode = !strict_mode && linkProperties.isPrivateDnsActive();

            Log.d(TAG, "Private DNS: " + (strict_mode ? "strict" : (opportunistic_mode ? "opportunistic" : "off")));
            if(!mSettings.root_capture) {
                mDnsEncrypted = strict_mode;

                /* Private DNS can be in one of these modes:
                 *  1. Off
                 *  2. Automatic (default): also called "opportunistic", only use it if not blocked
                 *  3. Strict: private DNS is enforced, Internet unavailable if blocked. User must set a specific DNS server.
                 * When in opportunistic mode, PCAPdroid will block private DNS connections to force the use of plain-text
                 * DNS queries, which can be extracted by PCAPdroid. */
                if (mBlockPrivateDns != opportunistic_mode) {
                    mBlockPrivateDns = opportunistic_mode;
                    setPrivateDnsBlocked(mBlockPrivateDns);
                }
            } else
                // in root capture we don't block private DNS requests in opportunistic mode
                mDnsEncrypted = strict_mode || opportunistic_mode;

            if(mDnsEncrypted && !mStrictDnsNoticeShown) {
                mStrictDnsNoticeShown = true;
                Utils.showToastLong(this, R.string.private_dns_message_notice);
            }
        }
    }

    private void signalServicesTermination() {
        mPendingUpdates.offer(new Pair<>(null, null));
        stopPcapDump();
    }

    // NOTE: do not call this on the main thread, otherwise it will be an ANR
    private void stopAndJoinThreads() {
        signalServicesTermination();

        Log.d(TAG, "Joining threads...");

        while((mConnUpdateThread != null) && (mConnUpdateThread.isAlive())) {
            try {
                Log.d(TAG, "Joining conn update thread...");
                mConnUpdateThread.join();
            } catch (InterruptedException ignored) {
                mPendingUpdates.offer(new Pair<>(null, null));
            }
        }
        mConnUpdateThread = null;

        while((mDumperThread != null) && (mDumperThread.isAlive())) {
            try {
                Log.d(TAG, "Joining dumper thread...");
                mDumperThread.join();
            } catch (InterruptedException ignored) {
                stopPcapDump();
            }
        }
        mDumperThread = null;
        mDumper = null;

        if(mMitmReceiver != null) {
            try {
                mMitmReceiver.stop();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mMitmReceiver = null;
        }
    }

    /* Stops the running Service. The SERVICE_STATUS_STOPPED notification is sent asynchronously
     * when mCaptureThread terminates. */
    public static void stopService() {
        CaptureService captureService = INSTANCE;
        if(captureService == null)
            return;

        stopPacketLoop();
        captureService.signalServicesTermination();

        captureService.stopForeground(true /* remove notification */);
        captureService.stopSelf();
    }

    /* Check if the VPN service was launched */
    public static boolean isServiceActive() {
        return((INSTANCE != null) &&
                (INSTANCE.mCaptureThread != null));
    }

    public static boolean isAlwaysOnVPN() {
        return((INSTANCE != null) && INSTANCE.mIsAlwaysOnVPN);
    }

    private void checkBlacklistsUpdates() {
        if(!mMalwareDetectionEnabled || (mBlacklistsUpdateThread != null))
            return;

        if(mBlacklistsUpdateRequested || mBlacklists.needsUpdate()) {
            mBlacklistsUpdateThread = new Thread(this::updateBlacklistsWork, "Blacklists Update");
            mBlacklistsUpdateThread.start();
        }
    }

    private void updateBlacklistsWork() {
        mBlacklistsUpdateRequested = false;
        mBlacklists.update();
        reloadBlacklists();
        mBlacklistsUpdateThread = null;
    }

    private String getIfname(int ifidx) {
        if(ifidx <= 0)
            return "";

        String rv = mIfIndexToName.get(ifidx);
        if(rv != null)
            return rv;

        // Not found, try to retrieve it
        NetworkInterface iface = null;
        try {
            iface = NetworkInterface.getByIndex(ifidx);
        } catch (SocketException ignored) {}
        rv = (iface != null) ? iface.getName() : "";

        // store it even if not found, to avoid looking up it again
        mIfIndexToName.put(ifidx, rv);
        return rv;
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

    public static boolean isDNSEncrypted() {
        return((INSTANCE != null) && INSTANCE.mDnsEncrypted);
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

    // See MitmReceiver.dumpSslkeylogfile
    public static boolean dumpSslkeylogfile(SslkeylogDumpListener listener) {
        if(INSTANCE != null)
            return INSTANCE.mMitmReceiver.dumpSslkeylogfile(listener);
        return false;
    }

    public static boolean isCapturingAsRoot() {
        return((INSTANCE != null) &&
                (INSTANCE.isRootCapture() == 1));
    }

    public static boolean isDecryptingTLS() {
        return((INSTANCE != null) &&
                (INSTANCE.isTlsDecryptionEnabled() == 1));
    }

    public static Prefs.PayloadMode getCurPayloadMode() {
        if(INSTANCE == null)
            return Prefs.PayloadMode.MINIMAL;

        // With TLS decryption, payload mode is always "full"
        if(INSTANCE.mSettings.tls_decryption)
            return Prefs.PayloadMode.FULL;

        return INSTANCE.mSettings.full_payload ? Prefs.PayloadMode.FULL : Prefs.PayloadMode.MINIMAL;
    }

    public static void requestBlacklistsUpdate() {
        if(INSTANCE != null) {
            INSTANCE.mBlacklistsUpdateRequested = true;

            // Wake the update thread to run the blacklist thread
            INSTANCE.mPendingUpdates.offer(new Pair<>(new ConnectionDescriptor[0], new ConnectionUpdate[0]));
        }
    }

    public static String getInterfaceName(int ifidx) {
        String ifname = null;

        if(INSTANCE != null)
            ifname = INSTANCE.getIfname(ifidx);
        return (ifname != null) ? ifname : "";
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
                } else
                    Log.e(TAG, "Invalid VPN fd: " + fd);
            }
        }

        // After the capture is stopped
        if(mMalwareDetectionEnabled)
            mBlacklists.save();

        // Important: the fd must be closed to properly terminate the VPN
        if(mParcelFileDescriptor != null) {
            try {
                mParcelFileDescriptor.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mParcelFileDescriptor = null;
        }

        // NOTE: join the threads here instead in onDestroy to avoid ANR
        stopAndJoinThreads();

        stopService();

        // Notify
        mHandler.post(() -> {
            sendServiceStatus(SERVICE_STATUS_STOPPED);
            CaptureCtrl.notifyCaptureStopped(this);
        });

        mCaptureThread = null;
    }

    private void connUpdateWork() {
        while(true) {
            Pair<ConnectionDescriptor[], ConnectionUpdate[]> item;
            try {
                item = mPendingUpdates.take();
            } catch (InterruptedException e) {
                continue;
            }

            if(item.first == null) // termination request
                break;

            ConnectionDescriptor[] new_conns = item.first;
            ConnectionUpdate[] conns_updates = item.second;

            checkBlacklistsUpdates();

            // synchronize the conn_reg to ensure that newConnections and connectionsUpdates run atomically
            // thus preventing the ConnectionsAdapter from interleaving other operations
            synchronized (conn_reg) {
                if(new_conns.length > 0)
                    conn_reg.newConnections(new_conns);

                if(conns_updates.length > 0)
                    conn_reg.connectionsUpdates(conns_updates);
            }
        }
    }

    private void dumpWork() {
        while(true) {
            byte[] data;
            try {
                data = mDumpQueue.take();
            } catch (InterruptedException e) {
                continue;
            }

            if(data.length == 0) // termination request
                break;

            try {
                mDumper.dumpData(data);
            } catch (IOException e) {
                // Stop the capture
                e.printStackTrace();
                reportError(e.getLocalizedMessage());
                mHandler.post(CaptureService::stopPacketLoop);
                break;
            }
        }

        try {
            mDumper.stopDumper();
        } catch (IOException e) {
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

    public int getSocks5Enabled() { return mSocks5Enabled ? 1 : 0; }

    public String getSocks5ProxyAddress() {  return(mSocks5Address); }

    public int getSocks5ProxyPort() {  return(mSocks5Port);  }

    public String getSocks5ProxyAuth() {  return(mSocks5Auth);  }

    public int getIPv6Enabled() { return(mSettings.ipv6_enabled ? 1 : 0); }

    public int isRootCapture() { return(mSettings.root_capture ? 1 : 0); }

    public int isTlsDecryptionEnabled() { return mSettings.tls_decryption ? 1 : 0; }

    public int malwareDetectionEnabled() { return(mMalwareDetectionEnabled ? 1 : 0); }

    public int addPcapdroidTrailer() { return(mSettings.pcapdroid_trailer ? 1 : 0); }

    public int getAppFilterUid() { return(app_filter_uid); }

    public String getCaptureInterface() { return(mSettings.capture_interface); }

    public int getSnaplen() {  return mSettings.snaplen; }

    public int getMaxPktsPerFlow() {  return mSettings.max_pkts_per_flow; }

    public int getMaxDumpSize() {  return mSettings.max_dump_size; }

    public int getPayloadMode() { return getCurPayloadMode().ordinal(); }

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
        if(mQueueFull)
            // if the queue is full, stop receiving updates to avoid inconsistent incr_ids
            return;

        // Put the update into a queue to avoid performing much work on the capture thread.
        // This will be processed by mConnUpdateThread.
        if(!mPendingUpdates.offer(new Pair<>(new_conns, conns_updates))) {
            Log.e(TAG, "The updates queue is full, this should never happen!");
            mQueueFull = true;
            mHandler.post(CaptureService::stopPacketLoop);
        }
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

        if(cur_status.equals(SERVICE_STATUS_STARTED)) {
            if(mMalwareDetectionEnabled)
                reloadMalwareWhitelist();
            reloadBlocklist();
        }
    }

    // NOTE: to be invoked only by the native code
    public String getApplicationByUid(int uid) {
        AppDescriptor dsc = appsResolver.get(uid, 0);

        if(dsc == null)
            return "";

        return dsc.getName();
    }

    /* Exports a PCAP data chunk */
    public void dumpPcapData(byte[] data) {
        if((mDumper != null) && (data.length > 0)) {
            while(true) {
                try {
                    // wait until the queue has space to insert the data. If the queue is full, we
                    // will experience slow-downs/drops but this is expected
                    mDumpQueue.put(data);
                    break;
                } catch (InterruptedException e) {
                    // retry
                }
            }
        }
    }

    public void stopPcapDump() {
        if((mDumpQueue != null) && (mDumperThread != null) && (mDumperThread.isAlive()))
            mDumpQueue.offer(new byte[0]);
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
        // this is invoked from the packet capture thread. Use the handler to save time.
        mHandler.post(() -> mBlacklists.onNativeLoaded(loaded_blacklists));
    }

    public BlacklistDescriptor[] getBlacklistsInfo() {
        BlacklistDescriptor[] blsinfo = new BlacklistDescriptor[mBlacklists.getNumBlacklists()];
        int i = 0;

        Iterator<BlacklistDescriptor> it = mBlacklists.iter();
        while(it.hasNext())
            blsinfo[i++] = it.next();

        return blsinfo;
    }

    public void reloadBlocklist() {
        if(!mBilling.isRedeemed(Billing.FIREWALL_SKU) || mSettings.root_capture)
            return;

        reloadBlocklist(mBlocklist.toListDescriptor());
    }

    public static void reloadMalwareWhitelist() {
        if((INSTANCE == null) || !INSTANCE.mMalwareDetectionEnabled)
            return;

        reloadMalwareWhitelist(INSTANCE.mWhitelist.toListDescriptor());
    }

    private static native void runPacketLoop(int fd, CaptureService vpn, int sdk);
    private static native void stopPacketLoop();
    private static native int getFdSetSize();
    private static native void setPrivateDnsBlocked(boolean to_block);
    private static native void setDnsServer(String server);
    private static native void reloadBlacklists();
    private static native boolean reloadBlocklist(MatchList.ListDescriptor blocklist);
    private static native boolean reloadMalwareWhitelist(MatchList.ListDescriptor whitelist);
    public static native void askStatsDump();
    public static native byte[] getPcapHeader();
    public static native int getNumCheckedConnections();
}
