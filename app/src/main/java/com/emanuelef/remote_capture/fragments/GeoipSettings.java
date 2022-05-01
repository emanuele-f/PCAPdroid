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

package com.emanuelef.remote_capture.fragments;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.emanuelef.remote_capture.Geolocation;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;

import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GeoipSettings extends PreferenceFragmentCompat {
    private static final String TAG = "GeoipSettings";
    private Preference mStatus;
    private Preference mDelete;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.geoip_preferences, rootKey);

        mStatus = requirePreference("status");
        mDelete = requirePreference("delete");
        refreshStatus();

        mDelete.setOnPreferenceClickListener(preference -> {
            Geolocation.deleteDb(requireContext());
            refreshStatus();
            return true;
        });

        requirePreference("download")
                .setOnPreferenceClickListener(preference -> {
            downloadDatabases();
            return true;
        });
    }

    private void refreshStatus() {
        Date builtDate = Geolocation.getDbDate(requireContext());
        if(builtDate != null) {
            String dateStr = Utils.formatEpochFull(requireContext(), builtDate.getTime() / 1000);
            mStatus.setSummary("DB-IP Lite free\n" +
                    String.format(getString(R.string.built_on), dateStr) + "\n" +
                    String.format(getString(R.string.size_x), Utils.formatBytes(Geolocation.getDbSize(requireContext()))));
        } else
            mStatus.setSummary(R.string.geo_db_not_found);

        mDelete.setVisible((builtDate != null));
    }

    private void downloadDatabases() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());

        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle(R.string.downloading);
        builder.setMessage(R.string.download_in_progress);

        final AlertDialog alert = builder.create();
        alert.setCanceledOnTouchOutside(false);
        alert.show();

        alert.setOnCancelListener(dialogInterface -> {
            Log.i(TAG, "Abort download");
            executor.shutdownNow();
        });

        executor.execute(() -> {
            boolean result = Geolocation.downloadDb(requireContext());

            handler.post(() -> {
                if(!result)
                    Utils.showToastLong(requireContext(), R.string.download_failed);

                alert.dismiss();
                refreshStatus();
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
