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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.VpnService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.AsyncTaskLoader;
import androidx.loader.content.Loader;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;

import cat.ereza.customactivityoncrash.config.CaocConfig;

public class MainActivity extends AppCompatActivity implements LoaderManager.LoaderCallbacks<List<AppDescriptor>>, AppStateListener {
    SharedPreferences mPrefs;
    Menu mMenu;
    Drawable mFilterIcon;
    String mFilterApp;
    boolean mOpenAppsWhenDone;
    List<AppDescriptor> mInstalledApps;
    AppState mState;
    StatusFragment mStatusFragment;
    ConnectionsFragment mConnectionsFragment;
    ViewPager2 viewPager2;
    TabLayout tabLayout;
    private final static int POS_STATUS = 0;
    private final static int POS_CONNECTIONS = 1;
    private final static int TOTAL_COUNT = 2;

    private static final int REQUEST_CODE_VPN = 2;
    private static final int MENU_ITEM_APP_SELECTOR_IDX = 0;
    public static final int OPERATION_SEARCH_LOADER = 23;

    public static final String TELEGRAM_GROUP_NAME = "PCAPdroid";
    public static final String GITHUB_PROJECT_URL = "https://github.com/emanuele-f/PCAPdroid";
    public static final String GITHUB_DOCS_URL = "https://emanuele-f.github.io/PCAPdroid";

    /* App state handling: ready -> starting -> running -> stopping -> ready  */
    enum AppState {
        ready,
        starting,
        running,
        stopping
    }

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

