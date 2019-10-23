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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.VpnService;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;

import cat.ereza.customactivityoncrash.config.CaocConfig;

public class MainActivity extends AppCompatActivity {
    ListView mAppList;
    Button mStartButton;
    View mSelectedApp;
    int mFilterUid;
    SharedPreferences mPrefs;

    private static final int REQUECT_CODE_VPN = 2;

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

        CaocConfig.Builder.create()
                .errorDrawable(R.drawable.ic_app_crash)
                .apply();

        setContentView(R.layout.activity_main);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mFilterUid = -1;

        mAppList = findViewById(R.id.installed_app_list);
        mStartButton = findViewById(R.id.button_start);

        List<AppDescriptor> installedApps = getInstalledApps();
        AppAdapter installedAppAdapter = new AppAdapter(MainActivity.this, installedApps);
        //mAppList.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
        mAppList.setAdapter(installedAppAdapter);
        mAppList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                if(CaptureService.isRunning())
                    return;

                if(mSelectedApp == view) {
                    // Deselect
                    mSelectedApp = null;
                    mFilterUid = -1;
                    view.setBackgroundColor(Color.TRANSPARENT);
                } else {
                    if(mSelectedApp != null)
                        mSelectedApp.setBackgroundColor(Color.TRANSPARENT);

                    view.setBackgroundColor(Color.LTGRAY);
                    mSelectedApp = view;
                    mFilterUid = ((AppDescriptor) mAppList.getAdapter().getItem(i)).getUid();
                }

                Log.w("Main", "App filter: " + mFilterUid);
            }
        });

        updateConnectStatus(CaptureService.isRunning());

        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d("Main", "Clicked");

                if(CaptureService.isRunning()) {
                    CaptureService.stopService();
                    updateConnectStatus(false);
                } else {
                    Intent vpnPrepareIntent = VpnService.prepare(MainActivity.this);
                    if (vpnPrepareIntent != null) {
                        startActivityForResult(vpnPrepareIntent, REQUECT_CODE_VPN);
                    } else {
                        onActivityResult(REQUECT_CODE_VPN, RESULT_OK, null);
                    }
                    updateConnectStatus(true);
                }
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.settings_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode == REQUECT_CODE_VPN && resultCode == RESULT_OK) {
            Intent intent = new Intent(MainActivity.this, CaptureService.class);
            Bundle bundle = new Bundle();

            // the configuration for the VPN
            bundle.putString("dns_server", "8.8.8.8"); // TODO: read system DNS
            bundle.putString(Prefs.PREF_COLLECTOR_IP_KEY, mPrefs.getString(Prefs.PREF_COLLECTOR_IP_KEY, getString(R.string.default_collector_ip)));
            bundle.putInt(Prefs.PREF_COLLECTOR_PORT_KEY, Integer.parseInt(mPrefs.getString(Prefs.PREF_COLLECTOR_PORT_KEY, getString(R.string.default_collector_port))));
            bundle.putInt(Prefs.PREF_UID_FILTER, mFilterUid);
            intent.putExtra("settings", bundle);

            Log.d("Main", "onActivityResult -> start CaptureService");

            startService(intent);
        }
    }

    private List<AppDescriptor> getInstalledApps() {
        List<AppDescriptor> apps = new ArrayList<>();
        List<PackageInfo> packs = getPackageManager().getInstalledPackages(0);

        for (int i = 0; i < packs.size(); i++) {
            PackageInfo p = packs.get(i);

            if((p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                String appName = p.applicationInfo.loadLabel(getPackageManager()).toString();
                Drawable icon = p.applicationInfo.loadIcon(getPackageManager());
                String packages = p.applicationInfo.packageName;
                int uid = p.applicationInfo.uid;
                apps.add(new AppDescriptor(appName, icon, packages, uid));
            }
        }
        return apps;
    }
}
