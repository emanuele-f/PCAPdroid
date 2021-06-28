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

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.AppDescriptor;

public class AppDetailsActivity extends BaseActivity {
    private static final String TAG = "AppDetailsActivity";
    public static final String APP_UID_EXTRA = "app_uid";
    private TableLayout mTable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.app_details);
        setContentView(R.layout.app_details_activity);

        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null)
            actionBar.setDisplayHomeAsUpEnabled(true);

        int uid = getIntent().getIntExtra(APP_UID_EXTRA, Utils.UID_UNKNOWN);
        AppsResolver res = new AppsResolver(this);
        AppDescriptor dsc = res.get(uid, PackageManager.GET_PERMISSIONS);

        if(dsc == null) {
            finish();
            return;
        }

        ((TextView)findViewById(R.id.uid)).setText(Utils.formatInteger(this, dsc.getUid()));
        ((TextView)findViewById(R.id.name)).setText(dsc.getName());
        PackageInfo pinfo = dsc.getPackageInfo();
        ((ImageView)findViewById(R.id.app_icon)).setImageDrawable(dsc.getIcon());

        if(pinfo != null) {
            ((TextView)findViewById(R.id.package_name)).setText(dsc.getPackageName());
            ((TextView)findViewById(R.id.version)).setText(pinfo.versionName);
            ((TextView)findViewById(R.id.target_sdk)).setText(Utils.formatInteger(this, pinfo.applicationInfo.targetSdkVersion));
            ((TextView)findViewById(R.id.install_date)).setText(Utils.formatEpochFull(this, pinfo.firstInstallTime / 1000));
            ((TextView)findViewById(R.id.last_update)).setText(Utils.formatEpochFull(this, pinfo.lastUpdateTime / 1000));

            if((pinfo.requestedPermissions != null) && (pinfo.requestedPermissions.length != 0)) {
                StringBuilder builder = new StringBuilder();
                boolean first = true;

                for(String perm: pinfo.requestedPermissions) {
                    if(first)
                        first = false;
                    else
                        builder.append("\n");

                    builder.append(perm);
                }

                ((TextView)findViewById(R.id.permissions)).setText(builder.toString());
            } else {
                findViewById(R.id.permissions_label).setVisibility(View.GONE);
                findViewById(R.id.permissions).setVisibility(View.GONE);
            }
        } else {
            // This is a virtual App
            if(!dsc.getDescription().isEmpty()) {
               ((TextView) findViewById(R.id.vapp_info)).setText(dsc.getDescription());
               findViewById(R.id.vapp_info).setVisibility(View.VISIBLE);
            }

            findViewById(R.id.package_name_row).setVisibility(View.GONE);
            findViewById(R.id.version_row).setVisibility(View.GONE);
            findViewById(R.id.target_sdk_row).setVisibility(View.GONE);
            findViewById(R.id.install_date_row).setVisibility(View.GONE);
            findViewById(R.id.last_update_row).setVisibility(View.GONE);
            findViewById(R.id.permissions_label).setVisibility(View.GONE);
            findViewById(R.id.permissions).setVisibility(View.GONE);
            findViewById(R.id.app_settings).setVisibility(View.GONE);
        }

        mTable = findViewById(R.id.table);

        findViewById(R.id.app_settings).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", dsc.getPackageName(), null));
            startActivity(intent);
        });

        findViewById(R.id.show_connections).setOnClickListener(v -> {
            // TODO implement new activity
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra(MainActivity.FILTER_EXTRA, dsc.getPackageName());
            startActivity(intent);
        });
    }

    private String asString() {
        if(findViewById(R.id.permissions).getVisibility() == View.GONE)
            return Utils.table2Text(mTable);

        return Utils.table2Text(mTable) +
                "\n" +
                getString(R.string.permissions) +
                ":\n" +
                ((TextView) findViewById(R.id.permissions)).getText();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.copy_share_menu, menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.copy_to_clipboard) {
            Utils.copyToClipboard(this, asString());
            return true;
        } else if(id == R.id.share) {
            Intent intent = new Intent(android.content.Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.app_details));
            intent.putExtra(android.content.Intent.EXTRA_TEXT, asString());

            startActivity(Intent.createChooser(intent, getResources().getString(R.string.share)));

            return true;
        } else if(item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
