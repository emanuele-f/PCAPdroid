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
import android.net.InetAddresses;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Patterns;

import androidx.annotation.Nullable;
import androidx.preference.DropDownPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.PlayBilling;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.R;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.regex.Matcher;

public class SettingsActivity extends BaseActivity {
    private static final String ACTION_LANG_RESTART = "lang_restart";
    public static final String TARGET_PREF_EXTRA = "target_pref";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.title_activity_settings); // note: setting via manifest does not honor custom locale
        displayBackAction();
        setContentView(R.layout.settings_activity);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings_container, new SettingsFragment())
                .commit();
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

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private SwitchPreference mTlsDecryptionEnabled; // TODO rename
        private SwitchPreference mRootCaptureEnabled;
        private EditTextPreference mSocks5ProxyIp;
        private EditTextPreference mSocks5ProxyPort;
        private Preference mTlsHelp;
        private Preference mProxyPrefs;
        private Preference mIpv6Enabled;
        private DropDownPreference mCapInterface;
        private SwitchPreference mMalwareDetectionEnabled;
        private Billing mIab;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            mIab = Billing.newInstance(requireContext());
            mIab.connectBilling();

            // Important: keep at the end
            super.onCreate(savedInstanceState);
        }

        @Override
        public void onDestroy() {
            mIab.disconnectBilling();
            super.onDestroy();
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            setupUdpExporterPrefs();
            setupHttpServerPrefs();
            setupSocks5ProxyPrefs();
            setupCapturePrefs();
            setupSecurityPrefs();
            setupOtherPrefs();

            socks5ProxyHideShow(mTlsDecryptionEnabled.isChecked());
            rootCaptureHideShow(Utils.isRootAvailable() && mRootCaptureEnabled.isChecked());

            Intent intent = requireActivity().getIntent();
            if(intent != null) {
                String target_pref = intent.getStringExtra(TARGET_PREF_EXTRA);
                if(target_pref != null)
                    scrollToPreference(target_pref);
            }
        }

        private boolean validatePort(String value) {
            try {
                int val = Integer.parseInt(value);
                return((val > 0) && (val < 65535));
            } catch(NumberFormatException e) {
                return false;
            }
        }

        @SuppressWarnings("deprecation")
        private void setupUdpExporterPrefs() {
            /* Collector IP validation */
            EditTextPreference mRemoteCollectorIp = findPreference(Prefs.PREF_COLLECTOR_IP_KEY);
            mRemoteCollectorIp.setOnPreferenceChangeListener((preference, newValue) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    return (InetAddresses.isNumericAddress(newValue.toString()));
                else {
                    Matcher matcher = Patterns.IP_ADDRESS.matcher(newValue.toString());
                    return(matcher.matches());
                }
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

        private void refreshInterfaces() {
            ArrayList<String> labels = new ArrayList<>();
            ArrayList<String> values = new ArrayList<>();

            labels.add(getString(R.string.internet));
            values.add("@inet");
            labels.add(getString(R.string.all_interfaces));
            values.add("any");

            try {
                Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();

                while (ifaces.hasMoreElements()) {
                    NetworkInterface iface = ifaces.nextElement();
                    if(!iface.isUp())
                        continue;

                    String name = iface.getName();
                    labels.add(name);
                    values.add(name);
                }
            } catch (SocketException e) {
                e.printStackTrace();
            }

            mCapInterface.setEntryValues(values.toArray(new String[0]));
            mCapInterface.setEntries(labels.toArray(new String[0]));
        }

        private void setupCapturePrefs() {
            mCapInterface = findPreference(Prefs.PREF_CAPTURE_INTERFACE);
            refreshInterfaces();
        }

        private void setupSecurityPrefs() {
            mMalwareDetectionEnabled = findPreference(Prefs.PREF_MALWARE_DETECTION);

            if(!mIab.isAvailable(Billing.MALWARE_DETECTION_SKU)) {
                getPreferenceScreen().removePreference(findPreference("security"));
                return;
            }

            // Billing code here
            mMalwareDetectionEnabled.setOnPreferenceClickListener(preference -> {
                if(!mIab.isRedeemed(Billing.MALWARE_DETECTION_SKU)) {
                    mMalwareDetectionEnabled.setChecked(false);
                    Intent intent = new Intent(requireActivity(), IABActivity.class);
                    startActivity(intent);
                    return true;
                }

                return false;
            });
            if(!mIab.isRedeemed(Billing.MALWARE_DETECTION_SKU))
                mMalwareDetectionEnabled.setChecked(false);
        }

        @SuppressWarnings("deprecation")
        private void setupSocks5ProxyPrefs() {
            mProxyPrefs = findPreference("proxy_prefs");
            mTlsHelp = findPreference("tls_how_to");

            mTlsDecryptionEnabled = findPreference(Prefs.PREF_TLS_DECRYPTION_ENABLED_KEY);
            mTlsDecryptionEnabled.setOnPreferenceChangeListener((preference, newValue) -> {
                socks5ProxyHideShow((Boolean) newValue);
                return true;
            });

            /* TLS Proxy IP validation */
            mSocks5ProxyIp = findPreference(Prefs.PREF_SOCKS5_PROXY_IP_KEY);
            mSocks5ProxyIp.setOnPreferenceChangeListener((preference, newValue) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    return (InetAddresses.isNumericAddress(newValue.toString()));
                else {
                    Matcher matcher = Patterns.IP_ADDRESS.matcher(newValue.toString());
                    return(matcher.matches());
                }
            });

            /* TLS Proxy port validation */
            mSocks5ProxyPort = findPreference(Prefs.PREF_SOCKS5_PROXY_PORT_KEY);
            mSocks5ProxyPort.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED));
            mSocks5ProxyPort.setOnPreferenceChangeListener((preference, newValue) -> validatePort(newValue.toString()));
        }

        private void socks5ProxyHideShow(boolean decryptionEnabled) {
            mSocks5ProxyIp.setVisible(decryptionEnabled);
            mSocks5ProxyPort.setVisible(decryptionEnabled);

            //mTlsHelp.setVisible(decryptionEnabled);
            mTlsHelp.setVisible(true);
        }

        private void setupOtherPrefs() {
            DropDownPreference appLang = findPreference(Prefs.PREF_APP_LANGUAGE);

            if(SettingsActivity.ACTION_LANG_RESTART.equals(getActivity().getIntent().getAction()))
                scrollToPreference(appLang);

            // Current locale applied via BaseActivity.attachBaseContext
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

            mRootCaptureEnabled = findPreference(Prefs.PREF_ROOT_CAPTURE);

            if(Utils.isRootAvailable()) {
                mRootCaptureEnabled.setOnPreferenceChangeListener((preference, newValue) -> {
                    rootCaptureHideShow((Boolean) newValue);
                    return true;
                });
            } else
                mRootCaptureEnabled.setVisible(false);

            mIpv6Enabled = findPreference(Prefs.PREF_IPV6_ENABLED);

            Preference ctrlPerm = findPreference("control_permissions");
            if(!PCAPdroid.getInstance().getCtrlPermissions().hasRules())
                ctrlPerm.setVisible(false);
            else
                ctrlPerm.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(requireContext(), EditCtrlPermissions.class);
                    startActivity(intent);
                    return true;
                });
        }

        private void rootCaptureHideShow(boolean enabled) {
            mProxyPrefs.setVisible(!enabled);
            mIpv6Enabled.setVisible(!enabled);
            mCapInterface.setVisible(enabled);
        }
    }
}
