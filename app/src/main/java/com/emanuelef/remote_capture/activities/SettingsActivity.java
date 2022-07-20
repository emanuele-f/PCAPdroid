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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.InetAddresses;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.Patterns;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.DropDownPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.MitmAddon;
import com.emanuelef.remote_capture.fragments.GeoipSettings;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.R;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.regex.Matcher;

public class SettingsActivity extends BaseActivity implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback, FragmentManager.OnBackStackChangedListener {
    private static final String TAG = "SettingsActivity";
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
                .replace(R.id.settings_container, new SettingsFragment(), "root")
                .commit();

        getSupportFragmentManager().addOnBackStackChangedListener(this);
    }

    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller, @NonNull Preference pref) {
        PreferenceFragmentCompat targetFragment = null;
        String prefKey = pref.getKey();

        Log.d(TAG, "startFragment: " + prefKey);

        if(prefKey.equals("geolocation")) {
            targetFragment = new GeoipSettings();
            setTitle(R.string.geolocation);
        }

        if(targetFragment != null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings_container, targetFragment, pref.getKey())
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    .addToBackStack(pref.getKey())
                    .commit();
            return true;
        }

        return false;
    }

    @Override
    public void onBackStackChanged() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.settings_container);
        if(f instanceof SettingsFragment)
            setTitle(R.string.title_activity_settings);
    }

    @Override
    public void onBackPressed() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.settings_container);
        if(f instanceof SettingsFragment) {
            // Use a custom intent to provide "up" navigation after ACTION_LANG_RESTART took place
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        } else
            super.onBackPressed();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private SwitchPreference mSocks5Enabled;
        private SwitchPreference mTlsDecryption;
        private SwitchPreference mBlockQuic;
        private SwitchPreference mFullPayloadEnabled;
        private SwitchPreference mRootCaptureEnabled;
        private SwitchPreference mAutoBlockPrivateDNS;
        private EditTextPreference mSocks5ProxyIp;
        private EditTextPreference mSocks5ProxyPort;
        private DropDownPreference mIpMode;
        private DropDownPreference mCapInterface;
        private Preference mVpnExceptions;
        private SwitchPreference mMalwareDetectionEnabled;
        private Billing mIab;
        private boolean mHasStartedMitmWizard;

        private final ActivityResultLauncher<String> requestPermissionLauncher =
                registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted ->
                        Log.d(TAG, "Write permission " + (isGranted ? "granted" : "denied")));

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            mIab = Billing.newInstance(requireContext());

            setupUdpExporterPrefs();
            setupHttpServerPrefs();
            setupTrafficInspectionPrefs();
            setupCapturePrefs();
            setupSecurityPrefs();
            setupOtherPrefs();

            fullPayloadHideShow(mTlsDecryption.isChecked());
            socks5ProxyHideShow(mTlsDecryption.isChecked(), mSocks5Enabled.isChecked());
            mBlockQuic.setVisible(mTlsDecryption.isChecked());
            rootCaptureHideShow(Utils.isRootAvailable() && mRootCaptureEnabled.isChecked());

            Intent intent = requireActivity().getIntent();
            if(intent != null) {
                String target_pref = intent.getStringExtra(TARGET_PREF_EXTRA);
                if(target_pref != null)
                    scrollToPreference(target_pref);
            }
        }

        @Override
        public void onResume() {
            super.onResume();

            if(mHasStartedMitmWizard && !MitmAddon.needsSetup(requireContext())) {
                Log.d(TAG, "mitm setup complete, enabling");
                mTlsDecryption.setChecked(true);
            }
            mHasStartedMitmWizard = false;
        }

        private @NonNull <T extends Preference> T requirePreference(String key) {
            T pref = findPreference(key);
            if(pref == null)
                throw new IllegalStateException();
            return pref;
        }

        private boolean validatePort(String value) {
            try {
                int val = Integer.parseInt(value);
                return((val > 0) && (val < 65535));
            } catch(NumberFormatException e) {
                return false;
            }
        }

        private boolean validateIp(String value) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                return (InetAddresses.isNumericAddress(value));
            else {
                Matcher matcher = Patterns.IP_ADDRESS.matcher(value);
                return(matcher.matches());
            }
        }

        @SuppressWarnings("deprecation")
        private void setupUdpExporterPrefs() {
            /* Collector IP validation */
            EditTextPreference mRemoteCollectorIp = requirePreference(Prefs.PREF_COLLECTOR_IP_KEY);
            mRemoteCollectorIp.setOnPreferenceChangeListener((preference, newValue) -> validateIp(newValue.toString()));

            /* Collector port validation */
            EditTextPreference mRemoteCollectorPort = requirePreference(Prefs.PREF_COLLECTOR_PORT_KEY);
            mRemoteCollectorPort.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED));
            mRemoteCollectorPort.setOnPreferenceChangeListener((preference, newValue) -> validatePort(newValue.toString()));
        }

        private void setupHttpServerPrefs() {
            /* HTTP Server port validation */
            EditTextPreference mHttpServerPort = requirePreference(Prefs.PREF_HTTP_SERVER_PORT);
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
            mCapInterface = requirePreference(Prefs.PREF_CAPTURE_INTERFACE);
            mVpnExceptions = requirePreference(Prefs.PREF_VPN_EXCEPTIONS);
            refreshInterfaces();

            mVpnExceptions.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), VpnExemptionsActivity.class);
                startActivity(intent);
                return true;
            });
        }

        private void setupSecurityPrefs() {
            mMalwareDetectionEnabled = requirePreference(Prefs.PREF_MALWARE_DETECTION);

            if(!mIab.isAvailable(Billing.MALWARE_DETECTION_SKU)) {
                getPreferenceScreen().removePreference(requirePreference("security"));
                return;
            }

            // Billing code here
        }

        @SuppressWarnings("deprecation")
        private void setupTrafficInspectionPrefs() {
            mAutoBlockPrivateDNS = requirePreference("auto_block_private_dns");

            mTlsDecryption = requirePreference(Prefs.PREF_TLS_DECRYPTION_KEY);
            mTlsDecryption.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enabled = (boolean) newValue;
                Context ctx = requireContext();

                if(enabled && MitmAddon.needsSetup(ctx)) {
                    mHasStartedMitmWizard = true;
                    Intent intent = new Intent(ctx, MitmSetupWizard.class);
                    startActivity(intent);
                    return false;
                }

                fullPayloadHideShow((boolean) newValue);
                mBlockQuic.setVisible((boolean) newValue);
                socks5ProxyHideShow((boolean) newValue, mSocks5Enabled.isChecked());
                return true;
            });

            mFullPayloadEnabled = requirePreference(Prefs.PREF_FULL_PAYLOAD);
            mBlockQuic = requirePreference(Prefs.PREF_BLOCK_QUIC);

            mSocks5Enabled = requirePreference(Prefs.PREF_SOCKS5_ENABLED_KEY);
            mSocks5Enabled.setOnPreferenceChangeListener((preference, newValue) -> {
                socks5ProxyHideShow(mTlsDecryption.isChecked(), (boolean)newValue);
                return true;
            });

            /* TLS Proxy IP validation */
            mSocks5ProxyIp = requirePreference(Prefs.PREF_SOCKS5_PROXY_IP_KEY);
            mSocks5ProxyIp.setOnPreferenceChangeListener((preference, newValue) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    return (InetAddresses.isNumericAddress(newValue.toString()));
                else {
                    Matcher matcher = Patterns.IP_ADDRESS.matcher(newValue.toString());
                    return(matcher.matches());
                }
            });

            /* TLS Proxy port validation */
            mSocks5ProxyPort = requirePreference(Prefs.PREF_SOCKS5_PROXY_PORT_KEY);
            mSocks5ProxyPort.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED));
            mSocks5ProxyPort.setOnPreferenceChangeListener((preference, newValue) -> validatePort(newValue.toString()));
        }

        private void fullPayloadHideShow(boolean tlsDecryption) {
            mFullPayloadEnabled.setVisible(!tlsDecryption);
        }

        private void socks5ProxyHideShow(boolean tlsDecryption, boolean socks5Enabled) {
            mSocks5Enabled.setVisible(!tlsDecryption);
            mSocks5ProxyIp.setVisible(socks5Enabled && !tlsDecryption);
            mSocks5ProxyPort.setVisible(socks5Enabled && !tlsDecryption);
        }

        private void setupOtherPrefs() {
            DropDownPreference appLang = requirePreference(Prefs.PREF_APP_LANGUAGE);

            if(SettingsActivity.ACTION_LANG_RESTART.equals(requireActivity().getIntent().getAction()))
                scrollToPreference(appLang);

            // Current locale applied via BaseActivity.attachBaseContext
            appLang.setOnPreferenceChangeListener((preference, newValue) -> {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

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

            DropDownPreference appTheme = requirePreference(Prefs.PREF_APP_THEME);

            appTheme.setOnPreferenceChangeListener((preference, newValue) -> {
                Utils.setAppTheme(newValue.toString());

                return true;
            });

            mRootCaptureEnabled = requirePreference(Prefs.PREF_ROOT_CAPTURE);

            if(Utils.isRootAvailable()) {
                mRootCaptureEnabled.setOnPreferenceChangeListener((preference, newValue) -> {
                    rootCaptureHideShow((Boolean) newValue);
                    return true;
                });
            } else
                mRootCaptureEnabled.setVisible(false);

            mIpMode = requirePreference(Prefs.PREF_IP_MODE);

            Preference ctrlPerm = requirePreference("control_permissions");
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
            if(enabled) {
                mTlsDecryption.setVisible(false);
                mAutoBlockPrivateDNS.setVisible(false);
                mSocks5Enabled.setVisible(false);
                mSocks5ProxyIp.setVisible(false);
                mSocks5ProxyPort.setVisible(false);
                mFullPayloadEnabled.setVisible(true);
                mBlockQuic.setVisible(false);
            } else {
                mTlsDecryption.setVisible(true);
                mAutoBlockPrivateDNS.setVisible(true);
                fullPayloadHideShow(mTlsDecryption.isChecked());
                mBlockQuic.setVisible(mTlsDecryption.isChecked());
                socks5ProxyHideShow(mTlsDecryption.isChecked(), mSocks5Enabled.isChecked());
            }

            mIpMode.setVisible(!enabled);
            mCapInterface.setVisible(enabled);
            mVpnExceptions.setVisible(!enabled);
        }
    }
}
