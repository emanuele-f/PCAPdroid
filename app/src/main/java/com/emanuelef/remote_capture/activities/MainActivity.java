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
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.VpnService;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.emanuelef.remote_capture.AppsLoader;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.fragments.AppsFragment;
import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppState;
import com.emanuelef.remote_capture.interfaces.AppStateListener;
import com.emanuelef.remote_capture.views.AppsListView;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.fragments.ConnectionsFragment;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.fragments.StatusFragment;
import com.emanuelef.remote_capture.Utils;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import cat.ereza.customactivityoncrash.config.CaocConfig;

public class MainActivity extends AppCompatActivity implements AppsLoadListener {
    SharedPreferences mPrefs;
    Menu mMenu;
    MenuItem mMenuItemStats;
    MenuItem mMenuItemStartBtn;
    MenuItem mMenuItemAppSel;
    MenuItem mMenuSettings;
    Drawable mFilterIcon;
    String mFilterApp;
    boolean mOpenAppsWhenDone;
    List<AppDescriptor> mInstalledApps;
    AppState mState;
    ViewPager2 viewPager2;
    TabLayout tabLayout;
    List<AppStateListener> mStateListeners;
    AppsListView.OnSelectedAppListener mTmpAppFilterListener;
    AppDescriptor mNoFilterApp;

    private static final String TAG = "Main";

    public static final int POS_STATUS = 0;
    public static final int POS_APPS = 1;
    public static final int POS_CONNECTIONS = 2;
    private static final int TOTAL_COUNT = 3;

    private static final int REQUEST_CODE_VPN = 2;

    public static final String TELEGRAM_GROUP_NAME = "PCAPdroid";
    public static final String GITHUB_PROJECT_URL = "https://github.com/emanuele-f/PCAPdroid";
    public static final String GITHUB_DOCS_URL = "https://emanuele-f.github.io/PCAPdroid";

    private static class MainStateAdapter extends FragmentStateAdapter {
        MainStateAdapter(final FragmentActivity fa) {
            super(fa);
        }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            switch (position) {
                default: // Deliberate fall-through to status tab
                case POS_STATUS:
                    return new StatusFragment();
                case POS_CONNECTIONS:
                    return new ConnectionsFragment();
                case POS_APPS:
                    return new AppsFragment();
            }
        }

