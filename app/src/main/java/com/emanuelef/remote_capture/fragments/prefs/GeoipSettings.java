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

package com.emanuelef.remote_capture.fragments.prefs;
import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import com.emanuelef.remote_capture.Geolocation;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.Prefs;

import java.util.Date;

public class GeoipSettings extends SettingsSubFragment {
    private Preference mStatus;
    private Preference mDownload;
    private Preference mDelete;
    private SwitchPreference mAutoUpdate;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.geoip_preferences, rootKey);
        Context context = requireContext();

        mStatus = requirePreference("status");
        mDownload = requirePreference("download");
        mDelete = requirePreference("delete");
        mAutoUpdate = requirePreference(Prefs.PREF_GEO_DB_AUTO_UPDATE);
        refreshStatus(context);

        mDelete.setOnPreferenceClickListener(preference -> {
            mAutoUpdate.setChecked(false);
            Geolocation.deleteDb(context);
            refreshStatus(context);
            return true;
        });

        mDownload.setOnPreferenceClickListener(preference -> {
            PCAPdroid.getInstance().getGeoDbUpdater().download(success -> {
                if(!success)
                    Utils.showToastLong(context, R.string.download_failed);
            });
            return true;
        });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Context context = requireContext();
        PCAPdroid.getInstance().getGeoDbUpdater().observeDownloading(getViewLifecycleOwner(),
                downloading -> onDownloadStateChanged(context, downloading));
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

    private void onDownloadStateChanged(Context context, boolean downloading) {
        mDownload.setEnabled(!downloading);
        mDelete.setEnabled(!downloading);

        if(downloading) {
            mStatus.setSummary(R.string.download_in_progress);
            mStatus.setEnabled(false);
        } else
            refreshStatus(context);
    }

    private @NonNull
    <T extends Preference> T requirePreference(String key) {
        T pref = findPreference(key);
        if(pref == null)
            throw new IllegalStateException();
        return pref;
    }
}
