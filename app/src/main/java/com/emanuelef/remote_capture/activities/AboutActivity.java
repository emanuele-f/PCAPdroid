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
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import androidx.core.text.HtmlCompat;

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
        appVersion.setText("PCAPdroid " + Utils.getAppVersion(this));

        ((TextView)findViewById(R.id.app_license)).setMovementMethod(LinkMovementMethod.getInstance());
        ((TextView)findViewById(R.id.opensource_licenses)).setMovementMethod(LinkMovementMethod.getInstance());

        TextView sourceLink = findViewById(R.id.app_source_link);
        String localized = sourceLink.getText().toString();
        sourceLink.setText(HtmlCompat.fromHtml("<a href='" + MainActivity.GITHUB_PROJECT_URL + "'>" + localized + "</a>", HtmlCompat.FROM_HTML_MODE_LEGACY));
        sourceLink.setMovementMethod(LinkMovementMethod.getInstance());
    }
}
