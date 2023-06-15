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
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.Prefs;

import java.util.Objects;

public class DnsSettings extends PreferenceFragmentCompat {
    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.dns_preferences, rootKey);

        EditTextPreference p1 = Objects.requireNonNull(findPreference(Prefs.PREF_DNS_SERVER_V4));
        p1.setOnPreferenceChangeListener((preference, newValue) -> Utils.validateIpv4Address(newValue.toString()));

        EditTextPreference p2 = Objects.requireNonNull(findPreference(Prefs.PREF_DNS_SERVER_V6));
        p2.setOnPreferenceChangeListener((preference, newValue) -> {
            String ip = newValue.toString();
            return !ip.equals("::") && Utils.validateIpv6Address(ip);
        });
    }
}
