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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.VpnService;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.AsyncTaskLoader;
import androidx.loader.content.Loader;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import cat.ereza.customactivityoncrash.config.CaocConfig;

public class MainActivity extends AppCompatActivity implements LoaderManager.LoaderCallbacks<List<AppDescriptor>> {
    Button mStartButton;
    SharedPreferences mPrefs;
    TextView mExporterInfo;
    TextView mCaptureStatus;
    Menu mMenu;
    int mFilterUid;
    boolean mOpenAppsWhenDone;
    List<AppDescriptor> mInstalledApps;

    private static final int REQUEST_CODE_VPN = 2;
    private static final int MENU_ITEM_APP_SELECTOR_IDX = 0;
    public static final int OPERATION_SEARCH_LOADER = 23;

    private void updateConnectStatus(boolean is_running) {
        if(is_running) {
            Log.d("Main", "VPN Running");
            mStartButton.setText(R.string.stop_button);
        } else {
            Log.d("Main", "VPN NOT Running");
            mStartButton.setText(R.string.start_button);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mFilterUid = -1;
        mOpenAppsWhenDone = false;
        mInstalledApps = null;

        CaocConfig.Builder.create()
                .errorDrawable(R.drawable.ic_app_crash)
                .apply();

        setContentView(R.layout.activity_main);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mStartButton = findViewById(R.id.button_start);
        mExporterInfo = findViewById(R.id.exporter_info);
        mCaptureStatus = findViewById(R.id.status_view);

        mPrefs.registerOnSharedPreferenceChangeListener(new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
                refreshExporterInfo();
            }
        });
        refreshExporterInfo();

        updateConnectStatus(CaptureService.isRunning());

        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d("Main", "Clicked");

