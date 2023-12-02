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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Pair;
import android.util.SparseArray;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.activities.CaptureCtrl;
import com.emanuelef.remote_capture.activities.ConnectionsActivity;
import com.emanuelef.remote_capture.activities.FirewallActivity;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.fragments.ConnectionsFragment;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.BlacklistDescriptor;
import com.emanuelef.remote_capture.model.Blocklist;
import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.ConnectionUpdate;
import com.emanuelef.remote_capture.model.FilterDescriptor;
import com.emanuelef.remote_capture.model.MatchList;
import com.emanuelef.remote_capture.model.PortMapping;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.model.CaptureStats;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class CaptureService extends VpnService implements Runnable {
    private static final String TAG = "CaptureService";
    private static final String VpnSessionName = "PCAPdroid VPN";
    private static final String NOTIFY_CHAN_VPNSERVICE = "VPNService";
    private static final String NOTIFY_CHAN_MALWARE_DETECTION = "Malware detection";
    private static final String NOTIFY_CHAN_OTHER = "Other";
    private static final int VPN_MTU = 10000;
    public static final int NOTIFY_ID_VPNSERVICE = 1;
    public static final int NOTIFY_ID_LOW_MEMORY = 2;
    public static final int NOTIFY_ID_APP_BLOCKED = 3;
    private static CaptureService INSTANCE;
    private static boolean HAS_ERROR = false;
    final ReentrantLock mLock = new ReentrantLock();
    final Condition mCaptureStopped = mLock.newCondition();
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
    private String mPcapFname;
    private NotificationCompat.Builder mStatusBuilder;
    private NotificationCompat.Builder mMalwareBuilder;
    private long mMonitoredNetwork;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private AppsResolver nativeAppsResolver; // can only be accessed by native code to avoid concurrency issues
    private boolean mMalwareDetectionEnabled;
    private boolean mBlacklistsUpdateRequested;
    private boolean mFirewallEnabled;
    private boolean mBlockPrivateDns;
    private boolean mDnsEncrypted;
    private boolean mStrictDnsNoticeShown;
    private boolean mQueueFull;
    private boolean mStopping;
    private Blacklists mBlacklists;
    private Blocklist mBlocklist;
    private MatchList mMalwareWhitelist;
    private MatchList mFirewallWhitelist;
    private MatchList mDecryptionList;
    private SparseArray<String> mIfIndexToName;
    private boolean mSocks5Enabled;
    private String mSocks5Address;
    private int mSocks5Port;
    private String mSocks5Auth;
    private static final MutableLiveData<CaptureStats> lastStats = new MutableLiveData<>();
    private static final MutableLiveData<ServiceStatus> serviceStatus = new MutableLiveData<>();
    private boolean mLowMemory;
    private BroadcastReceiver mNewAppsInstallReceiver;
    private Utils.PrivateDnsMode mPrivateDnsMode;

    /* The maximum connections to log into the ConnectionsRegister. Older connections are dropped.
     * Max estimated memory usage: less than 4 MB (+8 MB with payload mode minimal). */
    public static final int CONNECTIONS_LOG_SIZE = 8192;

    /* The IP address of the virtual network interface */
    public static final String VPN_IP_ADDRESS = "10.215.173.1";
    public static final String VPN_IP6_ADDRESS = "fd00:2:fd00:1:fd00:1:fd00:1";

    /* The DNS server IP address to use to internally analyze the DNS requests.
     * It must be in the same subnet of the VPN network interface.
     * After the analysis, requests will be routed to the primary DNS server. */
    public static final String VPN_VIRTUAL_DNS_SERVER = "10.215.173.2";

    public enum ServiceStatus {
        STOPPED,
        STARTED
    }

    static {
        /* Load native library */
        try {
            System.loadLibrary("capture");
            CaptureService.initPlatformInfo(Utils.getAppVersionString(), Utils.getDeviceModel(), Utils.getOsVersion());
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
        nativeAppsResolver = new AppsResolver(this);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mSettings = new CaptureSettings(this, mPrefs); // initialize to prevent NULL pointer exceptions in methods (e.g. isRootCapture)

        INSTANCE = this;
        super.onCreate();
    }

    private int abortStart() {
        stopService();
        updateServiceStatus(ServiceStatus.STOPPED);
        return START_NOT_STICKY;
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        mStopping = false;

        // startForeground must always be called since the Service is being started with
        // ContextCompat.startForegroundService.
        // NOTE: since Android 12, startForeground cannot be called when the app is in background
        // (unless invoked via an Intent).
        setupNotifications();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIFY_ID_VPNSERVICE, getStatusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
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
        CaptureSettings settings = ((intent == null) ? null : Utils.getSerializableExtra(intent, "settings", CaptureSettings.class));
        if(settings == null) {
            // Use the settings from mPrefs

            // An Intent without extras is delivered in case of always on VPN
            // https://developer.android.com/guide/topics/connectivity/vpn#always-on
            mIsAlwaysOnVPN = (intent != null);
            Log.i(CaptureService.TAG, "Missing capture settings, using SharedPrefs");
        } else {
            // Use the provided settings
            mSettings = settings;
            mIsAlwaysOnVPN = false;
        }

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            mIsAlwaysOnVPN |= isAlwaysOn();

        Log.d(TAG, "alwaysOn? " + mIsAlwaysOnVPN);
        if(mIsAlwaysOnVPN) {
            mSettings.root_capture = false;
            mSettings.input_pcap_path = null;
        }

        if(mSettings.readFromPcap()) {
            // Disable incompatible settings
            mSettings.dump_mode = Prefs.DumpMode.NONE;
            mSettings.app_filter = "";
            mSettings.socks5_enabled = false;
            mSettings.tls_decryption = false;
            mSettings.root_capture = false;
            mSettings.auto_block_private_dns = false;
            mSettings.capture_interface = mSettings.input_pcap_path;
        }

        // Retrieve DNS server
        String fallbackDnsV4 = Prefs.getDnsServerV4(mPrefs);
        dns_server = fallbackDnsV4;
        mBlockPrivateDns = false;
        mStrictDnsNoticeShown = false;
        mDnsEncrypted = false;
        setPrivateDnsBlocked(false);

        // Map network interfaces
        mIfIndexToName = new SparseArray<>();

        Enumeration<NetworkInterface> ifaces = Utils.getNetworkInterfaces();
        while(ifaces.hasMoreElements()) {
            NetworkInterface iface = ifaces.nextElement();

            Log.d(TAG, "ifidx " + iface.getIndex() + " -> " + iface.getName());
            mIfIndexToName.put(iface.getIndex(), iface.getName());
        }

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Service.CONNECTIVITY_SERVICE);
            Network net = cm.getActiveNetwork();

            if(net != null) {
                handleLinkProperties(cm.getLinkProperties(net));

                if(Prefs.useSystemDns(mPrefs) || mSettings.root_capture) {
                    dns_server = Utils.getDnsServer(cm, net);
                    if (dns_server == null)
                        dns_server = fallbackDnsV4;
                    else {
                        mMonitoredNetwork = net.getNetworkHandle();
                        registerNetworkCallbacks();
                    }
                } else
                    dns_server = fallbackDnsV4;
            }
        }

        vpn_dns = VPN_VIRTUAL_DNS_SERVER;
        vpn_ipv4 = VPN_IP_ADDRESS;
        last_bytes = 0;
        last_connections = 0;
        mLowMemory = false;
        conn_reg = new ConnectionsRegister(this, CONNECTIONS_LOG_SIZE);
        mDumper = null;
        mDumpQueue = null;
        mPendingUpdates.clear();
        mPcapFname = null;
        HAS_ERROR = false;

        // Possibly allocate the dumper
        if(mSettings.dump_mode == Prefs.DumpMode.HTTP_SERVER)
            mDumper = new HTTPServer(this, mSettings.http_server_port, mSettings.pcapng_format);
        else if(mSettings.dump_mode == Prefs.DumpMode.PCAP_FILE) {
            mPcapFname = !mSettings.pcap_name.isEmpty() ? mSettings.pcap_name : Utils.getUniquePcapFileName(this, mSettings.pcapng_format);

            if(!mSettings.pcap_uri.isEmpty())
                mPcapUri = Uri.parse(mSettings.pcap_uri);
            else
                mPcapUri = Utils.getDownloadsUri(this, mPcapFname);

            if(mPcapUri == null)
                return abortStart();

            mDumper = new FileDumper(this, mPcapUri);
        } else if(mSettings.dump_mode == Prefs.DumpMode.UDP_EXPORTER) {
            InetAddress addr;

            try {
                addr = InetAddress.getByName(mSettings.collector_address);
            } catch (UnknownHostException e) {
                reportError(e.getLocalizedMessage());
                e.printStackTrace();
                return abortStart();
            }

            mDumper = new UDPDumper(new InetSocketAddress(addr, mSettings.collector_port), mSettings.pcapng_format);
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

                mMitmReceiver = new MitmReceiver(this, mSettings, mSocks5Auth);
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

                if(!mSettings.socks5_username.isEmpty() && !mSettings.socks5_password.isEmpty())
                    mSocks5Auth = mSettings.socks5_username + ":" + mSettings.socks5_password;
                else
                    mSocks5Auth = null;
            }
        }

        if(mSettings.tls_decryption && !mSettings.root_capture && !mSettings.readFromPcap())
            mDecryptionList = PCAPdroid.getInstance().getDecryptionList();
        else
            mDecryptionList = null;

        if ((mSettings.app_filter != null) && (!mSettings.app_filter.isEmpty())) {
            try {
                app_filter_uid = Utils.getPackageUid(getPackageManager(), mSettings.app_filter, 0);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
                app_filter_uid = -1;
            }
        } else
            app_filter_uid = -1;

        mMalwareDetectionEnabled = Prefs.isMalwareDetectionEnabled(this, mPrefs);
        mFirewallEnabled = Prefs.isFirewallEnabled(this, mPrefs);

        if(!mSettings.root_capture && !mSettings.readFromPcap()) {
            Log.i(TAG, "Using DNS server " + dns_server);

            // VPN
            /* In order to see the DNS packets into the VPN we must set an internal address as the DNS
             * server. */
            Builder builder = new Builder()
                    .setMtu(VPN_MTU);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                builder.setMetered(false);

            if (getIPv4Enabled() == 1) {
                builder.addAddress(vpn_ipv4, 30)
                        .addRoute("0.0.0.0", 1)
                        .addRoute("128.0.0.0", 1)
                        .addDnsServer(vpn_dns);
            }

            if (getIPv6Enabled() == 1) {
                builder.addAddress(VPN_IP6_ADDRESS, 128);

                // Route unicast IPv6 addresses
                builder.addRoute("2000::", 3);
                builder.addRoute("fc00::", 7);

                try {
                    builder.addDnsServer(InetAddress.getByName(Prefs.getDnsServerV6(mPrefs)));
                } catch (UnknownHostException | IllegalArgumentException e) {
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
            } else {
                // VPN exceptions
                Set<String> exceptions = mPrefs.getStringSet(Prefs.PREF_VPN_EXCEPTIONS, new HashSet<>());
                for(String packageName: exceptions) {
                    try {
                        builder.addDisallowedApplication(packageName);
                    } catch (PackageManager.NameNotFoundException e) {
                        e.printStackTrace();
                    }
                }

                if(mSettings.tls_decryption) {
                    // Exclude the mitm addon traffic in case system-wide decryption is performed
                    // Important: cannot call addDisallowedApplication with addAllowedApplication
                    try {
                        builder.addDisallowedApplication(MitmAPI.PACKAGE_NAME);
                    } catch (PackageManager.NameNotFoundException e) {
                        e.printStackTrace();
                    }
                }
            }

            if(Prefs.isPortMappingEnabled(mPrefs)) {
                PortMapping portMap = new PortMapping(this);
                Iterator<PortMapping.PortMap> it = portMap.iter();
                while (it.hasNext()) {
                    PortMapping.PortMap mapping = it.next();
                    addPortMapping(mapping.ipproto, mapping.orig_port, mapping.redirect_port, mapping.redirect_ip);
                }
            }

            try {
                mParcelFileDescriptor = builder.setSession(CaptureService.VpnSessionName).establish();
            } catch (IllegalArgumentException | IllegalStateException e) {
                Utils.showToast(this, R.string.vpn_setup_failed);
                return abortStart();
            }
        }

        mMalwareWhitelist = PCAPdroid.getInstance().getMalwareWhitelist();
        mBlacklists = PCAPdroid.getInstance().getBlacklists();
        if(mMalwareDetectionEnabled && !mBlacklists.needsUpdate(true))
            reloadBlacklists();
        checkBlacklistsUpdates(true);

        mBlocklist = PCAPdroid.getInstance().getBlocklist();
        mFirewallWhitelist = PCAPdroid.getInstance().getFirewallWhitelist();

        mConnUpdateThread = new Thread(this::connUpdateWork, "UpdateListener");
        mConnUpdateThread.start();

        if(mDumper != null) {
            mDumperThread = new Thread(this::dumpWork, "DumperThread");
            mDumperThread.start();
        }

        if(mFirewallEnabled) {
            mNewAppsInstallReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // executed on the main thread
                    if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                        boolean newInstall = !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                        String packageName = intent.getData().getSchemeSpecificPart();

                        if(newInstall && Prefs.blockNewApps(mPrefs)) {
                            if(!mBlocklist.addApp(packageName))
                                return;

                            mBlocklist.save();
                            reloadBlocklist();

                            AppDescriptor app = AppsResolver.resolveInstalledApp(getPackageManager(), packageName, 0);
                            String label = (app != null) ? app.getName() : packageName;

                            Log.i(TAG, "Blocking newly installed app: " + packageName + ((app != null) ? " - " + app.getUid() : ""));

                            PendingIntent pi = PendingIntent.getActivity(CaptureService.this, 0,
                                    new Intent(CaptureService.this, FirewallActivity.class), Utils.getIntentFlags(0));

                            PendingIntent unblockIntent = PendingIntent.getBroadcast(CaptureService.this, 0,
                                    new Intent(CaptureService.this, ActionReceiver.class)
                                            .putExtra(ActionReceiver.EXTRA_UNBLOCK_APP, packageName), Utils.getIntentFlags(PendingIntent.FLAG_UPDATE_CURRENT));

                            // Notify the user
                            NotificationManagerCompat man = NotificationManagerCompat.from(context);
                            if(man.areNotificationsEnabled()) {
                                Notification notification = new NotificationCompat.Builder(CaptureService.this, NOTIFY_CHAN_OTHER)
                                        .setContentIntent(pi)
                                        .setSmallIcon(R.drawable.ic_logo)
                                        .setColor(ContextCompat.getColor(CaptureService.this, R.color.colorPrimary))
                                        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                                        .setContentTitle(getString(R.string.app_blocked))
                                        .setContentText(getString(R.string.app_blocked_info, label))
                                        .setAutoCancel(true)
                                        .addAction(R.drawable.ic_check_solid, getString(R.string.action_unblock), unblockIntent)
                                        .build();

                                man.notify(NOTIFY_ID_APP_BLOCKED, notification);
                            }
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            registerReceiver(mNewAppsInstallReceiver, filter);
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

        unregisterNetworkCallbacks();

        if(mBlacklists != null)
            mBlacklists.abortUpdate();

        if(mCaptureThread != null)
            mCaptureThread.interrupt();
        if(mBlacklistsUpdateThread != null)
            mBlacklistsUpdateThread.interrupt();

        if(mNewAppsInstallReceiver != null) {
            unregisterReceiver(mNewAppsInstallReceiver);
            mNewAppsInstallReceiver = null;
        }

        super.onDestroy();
    }

    private void setupNotifications() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            // VPN running notification channel
            NotificationChannel chan = new NotificationChannel(NOTIFY_CHAN_VPNSERVICE,
                    NOTIFY_CHAN_VPNSERVICE, NotificationManager.IMPORTANCE_LOW); // low: no sound
            chan.setShowBadge(false);
            nm.createNotificationChannel(chan);

            // Blacklisted connection notification channel
            chan = new NotificationChannel(NOTIFY_CHAN_MALWARE_DETECTION,
                    getString(R.string.malware_detection), NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(chan);

            // Other notifications
            chan = new NotificationChannel(NOTIFY_CHAN_OTHER,
                    getString(R.string.other_prefs), NotificationManager.IMPORTANCE_DEFAULT);
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

        // Malware notification builder
        mMalwareBuilder = new NotificationCompat.Builder(this, NOTIFY_CHAN_MALWARE_DETECTION)
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
        if(mStopping)
            return;

        Notification notification = getStatusNotification();
        NotificationManagerCompat.from(this).notify(NOTIFY_ID_VPNSERVICE, notification);
    }

    public void notifyBlacklistedConnection(ConnectionDescriptor conn) {
        int uid = conn.uid;

        AppsResolver resolver = new AppsResolver(this);
        AppDescriptor app = resolver.getAppByUid(conn.uid, 0);
        if(app == null)
            return;

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

        mMalwareBuilder
                .setContentIntent(pi)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(String.format(getResources().getString(R.string.malicious_connection_app), app.getName()))
                .setContentText(rule_label);
        Notification notification = mMalwareBuilder.build();

        // Use the UID as the notification ID to group alerts from the same app
        mHandler.post(() -> Utils.sendImportantNotification(this, uid, notification));
    }

    public void notifyLowMemory(CharSequence msg) {
        Notification notification = new NotificationCompat.Builder(this, NOTIFY_CHAN_OTHER)
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_logo)
                .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setWhen(System.currentTimeMillis())
                .setContentTitle(getString(R.string.low_memory))
                .setContentText(msg)
                .build();

        mHandler.post(() -> Utils.sendImportantNotification(this, NOTIFY_ID_LOW_MEMORY, notification));
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void registerNetworkCallbacks() {
        if(mNetworkCallback != null)
            return;

        String fallbackDns = Prefs.getDnsServerV4(mPrefs);
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Service.CONNECTIVITY_SERVICE);
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(@NonNull Network network) {
                Log.d(TAG, "onLost " + network);

                // If the network goes offline we roll back to the fallback DNS server to
                // avoid possibly using a private IP DNS server not reachable anymore
                if(network.getNetworkHandle() == mMonitoredNetwork) {
                    Log.i(TAG, "Main network " + network + " lost, using fallback DNS " + fallbackDns);
                    dns_server = fallbackDns;
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

        try {
            Log.d(TAG, "registerNetworkCallback");
            cm.registerNetworkCallback(
                    new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                    mNetworkCallback);
        } catch (SecurityException e) {
            // this is a bug in Android 11 - https://issuetracker.google.com/issues/175055271?pli=1
            e.printStackTrace();

            Log.w(TAG, "registerNetworkCallback failed, DNS server detection disabled");
            dns_server = fallbackDns;
            mNetworkCallback = null;
        }
    }

    private void unregisterNetworkCallbacks() {
        if(mNetworkCallback != null) {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Service.CONNECTIVITY_SERVICE);

            try {
                Log.d(TAG, "unregisterNetworkCallback");
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
            mPrivateDnsMode = Utils.getPrivateDnsMode(linkProperties);
            Log.i(TAG, "Private DNS: " + mPrivateDnsMode);

            if(mSettings.readFromPcap()) {
                mDnsEncrypted = false;
                setPrivateDnsBlocked(false);
            } else if(!mSettings.root_capture && mSettings.auto_block_private_dns) {
                mDnsEncrypted = mPrivateDnsMode.equals(Utils.PrivateDnsMode.STRICT);
                boolean opportunistic_mode = mPrivateDnsMode.equals(Utils.PrivateDnsMode.OPPORTUNISTIC);

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
            } else {
                // in root capture we don't block private DNS requests in opportunistic mode
                mDnsEncrypted = !mPrivateDnsMode.equals(Utils.PrivateDnsMode.DISABLED);
                setPrivateDnsBlocked(false);
            }

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
    @SuppressWarnings("deprecation")
    public static void stopService() {
        CaptureService captureService = INSTANCE;
        Log.d(TAG, "stopService called (instance? " + (captureService != null) + ")");

        if(captureService == null)
            return;

        captureService.mStopping = true;
        stopPacketLoop();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            captureService.stopForeground(STOP_FOREGROUND_REMOVE);
        else
            captureService.stopForeground(true);

        captureService.stopSelf();
    }

    /* Check if the VPN service was launched */
    public static boolean isServiceActive() {
        return((INSTANCE != null) &&
                (INSTANCE.mCaptureThread != null));
    }

    public static MitmReceiver.Status getMitmProxyStatus() {
        if((INSTANCE == null) || (INSTANCE.mMitmReceiver == null))
            return MitmReceiver.Status.NOT_STARTED;

        return INSTANCE.mMitmReceiver.getProxyStatus();
    }

    public static boolean isLowMemory() {
        return((INSTANCE != null) && (INSTANCE.mLowMemory));
    }

    public static boolean isAlwaysOnVPN() {
        return((INSTANCE != null) && INSTANCE.mIsAlwaysOnVPN);
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    public static boolean isLockdownVPN() {
        return ((INSTANCE != null) && INSTANCE.isLockdownEnabled());
    }

    private void checkBlacklistsUpdates(boolean firstUpdate) {
        if(!mMalwareDetectionEnabled || (mBlacklistsUpdateThread != null))
            return;

        if(mBlacklistsUpdateRequested || mBlacklists.needsUpdate(firstUpdate)) {
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

    public static String getPcapFname() {
        return ((INSTANCE != null) ? INSTANCE.mPcapFname : null);
    }

    public static boolean isUserDefinedPcapUri() {
        return (INSTANCE == null || !INSTANCE.mSettings.pcap_uri.isEmpty());
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

    public static boolean isCapturingAsRoot() {
        return((INSTANCE != null) &&
                (INSTANCE.isRootCapture() == 1));
    }

    public static boolean isDecryptingTLS() {
        return((INSTANCE != null) &&
                (INSTANCE.isTlsDecryptionEnabled() == 1));
    }

    public static boolean isReadingFromPcapFile() {
        return((INSTANCE != null) &&
                (INSTANCE.isPcapFileCapture() == 1));
    }

    public static boolean isDecryptionListEnabled() {
        return(INSTANCE != null && (INSTANCE.mDecryptionList != null));
    }

    public static Prefs.PayloadMode getCurPayloadMode() {
        if(INSTANCE == null)
            return Prefs.PayloadMode.MINIMAL;

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
            // Check for INTERACT_ACROSS_USERS, required to query apps of other users/work profiles
            if(checkCallingOrSelfPermission(Utils.INTERACT_ACROSS_USERS) != PackageManager.PERMISSION_GRANTED) {
                boolean success = Utils.rootGrantPermission(this, Utils.INTERACT_ACROSS_USERS);
                mHandler.post(() -> Utils.showToast(this, success ? R.string.permission_granted : R.string.permission_grant_fail, "INTERACT_ACROSS_USERS"));
            }

            runPacketLoop(-1, this, Build.VERSION.SDK_INT);
        } else {
            if(mParcelFileDescriptor != null) {
                int fd = mParcelFileDescriptor.getFd();
                int fd_setsize = getFdSetSize();

                if((fd > 0) && (fd < fd_setsize)) {
                    Log.d(TAG, "VPN fd: " + fd + " - FD_SETSIZE: " + fd_setsize);
                    runPacketLoop(fd, this, Build.VERSION.SDK_INT);

                    // if always-on VPN is stopped, it's not an always-on anymore
                    mIsAlwaysOnVPN = false;
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

        mLock.lock();
        mCaptureThread = null;
        mCaptureStopped.signalAll();
        mLock.unlock();

        // Notify
        mHandler.post(() -> {
            updateServiceStatus(ServiceStatus.STOPPED);
            CaptureCtrl.notifyCaptureStopped(this, getStats());
        });
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

            checkBlacklistsUpdates(false);
            if(mBlocklist.checkGracePeriods())
                mHandler.post(this::reloadBlocklist);

            if(!mLowMemory)
                checkAvailableHeap();

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

    private void checkAvailableHeap() {
        // This does not account per-app jvm limits
        long availableHeap = Utils.getAvailableHeap();

        if(availableHeap <= Utils.LOW_HEAP_THRESHOLD) {
            Log.w(TAG, "Detected low HEAP memory: " + Utils.formatBytes(availableHeap));
            handleLowMemory();
        }
    }

    // NOTE: this is only called on low system memory (e.g. obtained via getMemoryInfo). The app
    // may still run out of heap memory, whose monitoring requires polling (see checkAvailableHeap)
    @Override
    public void onTrimMemory(int level) {
        String lvlStr = Utils.trimlvl2str(level);
        boolean lowMemory = (level != TRIM_MEMORY_UI_HIDDEN) && (level >= TRIM_MEMORY_RUNNING_LOW);
        boolean critical = lowMemory && (level >= TRIM_MEMORY_COMPLETE);

        Log.d(TAG, "onTrimMemory: " + lvlStr + " - low=" + lowMemory + ", critical=" + critical);

        if(critical && !mLowMemory)
            handleLowMemory();
    }

    private void handleLowMemory() {
        Log.w(TAG, "handleLowMemory called");
        mLowMemory = true;
        boolean fullPayload = getCurPayloadMode() == Prefs.PayloadMode.FULL;

        if(fullPayload) {
            Log.w(TAG, "Disabling full payload");

            // Disable full payload for new connections
            mSettings.full_payload = false;
            setPayloadMode(Prefs.PayloadMode.NONE.ordinal());

            if(mSettings.tls_decryption) {
                // TLS decryption without payload has little use, stop the capture all together
                stopService();
                notifyLowMemory(getString(R.string.capture_stopped_low_memory));
            } else {
                // Release memory for existing connections
                if(conn_reg != null) {
                    conn_reg.releasePayloadMemory();

                    // *possibly* call the gc
                    System.gc();

                    Log.i(TAG, "Memory stats full payload release:\n" + Utils.getMemoryStats(this));
                }

                notifyLowMemory(getString(R.string.full_payload_disabled));
            }
        } else {
            // TODO lower memory consumption (e.g. reduce connections register size)
            Log.w(TAG, "low memory detected, expect crashes");
            notifyLowMemory(getString(R.string.low_memory_info));
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

    public String getIpv6DnsServer() { return(Prefs.getDnsServerV6(mPrefs)); }

    public int getSocks5Enabled() { return mSocks5Enabled ? 1 : 0; }

    public String getSocks5ProxyAddress() {  return(mSocks5Address); }

    public int getSocks5ProxyPort() {  return(mSocks5Port);  }

    public String getSocks5ProxyAuth() {  return(mSocks5Auth);  }

    public int getIPv4Enabled() { return((mSettings.ip_mode != Prefs.IpMode.IPV6_ONLY) ? 1 : 0); }

    public int getIPv6Enabled() { return((mSettings.ip_mode != Prefs.IpMode.IPV4_ONLY) ? 1 : 0); }

    public int isVpnCapture() { return (isRootCapture() | isPcapFileCapture()) == 1 ? 0 : 1; }

    public int isRootCapture() { return(mSettings.root_capture ? 1 : 0); }

    public int isPcapFileCapture() { return(mSettings.readFromPcap() ? 1 : 0); }

    public int isTlsDecryptionEnabled() { return mSettings.tls_decryption ? 1 : 0; }

    public int malwareDetectionEnabled() { return(mMalwareDetectionEnabled ? 1 : 0); }

    public int firewallEnabled() { return(mFirewallEnabled ? 1 : 0); }

    public int addPcapdroidTrailer() { return(mSettings.pcapdroid_trailer ? 1 : 0); }

    public int isPcapngEnabled() { return(mSettings.pcapng_format ? 1 : 0); }

    public int getAppFilterUid() { return(app_filter_uid); }

    public int getMitmAddonUid() {
        return MitmAddon.getUid(this);
    }

    public String getCaptureInterface() { return(mSettings.capture_interface); }

    public int getSnaplen() {  return mSettings.snaplen; }

    public int getMaxPktsPerFlow() {  return mSettings.max_pkts_per_flow; }

    public int getMaxDumpSize() {  return mSettings.max_dump_size; }

    public int getPayloadMode() { return getCurPayloadMode().ordinal(); }

    public int getVpnMTU()      { return VPN_MTU; }

    public int blockQuick()     { return(mSettings.block_quic ? 1 : 0); }

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
    public int getUidQ(int protocol, String saddr, int sport, String daddr, int dport) {
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

    // called from native
    public void sendStatsDump(CaptureStats stats) {
        //Log.d(TAG, "sendStatsDump");

        last_bytes = stats.bytes_sent + stats.bytes_rcvd;
        last_connections = stats.tot_conns;
        mHandler.post(this::updateNotification);

        // notify the observers
        lastStats.postValue(stats);
    }

    // called from native
    private void sendServiceStatus(String cur_status) {
        updateServiceStatus(cur_status.equals("started") ? ServiceStatus.STARTED : ServiceStatus.STOPPED);
    }

    private void updateServiceStatus(ServiceStatus cur_status) {
        // notify the observers
        // NOTE: new subscribers will receive the STOPPED status right after their registration
        serviceStatus.postValue(cur_status);

        if(cur_status == ServiceStatus.STARTED) {
            if(mMalwareDetectionEnabled)
                reloadMalwareWhitelist();
            if(mDecryptionList != null)
                reloadDecryptionList();
            reloadBlocklist();
            reloadFirewallWhitelist();
        }
    }

    // NOTE: to be invoked only by the native code
    public String getApplicationByUid(int uid) {
        AppDescriptor dsc = nativeAppsResolver.getAppByUid(uid, 0);

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
        HAS_ERROR = true;

        mHandler.post(() -> {
            String err = msg;

            // Try to get a translated string (see errors.h)
            switch (msg) {
                case "Invalid PCAP file":
                    err = getString(R.string.invalid_pcap_file);
                    break;
                case "Could not open the capture interface":
                    err = getString(R.string.capture_interface_open_error);
                    break;
                case "Unsupported datalink":
                    err = getString(R.string.unsupported_pcap_datalink);
                    break;
                case "The specified PCAP file does not exist":
                    err = getString(R.string.pcap_file_not_exists);
                    break;
                case "pcapd daemon start failure":
                    if(mSettings.root_capture)
                        err = getString(R.string.root_capture_pcapd_start_failure);
                    break;
                case "pcapd daemon did not spawn":
                    if(mSettings.root_capture)
                        err = getString(R.string.root_capture_start_failed);
                    break;
                case "PCAP read error":
                    err = getString(R.string.pcap_read_error);
                    break;
            }

            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
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
        if(!mBilling.isFirewallVisible())
            return;

        Log.i(TAG, "reloading firewall blocklist");
        reloadBlocklist(mBlocklist.toListDescriptor());
    }

    public void reloadFirewallWhitelist() {
        if(!mBilling.isFirewallVisible())
            return;

        Log.i(TAG, "reloading firewall whitelist");
        reloadFirewallWhitelist(Prefs.isFirewallWhitelistMode(mPrefs) ? mFirewallWhitelist.toListDescriptor() : null);
    }

    public static void reloadMalwareWhitelist() {
        if((INSTANCE == null) || !INSTANCE.mMalwareDetectionEnabled)
            return;

        Log.i(TAG, "reloading malware whitelist");
        reloadMalwareWhitelist(INSTANCE.mMalwareWhitelist.toListDescriptor());
    }

    public static void reloadDecryptionList() {
        if((INSTANCE == null) || (INSTANCE.mDecryptionList == null))
            return;

        Log.i(TAG, "reloading TLS decryption whitelist");
        reloadDecryptionList(INSTANCE.mDecryptionList.toListDescriptor());
    }

    public static void setFirewallEnabled(boolean enabled) {
        if(INSTANCE == null)
            return;

        INSTANCE.mFirewallEnabled = enabled;
        nativeSetFirewallEnabled(enabled);
    }

    public static @NonNull CaptureStats getStats() {
        CaptureStats stats = lastStats.getValue();
        return((stats != null) ? stats : new CaptureStats());
    }

    public static void observeStats(LifecycleOwner lifecycleOwner, Observer<CaptureStats> observer) {
        lastStats.observe(lifecycleOwner, observer);
    }

    public static void observeStatus(LifecycleOwner lifecycleOwner, Observer<ServiceStatus> observer) {
        serviceStatus.observe(lifecycleOwner, observer);
    }

    public static void waitForCaptureStop() {
        if(INSTANCE == null)
            return;

        Log.d(TAG, "waitForCaptureStop " + Thread.currentThread().getName());
        INSTANCE.mLock.lock();
        try {
            while(INSTANCE.mCaptureThread != null) {
                try {
                    INSTANCE.mCaptureStopped.await();
                } catch (InterruptedException ignored) {}
            }
        } finally {
            INSTANCE.mLock.unlock();
        }
        Log.d(TAG, "waitForCaptureStop done " + Thread.currentThread().getName());
    }

    public static boolean hasError() {
        return HAS_ERROR;
    }

    public static @Nullable Utils.PrivateDnsMode getPrivateDnsMode() {
        return isServiceActive() ? INSTANCE.mPrivateDnsMode : null;
    }

    public static native int initLogger(String path, int level);
    public static native int writeLog(int logger, int lvl, String message);
    private static native void initPlatformInfo(String appver, String device, String os);
    private static native void runPacketLoop(int fd, CaptureService vpn, int sdk);
    private static native void stopPacketLoop();
    private static native int getFdSetSize();
    private static native void setPrivateDnsBlocked(boolean to_block);
    private static native void setDnsServer(String server);
    private static native void addPortMapping(int ipproto, int orig_port, int redirect_port, String redirect_ip);
    private static native void reloadBlacklists();
    private static native boolean reloadBlocklist(MatchList.ListDescriptor blocklist);
    private static native boolean reloadFirewallWhitelist(MatchList.ListDescriptor whitelist);
    private static native boolean reloadMalwareWhitelist(MatchList.ListDescriptor whitelist);
    private static native boolean reloadDecryptionList(MatchList.ListDescriptor whitelist);
    public static native void askStatsDump();
    public static native byte[] getPcapHeader();
    public static native void nativeSetFirewallEnabled(boolean enabled);
    public static native int getNumCheckedMalwareConnections();
    public static native int getNumCheckedFirewallConnections();
    public static native int rootCmd(String prog, String args);
    public static native void setPayloadMode(int mode);
    public static native List<String> getL7Protocols();
    public static native void dumpMasterSecret(byte[] secret);
    public static native boolean hasSeenPcapdroidTrailer();
}
