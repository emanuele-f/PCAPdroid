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
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
import androidx.core.content.pm.PackageInfoCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.PlayBilling;
import com.emanuelef.remote_capture.BuildConfig;
import com.emanuelef.remote_capture.CaptureHelper;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.MitmReceiver;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.VpnReconnectService;
import com.emanuelef.remote_capture.activities.prefs.SettingsActivity;
import com.emanuelef.remote_capture.fragments.ConnectionsFragment;
import com.emanuelef.remote_capture.fragments.StatusFragment;
import com.emanuelef.remote_capture.interfaces.AppStateListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppState;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.model.Blocklist;
import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.MitmAddon;
import com.emanuelef.remote_capture.model.CaptureStats;
import com.emanuelef.remote_capture.model.ListInfo;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BaseActivity implements NavigationView.OnNavigationItemSelectedListener {
    private PlayBilling mIab;
    private ViewPager2 mPager;
    private AppState mState;
    private AppStateListener mListener;
    private File mKeylogFile;
    private DrawerLayout mDrawer;
    private SharedPreferences mPrefs;
    private NavigationView mNavView;
    private CaptureHelper mCapHelper;
    private AlertDialog mPcapLoadDialog;
    private Uri mPcapUri;
    private ExecutorService mPcapExecutor;

    // helps detecting duplicate state reporting of STOPPED in MutableLiveData
    private boolean mWasStarted = false;
    private boolean mStartPressed = false;
    private boolean mDecEmptyRulesNoticeShown = false;
    private boolean mExtensionsNoticeShown = false;
    private boolean mOpenPcapDecrypt = false;
    private boolean mDecryptPcap = false;

    private static final String TAG = "Main";

    private static final int POS_STATUS = 0;
    private static final int POS_CONNECTIONS = 1;
    private static final int TOTAL_COUNT = 2;

    public static final String TELEGRAM_GROUP_NAME = "PCAPdroid";
    public static final String GITHUB_PROJECT_URL = "https://github.com/emanuele-f/PCAPdroid";
    public static final String DOCS_URL = "https://emanuele-f.github.io/PCAPdroid";
    public static final String PRIVACY_POLICY_URL = DOCS_URL + "/privacy";
    public static final String DONATE_URL = "https://emanuele-f.github.io/PCAPdroid/donate";
    public static final String TLS_DECRYPTION_DOCS_URL = DOCS_URL + "/tls_decryption";
    public static final String PAID_FEATURES_URL = DOCS_URL + "/paid_features";
    public static final String FIREWALL_DOCS_URL = PAID_FEATURES_URL + "#51-firewall";
    public static final String MALWARE_DETECTION_DOCS_URL = PAID_FEATURES_URL + "#52-malware-detection";
    public static final String API_DOCS_URL = GITHUB_PROJECT_URL + "/blob/master/docs/app_api.md";
    public static final String PCAPNG_DOCS_URL = PAID_FEATURES_URL + "#53-pcapng-format";

    private final ActivityResultLauncher<Intent> sslkeyfileExportLauncher =
            registerForActivityResult(new StartActivityForResult(), this::sslkeyfileExportResult);
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new RequestPermission(), isGranted ->
                Log.d(TAG, "Write permission " + (isGranted ? "granted" : "denied"))
            );
    private final ActivityResultLauncher<Intent> peerInfoLauncher =
            registerForActivityResult(new StartActivityForResult(), this::peerInfoResult);
    private final ActivityResultLauncher<Intent> pcapFileOpenLauncher =
            registerForActivityResult(new StartActivityForResult(), this::pcapFileOpenResult);
    private final ActivityResultLauncher<Intent> keylogFileOpenLauncher =
            registerForActivityResult(new StartActivityForResult(), this::keylogFileOpenResult);

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
            // only refresh app version on on-boarding done
            Intent intent = new Intent(MainActivity.this, OnBoardingActivity.class);
            startActivity(intent);
            finish();
            return;
        } else {
            if (appver < 79)
                showWhatsNew();

            Prefs.refreshAppVersion(mPrefs);
        }

        mIab = new PlayBilling(this);
        mIab.setPurchaseReadyListener(new PlayBilling.PurchaseReadyListener() {
            @Override
            public void onPurchasesReady() {
                checkPurchasesAvailable();
                checkPaidDrawerEntries();
            }
        });

        initPeerAppInfo();
        initAppState();
        checkPermissions();

        mCapHelper = new CaptureHelper(this, true);
        mCapHelper.setListener(success -> {
            if(!success) {
                Log.w(TAG, "Capture start failed");
                appStateReady();
            }
        });

        mPager = findViewById(R.id.pager);
        Utils.fixViewPager2Insets(mPager);
        setupTabs();

        /* Register for service status */
        CaptureService.observeStatus(this, serviceStatus -> {
            Log.d(TAG, "Service status: " + serviceStatus.name());

            if (serviceStatus == CaptureService.ServiceStatus.STARTED) {
                appStateRunning();
                mWasStarted = true;
            } else if(mWasStarted) { /* STARTED -> STOPPED */
                // The service may still be active (on premature native termination)
                if (CaptureService.isServiceActive())
                    CaptureService.stopService();

                mKeylogFile = MitmReceiver.getKeylogFilePath(MainActivity.this);
                if(!mKeylogFile.exists() || !CaptureService.isDecryptingTLS())
                    mKeylogFile = null;

                Log.d(TAG, "sslkeylog? " + (mKeylogFile != null));

                CaptureSettings settings = CaptureService.getCaptureSettings();

                // do not show "PCAP saved" dialog and keylog export if capture was started through API
                if((settings != null) && settings.api_capture) {
                    if(mKeylogFile != null) {
                        // save SSLKEYLOGFILE to Downloads directory
                        if (!settings.sslkeylog_name.isBlank()) {
                            Uri uri = Utils.getDownloadsUri(MainActivity.this, settings.sslkeylog_name);
                            if (uri != null) {
                                try (OutputStream out = getContentResolver().openOutputStream(uri, "rwt")) {
                                    Utils.copy(mKeylogFile, out);
                                    Utils.showToast(this, R.string.save_ok);
                                } catch (IOException e) {
                                    e.printStackTrace();
                                    Utils.showToastLong(this, R.string.export_failed);
                                }
                            } else
                                Log.e(TAG, "Cannot export keylog to " + settings.sslkeylog_name);
                        }

                        // if capture was started through API, we automatically delete the keylog here after optionally dumping it to provided file
                        //noinspection ResultOfMethodCallIgnored
                        mKeylogFile.delete();
                        mKeylogFile = null;
                    }

                    // reset the decryption list after API capture in case it was set
                    if(!settings.decryption_rules_json.isBlank())
                        PCAPdroid.getInstance().getDecryptionList().reload();
                } else {
                    if ((Prefs.getDumpMode(mPrefs) == Prefs.DumpMode.PCAP_FILE)) {
                        showPcapActionDialog();

                        // will export the keylogfile after saving/sharing pcap
                    } else if (mKeylogFile != null)
                        startExportSslkeylogfile();
                }
                appStateReady();
                mWasStarted = false;
                mStartPressed = false;
            } else /* STOPPED -> STOPPED */
                appStateReady();
        });
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

        if(!CaptureService.isServiceActive()) {
            boolean ignored = getTmpPcapPath().delete();
        }

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

        if(mNavView != null) {
            Menu navMenu = mNavView.getMenu();
            navMenu.findItem(R.id.tls_decryption).setVisible(Prefs.getTlsDecryptionEnabled(mPrefs) && !Prefs.isRootCaptureEnabled(mPrefs));
        }

        checkPaidDrawerEntries();
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

        ViewCompat.setOnApplyWindowInsetsListener(mDrawer, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                    WindowInsetsCompat.Type.displayCutout());

            // layout hamburger menu and drawer when in horizontal orientation
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            mlp.leftMargin = insets.left;
            mlp.rightMargin = insets.right;

            // only pass down the vertical insets
            return windowInsets.inset(insets.left, 0, insets.right, 0);
        });

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

        navMenu.findItem(R.id.action_donate).setVisible(false);
        checkPurchasesAvailable();
    }

    private void showWhatsNew() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.whats_new)
                .setMessage(
                        "- Android 15 support with edge-to-edge UI\n" +
                        (PCAPdroid.getInstance().isUsharkAvailable() ?
                                "- Decrypt PCAP/Pcapng files (Wireshark integration)\n" : "") +
                        "- New firewall rules: block by country and by CIDR\n" +
                        "- Add support for PCAPdroid extensions in Pcapng\n" +
                        "- Reduced RAM usage of malware blacklists\n"
                )
                .setNeutralButton(R.string.ok, (dialogInterface, i) -> {})
                .show();
    }

    // keep this in a separate function, used by play billing code
    private void checkPaidDrawerEntries() {
        if(mNavView == null)
            return;
        Menu navMenu = mNavView.getMenu();
        navMenu.findItem(R.id.malware_detection).setVisible(Prefs.isMalwareDetectionEnabled(this, mPrefs));
        navMenu.findItem(R.id.firewall).setVisible(mIab.isFirewallVisible());
    }

    @Override
    @SuppressWarnings("deprecation")
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
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Needed to write PCAP files
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    try {
                        requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    } catch (ActivityNotFoundException e) {
                        Utils.showToastLong(this, R.string.no_intent_handler_found);
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
        if(!BuildConfig.APPLICATION_ID.equals("com.emanuelef.remote_capture.debug"))
            return;

        final String peerAppPackage = "com.emanuelef.remote_capture";

        AppDescriptor peer = AppsResolver.resolveInstalledApp(getPackageManager(), peerAppPackage, 0);
        if(peer == null) {
            Log.d(TAG, "Peer app not found");
            mIab.clearPeerSkus();
            return;
        }

        PackageInfo pInfo = peer.getPackageInfo();
        if((pInfo == null) || (PackageInfoCompat.getLongVersionCode(pInfo) < 56)) {
            Log.d(TAG, "Unsupported peer app version found");
            mIab.clearPeerSkus();
            return;
        }

        // Verify that the peer signature
        Utils.BuildType buildType = Utils.getVerifiedBuild(this, peerAppPackage);
        if((buildType != Utils.BuildType.FDROID) && (buildType != Utils.BuildType.PLAYSTORE) && (buildType != Utils.BuildType.GITHUB)) {
            Log.d(TAG, "Unsupported peer app build: " + buildType.name());
            mIab.clearPeerSkus();
            return;
        }

        Log.d(TAG, "Valid peer app found (" + pInfo.versionName + " - " + PackageInfoCompat.getLongVersionCode(pInfo) + ")");
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(peerAppPackage, "com.emanuelef.remote_capture.activities.CaptureCtrl");
        intent.putExtra("action", "get_peer_info");

        try {
            peerInfoLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Log.d(TAG, "Peer app launch failed");
            mIab.clearPeerSkus();
        }
    }

    private void peerInfoResult(final ActivityResult result) {
        if((result.getResultCode() == RESULT_OK) && (result.getData() != null)) {
            Intent data = result.getData();

            try {
                @SuppressWarnings("unchecked")
                HashSet<String> skus = Utils.getSerializableExtra(data, "skus", HashSet.class);

                if(skus != null) {
                    Log.d(TAG, "Found peer app info");

                    mIab.handlePeerSkus(skus);

                    // success
                    return;
                }
            } catch (ClassCastException ignored) {}
        }

        // fail
        Log.d(TAG, "Invalid peer app result");
        mIab.clearPeerSkus();
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
        } else if(id == R.id.tls_decryption) {
            Intent intent = new Intent(MainActivity.this, EditListActivity.class);
            intent.putExtra(EditListActivity.LIST_TYPE_EXTRA, ListInfo.Type.DECRYPTION_LIST);
            startActivity(intent);
        } else if(id == R.id.firewall) {
            Intent intent = new Intent(MainActivity.this, FirewallActivity.class);
            startActivity(intent);
        } else if(id == R.id.open_log) {
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

        if(mPcapLoadDialog != null)
            checkLoadedPcap();
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
        else if(mStartPressed) { // STOPPED -> STARTED
            if(CaptureService.isDecryptingTLS() && !CaptureService.isCapturingAsRoot())
                checkDecryptionRulesNotice();
        }

        if (mIab.isFirewallVisible()) {
            Blocklist blocklist = PCAPdroid.getInstance().getBlocklist();
            if (blocklist.hasCountryRules())
                blocklist.showNoticeIfGeoMissing(this);
        }
    }

    public void appStateStopping() {
        mState = AppState.stopping;
        notifyAppState();
    }

    private void checkDecryptionRulesNotice() {
        if(!mDecEmptyRulesNoticeShown && PCAPdroid.getInstance().getDecryptionList().isEmpty()) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.tls_decryption_no_rules_notice)
                    .setPositiveButton(R.string.yes, (d, whichButton) -> {
                        Intent intent = new Intent(MainActivity.this, EditListActivity.class);
                        intent.putExtra(EditListActivity.LIST_TYPE_EXTRA, ListInfo.Type.DECRYPTION_LIST);
                        startActivity(intent);
                    })
                    .setNegativeButton(R.string.no, (d, whichButton) -> {
                    })
                    .show();
            mDecEmptyRulesNoticeShown = true;
        }
    }

    private void dismissPcapLoadDialog() {
        if(mPcapLoadDialog != null) {
            mPcapLoadDialog.dismiss();
            mPcapLoadDialog = null;
        }

        mPcapExecutor = null;
        mPcapUri = null;
    }

    private void checkLoadedPcap() {
        dismissPcapLoadDialog();

        if(!CaptureService.hasError()) {
            // pcap file loaded successfully
            ConnectionsRegister reg = CaptureService.getConnsRegister();

            if((reg != null) && (reg.getConnCount() > 0)
                    && !CaptureService.hasSeenDumpExtensions()
                    && !mExtensionsNoticeShown
            ) {
                new AlertDialog.Builder(this)
                        .setMessage(getString(R.string.pcapdroid_trailer_notice,
                                getString(R.string.unknown_app), getString(R.string.dump_extensions)))
                        .setPositiveButton(R.string.ok, (d, whichButton) -> mExtensionsNoticeShown = true)
                        .show();
            } else
                Utils.showToastLong(this, R.string.pcap_load_success);

            mPager.setCurrentItem(POS_CONNECTIONS);
        }
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
            Utils.getPackageInfo(getPackageManager(), "org.telegram.messenger", 0);

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
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.action_start) {
            mStartPressed = true;
            startCapture();
            return true;
        } else if(id == R.id.action_stop) {
            stopCapture();
            return true;
        } else if(id == R.id.open_pcap) {
            selectOpenPcapFile(false);
            return true;
        } else if(id == R.id.decrypt_pcap) {
            selectOpenPcapFile(true);
            return true;
        } else if (id == R.id.action_settings) {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void initAppState() {
        boolean is_active = CaptureService.isServiceActive();

        if (!is_active) {
            appStateReady();

            // PCAPdroid could have been closed unexpectedly (e.g. due to low memory), try to export
            // the keylog file if exists
            mKeylogFile = MitmReceiver.getKeylogFilePath(MainActivity.this);
            if(mKeylogFile.exists())
                startExportSslkeylogfile();
        } else
            appStateRunning();
    }

    private void doStartCaptureService(String input_pcap_path) {
        if (mCapHelper == null) {
            Log.e(TAG, "Activity destroyed, capture cannot start");
            return;
        }

        appStateStarting();

        PCAPdroid.getInstance().setIsDecryptingPcap(mDecryptPcap);
        mDecryptPcap = false;

        CaptureSettings settings = new CaptureSettings(this, mPrefs);
        settings.input_pcap_path = input_pcap_path;

        // to properly show decrypted PCAP files, full payload must be enabled
        if (PCAPdroid.getInstance().isDecryptingPcap())
            settings.full_payload = true;

        mCapHelper.startCapture(settings);
    }

    public void startCapture() {
        if (VpnReconnectService.isAvailable())
            VpnReconnectService.stopService();

        if(showRemoteServerAlert())
            return;

        if(Prefs.getTlsDecryptionEnabled(mPrefs)) {
            if (MitmAddon.needsSetup(this)) {
                Intent intent = new Intent(this, MitmSetupWizard.class);
                startActivity(intent);
                return;
            }

            if (!MitmAddon.getNewVersionAvailable(this).isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.update_available)
                        .setMessage(R.string.mitm_addon_update_available)
                        .setCancelable(false)
                        .setPositiveButton(R.string.update_action, (dialog, whichButton) -> {
                            Intent intent = new Intent(this, MitmSetupWizard.class);
                            startActivity(intent);
                        })
                        .setNegativeButton(R.string.cancel_action, (dialog, whichButton) -> {
                            MitmAddon.ignoreNewVersion(this);
                            startCapture();
                        })
                        .show();

                return;
            }
        }

        if(!Prefs.isRootCaptureEnabled(mPrefs) && (Utils.getRunningVpn(this) != null)) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.active_vpn_detected)
                    .setMessage(R.string.disconnect_vpn_confirm)
                    .setPositiveButton(R.string.ok, (dialog, whichButton) -> doStartCaptureService(null))
                    .setNegativeButton(R.string.cancel_action, (dialog, whichButton) -> {})
                    .show();
        } else
            doStartCaptureService(null);
    }

    public void stopCapture() {
        appStateStopping();
        CaptureService.stopService();
    }

    // see also CaptureCtrl.checkRemoteServerNotAllowed
    private boolean showRemoteServerAlert() {
        if(mPrefs.getBoolean(Prefs.PREF_REMOTE_COLLECTOR_ACK, false))
            return false; // already acknowledged

        boolean exporterEnabled = (Prefs.getDumpMode(mPrefs) == Prefs.DumpMode.UDP_EXPORTER) ||
                (Prefs.getDumpMode(mPrefs) == Prefs.DumpMode.TCP_EXPORTER);

        if((exporterEnabled && !Utils.isLocalNetworkAddress(Prefs.getCollectorIp(mPrefs))) ||
                (Prefs.getSocks5Enabled(mPrefs) && !Utils.isLocalNetworkAddress(Prefs.getSocks5ProxyHost(mPrefs)))) {
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

    public void showPcapActionDialog() {
        Log.d(TAG, "showPcapActionDialog called");

        if(CaptureService.isUserDefinedPcapUri())
            return;

        Uri pcapUri = CaptureService.getPcapUri();
        if(pcapUri == null)
            return;

        CaptureStats stats = CaptureService.getStats();
        Log.d(TAG, "Pcap dump size is " + stats.pcap_dump_size);

        if(stats.pcap_dump_size <= 0) {
            deletePcapFile(pcapUri); // empty file, delete
            return;
        }

        String pcapName = CaptureService.getPcapFname();
        if(pcapName == null)
            pcapName = "unknown";

        String message = String.format(getResources().getString(R.string.pcap_file_action), pcapName, Utils.formatBytes(stats.pcap_dump_size));

        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setMessage(message);

        builder.setPositiveButton(R.string.share, (dialog, which) -> {
            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("application/cap");
            sendIntent.putExtra(Intent.EXTRA_STREAM, pcapUri);
            sendIntent.setClipData(ClipData.newRawUri("", pcapUri));
            sendIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Utils.startActivity(this, Intent.createChooser(sendIntent, getResources().getString(R.string.share)));
        });
        builder.setNegativeButton(R.string.delete, (dialog, which) -> deletePcapFile(pcapUri));
        builder.setNeutralButton(R.string.ok, (dialog, which) -> {});
        builder.setOnDismissListener(dialogInterface -> {
            // also export the keylog
            if(mKeylogFile != null)
                startExportSslkeylogfile();
        });

        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
    }

    private void deletePcapFile(Uri pcapUri) {
        boolean deleted = false;

        // The getContentResolver().delete in some Android versions does not work, try to delete
        // using file path first
        String fpath = Utils.uriToFilePath(this, pcapUri);
        if(fpath != null) {
            Log.d(TAG, "deletePcapFile: path=" + fpath);

            try {
                deleted = new File(fpath).delete();
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            Log.d(TAG, "deletePcapFile: uri=" + pcapUri);

            try {
                deleted = (getContentResolver().delete(pcapUri, null, null) == 1);
            } catch (UnsupportedOperationException | SecurityException e) {
                e.printStackTrace();
            }
        }

        if(!deleted)
            Utils.showToast(MainActivity.this, R.string.delete_error);
    }

    public AppState getState() {
        return(mState);
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

        if(mKeylogFile != null) {
            // upon closing the dialog, delete the keylog

            //noinspection ResultOfMethodCallIgnored
            mKeylogFile.delete();
            mKeylogFile = null;
        }
    }

    private void selectOpenPcapFile(boolean decrypt) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        Log.d(TAG, "selectOpenPcapFile: launching dialog");
        mOpenPcapDecrypt = decrypt;
        if (mOpenPcapDecrypt)
            Utils.showToast(this, R.string.select_the_pcap_file);
        Utils.launchFileDialog(this, intent, pcapFileOpenLauncher);
    }

    private void pcapFileOpenResult(final ActivityResult result) {
        if ((result.getResultCode() == RESULT_OK) && (result.getData() != null)) {
            Uri uri = result.getData().getData();
            if (uri == null)
                return;

            Log.d(TAG, "pcapFileOpenResult: " + uri);
            if (mOpenPcapDecrypt && !Utils.isPcapng(this, uri)) {
                // Ask to select the keylog
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");

                Log.i(TAG, "separate keylog file needed, launching dialog");
                mPcapUri = uri;
                Utils.showToast(this, R.string.select_the_keylog_file);
                Utils.launchFileDialog(this, intent, keylogFileOpenLauncher);
            } else
                startOpenPcap(uri, null);
        }
    }

    private void keylogFileOpenResult(final ActivityResult result) {
        if ((result.getResultCode() == RESULT_OK) && (result.getData() != null)) {
            Uri uri = result.getData().getData();
            if (uri == null)
                return;

            Log.d(TAG, "keylogFileOpenResult: " + uri);
            startOpenPcap(mPcapUri, uri);
        }
    }

    private void startOpenPcap(Uri pcap_uri, Uri keylog_uri) {
        mPcapExecutor = Executors.newSingleThreadExecutor();

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.loading);
        builder.setMessage(R.string.pcap_load_in_progress);

        mPcapLoadDialog = builder.create();
        mPcapLoadDialog.setCanceledOnTouchOutside(false);
        mPcapLoadDialog.setOnCancelListener(dialogInterface -> {
            Log.i(TAG, "Abort PCAP loading");

            if (mPcapExecutor != null) {
                mPcapExecutor.shutdownNow();
                mPcapExecutor = null;
            }

            if (CaptureService.isServiceActive())
                CaptureService.stopService();

            Utils.showToastLong(this, R.string.pcap_file_load_aborted);
        });
        mPcapLoadDialog.setOnDismissListener(dialog -> mPcapLoadDialog = null);
        mPcapLoadDialog.show();

        // get an actual file path which can be read from the native side
        String path = Utils.uriToFilePath(this, pcap_uri);
        if((path == null) || !Utils.isReadable(path)) {
            // Unable to get a direct file path (e.g. for files in Downloads). Copy file to the
            // cache directory
            File out = getTmpPcapPath();
            out.deleteOnExit();
            String abs_path = out.getAbsolutePath();

            // PCAP file can be big, copy in a different thread
            mPcapExecutor.execute(() -> {
                try (InputStream in_stream = getContentResolver().openInputStream(pcap_uri)) {
                    Utils.copy(in_stream, out);
                } catch (IOException | RuntimeException e) {
                    e.printStackTrace();

                    runOnUiThread(() -> {
                        Utils.showToastLong(this, R.string.copy_error);
                        dismissPcapLoadDialog();
                    });
                    return;
                }

                runOnUiThread(() -> continueOpenPcap(abs_path, keylog_uri));
            });
        } else {
            Log.d(TAG, "pcapFileOpenResult: path: " + path);
            continueOpenPcap(path, keylog_uri);
        }
    }

    private void continueOpenPcap(String pcap_path, Uri keylog_uri) {
        //noinspection ResultOfMethodCallIgnored
        getKeylogPath().delete();

        if (mOpenPcapDecrypt)
            loadKeylogfile(pcap_path, keylog_uri);
        else
            doStartCaptureService(pcap_path);
    }

    private void loadKeylogfile(String pcap_path, Uri keylog_uri) {
        mPcapExecutor.execute(() -> {
            File out = getKeylogPath();
            out.deleteOnExit();

            if (keylog_uri != null) {
                // keylog is in a separate file
                try (InputStream in_stream = getContentResolver().openInputStream(keylog_uri)) {
                    Utils.copy(in_stream, out);
                } catch (IOException | RuntimeException e) {
                    e.printStackTrace();

                    runOnUiThread(() -> {
                        Utils.showToastLong(this, R.string.keylog_read_error);
                        dismissPcapLoadDialog();
                    });
                    return;
                }

                runOnUiThread(() -> {
                    mDecryptPcap = true;
                    doStartCaptureService(pcap_path);
                });
            } else {
                // keylog is from PCAPNG
                boolean success = CaptureService.extractKeylogFromPcapng(pcap_path, out.getAbsolutePath());

                runOnUiThread(() -> {
                    if (success && out.exists()) {
                        mDecryptPcap = true;
                        doStartCaptureService(pcap_path);
                    } else {
                        Utils.showToastLong(this, R.string.keylog_read_error);
                        dismissPcapLoadDialog();
                    }
                });
            }
        });
    }

    private File getTmpPcapPath() {
        return new File(getCacheDir() + "/tmp.pcap");
    }

    private File getKeylogPath() {
        // NOTE: keep in sync with run_libpcap
        return new File(getCacheDir() + "/sslkeylog.txt");
    }
}