        @Override
        public int getItemCount() {
            return TOTAL_COUNT;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Drawable icon = ContextCompat.getDrawable(this, android.R.color.transparent);
        mNoFilterApp = new AppDescriptor("", icon, this.getResources().getString(R.string.no_filter), -1, false, true);

        mFilterApp = CaptureService.getAppFilter();

        if((mFilterApp == null) && (savedInstanceState != null)) {
            // Possibly get the temporary filter
            mFilterApp = savedInstanceState.getString("FilterApp");
        }

        mOpenAppsWhenDone = false;
        mInstalledApps = null;
        mTmpAppFilterListener = null;
        mStateListeners = new ArrayList<>();

        CaocConfig.Builder.create()
                .errorDrawable(R.drawable.ic_app_crash)
                .apply();

        setContentView(R.layout.main_activity);

        tabLayout = findViewById(R.id.main_tablayout);

        viewPager2 = findViewById(R.id.main_viewpager2);
        final MainStateAdapter stateAdapter = new MainStateAdapter(this);
        viewPager2.setAdapter(stateAdapter);

        new TabLayoutMediator(tabLayout, viewPager2, (tab, position) -> {
            switch (position) {
                default: // Deliberate fall-through to status tab
                case POS_STATUS:
                    tab.setText(R.string.status_view);
                    break;
                case POS_CONNECTIONS:
                    tab.setText(R.string.connections_view);
                    break;
                case POS_APPS:
                    tab.setText(R.string.apps);
                }
        }).attach();

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        (new AppsLoader(this))
                .setAppsLoadListener(this)
                .loadAllApps();

        LocalBroadcastManager bcast_man = LocalBroadcastManager.getInstance(this);

        /* Register for service status */
        bcast_man.registerReceiver(new BroadcastReceiver() {
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

                        appStateReady();
                    }
                }
            }
        }, new IntentFilter(CaptureService.ACTION_SERVICE_STATUS));
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);

        savedInstanceState.putString("FilterApp", mFilterApp);
    }

    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {

    }

    @Override
    public void onResume() {
        super.onResume();

        if(mMenu != null)
            initAppState();
    }

    @Override
    public void onAppsInfoLoaded(Map<Integer, AppDescriptor> apps) {
        // TODO optimize: show the apps even when icon not loaded
    }

    @Override
    public void onAppsIconsLoaded(Map<Integer, AppDescriptor> apps) {
        mInstalledApps = new ArrayList<>();
        AppDescriptor filterApp = null;

        for (Map.Entry<Integer, AppDescriptor> pair : apps.entrySet()) {
            AppDescriptor app = pair.getValue();

            if(!app.isVirtual()) {
                mInstalledApps.add(app);

                if (app.getPackageName().equals(mFilterApp))
                    filterApp = app;
            }
        }

        Collections.sort(mInstalledApps);

        if (filterApp != null) {
            /* An filter is active, try to set the corresponding app image */
            setSelectedApp(filterApp);
        }

        if (mOpenAppsWhenDone)
            openAppSelector();
    }

    private void notifyAppState() {
        for(AppStateListener listener: mStateListeners)
            listener.appStateChanged(mState);
    }

    public void appStateReady() {
        mState = AppState.ready;
        notifyAppState();

        mMenuItemStartBtn.setIcon(
                ContextCompat.getDrawable(this, android.R.drawable.ic_media_play));
        mMenuItemStartBtn.setTitle(R.string.start_button);
        mMenuItemStartBtn.setEnabled(true);
        mMenuItemAppSel.setEnabled(true);
        mMenuItemStats.setVisible(false);
        mMenuSettings.setVisible(true);
    }

    public void appStateStarting() {
        mState = AppState.starting;
        notifyAppState();

        mMenuItemStartBtn.setEnabled(false);
        mMenuSettings.setVisible(false);

        if(mTmpAppFilterListener != null)
            mTmpAppFilterListener.onSelectedApp(null);
    }

    public void appStateRunning() {
        mState = AppState.running;
        notifyAppState();

        mMenuItemStartBtn.setIcon(
                ContextCompat.getDrawable(this, R.drawable.ic_media_stop));
        mMenuItemStartBtn.setTitle(R.string.stop_button);
        mMenuItemStartBtn.setEnabled(true);
        mMenuSettings.setVisible(false);
        mMenuItemStats.setVisible(true);
        mMenuItemAppSel.setEnabled(canApplyTmpFilter());
    }

    public void appStateStopping() {
        mState = AppState.stopping;
        notifyAppState();

        mMenuItemStartBtn.setEnabled(false);
        mMenuItemAppSel.setEnabled(false);
    }

    public boolean canApplyTmpFilter() {
        // the tmp filter can only be applied when a filter is not set before starting the app
        return (CaptureService.getAppFilter() == null);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.settings_menu, menu);

        mMenu = menu;
        mMenuItemStats = mMenu.findItem(R.id.action_stats);
        mMenuItemStartBtn = mMenu.findItem(R.id.action_start);
        mMenuItemAppSel = mMenu.findItem(R.id.action_show_app_filter);
        mMenuSettings = mMenu.findItem(R.id.action_settings);

        mFilterIcon = mMenuItemAppSel.getIcon();

        initAppState();

        return true;
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
        } else if (id == R.id.action_show_app_filter) {
            if(mFilterApp != null)
                setSelectedApp(null);
            else
                openAppSelector();

            return true;
        } else if (id == R.id.action_open_github) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_PROJECT_URL));
            startActivity(browserIntent);
            return true;
        } else if (id == R.id.action_open_telegram) {
            openTelegram();
            return true;
        } else if (id == R.id.action_open_user_guide) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_DOCS_URL));
            startActivity(browserIntent);
            return true;
        } else if (id == R.id.action_rate_app) {
            rateApp();
            return true;
        } else if (id == R.id.action_stats) {
            Intent intent = new Intent(MainActivity.this, StatsActivity.class);
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

                bundle.putString(Prefs.PREF_APP_FILTER, mFilterApp);
                intent.putExtra("settings", bundle);

                Log.d(TAG, "onActivityResult -> start CaptureService");

                startService(intent);
            } else {
                Log.w(TAG, "VPN request failed");
                appStateReady();
            }
        }
    }

    private void initAppState() {
        boolean is_active = CaptureService.isServiceActive();

        if (!is_active)
            appStateReady();
        else
            appStateRunning();
    }

    private void startService() {
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
            if(Utils.hasVPNRunning(this)) {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.existing_vpn_confirm)
                        .setPositiveButton(R.string.yes, (dialog, whichButton) -> startService())
                        .setNegativeButton(R.string.no, (dialog, whichButton) -> {})
                        .show();
            } else
                startService();
        }
    }

    public void addAppStateListener(AppStateListener listener) {
        mStateListeners.add(listener);
    }

    public void removeAppStateListener(AppStateListener listener) {
        mStateListeners.remove(listener);
    }

    public AppState getState() {
        return(mState);
    }

    public void setTmpAppFilterListener(AppsListView.OnSelectedAppListener listener) {
        mTmpAppFilterListener = listener;
    }

    // get the temporary filter, which can be set by the user *after* the capture has started
    public AppDescriptor getTmpFilter() {
        if((mFilterApp == null) || !canApplyTmpFilter())
            return null;

        // TODO: fixme
        return null;
        //return findAppByPackage(mFilterApp);
    }

    public void setSelectedApp(AppDescriptor app) {
        if(app == null)
            app = mNoFilterApp;

        Log.d(TAG, "Selected app: " + app.getUid());

        if(app.getUid() != -1) {
            // an app has been selected
            mFilterApp = app.getPackageName();

            // clone the drawable to avoid a "zoom-in" effect when clicked
            Drawable drawable = (app.getIcon() != null) ? Objects.requireNonNull(app.getIcon().getConstantState()).newDrawable() : null;

            if(drawable != null) {
                mMenuItemAppSel.setIcon(drawable);
                mMenuItemAppSel.setTitle(R.string.remove_app_filter);
            }
        } else {
            // no filter
            mFilterApp = null;
            mMenuItemAppSel.setIcon(mFilterIcon);
            mMenuItemAppSel.setTitle(R.string.set_app_filter);
        }

        if(mTmpAppFilterListener != null)
            mTmpAppFilterListener.onSelectedApp(getTmpFilter());
    }

    private void openAppSelector() {
        if(mInstalledApps == null) {
            /* The applications loader has not finished yet. */
            mOpenAppsWhenDone = true;
            Utils.showToast(this, R.string.apps_loading_please_wait);
            return;
        }

        mOpenAppsWhenDone = false;
        List<AppDescriptor> appsData = mInstalledApps;

        if(mState == AppState.running) {
            // Only show the seen apps
            ConnectionsRegister reg = CaptureService.getConnsRegister();

            if(reg != null) {
                Set<Integer> seen_uids = reg.getSeenUids();
                appsData = new ArrayList<>();

                for(AppDescriptor app : mInstalledApps) {
                    int uid = app.getUid();

                    if((uid == -1) || seen_uids.contains(uid))
                        appsData.add(app);
                }
            }
        }

        View dialogLayout = getLayoutInflater().inflate(R.layout.apps_selector, null);
        SearchView searchView = dialogLayout.findViewById(R.id.apps_search);
        AppsListView apps = dialogLayout.findViewById(R.id.apps_list);
        TextView emptyText = dialogLayout.findViewById(R.id.no_apps);

        apps.setApps(appsData);
        apps.setEmptyView(emptyText);
        searchView.setOnQueryTextListener(apps);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.app_filter);
        builder.setView(dialogLayout);

        final AlertDialog alert = builder.create();

        apps.setSelectedAppListener(app -> {
            setSelectedApp(app);

            // dismiss the dialog
            alert.cancel();
        });

        alert.show();
    }

    public void setActivePage(int pos) {
        if((pos >= 0) && (pos < TOTAL_COUNT))
            viewPager2.setCurrentItem(pos);
    }
}
