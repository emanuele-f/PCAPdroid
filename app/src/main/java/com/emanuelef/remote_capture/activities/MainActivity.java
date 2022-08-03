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

package com.emanuelef.remote_capture.activities;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.PlayBilling;
import com.emanuelef.remote_capture.BuildConfig;
import com.emanuelef.remote_capture.CaptureHelper;
import com.emanuelef.remote_capture.MitmReceiver;
import com.emanuelef.remote_capture.fragments.ConnectionsFragment;
import com.emanuelef.remote_capture.fragments.StatusFragment;
import com.emanuelef.remote_capture.interfaces.AppStateListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppState;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.MitmAddon;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.HashSet;

public class MainActivity extends BaseActivity implements NavigationView.OnNavigationItemSelectedListener {
    private PlayBilling mIab;
    private ViewPager2 mPager;
    private AppState mState;
    private AppStateListener mListener;
    private Uri mPcapUri;
    private File mKeylogFile;
    private BroadcastReceiver mReceiver;
    private String mPcapFname;
    private DrawerLayout mDrawer;
    private SharedPreferences mPrefs;
    private NavigationView mNavView;
    private CaptureHelper mCapHelper;
    private boolean usingMediaStore;

    private static final String TAG = "Main";

    private static final int POS_STATUS = 0;
    private static final int POS_CONNECTIONS = 1;
    private static final int TOTAL_COUNT = 2;

    public static final String TELEGRAM_GROUP_NAME = "PCAPdroid";
    public static final String GITHUB_PROJECT_URL = "https://github.com/emanuele-f/PCAPdroid";
    public static final String DOCS_URL = "https://emanuele-f.github.io/PCAPdroid";
    public static final String PRIVACY_POLICY_URL = DOCS_URL + "/privacy";
    public static final String DONATE_URL = "https://emanuele-f.github.io/PCAPdroid/donate";
    public static final String FIREWALL_DOCS_URL = DOCS_URL + "/paid_features#51-firewall";
    public static final String MALWARE_DETECTION_DOCS_URL = DOCS_URL + "/paid_features#52-malware-detection";
    public static final String TLS_DECRYPTION_DOCS_URL = DOCS_URL + "/tls_decryption";

