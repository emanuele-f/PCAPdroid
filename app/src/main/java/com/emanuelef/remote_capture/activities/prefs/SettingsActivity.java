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

package com.emanuelef.remote_capture.activities.prefs;

import android.app.LocaleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.provider.Settings;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.MitmAddon;
import com.emanuelef.remote_capture.VpnReconnectService;
import com.emanuelef.remote_capture.activities.BaseActivity;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.activities.MitmSetupWizard;
import com.emanuelef.remote_capture.fragments.prefs.DnsSettings;
import com.emanuelef.remote_capture.fragments.prefs.GeoipSettings;
import com.emanuelef.remote_capture.fragments.prefs.Socks5Settings;
import com.emanuelef.remote_capture.interfaces.FragmentViewCreatedListener;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.R;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;

public class SettingsActivity extends BaseActivity implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
        FragmentManager.OnBackStackChangedListener,
        FragmentViewCreatedListener {
    private static final String TAG = "SettingsActivity";
    private static final String ACTION_LANG_RESTART = "lang_restart";
    public static final String TARGET_PREF_EXTRA = "target_pref";
    private WindowInsetsCompat mInsets = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.title_activity_settings); // note: setting via manifest does not honor custom locale
        displayBackAction();
        setContentView(R.layout.fragment_activity);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment, new SettingsFragment(), "root")
                .commit();

        getSupportFragmentManager().addOnBackStackChangedListener(this);
    }

    @Nullable
    @Override
    public View onCreateView(@Nullable View parent, @NonNull String name, @NonNull Context context, @NonNull AttributeSet attrs) {
        View view = super.onCreateView(parent, name, context, attrs);
        if (view != null)
            ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
                mInsets = windowInsets;
                return windowInsets;
            });

        return view;
    }

    @Override
    public void onFragmentViewCreated(@NonNull View view) {
        // necessary, otherwise insets are not dispatched after fragment replace
        if (mInsets != null)
            ViewCompat.dispatchApplyWindowInsets(view, mInsets);
    }

    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller, @NonNull Preference pref) {
        PreferenceFragmentCompat targetFragment = null;
        String prefKey = pref.getKey();

        Log.d(TAG, "startFragment: " + prefKey);

        if(prefKey.equals("geolocation")) {
            targetFragment = new GeoipSettings();
            setTitle(R.string.geolocation);
        } else if(prefKey.equals("dns_settings")) {
            targetFragment = new DnsSettings();
            setTitle(R.string.dns_servers);
        } else if(prefKey.equals("socks5_settings")) {
            targetFragment = new Socks5Settings();
            setTitle(R.string.socks5_proxy);
        }

        if(targetFragment != null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment, targetFragment, pref.getKey())
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
                    .addToBackStack(pref.getKey())
                    .commit();
            return true;
        }

        return false;
    }

    @Override
    public void onBackStackChanged() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment);
        if(f instanceof SettingsFragment) {
            setTitle(R.string.title_activity_settings);

            var view = f.getView();
            if ((mInsets != null) && (view != null))
                ViewCompat.dispatchApplyWindowInsets(view, mInsets);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment);
        if(f instanceof SettingsFragment) {
            Intent intent = getIntent();

            if ((intent != null) && SettingsActivity.ACTION_LANG_RESTART.equals(intent.getAction())) {
                // Use a custom intent to provide "up" navigation after ACTION_LANG_RESTART took place
                intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                finish();
                return;
            }
        }

        // default behavior
        super.onBackPressed();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private SwitchPreference mTlsDecryption;
        private SwitchPreference mFullPayloadEnabled;
        private SwitchPreference mRootCaptureEnabled;
        private SwitchPreference mAutoBlockPrivateDNS;
        private EditTextPreference mMitmproxyOpts;
        private DropDownPreference mIpMode;
        private DropDownPreference mCapInterface;
        private DropDownPreference mBlockQuic;
        private Preference mVpnExceptions;
        private Preference mSocks5Settings;
        private Preference mDnsSettings;
        private Preference mPortMapping;
        private Preference mMitmWizard;
        private SwitchPreference mMalwareDetectionEnabled;
        private SwitchPreference mPcapngEnabled;
        private SwitchPreference mRestartOnDisconnect;
        private Billing mIab;
        private boolean mHasStartedMitmWizard;
        private boolean mRootDecryptionNoticeShown = false;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            mIab = Billing.newInstance(requireContext());

            setupExporterPrefs();
            setupHttpServerPrefs();
            setupTrafficInspectionPrefs();
            setupCapturePrefs();
            setupSecurityPrefs();
            setupOtherPrefs();

            socks5ProxyHideShow(mTlsDecryption.isChecked(), rootCaptureEnabled());
            mBlockQuic.setVisible(!rootCaptureEnabled());
            rootCaptureHideShow(rootCaptureEnabled());

            Intent intent = requireActivity().getIntent();
            if(intent != null) {
                String target_pref = intent.getStringExtra(TARGET_PREF_EXTRA);
                if(target_pref != null)
                    scrollToPreference(target_pref);
            }
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
                Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                        WindowInsetsCompat.Type.displayCutout());
                v.setPadding(insets.left, insets.top, insets.right, insets.bottom);

                return WindowInsetsCompat.CONSUMED;
            });
        }

        @Override
        public void onResume() {
            super.onResume();

            if(mHasStartedMitmWizard && !MitmAddon.needsSetup(requireContext())) {
                Log.d(TAG, "mitm setup complete, enabling");
                mTlsDecryption.setChecked(true);
                mFullPayloadEnabled.setChecked(true);
            }
            mHasStartedMitmWizard = false;
        }

        private @NonNull <T extends Preference> T requirePreference(String key) {
            T pref = findPreference(key);
            if(pref == null)
                throw new IllegalStateException();
            return pref;
        }

        @SuppressWarnings("deprecation")
        private void setupExporterPrefs() {
            /* Collector IP validation */
            EditTextPreference mRemoteCollectorIp = requirePreference(Prefs.PREF_COLLECTOR_IP_KEY);
            mRemoteCollectorIp.setOnPreferenceChangeListener((preference, newValue) -> Utils.validateIpAddress(newValue.toString()));

            /* Collector port validation */
            EditTextPreference mRemoteCollectorPort = requirePreference(Prefs.PREF_COLLECTOR_PORT_KEY);
            mRemoteCollectorPort.setOnBindEditTextListener(editText -> editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED));
            mRemoteCollectorPort.setOnPreferenceChangeListener((preference, newValue) -> Utils.validatePort(newValue.toString()));
        }

        private void setupHttpServerPrefs() {
            /* HTTP Server port validation */
            EditTextPreference mHttpServerPort = requirePreference(Prefs.PREF_HTTP_SERVER_PORT);
            mHttpServerPort.setOnPreferenceChangeListener((preference, newValue) -> Utils.validatePort(newValue.toString()));
        }

        private boolean rootCaptureEnabled() {
            return Utils.isRootAvailable() && mRootCaptureEnabled.isChecked();
        }

        private boolean isPcapngEnabled() {
            return mIab.isPurchased(Billing.PCAPNG_SKU) && mPcapngEnabled.isChecked();
        }

        private void refreshInterfaces() {
            ArrayList<String> labels = new ArrayList<>();
            ArrayList<String> values = new ArrayList<>();

            labels.add(getString(R.string.internet));
            values.add("@inet");
            labels.add(getString(R.string.all_interfaces));
            values.add("any");

            try {
                Enumeration<NetworkInterface> ifaces = Utils.getNetworkInterfaces();

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
            refreshInterfaces();

            mRootCaptureEnabled = requirePreference(Prefs.PREF_ROOT_CAPTURE);
            if(Utils.isRootAvailable()) {
                mRootCaptureEnabled.setOnPreferenceChangeListener((preference, newValue) -> {
                    rootCaptureHideShow((Boolean) newValue);
                    return checkDecrpytionWithRoot((Boolean) newValue, mTlsDecryption.isChecked());
                });
            } else
                mRootCaptureEnabled.setVisible(false);

            mRestartOnDisconnect = requirePreference(Prefs.PREF_RESTART_ON_DISCONNECT);
            mRestartOnDisconnect.setVisible(VpnReconnectService.isAvailable());

            mDnsSettings = requirePreference("dns_settings");;
            mVpnExceptions = requirePreference(Prefs.PREF_VPN_EXCEPTIONS);
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

                if(!checkDecrpytionWithRoot(rootCaptureEnabled(), (boolean) newValue))
                    return false;

                if(enabled && MitmAddon.needsSetup(ctx)) {
                    mHasStartedMitmWizard = true;
                    Intent intent = new Intent(ctx, MitmSetupWizard.class);
                    startActivity(intent);
                    return false;
                }

                mMitmWizard.setVisible((boolean) newValue);
                mMitmproxyOpts.setVisible((boolean) newValue);
                socks5ProxyHideShow((boolean) newValue, rootCaptureEnabled());
                return true;
            });

            mPcapngEnabled = requirePreference("pcapng_format");

            if(mIab.isAvailable(Billing.PCAPNG_SKU)) {
                mPcapngEnabled.setOnPreferenceClickListener((preference -> {
                    // Billing code here

                    return false;
                }));
                if(!mIab.isPurchased(Billing.PCAPNG_SKU))
                    mPcapngEnabled.setChecked(false);
            } else
                mPcapngEnabled.setVisible(false);

            mFullPayloadEnabled = requirePreference(Prefs.PREF_FULL_PAYLOAD);
            mBlockQuic = requirePreference(Prefs.PREF_BLOCK_QUIC);
            mMitmproxyOpts = requirePreference(Prefs.PREF_MITMPROXY_OPTS);
            mMitmproxyOpts.setVisible(mTlsDecryption.isChecked());
            mMitmWizard = requirePreference("mitm_setup_wizard");
            mMitmWizard.setVisible(mTlsDecryption.isChecked());
            mMitmWizard.setOnPreferenceClickListener(preference -> {
                mHasStartedMitmWizard = true;
                Intent intent = new Intent(requireContext(), MitmSetupWizard.class);
                startActivity(intent);
                return true;
            });

            mSocks5Settings = requirePreference("socks5_settings");
        }

        private void socks5ProxyHideShow(boolean tlsDecryption, boolean rootEnabled) {
            mSocks5Settings.setVisible(!tlsDecryption && !rootEnabled);
        }

        private void setupAppLanguagePref() {
            DropDownPreference appLang = requirePreference(Prefs.PREF_APP_LANGUAGE);
            Preference appLangExternal = requirePreference("app_language_external");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // On Android 33+, app language is configurable from the system settings
                appLang.setVisible(false);
                appLangExternal.setVisible(true);

                LocaleList locales = requireContext().getSystemService(LocaleManager.class)
                        .getApplicationLocales();
                if (locales.equals(LocaleList.getEmptyLocaleList()))
                    appLangExternal.setSummary(getString(R.string.system_default));
                else if (!locales.isEmpty())
                    appLangExternal.setSummary(locales.get(0).getDisplayName());

                appLangExternal.setOnPreferenceClickListener(preference -> {
                    Intent intent = new Intent(Settings.ACTION_APP_LOCALE_SETTINGS);
                    intent.setData(Uri.fromParts("package", requireContext().getPackageName(), null));
                    startActivity(intent);
                    return true;
                });
            } else {
                // Fallback selector for older Android versions
                if (SettingsActivity.ACTION_LANG_RESTART.equals(requireActivity().getIntent().getAction()))
                    scrollToPreference(appLang);

                // Current locale applied via BaseActivity.attachBaseContext
                appLang.setOnPreferenceChangeListener((preference, newValue) -> {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

                    if (prefs.edit().putString(Prefs.PREF_APP_LANGUAGE, newValue.toString()).commit()) {
                        // Restart the activity to apply the language change
                        Intent intent = new Intent(requireContext(), SettingsActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        intent.setAction(SettingsActivity.ACTION_LANG_RESTART);
                        startActivity(intent);

                        Runtime.getRuntime().exit(0);
                    }

                    return false;
                });
            }
        }

        private void setupOtherPrefs() {
            setupAppLanguagePref();

            mPortMapping = requirePreference(Prefs.PREF_PORT_MAPPING);
            mPortMapping.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(requireContext(), PortMapActivity.class);
                startActivity(intent);
                return true;
            });

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
                mAutoBlockPrivateDNS.setVisible(false);
                mBlockQuic.setVisible(false);
                mSocks5Settings.setVisible(false);
            } else {
                mAutoBlockPrivateDNS.setVisible(true);
                mBlockQuic.setVisible(true);
                socks5ProxyHideShow(mTlsDecryption.isChecked(), false);
            }

            if (VpnReconnectService.isAvailable())
                mRestartOnDisconnect.setVisible(!enabled);

            mIpMode.setVisible(!enabled);
            mCapInterface.setVisible(enabled);
            mVpnExceptions.setVisible(!enabled);
            mDnsSettings.setVisible(!enabled);
            mPortMapping.setVisible(!enabled);
        }

        private boolean checkDecrpytionWithRoot(boolean rootEnabled, boolean tlsDecryption) {
            if(mRootDecryptionNoticeShown || !rootEnabled || !tlsDecryption)
                return true;

            new AlertDialog.Builder(requireContext())
                    .setMessage(R.string.tls_decryption_with_root_msg)
                    .setPositiveButton(R.string.ok, (dialog, whichButton) -> {
                        mRootCaptureEnabled.setChecked(true);
                        mTlsDecryption.setChecked(true);

                        mRootDecryptionNoticeShown = true;
                    })
                    .show();

            return false;
        }
    }
}
