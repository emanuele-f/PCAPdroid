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
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;
import android.view.MenuItem;

import androidx.appcompat.app.ActionBar;
import androidx.preference.DropDownPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;

import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.R;

import java.util.regex.Matcher;

public class SettingsActivity extends BaseActivity {
    private static final String ACTION_LANG_RESTART = "lang_restart";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.title_activity_settings); // note: setting via manifest does not honor custom locale
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

    @Override
    public void onBackPressed() {
        super.onBackPressed();

        // Use a custom intent to provide "up" navigation after ACTION_LANG_RESTART took place
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if(item.getItemId() == android.R.id.home) {
            /* Make the back button in the action bar behave like the back button */
            onBackPressed();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private SwitchPreference mTlsDecryptionEnabled;
        private EditTextPreference mTlsProxyIp;
        private EditTextPreference mTlsProxyPort;
        private Preference mTlsHelp;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            setupUdpExporterPrefs();
            setupHttpServerPrefs();
            setupTlsProxyPrefs();
            setupOtherPrefs();

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
            mTlsHelp = findPreference("tls_how_to");

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
            mTlsHelp.setVisible(decryptionEnabled);
        }

        private void setupOtherPrefs() {
            DropDownPreference appLang = findPreference(Prefs.PREF_APP_LANGUAGE);

            if(SettingsActivity.ACTION_LANG_RESTART.equals(getActivity().getIntent().getAction()))
                scrollToPreference(appLang);

            appLang.setOnPreferenceChangeListener((preference, newValue) -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

                if(prefs.edit().putString(Prefs.PREF_APP_LANGUAGE, newValue.toString()).commit()) {
                    // Restart the activity to apply the language change
                    Intent intent = new Intent(getContext(), SettingsActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    intent.setAction(SettingsActivity.ACTION_LANG_RESTART);
                    startActivity(intent);

                    Runtime.getRuntime().exit(0);
                }

                return false;
            });

            DropDownPreference appTheme = findPreference(Prefs.PREF_APP_THEME);

            appTheme.setOnPreferenceChangeListener((preference, newValue) -> {
                Utils.setAppTheme(newValue.toString());

                return true;
            });
        }
    }
}