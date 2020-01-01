/*
    This file is part of RemoteCapture.

    RemoteCapture is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    RemoteCapture is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with RemoteCapture.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2019 by Emanuele Faranda
*/

package com.emanuelef.remote_capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.VpnService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.AsyncTaskLoader;
import androidx.loader.content.Loader;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.viewpager.widget.ViewPager;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import java.util.List;

import cat.ereza.customactivityoncrash.config.CaocConfig;

public class MainActivity extends AppCompatActivity implements LoaderManager.LoaderCallbacks<List<AppDescriptor>>, AppStateListener {
    SharedPreferences mPrefs;
    Menu mMenu;
    int mFilterUid;
    boolean mOpenAppsWhenDone;
    List<AppDescriptor> mInstalledApps;
    AppState mState;
    StatusFragment mStatusFragment;
    ConnectionsFragment mConnectionsFragment;

    private static final int REQUEST_CODE_VPN = 2;
    private static final int MENU_ITEM_APP_SELECTOR_IDX = 0;
    public static final int OPERATION_SEARCH_LOADER = 23;

    /* App state handling: ready -> starting -> running -> stopping -> ready  */
    enum AppState {
        ready,
        starting,
        running,
        stopping
    }

    public class MainPagerAdapter extends FragmentPagerAdapter {
        public MainPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            if (position == 0) {
                return new StatusFragment();
            } else {
                return new ConnectionsFragment();
            }
        }

        @Override
        public CharSequence getPageTitle(int position) {
            if (position == 0) {
                return getResources().getString(R.string.status_view);
            } else {
                return getResources().getString(R.string.connections_view);
            }
        }


        @Override
        public int getCount() {
            return 2;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mFilterUid = CaptureService.getUidFilter();
        mOpenAppsWhenDone = false;
        mInstalledApps = null;
        mStatusFragment = null;
        mConnectionsFragment = null;

        CaocConfig.Builder.create()
                .errorDrawable(R.drawable.ic_app_crash)
                .apply();

        setContentView(R.layout.main_activity);

        ViewPager viewPager = (ViewPager) findViewById(R.id.main_viewpager);
        MainPagerAdapter pagerAdapter = new MainPagerAdapter(getSupportFragmentManager());
        viewPager.setAdapter(pagerAdapter);

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

        recheckFragments();

        return true;
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
        } else if (id == R.id.action_about) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/emanuele-f/RemoteCapture"));
            startActivity(browserIntent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_VPN && resultCode == RESULT_OK) {
            Intent intent = new Intent(MainActivity.this, CaptureService.class);
            Bundle bundle = new Bundle();
            String dns_server = Utils.getDnsServer(getApplicationContext());

            Log.i("Main", "Using DNS server " + dns_server);

            // the configuration for the VPN
            bundle.putString("dns_server", dns_server);
            bundle.putString(Prefs.PREF_COLLECTOR_IP_KEY, getCollectorIPPref());
            bundle.putInt(Prefs.PREF_COLLECTOR_PORT_KEY, Integer.parseInt(getCollectorPortPref()));
            bundle.putInt(Prefs.PREF_UID_FILTER, mFilterUid);
            bundle.putBoolean(Prefs.PREF_CAPTURE_UNKNOWN_APP_TRAFFIC, getCaptureUnknownTrafficPref());
            intent.putExtra("settings", bundle);

            Log.d("Main", "onActivityResult -> start CaptureService");

            startService(intent);
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

        if (mFilterUid != -1) {
            /* An filter is active, try to set the corresponding app image */
            AppDescriptor app = findAppByUid(mFilterUid);

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
        if (mInstalledApps == null)
            return (null);

        for (int i = 0; i < mInstalledApps.size(); i++) {
            AppDescriptor app = mInstalledApps.get(i);

            if (app.getUid() == uid) {
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

    public void toggleService() {
        if (CaptureService.isServiceActive()) {
            CaptureService.stopService();
            appStateStopping();
        } else {
            Intent vpnPrepareIntent = VpnService.prepare(MainActivity.this);
            if (vpnPrepareIntent != null)
                startActivityForResult(vpnPrepareIntent, REQUEST_CODE_VPN);
            else
                onActivityResult(REQUEST_CODE_VPN, RESULT_OK, null);

            appStateStarting();
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

        mOpenAppsWhenDone = false;

        AppsView apps = new AppsView(this, mInstalledApps);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.app_filter);
        builder.setView(apps);
        final AlertDialog alert = builder.create();

        apps.setSelectedAppListener(new AppsView.OnSelectedAppListener() {
            @Override
            public void onSelectedApp(AppDescriptor app) {
                mFilterUid = app.getUid();
                setSelectedAppIcon(app);

                // dismiss the dialog
                alert.cancel();
            }
        });

        alert.show();
    }

    String getCollectorIPPref() {
        return(mPrefs.getString(Prefs.PREF_COLLECTOR_IP_KEY, getString(R.string.default_collector_ip)));
    }

    String getCollectorPortPref() {
        return(mPrefs.getString(Prefs.PREF_COLLECTOR_PORT_KEY, getString(R.string.default_collector_port)));
    }

    private boolean getCaptureUnknownTrafficPref() {
        return(mPrefs.getBoolean(Prefs.PREF_CAPTURE_UNKNOWN_APP_TRAFFIC, true));
    }
}
