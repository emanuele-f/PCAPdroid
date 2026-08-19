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
 * Copyright 2026 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.activities.prefs;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.model.SettingsBackup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Objects;
import java.util.Scanner;

public class SettingsBackupHandler {
    private static final String TAG = "SettingsBackupHandler";
    private static final String EXPORT_FILE_NAME = "PCAPdroid_settings.json";

    private final Fragment mFragment;
    private final Context mAppContext;
    private final ActivityResultLauncher<Intent> mExportLauncher;
    private final ActivityResultLauncher<Intent> mImportLauncher;
    private boolean mLicenseWarning;

    public SettingsBackupHandler(Fragment fragment) {
        mFragment = fragment;
        mAppContext = fragment.requireContext().getApplicationContext();
        mExportLauncher = fragment.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::exportResult);
        mImportLauncher = fragment.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::importResult);
    }

    public void startExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, EXPORT_FILE_NAME);

        Utils.launchFileDialog(mFragment.requireContext(), intent, mExportLauncher);
    }

    private void exportResult(final ActivityResult result) {
        if ((result.getResultCode() != Activity.RESULT_OK) || (result.getData() == null))
            return;

        Context context = mFragment.requireContext();
        String data = SettingsBackup.serialize(PreferenceManager.getDefaultSharedPreferences(context));

        try (OutputStream out = context.getContentResolver().openOutputStream(
                Objects.requireNonNull(result.getData().getData()), "rwt"))
        {
            try (OutputStreamWriter writer = new OutputStreamWriter(out)) {
                writer.write(data);
            }

            Utils.showToast(context, R.string.save_ok);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "exportResult: " + e.getMessage());
            Utils.showToastLong(context, R.string.export_failed);
        }
    }

    public void startImport() {
        Context context = mFragment.requireContext();

        if (CaptureService.isServiceActive()) {
            Log.w(TAG, "Stop the capture before importing the settings");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        Utils.launchFileDialog(context, intent, mImportLauncher);
    }

    private void importResult(final ActivityResult result) {
        if ((result.getResultCode() != Activity.RESULT_OK) || (result.getData() == null))
            return;

        Context context = mFragment.requireContext();
        SettingsBackup backup;

        try (InputStream in = context.getContentResolver().openInputStream(
                Objects.requireNonNull(result.getData().getData())))
        {
            try (Scanner s = new Scanner(in).useDelimiter("\\A")) {
                backup = SettingsBackup.fromJson(s.hasNext() ? s.next() : "");
            }
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "importResult: " + e.getMessage());
            Utils.showToastLong(context, R.string.import_failed);
            return;
        }

        if (backup == null) {
            Utils.showToastLong(context, R.string.invalid_backup);
            return;
        }

        String date = Utils.formatEpochFull(context, backup.getCreationTime() / 1000);

        new AlertDialog.Builder(context)
                .setTitle(R.string.import_settings)
                .setMessage(mFragment.getString(R.string.import_settings_confirm, date))
                .setPositiveButton(R.string.import_action, (dialog, which) -> doImport(backup))
                .setNegativeButton(R.string.cancel_action, (dialog, which) -> {})
                .show();
    }

    private void doImport(SettingsBackup backup) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mAppContext);

        String localLicense = prefs.getString(SettingsBackup.LICENSE_KEY, "");

        backup.apply(prefs);
        mLicenseWarning = checkImportedLicense(prefs, localLicense);

        finishImport();
    }

    /* The license is bound to the installation id, which changes with the device and with the app
     * signature, so an imported license is usually not valid anymore. When it cannot be used, the
     * license which was already on this device is restored, as the import must not take away an
     * entitlement of this installation. The play build never keeps a license, as the purchases are
     * re-validated via the billing library.
     * Returns true if the user must be warned about the paid features. */
    @SuppressLint("ApplySharedPref")
    private boolean checkImportedLicense(SharedPreferences prefs, String localLicense) {
        // called after the import took place
        Billing billing = Billing.newInstance(mAppContext);

        String imported = billing.getLicense();
        if (imported.isEmpty() && localLicense.isEmpty())
            return false;

        boolean playStore = billing.isPlayStore();
        if (!playStore && billing.isValidLicense(imported))
            return false;

        boolean restore_needed = !playStore && billing.isValidLicense(localLicense);
        SharedPreferences.Editor editor = prefs.edit();

        if (restore_needed) {
            Log.i(TAG, "restoring the license of this installation");
            editor.putString(SettingsBackup.LICENSE_KEY, localLicense);
        } else {
            Log.i(TAG, "dropping the imported license (playStore=" + playStore + ")");
            editor.remove(SettingsBackup.LICENSE_KEY);
        }

        editor.commit();

        return !playStore && !restore_needed && !imported.isEmpty();
    }

    private void finishImport() {
        if (!mFragment.isAdded()) {
            restartApp();
            return;
        }

        StringBuilder msg = new StringBuilder(mFragment.getString(R.string.settings_imported));

        if (mLicenseWarning)
            msg.append("\n\n").append(mFragment.getString(R.string.imported_license_invalid));

        new AlertDialog.Builder(mFragment.requireContext())
                .setTitle(R.string.import_settings)
                .setMessage(msg.toString())
                .setCancelable(false)
                .setPositiveButton(R.string.ok, (dialog, which) -> restartApp())
                .show();
    }

    // Many settings are cached in memory (e.g. the firewall rules, the billing state, the locale),
    // so the app is restarted to consistently apply the imported configuration
    private void restartApp() {
        Intent intent = new Intent(mAppContext, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        mAppContext.startActivity(intent);

        Runtime.getRuntime().exit(0);
    }
}