        mFilterApp = CaptureService.getAppFilter();
        mOpenAppsWhenDone = false;
        mInstalledApps = null;
        mStatusFragment = null;
        mConnectionsFragment = null;

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
                }
        }).attach();

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (savedInstanceState != null) {
            mConnectionsFragment = (ConnectionsFragment) getSupportFragmentManager().getFragment(savedInstanceState, "ConnectionsFragment");
        }

        startLoadingApps();

        LocalBroadcastManager bcast_man = LocalBroadcastManager.getInstance(this);

        /* Register for service status */
        bcast_man.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String status = intent.getStringExtra(CaptureService.SERVICE_STATUS_KEY);

                if (status != null) {
                    if (status.equals(CaptureService.SERVICE_STATUS_STARTED) && (mState == AppState.starting)) {
                        appStateRunning();
                    } else if (status.equals(CaptureService.SERVICE_STATUS_STOPPED)) {
                        appStateReady();
                    }
                }
            }
        }, new IntentFilter(CaptureService.ACTION_SERVICE_STATUS));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if(mConnectionsFragment != null)
            getSupportFragmentManager().putFragment(outState, "ConnectionsFragment", mConnectionsFragment);
    }

    @Override
    public void appStateReady() {
        mState = AppState.ready;

        if (mStatusFragment != null)
            mStatusFragment.appStateReady();

        mMenu.getItem(MENU_ITEM_APP_SELECTOR_IDX).setEnabled(true);
    }

    @Override
    public void appStateStarting() {
        mState = AppState.starting;

        if (mStatusFragment != null)
            mStatusFragment.appStateStarting();

        mMenu.getItem(MENU_ITEM_APP_SELECTOR_IDX).setEnabled(false);
    }

    @Override
    public void appStateRunning() {
        mState = AppState.running;

        mMenu.getItem(MENU_ITEM_APP_SELECTOR_IDX).setEnabled(false);

        if (mStatusFragment != null)
            mStatusFragment.appStateRunning();

        if (mConnectionsFragment != null)
            mConnectionsFragment.reset();
    }

    @Override
    public void appStateStopping() {
        mState = AppState.stopping;

        if (mStatusFragment != null)
            mStatusFragment.appStateStopping();

        mMenu.getItem(MENU_ITEM_APP_SELECTOR_IDX).setEnabled(false);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.settings_menu, menu);
        mMenu = menu;
        mFilterIcon = mMenu.getItem(MENU_ITEM_APP_SELECTOR_IDX).getIcon();

        recheckFragments();

        return true;
    }

    private void openTelegram() {
        Intent intent = null;

        try {
            getPackageManager().getPackageInfo("org.telegram.messenger", 0);

            // Open directly into the telegram app
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=" + TELEGRAM_GROUP_NAME));
        } catch (Exception e) {
            // Telegram not found, open in the browser
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://t.me/" + TELEGRAM_GROUP_NAME));
        }

        if(intent != null)
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
        if (id == R.id.action_settings) {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
            return true;
        } else if (id == R.id.action_show_app_filter) {
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

                Log.d("Main", "onActivityResult -> start CaptureService");

                startService(intent);
            } else {
                Log.w("Main", "VPN request failed");
                appStateReady();
            }
        }
    }

    @NonNull
    @Override
    public Loader<List<AppDescriptor>> onCreateLoader(int id, @Nullable Bundle args) {
        return new AsyncTaskLoader<List<AppDescriptor>>(this) {

            @Nullable
            @Override
            public List<AppDescriptor> loadInBackground() {
                Log.d("AppsLoader", "Loading APPs...");
                return Utils.getInstalledApps(getContext());
            }
        };
    }

    @Override
    public void onLoadFinished(@NonNull Loader<List<AppDescriptor>> loader, List<AppDescriptor> data) {
        Log.d("AppsLoader", data.size() + " APPs loaded");
        mInstalledApps = data;

        if (mFilterApp != null) {
            /* An filter is active, try to set the corresponding app image */
            AppDescriptor app = findAppByPackage(mFilterApp);

            if (app != null)
                setSelectedAppIcon(app);
        }

        if (mOpenAppsWhenDone)
            openAppSelector();
    }

    @Override
    public void onLoaderReset(@NonNull Loader<List<AppDescriptor>> loader) {
    }

    AppDescriptor findAppByUid(int uid) {
        if((mInstalledApps == null) || (uid == -1))
            return (null);

        for (int i = 0; i < mInstalledApps.size(); i++) {
            AppDescriptor app = mInstalledApps.get(i);

            if (app.getUid() == uid) {
                return (app);
            }
        }

        return (null);
    }

    AppDescriptor findAppByPackage(String package_name) {
        if (mInstalledApps == null)
            return (null);

        for (int i = 0; i < mInstalledApps.size(); i++) {
            AppDescriptor app = mInstalledApps.get(i);

            if (app.getPackageName().equals(package_name)) {
                return (app);
            }
        }

        return (null);
    }

    /* Try to determine the current app state */
    private void setAppState() {
        boolean is_active = CaptureService.isServiceActive();

        if (!is_active)
            appStateReady();
        else
            appStateRunning();
    }

    private void startService() {
        Intent vpnPrepareIntent = VpnService.prepare(MainActivity.this);
        if (vpnPrepareIntent != null)
            startActivityForResult(vpnPrepareIntent, REQUEST_CODE_VPN);
        else
            onActivityResult(REQUEST_CODE_VPN, RESULT_OK, null);

        appStateStarting();
    }

    public void toggleService() {
        if (CaptureService.isServiceActive()) {
            CaptureService.stopService();
            appStateStopping();
        } else {
            if(Utils.hasVPNRunning(this)) {
                new AlertDialog.Builder(this)
                        .setMessage(R.string.existing_vpn_confirm)
                        .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                startService();
                            }})
                        .setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton){}})
                        .show();
            } else
                startService();
        }
    }

    void setStatusFragment(StatusFragment screen) {
        mStatusFragment = screen;
        recheckFragments();
    }

    void setConnectionsFragment(ConnectionsFragment view) {
        mConnectionsFragment = view;
        recheckFragments();
    }

    private void recheckFragments() {
        /* Must wait for the fragments to properly update them */
        if((mStatusFragment != null) && (mConnectionsFragment != null) && (mMenu != null)) {
            // Possibly set an initial app state
            setAppState();
        }
    }

    AppState getState() {
        return(mState);
    }

    private void startLoadingApps() {
        LoaderManager lm = LoaderManager.getInstance(this);
        Loader<List<AppDescriptor>> loader = lm.getLoader(OPERATION_SEARCH_LOADER);

        Log.d("startLoadingApps", "Loader? " + Boolean.toString(loader != null));

        if(loader==null)
            loader = lm.initLoader(OPERATION_SEARCH_LOADER, null, this);
        else
            loader = lm.restartLoader(OPERATION_SEARCH_LOADER, null, this);

        loader.forceLoad();
    }

    private void setSelectedAppIcon(AppDescriptor app) {
        // clone the drawable to avoid a "zoom-in" effect when clicked
        mMenu.getItem(MENU_ITEM_APP_SELECTOR_IDX).setIcon(app.getIcon().getConstantState().newDrawable());
    }

    private void openAppSelector() {
        if(mInstalledApps == null) {
            /* The applications loader has not finished yet. */
            mOpenAppsWhenDone = true;
            return;
        }

        // Filter non-system apps
        List<AppDescriptor> user_apps = new ArrayList<>();

        for(int i=0; i<mInstalledApps.size(); i++) {
            AppDescriptor app = mInstalledApps.get(i);

            if(!app.isSystem())
                user_apps.add(app);
        }

        mOpenAppsWhenDone = false;

        AppsView apps = new AppsView(this, user_apps);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.app_filter);
        builder.setView(apps);
        final AlertDialog alert = builder.create();

        apps.setSelectedAppListener(new AppsView.OnSelectedAppListener() {
            @Override
            public void onSelectedApp(AppDescriptor app) {
                if(app.getUid() != -1) {
                    // an app has been selected
                    mFilterApp = app.getPackageName();
                    setSelectedAppIcon(app);
                } else {
                    // no filter
                    mMenu.getItem(MENU_ITEM_APP_SELECTOR_IDX).setIcon(mFilterIcon);
                }

                // dismiss the dialog
                alert.cancel();
            }
        });

        alert.show();
    }
}
