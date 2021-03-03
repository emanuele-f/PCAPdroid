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

package com.emanuelef.remote_capture.activities;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.VpnService;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.emanuelef.remote_capture.AppsLoader;
import com.emanuelef.remote_capture.fragments.ConnectionsFragment;
import com.emanuelef.remote_capture.fragments.StatusFragment;
import com.emanuelef.remote_capture.interfaces.AppStateListener;
import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppState;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import cat.ereza.customactivityoncrash.config.CaocConfig;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener, AppsLoadListener {
    private SharedPreferences mPrefs;
    private ViewPager2 mPager;
    private TabLayout mTabLayout;
    private AppState mState;
    private AppStateListener mListener;
    private Uri mPcapUri;
    private BroadcastReceiver mReceiver;
    private String mPcapFname;
    private Map<Integer, AppDescriptor> mInstalledApps;
    private List<AppsLoadListener> mAppsListeners;

    private static final String TAG = "Main";

    private static final int POS_STATUS = 0;
    private static final int POS_CONNECTIONS = 1;
    private static final int TOTAL_COUNT = 2;

    public static final int REQUEST_CODE_VPN = 2;
    public static final int REQUEST_CODE_PCAP_FILE = 3;
    public static final int REQUEST_CODE_CSV_FILE = 4;

    public static final String TELEGRAM_GROUP_NAME = "PCAPdroid";
    public static final String GITHUB_PROJECT_URL = "https://github.com/emanuele-f/PCAPdroid";
    public static final String GITHUB_DOCS_URL = "https://emanuele-f.github.io/PCAPdroid";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        initAppState();

        mPcapUri = CaptureService.getPcapUri();

        CaocConfig.Builder.create()
                .errorDrawable(R.drawable.ic_app_crash)
                .apply();

        setContentView(R.layout.main_activity);

        mTabLayout = findViewById(R.id.tablayout);
        mPager = findViewById(R.id.pager);
        mAppsListeners = new ArrayList<>();

        setupTabs();

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        (new AppsLoader(this))
                .setAppsLoadListener(this)
                .loadAllApps();

        /* Register for service status */
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String status = intent.getStringExtra(CaptureService.SERVICE_STATUS_KEY);

                if (status != null) {
                    if (status.equals(CaptureService.SERVICE_STATUS_STARTED)) {
                        appStateRunning();
                    } else if (status.equals(CaptureService.SERVICE_STATUS_STOPPED)) {
                        // The service may still be active (on premature native termination)
                        if (CaptureService.isServiceActive())
                            CaptureService.stopService();

                        if((mPcapUri != null) && (Prefs.getDumpMode(mPrefs) == Prefs.DumpMode.PCAP_FILE)) {
                            showPcapActionDialog(mPcapUri);
                            mPcapUri = null;
                            mPcapFname = null;
                        }

                        appStateReady();
                    }
                }
            }
        };

        LocalBroadcastManager.getInstance(this)
                .registerReceiver(mReceiver, new IntentFilter(CaptureService.ACTION_SERVICE_STATUS));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if(mReceiver != null)
            LocalBroadcastManager.getInstance(this)
                    .unregisterReceiver(mReceiver);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        setupNavigationDrawer();
    }

    private void setupNavigationDrawer() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        DrawerLayout drawer = findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView navView = findViewById(R.id.nav_view);
        navView.setNavigationItemSelectedListener(this);
        View header = navView.getHeaderView(0);

        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = pInfo.versionName;
            boolean isRelease = version.contains(".");
            final String verStr = isRelease ? ("v" + version) : version;
            TextView appVer = header.findViewById(R.id.app_version);

            appVer.setText(verStr);
            appVer.setOnClickListener((ev) -> {
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_PROJECT_URL + "/tree/" + verStr));
                startActivity(browserIntent);
            });
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not retrieve package version");
        }
    }

    private static class MyStateAdapter extends FragmentStateAdapter {
        MyStateAdapter(final FragmentActivity fa) { super(fa); }

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
    }

    private void setupTabs() {
        final MyStateAdapter stateAdapter = new MyStateAdapter(this);
        mPager.setAdapter(stateAdapter);

        new TabLayoutMediator(mTabLayout, mPager, (tab, position) -> {
            switch (position) {
                default: // Deliberate fall-through to status tab
                case POS_STATUS:
                    tab.setText(R.string.status);
                    break;
                case POS_CONNECTIONS:
                    tab.setText(R.string.connections_view);
                    break;
            }
        }).attach();
    }

    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.item_apps) {
            if(CaptureService.getConnsRegister() != null) {
                Intent intent = new Intent(MainActivity.this, AppsActivity.class);
                startActivity(intent);
            } else
                Utils.showToast(this, R.string.capture_not_started);
        } else if (id == R.id.action_open_github) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_PROJECT_URL));
            startActivity(browserIntent);
        } else if (id == R.id.action_open_telegram) {
            openTelegram();
        } else if (id == R.id.action_open_user_guide) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_DOCS_URL));
            startActivity(browserIntent);
        } else if (id == R.id.action_rate_app) {
            rateApp();
        } else if (id == R.id.action_stats) {
            if(mState == AppState.running) {
                Intent intent = new Intent(MainActivity.this, StatsActivity.class);
                startActivity(intent);
            } else
                Utils.showToast(this, R.string.capture_not_started);
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
    }

    public void appStateStopping() {
        mState = AppState.stopping;
        notifyAppState();
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

        startActivity(intent);
    }

    private void rateApp() {
        try {
            /* If playstore is installed */
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + this.getPackageName())));
        } catch (android.content.ActivityNotFoundException e) {
            /* If playstore is not available */
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("http://play.google.com/store/apps/details?id=" + this.getPackageName())));
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.action_start) {
            toggleService();
            return true;
        } else if (id == R.id.action_settings) {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_VPN) {
            if(resultCode == RESULT_OK) {
                Intent intent = new Intent(MainActivity.this, CaptureService.class);
                Bundle bundle = new Bundle();

                if((mPcapUri != null) && (Prefs.getDumpMode(mPrefs) == Prefs.DumpMode.PCAP_FILE))
                    bundle.putString(Prefs.PREF_PCAP_URI, mPcapUri.toString());

                intent.putExtra("settings", bundle);

                Log.d(TAG, "onActivityResult -> start CaptureService");

                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(intent);
                else
                    startService(intent);
            } else {
                Log.w(TAG, "VPN request failed");
                appStateReady();
            }
        } else if(requestCode == REQUEST_CODE_PCAP_FILE) {
            if(resultCode == RESULT_OK) {
                mPcapUri = data.getData();
                mPcapFname = null;
                Log.d(TAG, "PCAP to write: " + mPcapUri.toString());

                toggleService();
            } else
                mPcapUri = null;
        }
    }

    private void initAppState() {
        boolean is_active = CaptureService.isServiceActive();

        if (!is_active)
            appStateReady();
        else
            appStateRunning();
    }

    private void startCaptureService() {
        appStateStarting();

        Intent vpnPrepareIntent = VpnService.prepare(MainActivity.this);

        if (vpnPrepareIntent != null)
            startActivityForResult(vpnPrepareIntent, REQUEST_CODE_VPN);
        else
            onActivityResult(REQUEST_CODE_VPN, RESULT_OK, null);
    }

    public void toggleService() {
        if (CaptureService.isServiceActive()) {
            appStateStopping();
            CaptureService.stopService();
        } else {
            if((mPcapUri == null) && (Prefs.getDumpMode(mPrefs) == Prefs.DumpMode.PCAP_FILE)) {
                openFileSelector();
                return;
            }

            if(Utils.hasVPNRunning(this)) {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.existing_vpn_confirm)
                        .setPositiveButton(R.string.yes, (dialog, whichButton) -> startCaptureService())
                        .setNegativeButton(R.string.no, (dialog, whichButton) -> {})
                        .show();
            } else
                startCaptureService();
        }
    }

    public void openFileSelector() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/cap");
        intent.putExtra(Intent.EXTRA_TITLE, Utils.getUniquePcapFileName(this));

        startActivityForResult(intent, REQUEST_CODE_PCAP_FILE);
    }

    public void showPcapActionDialog(Uri pcapUri) {
        Cursor cursor;

        try {
            cursor = getContentResolver().query(pcapUri, null, null, null, null);
        } catch (Exception e) {
            return;
        }

        if((cursor == null) || !cursor.moveToFirst())
            return;

        long file_size = cursor.getLong(cursor.getColumnIndex(OpenableColumns.SIZE));
        String fname = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
        cursor.close();

        // If file is empty, delete it
        if(file_size == 0) {
            Log.d(TAG, "PCAP file is empty, deleting");

            try {
               DocumentsContract.deleteDocument(getContentResolver(), pcapUri);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            return;
        }

        String message = String.format(getResources().getString(R.string.pcap_file_action), fname, Utils.formatBytes(file_size));

        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setMessage(message);

        builder.setPositiveButton(R.string.share, (dialog, which) -> {
            Intent sendIntent = new Intent(Intent.ACTION_SEND);
            sendIntent.setType("application/cap");
            sendIntent.putExtra(Intent.EXTRA_STREAM, pcapUri);
            startActivity(Intent.createChooser(sendIntent, getResources().getString(R.string.share)));
        });
        builder.setNegativeButton(R.string.delete, (dialog, which) -> {
            Log.d(TAG, "Deleting PCAP file" + pcapUri.getPath());
            boolean deleted = false;

            try {
                deleted = DocumentsContract.deleteDocument(getContentResolver(), pcapUri);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }

            if(!deleted)
                Utils.showToast(MainActivity.this, R.string.delete_error);

            dialog.cancel();
        });
        builder.setNeutralButton(R.string.ok, (dialog, which) -> {
            dialog.cancel();
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

            String fname = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
            cursor.close();

            mPcapFname = fname;
            return fname;
        }

        return null;
    }

    public Map<Integer, AppDescriptor> getApps() {
        return mInstalledApps;
    }

    @Override
    public void onAppsInfoLoaded(Map<Integer, AppDescriptor> apps) {
        mInstalledApps = apps;

        for(AppsLoadListener listener: mAppsListeners)
            listener.onAppsInfoLoaded(apps);
    }

    @Override
    public void onAppsIconsLoaded(Map<Integer, AppDescriptor> apps) {
        mInstalledApps = apps;

        for(AppsLoadListener listener: mAppsListeners)
            listener.onAppsIconsLoaded(apps);
    }

    public void addAppLoadListener(AppsLoadListener l) {
        mAppsListeners.add(l);
    }

    public void removeAppLoadListener(AppsLoadListener l) {
        mAppsListeners.remove(l);
    }
}