    private final ActivityResultLauncher<Intent> pcapFileLauncher =
            registerForActivityResult(new StartActivityForResult(), this::pcapFileResult);
    private final ActivityResultLauncher<Intent> sslkeyfileExportLauncher =
            registerForActivityResult(new StartActivityForResult(), this::sslkeyfileExportResult);
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new RequestPermission(), isGranted ->
                Log.d(TAG, "Write permission " + (isGranted ? "granted" : "denied"))
            );
    private final ActivityResultLauncher<Intent> peerInfoLauncher =
            registerForActivityResult(new StartActivityForResult(), this::peerInfoResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme_NoActionBar);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);
        setTitle("PCAPdroid");
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        int appver = Prefs.getAppVersion(mPrefs);
        if(appver <= 0) {
            // First run, start on-boarding
            Intent intent = new Intent(MainActivity.this, OnBoardingActivity.class);
            startActivity(intent);
            finish();
            // only refresh app version on on-boarding done
        } else
            Prefs.refreshAppVersion(mPrefs);

        mIab = new PlayBilling(this);

        initPeerAppInfo();
        initAppState();
        checkPermissions();

        mPcapUri = CaptureService.isServiceActive() ? CaptureService.getPcapUri() : null;
        mCapHelper = new CaptureHelper(this);
        mCapHelper.setListener(success -> {
            if(!success) {
                Log.w(TAG, "VPN request failed");
                appStateReady();
            }
        });

        mPager = findViewById(R.id.pager);
        setupTabs();

        /* Register for service status */
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String status = intent.getStringExtra(CaptureService.SERVICE_STATUS_KEY);

                if (status != null) {
                    Log.d(TAG, "Service status: " + status);

                    if (status.equals(CaptureService.SERVICE_STATUS_STARTED))
                        appStateRunning();
                    else if (status.equals(CaptureService.SERVICE_STATUS_STOPPED)) {
                        // The service may still be active (on premature native termination)
                        if (CaptureService.isServiceActive())
                            CaptureService.stopService();

                        mKeylogFile = MitmReceiver.getKeylogFilePath(MainActivity.this);
                        if(!mKeylogFile.exists() || !CaptureService.isDecryptingTLS())
                            mKeylogFile = null;

                        Log.d(TAG, "sslkeylog? " + (mKeylogFile != null));

                        if((mPcapUri != null) && (Prefs.getDumpMode(mPrefs) == Prefs.DumpMode.PCAP_FILE)) {
                            showPcapActionDialog(mPcapUri);
                            mPcapUri = null;
                            mPcapFname = null;

                            // will export the keylogfile after saving/sharing pcap
                        } else if(mKeylogFile != null)
                            startExportSslkeylogfile();

                        appStateReady();
                    }
                }
            }
        };

        LocalBroadcastManager.getInstance(this)
                .registerReceiver(mReceiver, new IntentFilter(CaptureService.ACTION_SERVICE_STATUS));
    }

    private void checkPurchasesAvailable() {
        if(!mIab.isAvailable(Billing.MALWARE_DETECTION_SKU))
            // purchases not available
            return;

        // Show the paid features menu entry
        NavigationView navView = findViewById(R.id.nav_view);
        Menu menu = navView.getMenu();
        menu.findItem(R.id.paid_features).setVisible(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if(mReceiver != null)
            LocalBroadcastManager.getInstance(this)
                    .unregisterReceiver(mReceiver);

        mCapHelper = null;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        setupNavigationDrawer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIab.connectBilling();

        Menu navMenu = mNavView.getMenu();
        navMenu.findItem(R.id.open_root_log).setVisible(Prefs.isRootCaptureEnabled(mPrefs));
        navMenu.findItem(R.id.malware_detection).setVisible(Prefs.isMalwareDetectionEnabled(this, mPrefs));
        navMenu.findItem(R.id.firewall).setVisible(mIab.isFirewallVisible());
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIab.disconnectBilling();
    }

    private void setupNavigationDrawer() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mDrawer = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, mDrawer, toolbar, R.string.open_nav_drawer, R.string.close_nav_drawer);
        mDrawer.addDrawerListener(toggle);
        toggle.syncState();

        mNavView = findViewById(R.id.nav_view);
        mNavView.setNavigationItemSelectedListener(this);
        View header = mNavView.getHeaderView(0);

        TextView appVer = header.findViewById(R.id.app_version);
        String verStr = Utils.getAppVersion(this);
        appVer.setText(verStr);
        appVer.setOnClickListener((ev) -> {
            // e.g. it can be "1.5.2" or "1.5.2-2f2d3c8"
            String ref = verStr;
            int sep = ref.indexOf('-');
            if(sep != -1)
                ref = ref.substring(sep + 1);

            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_PROJECT_URL + "/tree/" + ref));
            Utils.startActivity(this, browserIntent);
        });

        Menu navMenu = mNavView.getMenu();

        if(Prefs.isRootCaptureEnabled(mPrefs))
            navMenu.findItem(R.id.open_root_log).setVisible(true);

        navMenu.findItem(R.id.action_donate).setVisible(false);
        checkPurchasesAvailable();
    }

    @Override
    public void onBackPressed() {
        if(mDrawer.isDrawerOpen(GravityCompat.START))
            mDrawer.closeDrawer(GravityCompat.START, true);
        else {
            if(mPager.getCurrentItem() == POS_CONNECTIONS) {
                Fragment fragment = getFragment(ConnectionsFragment.class);

                if((fragment != null) && ((ConnectionsFragment)fragment).onBackPressed())
                    return;
            }

            super.onBackPressed();
        }
    }

    private void checkPermissions() {
        String fname = "test.pcap";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, fname);

        if(!Utils.supportsFileDialog(this, intent)) {
            if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // Needed to write file on devices which do not support ACTION_CREATE_DOCUMENT
                    if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        try {
                            requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                        } catch (ActivityNotFoundException e) {
                            Utils.showToastLong(this, R.string.no_intent_handler_found);
                        }
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if(shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    AlertDialog dialog = new AlertDialog.Builder(this)
                            .setMessage(R.string.notifications_notice)
                            .setPositiveButton(R.string.ok, (d, whichButton) -> requestNotificationPermission())
                            .show();

                    dialog.setCanceledOnTouchOutside(false);
                } else
                    requestNotificationPermission();
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void requestNotificationPermission() {
        try {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
        } catch (ActivityNotFoundException e) {
            Utils.showToastLong(this, R.string.no_intent_handler_found);
        }
    }

    // On debug builds, if the user also has the non-debug app installed (peer app), unlock the
    // already-purchased features also on this beta app
    private void initPeerAppInfo() {
        if(!BuildConfig.DEBUG)
            return;

        final String peerAppPackage = "com.emanuelef.remote_capture";

        AppDescriptor peer = AppsResolver.resolve(getPackageManager(), peerAppPackage, 0);
        if(peer == null) {
            Log.d(TAG, "Peer app not found");
            return;
        }

        PackageInfo pInfo = peer.getPackageInfo();
        if((pInfo == null) || (pInfo.versionCode < 56)) {
            Log.d(TAG, "Unsupported peer app version found");
            return;
        }

        // Verify that the peer signature
        Utils.BuildType buildType = Utils.getVerifiedBuild(this, peerAppPackage);
        if((buildType != Utils.BuildType.FDROID) && (buildType != Utils.BuildType.PLAYSTORE) && (buildType != Utils.BuildType.GITHUB)) {
            Log.d(TAG, "Unsupported peer app build: " + buildType.name());
            return;
        }

        Log.d(TAG, "Valid peer app found (" + pInfo.versionName + " - " + pInfo.versionCode + ")");
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(peerAppPackage, "com.emanuelef.remote_capture.activities.CaptureCtrl");
        intent.putExtra("action", "get_peer_info");

        try {
            peerInfoLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "Peer app launch failed");
        }
    }

    private void peerInfoResult(final ActivityResult result) {
        if((result.getResultCode() == RESULT_OK) && (result.getData() != null)) {
            Intent data = result.getData();
            Serializable skus_extra = data.getSerializableExtra("skus");
            if(skus_extra instanceof HashSet) {
                HashSet<String> skus = (HashSet<String>) skus_extra;
                Log.d(TAG, "Found peer app info");

                for(String sku: skus) {
                    Log.d(TAG, "Peer sku: " + sku);
                    mIab.addPeerSku(sku);
                }

                return;
            }
        }

        Log.d(TAG, "Invalid peer app result");
    }

    private static class MainStateAdapter extends FragmentStateAdapter {
        MainStateAdapter(final FragmentActivity fa) { super(fa); }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            Log.d(TAG, "createFragment");

            switch (position) {
                default: // Deliberate fall-through to status tab
                case POS_STATUS:
                    return new StatusFragment();
                case POS_CONNECTIONS:
                    return new ConnectionsFragment();
            }
        }

        @Override
        public int getItemCount() {  return TOTAL_COUNT;  }

        public int getPageTitle(final int position) {
            switch (position) {
                default: // Deliberate fall-through to status tab
                case POS_STATUS:
                    return R.string.status;
                case POS_CONNECTIONS:
                    return R.string.connections_view;
            }
        }
    }

    private void setupTabs() {
        final MainStateAdapter stateAdapter = new MainStateAdapter(this);
        mPager.setAdapter(stateAdapter);

        new TabLayoutMediator(findViewById(R.id.tablayout), mPager, (tab, position) ->
                tab.setText(getString(stateAdapter.getPageTitle(position)))
        ).attach();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // This is required to properly handle the DPAD down press on Android TV, to properly
        // focus the tab content
        if(keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            View view = getCurrentFocus();

            Log.d(TAG, "onKeyDown focus " + view.getClass().getName());

            if(view instanceof TabLayout.TabView) {
                int pos = mPager.getCurrentItem();
                View focusOverride = null;

                Log.d(TAG, "TabLayout.TabView focus pos " + pos);

                if(pos == POS_STATUS)
                    focusOverride = findViewById(R.id.main_screen);
                else if(pos == POS_CONNECTIONS)
                    focusOverride = findViewById(R.id.connections_view);

                if(focusOverride != null) {
                    focusOverride.requestFocus();
                    return true;
                }
            }
        } else if(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Clicking "right" from the connections view goes to the fab down item
            if(mPager.getCurrentItem() == POS_CONNECTIONS) {
                RecyclerView rview = findViewById(R.id.connections_view);

                if(rview.getFocusedChild() != null) {
                    Log.d(TAG, "onKeyDown (right) focus " + rview.getFocusedChild());

                    View fab = findViewById(R.id.fabDown);

                    if(fab != null) {
                        fab.requestFocus();
                        return true;
                    }
                }
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.item_apps) {
            if (CaptureService.getConnsRegister() != null) {
                Intent intent = new Intent(MainActivity.this, AppsActivity.class);
                startActivity(intent);
            } else
                Utils.showToast(this, R.string.start_capture_first);
        } else if (id == R.id.malware_detection) {
            Intent intent = new Intent(MainActivity.this, MalwareDetection.class);
            startActivity(intent);
        } else if(id == R.id.firewall) {
            Intent intent = new Intent(MainActivity.this, FirewallActivity.class);
            startActivity(intent);
        } else if (id == R.id.open_root_log) {
            Intent intent = new Intent(MainActivity.this, LogviewActivity.class);
            startActivity(intent);
        } else if (id == R.id.action_donate) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL));
            Utils.startActivity(this, browserIntent);
        } else if (id == R.id.paid_features) {
            Intent intent = new Intent(MainActivity.this, IABActivity.class);
            startActivity(intent);
        } else if (id == R.id.action_open_telegram) {
            openTelegram();
        } else if (id == R.id.action_open_user_guide) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(DOCS_URL));
            Utils.startActivity(this, browserIntent);
        } else if (id == R.id.action_stats) {
            if(mState == AppState.running) {
                Intent intent = new Intent(MainActivity.this, StatsActivity.class);
                startActivity(intent);
            } else
                Utils.showToast(this, R.string.start_capture_first);
        } else if (id == R.id.action_about) {
            Intent intent = new Intent(MainActivity.this, AboutActivity.class);
            startActivity(intent);
        } else if (id == R.id.action_share_app) {
            String description = getString(R.string.about_text);
            String getApp = getString(R.string.get_app);
            String url = "https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture";

            Intent intent = new Intent(android.content.Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(android.content.Intent.EXTRA_TEXT, description + "\n" + getApp + "\n" + url);

            Utils.startActivity(this, Intent.createChooser(intent, getResources().getString(R.string.share)));
        }

        return false;
    }

    public void setAppStateListener(AppStateListener listener) {
        mListener = listener;
    }

    private void notifyAppState() {
        if(mListener != null)
            mListener.appStateChanged(mState);
    }

    public void appStateReady() {
        mState = AppState.ready;
        notifyAppState();
    }

    public void appStateStarting() {
        mState = AppState.starting;
        notifyAppState();
    }

    public void appStateRunning() {
        mState = AppState.running;
        notifyAppState();

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            checkVpnLockdownNotice();
    }

    public void appStateStopping() {
        mState = AppState.stopping;
        notifyAppState();
    }

    @RequiresApi(api = Build.VERSION_CODES.Q)
    private void checkVpnLockdownNotice() {
        if(!Prefs.lockdownVpnNoticeShown(mPrefs) && Prefs.isFirewallEnabled(this, mPrefs) && !CaptureService.isLockdownVPN()) {
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setMessage(R.string.vpn_lockdown_notice)
                    .setPositiveButton(R.string.yes, (d, whichButton) -> Utils.startActivity(this, new Intent("android.net.vpn.SETTINGS")))
                    .setNegativeButton(R.string.no, (d, whichButton) -> {})
                    .show();
            dialog.setCanceledOnTouchOutside(false);

            Prefs.setLockdownVpnNoticeShown(mPrefs);
        }
    }

    private void openTelegram() {
        Intent intent;

        try {
            getPackageManager().getPackageInfo("org.telegram.messenger", 0);

            // Open directly into the telegram app
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=" + TELEGRAM_GROUP_NAME));
        } catch (Exception e) {
            // Telegram not found, open in the browser
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://t.me/" + TELEGRAM_GROUP_NAME));
        }

        Utils.startActivity(this, intent);
    }

    /*private void rateApp() {
        try {
            // If playstore is installed
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + this.getPackageName())));
        } catch (android.content.ActivityNotFoundException e) {
            // If playstore is not available
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=" + this.getPackageName())));
        }
    }*/

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.action_start) {
            startCapture();
            return true;
        } else if(id == R.id.action_stop) {
            stopCapture();
            return true;
        } else if (id == R.id.action_settings) {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void pcapFileResult(final ActivityResult result) {
        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
            startWithPcapFile(result.getData().getData(),
                    (result.getData().getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0);
        } else {
            mPcapUri = null;
        }
    }

    private void startWithPcapFile(Uri uri, boolean persistable) {
        mPcapUri = uri;
        mPcapFname = null;
        boolean hasPermission = false;

        /* FLAG_GRANT_READ_URI_PERMISSION required for showPcapActionDialog (e.g. when auto-started at boot) */
        int peristMode = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

        // Revoke the previous permissions
        for(UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if(!permission.getUri().equals(uri)) {
                Log.d(TAG, "Releasing URI permission: " + permission.getUri().toString());
                getContentResolver().releasePersistableUriPermission(permission.getUri(), peristMode);
            } else
                hasPermission = true;
        }

        /* Request a persistent permission to write this URI without invoking the system picker.
         * This is needed to write to the URI when invoking PCAPdroid from other apps via Intents
         * or when starting the capture at boot. */
        if(persistable && !hasPermission) {
            try {
                getContentResolver().takePersistableUriPermission(uri, peristMode);
            } catch (SecurityException e) {
                // This should never occur
                Log.e(TAG, "Could not get PersistableUriPermission");
                e.printStackTrace();
                persistable = false;
            }
        }

        // Save the URI as a preference
        mPrefs.edit().putString(Prefs.PREF_PCAP_URI, mPcapUri.toString()).apply();

        // NOTE: part of app_api.md
        Log.d(TAG, "PCAP URI to write [persistable=" + persistable + "]: " + mPcapUri.toString());
        startCapture();
    }

    private void initAppState() {
        boolean is_active = CaptureService.isServiceActive();

        if (!is_active)
            appStateReady();
        else
            appStateRunning();
    }

    private void doStartCaptureService() {
        appStateStarting();
        mCapHelper.startCapture(new CaptureSettings(mPrefs));
    }

    public void startCapture() {
        if(showRemoteServerAlert())
            return;

        if(Prefs.getTlsDecryptionEnabled(mPrefs) && MitmAddon.needsSetup(this)) {
            Intent intent = new Intent(this, MitmSetupWizard.class);
            startActivity(intent);
            return;
        }

        if((mPcapUri == null) && (Prefs.getDumpMode(mPrefs) == Prefs.DumpMode.PCAP_FILE)) {
            openFileSelector();
            return;
        }

        if(!Prefs.isRootCaptureEnabled(mPrefs) && Utils.hasVPNRunning(this)) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.disconnect_vpn_confirm)
                    .setPositiveButton(R.string.yes, (dialog, whichButton) -> doStartCaptureService())
                    .setNegativeButton(R.string.no, (dialog, whichButton) -> {})
                    .show();
        } else
            doStartCaptureService();
    }

    public void stopCapture() {
        appStateStopping();
        CaptureService.stopService();
    }

    // see also CaptureCtrl.checkRemoteServerNotAllowed
    private boolean showRemoteServerAlert() {
        if(mPrefs.getBoolean(Prefs.PREF_REMOTE_COLLECTOR_ACK, false))
            return false; // already acknowledged

        if(((Prefs.getDumpMode(mPrefs) == Prefs.DumpMode.UDP_EXPORTER) && !Utils.isLocalNetworkAddress(Prefs.getCollectorIp(mPrefs))) ||
                (Prefs.getSocks5Enabled(mPrefs) && !Utils.isLocalNetworkAddress(Prefs.getSocks5ProxyAddress(mPrefs)))) {
            Log.i(TAG, "Showing possible scan notice");

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.warning)
                    .setMessage(R.string.remote_collector_notice)
                    .setPositiveButton(R.string.ok, (d, whichButton) -> mPrefs.edit().putBoolean(Prefs.PREF_REMOTE_COLLECTOR_ACK, true).apply())
                    .show();
            dialog.setCanceledOnTouchOutside(false);
            return true;
        }

        return false;
    }

    private void openFileSelector() {
        boolean noFileDialog = false;
        String fname = Utils.getUniquePcapFileName(this);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, fname);

        if(Utils.supportsFileDialog(this, intent)) {
            try {
                pcapFileLauncher.launch(intent);
            } catch (ActivityNotFoundException e) {
                noFileDialog = true;
            }
        } else
            noFileDialog = true;

        if(noFileDialog) {
            Log.w(TAG, "No app found to handle file selection");

            // Pick default path
            Uri uri = Utils.getInternalStorageFile(this, fname);

            if(uri != null) {
                usingMediaStore = true;

                // NOTE: cannot be persisted as it was not invoked via Intent
                startWithPcapFile(uri, false);
            } else
                Utils.showToastLong(this, R.string.no_activity_file_selection);
        }
    }

    public void showPcapActionDialog(Uri pcapUri) {
        Cursor cursor;

        Log.d(TAG, "showPcapActionDialog: " + pcapUri.toString());

        try {
            cursor = getContentResolver().query(pcapUri, null, null, null, null);
        } catch (Exception e) {
            return;
        }

        if((cursor == null) || !cursor.moveToFirst())
            return;

        int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
        long file_size = !cursor.isNull(sizeIndex) ? cursor.getLong(sizeIndex) : -1;
        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        String fname = (idx >= 0) ? cursor.getString(idx) : "*unknown*";
        cursor.close();

        // If file is empty, delete it
        // NOTE: the user may want to get a PersistableUriPermission, so don't auto delete the file
        /*if(file_size == 0) {
            Log.d(TAG, "PCAP file is empty, deleting");

            try {
                if(usingMediaStore)
                    getContentResolver().delete(pcapUri, null, null);
                else
                    DocumentsContract.deleteDocument(getContentResolver(), pcapUri);
            } catch (FileNotFoundException | UnsupportedOperationException e) {
                e.printStackTrace();
            }

            return;
        }*/

        String message = String.format(getResources().getString(R.string.pcap_file_action), fname, Utils.formatBytes(file_size));

        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setMessage(message);

        builder.setPositiveButton(R.string.share, (dialog, which) -> {
            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("application/cap");
            sendIntent.putExtra(Intent.EXTRA_STREAM, pcapUri);
            Utils.startActivity(this, Intent.createChooser(sendIntent, getResources().getString(R.string.share)));
        });
        builder.setNegativeButton(R.string.delete, (dialog, which) -> {
            Log.d(TAG, "Deleting PCAP file" + pcapUri.getPath());
            boolean deleted = false;

            try {
                if(usingMediaStore)
                    deleted = (getContentResolver().delete(pcapUri, null, null) == 1);
                else
                    deleted = DocumentsContract.deleteDocument(getContentResolver(), pcapUri);
            } catch (FileNotFoundException | UnsupportedOperationException e) {
                e.printStackTrace();
            }

            if(!deleted)
                Utils.showToast(MainActivity.this, R.string.delete_error);

            dialog.cancel();
        });
        builder.setNeutralButton(R.string.ok, (dialog, which) -> dialog.cancel());
        builder.setOnDismissListener(dialogInterface -> {
            if(mKeylogFile != null)
                startExportSslkeylogfile();
        });

        builder.create().show();
    }

    public AppState getState() {
        return(mState);
    }

    public String getPcapFname() {
        if((mState == AppState.running) && (mPcapUri != null)) {
            if(mPcapFname != null)
                return mPcapFname;

            Cursor cursor;

            try {
                cursor = getContentResolver().query(mPcapUri, null, null, null, null);
            } catch (Exception e) {
                return null;
            }

            if((cursor == null) || !cursor.moveToFirst())
                return null;

            int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            String fname = (idx >= 0) ? cursor.getString(idx) : "*unknown*";
            cursor.close();

            mPcapFname = fname;
            return fname;
        }

        return null;
    }

    private void startExportSslkeylogfile() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, "sslkeylogfile.txt");

        Log.d(TAG, "startExportSslkeylogfile: launching dialog");
        Utils.launchFileDialog(this, intent, sslkeyfileExportLauncher);
    }

    private void sslkeyfileExportResult(final ActivityResult result) {
        if((result.getResultCode() == RESULT_OK) && (result.getData() != null) && (mKeylogFile != null)) {
            try(OutputStream out = getContentResolver().openOutputStream(result.getData().getData(), "rwt")) {
                Utils.copy(mKeylogFile, out);
                Utils.showToast(this, R.string.save_ok);
            } catch (IOException e) {
                e.printStackTrace();
                Utils.showToastLong(this, R.string.export_failed);
            }
        }

        mKeylogFile = null;
    }
}
