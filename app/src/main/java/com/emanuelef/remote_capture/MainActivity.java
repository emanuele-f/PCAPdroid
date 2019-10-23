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

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;

import cat.ereza.customactivityoncrash.config.CaocConfig;

public class MainActivity extends AppCompatActivity {
    Button mStartButton;
    SharedPreferences mPrefs;
    Menu mMenu;
    int mFilterUid;

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

        mFilterUid = -1;

        CaocConfig.Builder.create()
                .errorDrawable(R.drawable.ic_app_crash)
                .apply();

        setContentView(R.layout.activity_main);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mStartButton = findViewById(R.id.button_start);

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
        mMenu = menu;
        return true;
    }

    private void openAppSelector() {
        AppsView apps = new AppsView(this);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.app_filter);
        builder.setView(apps);

        final AlertDialog alert = builder.create();

        apps.setSelectedAppListener(new AppsView.OnSelectedAppListener() {
            @Override
            public void onSelectedApp(AppDescriptor app) {
                mFilterUid = app.getUid();
                mMenu.getItem(0).setIcon(app.getIcon());

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
}
