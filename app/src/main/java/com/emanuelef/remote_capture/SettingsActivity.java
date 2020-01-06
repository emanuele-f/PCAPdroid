package com.emanuelef.remote_capture;

import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.Patterns;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;

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
        private EditTextPreference mRemoteCollectorIp;
        private EditTextPreference mRemoteCollectorPort;
        private EditTextPreference mHttpServerPort;
        private ListPreference mDumpModePref;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            mDumpModePref = findPreference(Prefs.PREF_PCAP_DUMP_MODE);
            mDumpModePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    dumpPrefsHideShow((String) newValue);
                    return true;
                }
            });

            setupUdpExporterPrefs();
            setupHttpServerPrefs();

            dumpPrefsHideShow(mDumpModePref.getValue());
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
            mRemoteCollectorIp = findPreference(Prefs.PREF_COLLECTOR_IP_KEY);
            mRemoteCollectorIp.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    Matcher matcher = Patterns.IP_ADDRESS.matcher(newValue.toString());
                    return(matcher.matches());
                }
            });

            /* Collector port validation */
            mRemoteCollectorPort = findPreference(Prefs.PREF_COLLECTOR_PORT_KEY);
            mRemoteCollectorPort.setOnBindEditTextListener(new EditTextPreference.OnBindEditTextListener() {
                @Override
                public void onBindEditText(@NonNull EditText editText) {
                    editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
                }
            });
            mRemoteCollectorPort.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    return validatePort(newValue.toString());
                }
            });
        }

        private void setupHttpServerPrefs() {
            /* HTTP Server port validation */
            mHttpServerPort = findPreference(Prefs.PREF_HTTP_SERVER_PORT);
            mHttpServerPort.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    return validatePort(newValue.toString());
                }
            });
        }

        /* This implements a radio-button like behaviour */
        private void dumpPrefsHideShow(String dumpMode) {
            Prefs.DumpMode mode = Prefs.getDumpMode(dumpMode);
            boolean show_http_prefs = (mode == Prefs.DumpMode.HTTP_SERVER);
            boolean show_udp_prefs = (mode == Prefs.DumpMode.UDP_EXPORTER);

            /* HTTP Server */
            mHttpServerPort.setVisible(show_http_prefs);

            /* UDP Receiver */
            mRemoteCollectorIp.setVisible(show_udp_prefs);
            mRemoteCollectorPort.setVisible(show_udp_prefs);

            /* Adjust label */
            int summary_id;

            switch(mode) {
                case HTTP_SERVER:
                    summary_id = R.string.http_server_info;
                    break;
                case UDP_EXPORTER:
                    summary_id = R.string.udp_exporter_info;
                    break;
                case NONE:
                default:
                    summary_id = R.string.no_dump_info;
                    break;
            }

            mDumpModePref.setSummary(summary_id);
        }
    }
}