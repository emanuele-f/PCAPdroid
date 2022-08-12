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
import com.emanuelef.remote_capture.model.Prefs;

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
        getMenuInflater().inflate(R.menu.about_menu, menu);

        Billing billing = Billing.newInstance(this);
        if(billing.isPlayStore())
            menu.findItem(R.id.paid_features).setVisible(false);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.paid_features) {
            showLicenseDialog();
            return true;
        } else if(id == R.id.on_boarding) {
            Intent intent = new Intent(this, OnBoardingActivity.class);
            intent.putExtra(OnBoardingActivity.ENABLE_BACK_BUTTON, true);
            startActivity(intent);
        } else if(id == R.id.build_info) {
            final String deviceInfo = Utils.getBuildInfo(this) + "\n\n" + Prefs.asString(this);

            LayoutInflater inflater = LayoutInflater.from(this);
            View view = inflater.inflate(R.layout.scrollable_dialog, null);
            ((TextView)view.findViewById(R.id.text)).setText(deviceInfo);

            new AlertDialog.Builder(this)
                    .setTitle(R.string.build_info)
                    .setView(view)
                    .setPositiveButton(R.string.ok, (dialogInterface, i) -> {})
                    .setNeutralButton(R.string.copy_to_clipboard, (dialogInterface, i) ->
                            Utils.copyToClipboard(this, deviceInfo)).show();
        }

        return super.onOptionsItemSelected(item);
    }

    private void showLicenseDialog() {
        Billing billing = Billing.newInstance(this);
        LayoutInflater inflater = getLayoutInflater();
        View content = inflater.inflate(R.layout.license_dialog, null);

        String instId = billing.getInstallationId();
        TextView instIdText = content.findViewById(R.id.installation_id);
        instIdText.setText(instId);
        if(Utils.isTv(this))
            instIdText.setOnClickListener(v -> Utils.shareText(this, getString(R.string.installation_id), instId));

        TextView validationRc = content.findViewById(R.id.validation_rc);
        EditText licenseCode = content.findViewById(R.id.license_code);
        licenseCode.setText(billing.getLicense());
        Utils.setTextUrls((content.findViewById(R.id.paid_features_msg)), R.string.access_paid_features_msg, MainActivity.PAID_FEATURES_URL);

        content.findViewById(R.id.copy_id).setOnClickListener(v -> Utils.copyToClipboard(this, instId));

        boolean was_valid = billing.isPurchased(Billing.SUPPORTER_SKU);

        AlertDialog myDialog = new AlertDialog.Builder(this)
                .setView(content)
                .setPositiveButton(R.string.ok, (dialog, whichButton) -> {
                    billing.setLicense(licenseCode.getText().toString());

                    if(!was_valid && billing.isPurchased(Billing.SUPPORTER_SKU))
                        Utils.showToastLong(this, R.string.paid_features_unlocked);
                })
                .setNeutralButton(R.string.validate, (dialog, which) -> {}) // see below
                .create();

        myDialog.show();
        myDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            boolean valid = billing.isValidLicense(licenseCode.getText().toString());
            validationRc.setText(valid ? R.string.valid : R.string.invalid);
            validationRc.setTextColor(ContextCompat.getColor(this, valid ? R.color.ok : R.color.danger));
        });
        myDialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
