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
 * Copyright 2020-26 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.activities;

import android.os.Bundle;

import androidx.appcompat.app.ActionBar;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.ListInfo;
import com.emanuelef.remote_capture.model.MatchList;

/* An activity to edit a MatchList, specified via LIST_INFO_EXTRA */
public class EditListActivity extends BaseActivity {
    private static final String TAG = "EditListActivity";
    public static final String LIST_TYPE_EXTRA = "list_type";
    public static final String APP_PACKAGE_EXTRA = "app_package";
    private static final String FITS_SYSTEM_WINDOWS_ARG = "fits_system_windows";
    private ListInfo mListInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if(getIntent() == null) {
            Log.e(TAG, "null intent");
            finish();
            return;
        }

        ListInfo.Type ltype = Utils.getSerializableExtra(getIntent(), LIST_TYPE_EXTRA, ListInfo.Type.class);
        if(ltype == null) {
            Log.e(TAG, "null list info");
            finish();
            return;
        }

        String appPackage = getIntent().getStringExtra(APP_PACKAGE_EXTRA);
        if((ltype == ListInfo.Type.APP_ALLOWLIST) && (appPackage == null)) {
            Log.e(TAG, "APP_ALLOWLIST requires APP_PACKAGE_EXTRA");
            finish();
            return;
        }

        mListInfo = new ListInfo(ltype, appPackage);

        if(ltype == ListInfo.Type.APP_ALLOWLIST)
            setAppAllowlistTitle(appPackage);
        else
            setTitle(mListInfo.getTitle());

        setContentView(R.layout.fragment_activity);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment, mListInfo.newFragment())
                .commit();
    }

    private void setAppAllowlistTitle(String appPackage) {
        AppDescriptor app = AppsResolver.resolveInstalledApp(getPackageManager(), appPackage, 0);
        String appName = (app != null) ? app.getName() : appPackage;
        setTitle(R.string.allowlist);

        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null)
            actionBar.setSubtitle(appName);
    }

    public MatchList getList() {
        return mListInfo.getList();
    }
}
