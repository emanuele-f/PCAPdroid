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

import android.os.Bundle;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.fragments.AppOverview;

public class AppDetailsActivity extends BaseActivity {
    private static final String TAG = "AppDetailsActivity";
    public static final String APP_UID_EXTRA = "app_uid";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.app_details);
        displayBackAction();
        setContentView(R.layout.fragment_activity);

        int uid = getIntent().getIntExtra(APP_UID_EXTRA, Utils.UID_UNKNOWN);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment, AppOverview.newInstance(uid))
                .commit();
    }
}
