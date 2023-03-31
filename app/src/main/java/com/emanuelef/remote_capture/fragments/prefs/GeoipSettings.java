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
 * Copyright 2020-22 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.fragments.prefs;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.emanuelef.remote_capture.Geolocation;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;

import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GeoipSettings extends PreferenceFragmentCompat {
    private static final String TAG = "GeoipSettings";
    private Preference mStatus;
    private Preference mDelete;
    private AlertDialog mAlertDialog;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.geoip_preferences, rootKey);
        Context context = requireContext();

        mStatus = requirePreference("status");
        mDelete = requirePreference("delete");
        refreshStatus(context);

        mDelete.setOnPreferenceClickListener(preference -> {
            Geolocation.deleteDb(context);
            refreshStatus(context);
            return true;
        });

        requirePreference("download")
                .setOnPreferenceClickListener(preference -> {
            downloadDatabases();
            return true;
        });
    }

    @Override
    public void onDestroyView() {
        // See https://stackoverflow.com/questions/22924825/view-not-attached-to-window-manager-crash
        if(mAlertDialog != null)
            mAlertDialog.dismiss();

        super.onDestroyView();
    }

    // NOTE: passing explicit context as this may be called when requireContext would return null
    private void refreshStatus(Context context) {
        Date builtDate = Geolocation.getDbDate(context);
        if(builtDate != null) {
            String dateStr = Utils.formatEpochFull(context, builtDate.getTime() / 1000);
            mStatus.setSummary("DB-IP Lite free\n" +
                    String.format(context.getString(R.string.built_on), dateStr) + "\n" +
                    String.format(context.getString(R.string.size_x), Utils.formatBytes(Geolocation.getDbSize(context))));
            mStatus.setEnabled(true);
        } else {
            mStatus.setSummary(R.string.geo_db_not_found);
            mStatus.setEnabled(false);
        }

        mDelete.setVisible((builtDate != null));
    }

    private void downloadDatabases() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(R.string.downloading);
        builder.setMessage(R.string.download_in_progress);

        mAlertDialog = builder.create();
        mAlertDialog.setCanceledOnTouchOutside(false);
        mAlertDialog.show();

        mAlertDialog.setOnCancelListener(dialogInterface -> {
            Log.i(TAG, "Abort download");
            executor.shutdownNow();
        });
        mAlertDialog.setOnDismissListener(dialog -> mAlertDialog = null);

        // Hold reference to context to avoid garbage collection before the handler is called
        final Context context = requireContext();
        executor.execute(() -> {
            boolean result = Geolocation.downloadDb(context);

            handler.post(() -> {
                if(!result)
                    Utils.showToastLong(context, R.string.download_failed);

                if(mAlertDialog != null)
                    mAlertDialog.dismiss();
                refreshStatus(context);
            });
        });
    }

    private @NonNull
    <T extends Preference> T requirePreference(String key) {
        T pref = findPreference(key);
        if(pref == null)
            throw new IllegalStateException();
        return pref;
    }
}
