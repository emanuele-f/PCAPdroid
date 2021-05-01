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
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;

public class AboutActivity extends BaseActivity {
    private static final String TAG = "AboutActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.about);
        setContentView(R.layout.about_activity);

        TextView appVersion = findViewById(R.id.app_version);
        appVersion.setText(getString(R.string.app_name) + " " + Utils.getAppVersion(this));

        TextView gplLicense = findViewById(R.id.app_license_link);
        String localized = gplLicense.getText().toString();
        gplLicense.setText(Html.fromHtml("<a href='https://www.gnu.org/licenses/gpl-3.0-standalone.html'>" + localized + "</a>"));
        gplLicense.setMovementMethod(LinkMovementMethod.getInstance());

        TextView sourceLink = findViewById(R.id.app_source_link);
        localized = sourceLink.getText().toString();
        sourceLink.setText(Html.fromHtml("<a href='" + MainActivity.GITHUB_PROJECT_URL + "'>" + localized + "</a>"));
        sourceLink.setMovementMethod(LinkMovementMethod.getInstance());
    }
}
