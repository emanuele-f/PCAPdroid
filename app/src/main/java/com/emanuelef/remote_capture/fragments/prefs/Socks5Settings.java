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
 * Copyright 2023 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.fragments.prefs;
import android.os.Bundle;
import android.text.InputType;

import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.Prefs;

import java.util.Objects;

public class Socks5Settings extends PreferenceFragmentCompat {
    private EditTextPreference mSocks5ProxyIp;
    private EditTextPreference mSocks5ProxyPort;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.socks5_preferences, rootKey);

        /* SOCKS5 Proxy IP validation */
        mSocks5ProxyIp = Objects.requireNonNull(findPreference(Prefs.PREF_SOCKS5_PROXY_IP_KEY));
        mSocks5ProxyIp.setOnPreferenceChangeListener((preference, newValue) -> Utils.validateIpAddress(newValue.toString()));

        /* SOCKS5 Proxy port validation */
        mSocks5ProxyPort = Objects.requireNonNull(findPreference(Prefs.PREF_SOCKS5_PROXY_PORT_KEY));
        mSocks5ProxyPort.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED));
        mSocks5ProxyPort.setOnPreferenceChangeListener((preference, newValue) -> Utils.validatePort(newValue.toString()));

        SwitchPreference mSocks5Enabled = Objects.requireNonNull(findPreference(Prefs.PREF_SOCKS5_ENABLED_KEY));
        mSocks5Enabled.setOnPreferenceChangeListener((preference, newValue) -> {
            toggleVisisiblity((boolean) newValue);
            return true;
        });
        toggleVisisiblity(mSocks5Enabled.isChecked());
    }

    private void toggleVisisiblity(boolean socks5_enabled) {
        mSocks5ProxyIp.setVisible(socks5_enabled);
        mSocks5ProxyPort.setVisible(socks5_enabled);
    }
}
