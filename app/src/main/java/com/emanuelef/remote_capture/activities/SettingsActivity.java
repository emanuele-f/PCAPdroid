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
 * Copyright 2020 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.activities;

import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;

import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.R;

import java.util.regex.Matcher;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new SettingsFragment())
                .commit();
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private SwitchPreference mTlsDecryptionEnabled;
        private EditTextPreference mTlsProxyIp;
        private EditTextPreference mTlsProxyPort;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            setupUdpExporterPrefs();
            setupHttpServerPrefs();
            setupTlsProxyPrefs();

            tlsDecryptionHideShow(mTlsDecryptionEnabled.isChecked());
        }

        private boolean validatePort(String value) {
            try {
                int val = Integer.parseInt(value);
                return((val > 0) && (val < 65535));
            } catch(NumberFormatException e) {
                return false;
            }
        }

        private void setupUdpExporterPrefs() {
            /* Collector IP validation */
            EditTextPreference mRemoteCollectorIp = findPreference(Prefs.PREF_COLLECTOR_IP_KEY);
            mRemoteCollectorIp.setOnPreferenceChangeListener((preference, newValue) -> {
                Matcher matcher = Patterns.IP_ADDRESS.matcher(newValue.toString());
                return(matcher.matches());
            });

            /* Collector port validation */
            EditTextPreference mRemoteCollectorPort = findPreference(Prefs.PREF_COLLECTOR_PORT_KEY);
            mRemoteCollectorPort.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED));
            mRemoteCollectorPort.setOnPreferenceChangeListener((preference, newValue) -> validatePort(newValue.toString()));
        }

        private void setupHttpServerPrefs() {
            /* HTTP Server port validation */
            EditTextPreference mHttpServerPort = findPreference(Prefs.PREF_HTTP_SERVER_PORT);
            mHttpServerPort.setOnPreferenceChangeListener((preference, newValue) -> validatePort(newValue.toString()));
        }

        private void setupTlsProxyPrefs() {
            mTlsDecryptionEnabled = findPreference(Prefs.PREF_TLS_DECRYPTION_ENABLED_KEY);
            mTlsDecryptionEnabled.setOnPreferenceChangeListener((preference, newValue) -> {
                tlsDecryptionHideShow((Boolean) newValue);
                return true;
            });

            /* TLS Proxy IP validation */
            mTlsProxyIp = findPreference(Prefs.PREF_TLS_PROXY_IP_KEY);
            mTlsProxyIp.setOnPreferenceChangeListener((preference, newValue) -> {
                Matcher matcher = Patterns.IP_ADDRESS.matcher(newValue.toString());
                return(matcher.matches());
            });

            /* TLS Proxy port validation */
            mTlsProxyPort = findPreference(Prefs.PREF_TLS_PROXY_PORT_KEY);
            mTlsProxyPort.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED));
            mTlsProxyPort.setOnPreferenceChangeListener((preference, newValue) -> validatePort(newValue.toString()));
        }

        private void tlsDecryptionHideShow(boolean decryptionEnabled) {
            mTlsProxyIp.setVisible(decryptionEnabled);
            mTlsProxyPort.setVisible(decryptionEnabled);
        }
    }
}