                if(CaptureService.isRunning()) {
                    CaptureService.stopService();
                    updateConnectStatus(false);
                    mStartButton.setEnabled(false);
                    //mCaptureStatus.setText(R.string.stopping);
                } else {
                    Intent vpnPrepareIntent = VpnService.prepare(MainActivity.this);
                    if (vpnPrepareIntent != null) {
                        startActivityForResult(vpnPrepareIntent, REQUEST_CODE_VPN);
                        mStartButton.setEnabled(false);
                    } else {
                        onActivityResult(REQUEST_CODE_VPN, RESULT_OK, null);
                    }
                    updateConnectStatus(true);
                    mMenu.getItem(MENU_ITEM_APP_SELECTOR_IDX).setEnabled(false);
                }
            }
        });

        startLoadingApps();

        LocalBroadcastManager bcast_man = LocalBroadcastManager.getInstance(this);

        /* Register for stats update */
        bcast_man.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                processStatsUpdateIntent(intent);
            }
        }, new IntentFilter(CaptureService.ACTION_TRAFFIC_STATS_UPDATE));

        /* Register for service status */
        bcast_man.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String status = intent.getStringExtra(CaptureService.SERVICE_STATUS_KEY);

                if(status.equals(CaptureService.SERVICE_STATUS_STARTED)) {
                    mCaptureStatus.setText(formatBytes(0));
                    mStartButton.setEnabled(true);
                } else if(status.equals(CaptureService.SERVICE_STATUS_STOPPED)) {
                    mCaptureStatus.setText(R.string.ready);
                    mStartButton.setEnabled(true);
                    mMenu.getItem(MENU_ITEM_APP_SELECTOR_IDX).setEnabled(true);
                }
            }
        }, new IntentFilter(CaptureService.ACTION_SERVICE_STATUS));
    }

    private void refreshExporterInfo() {
        mExporterInfo.setText("UDP Collector: " + getCollectorIPPref() + ":" + getCollectorPortPref());
    }

    private void startLoadingApps() {
        LoaderManager lm = LoaderManager.getInstance(this);
        Loader<List<AppDescriptor>> loader = lm.getLoader(OPERATION_SEARCH_LOADER);

        if(loader==null) {
            loader = lm.initLoader(OPERATION_SEARCH_LOADER, null, this);
            loader.forceLoad();
        } else
            lm.restartLoader(OPERATION_SEARCH_LOADER, null, this);
    }

    private List<AppDescriptor> getInstalledApps() {
        PackageManager pm = getPackageManager();
        List<AppDescriptor> apps = new ArrayList<>();
        List<PackageInfo> packs = pm.getInstalledPackages(0);

        // Add the "No Filter" app
        apps.add(new AppDescriptor("", getResources().getDrawable(android.R.drawable.ic_menu_view), getResources().getString(R.string.no_filter), -1));

        for (int i = 0; i < packs.size(); i++) {
            PackageInfo p = packs.get(i);

            if((p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                String appName = p.applicationInfo.loadLabel(pm).toString();
                Drawable icon = p.applicationInfo.loadIcon(pm);
                String packages = p.applicationInfo.packageName;
                int uid = p.applicationInfo.uid;
                apps.add(new AppDescriptor(appName, icon, packages, uid));

                Log.d("APPS", appName + " - " + packages + " [" + uid + "]");
            }
        }
        return apps;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.settings_menu, menu);
        mMenu = menu;
        return true;
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
                // clone the drawable to avoid a "zoom-in" effect when clicked
                mMenu.getItem(MENU_ITEM_APP_SELECTOR_IDX).setIcon(app.getIcon().getConstantState().newDrawable());

                // dismiss the dialog
                alert.cancel();
            }
        });

        alert.show();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
            return true;
        } else if(id == R.id.action_show_app_filter) {
            openAppSelector();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private String getCollectorIPPref() {
        return(mPrefs.getString(Prefs.PREF_COLLECTOR_IP_KEY, getString(R.string.default_collector_ip)));
    }

    private String getCollectorPortPref() {
        return(mPrefs.getString(Prefs.PREF_COLLECTOR_PORT_KEY, getString(R.string.default_collector_port)));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode == REQUEST_CODE_VPN && resultCode == RESULT_OK) {
            Intent intent = new Intent(MainActivity.this, CaptureService.class);
            Bundle bundle = new Bundle();

            // the configuration for the VPN
            bundle.putString("dns_server", "8.8.8.8"); // TODO: read system DNS
            bundle.putString(Prefs.PREF_COLLECTOR_IP_KEY, getCollectorIPPref());
            bundle.putInt(Prefs.PREF_COLLECTOR_PORT_KEY, Integer.parseInt(getCollectorPortPref()));
            bundle.putInt(Prefs.PREF_UID_FILTER, mFilterUid);
            intent.putExtra("settings", bundle);

            Log.d("Main", "onActivityResult -> start CaptureService");

            startService(intent);
            //mCaptureStatus.setText(R.string.starting);
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
                return getInstalledApps();
            }
        };
    }

    @Override
    public void onLoadFinished(@NonNull Loader<List<AppDescriptor>> loader, List<AppDescriptor> data) {
        Log.d("AppsLoader", data.size() + " APPs loaded");
        mInstalledApps = data;

        if(mOpenAppsWhenDone)
            openAppSelector();
    }

    @Override
    public void onLoaderReset(@NonNull Loader<List<AppDescriptor>> loader) {}

    public static String formatBytes(long bytes) {
        long divisor;
        String suffix;
        if(bytes < 1024) return bytes + " B";

        if(bytes < 1024*1024)               { divisor = 1024;           suffix = "KB"; }
        else if(bytes < 1024*1024*1024)     { divisor = 1024*1024;      suffix = "MB"; }
        else                                { divisor = 1024*1024*1024; suffix = "GB"; }

        return String.format("%.1f %s", ((float)bytes) / divisor, suffix);
    }

    public void processStatsUpdateIntent(Intent intent) {
        long bytes_sent = intent.getLongExtra(CaptureService.TRAFFIC_STATS_UPDATE_SENT_BYTES, 0);
        long bytes_rcvd = intent.getLongExtra(CaptureService.TRAFFIC_STATS_UPDATE_RCVD_BYTES, 0);
        int pkts_sent = intent.getIntExtra(CaptureService.TRAFFIC_STATS_UPDATE_SENT_PKTS, 0);
        int pkts_rcvd = intent.getIntExtra(CaptureService.TRAFFIC_STATS_UPDATE_RCVD_PKTS, 0);

        Log.d("MainReceiver", "Got StatsUpdate: bytes_sent=" + bytes_sent + ", bytes_rcvd=" +
                bytes_rcvd + ", pkts_sent=" + pkts_sent + ", pkts_rcvd=" + pkts_rcvd);

        mCaptureStatus.setText(formatBytes(bytes_sent + bytes_rcvd));
    }
}
