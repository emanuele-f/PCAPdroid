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
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.text.HtmlCompat;

import com.emanuelef.remote_capture.Billing;
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Billing billing = Billing.newInstance(this);
        if(billing.isPlayStore())
            return false;

        getMenuInflater().inflate(R.menu.unlock_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if(item.getItemId() == R.id.unlock_code) {
            showUnlockDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void showUnlockDialog() {
        Billing billing = Billing.newInstance(this);
        LayoutInflater inflater = getLayoutInflater();
        View content = inflater.inflate(R.layout.unlock_dialog, null);

        String systemId = billing.getSystemId();
        TextView systemIdText = content.findViewById(R.id.system_id);
        systemIdText.setText(systemId);
        if(Utils.isTv(this)) {
            systemIdText.setOnClickListener(v -> Utils.shareText(this, getString(R.string.system_id), systemId));
        }

        TextView validationRc = content.findViewById(R.id.validation_rc);
        EditText unlockCode = content.findViewById(R.id.unlock_code);
        unlockCode.setText(billing.getLicense());

        content.findViewById(R.id.copy_id).setOnClickListener(v -> Utils.copyToClipboard(this, systemId));

        AlertDialog myDialog = new AlertDialog.Builder(this)
                .setView(content)
                .setPositiveButton(R.string.ok, (dialog, whichButton) -> billing.setLicense(unlockCode.getText().toString()))
                .setNeutralButton(R.string.validate, (dialog, which) -> {}) // see below
                .create();

        myDialog.show();
        myDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            boolean valid = billing.isValidLicense(unlockCode.getText().toString());
            validationRc.setText(valid ? R.string.valid : R.string.invalid);
            validationRc.setTextColor(ContextCompat.getColor(this, valid ? R.color.ok : R.color.danger));
        });
        myDialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
