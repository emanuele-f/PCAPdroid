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
 * Copyright 2026 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.collection.ArraySet;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.model.SettingsBackup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SettingsBackupTest {
    SharedPreferences prefs;

    @Before
    public void setup() {
        Context ctx = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit().clear().commit();
    }

    private static Set<String> setOf(String... items) {
        ArraySet<String> rv = new ArraySet<>();
        for (String item: items)
            rv.add(item);
        return rv;
    }

    @Test
    public void testRoundTrip() {
        prefs.edit()
                .putBoolean(Prefs.PREF_ROOT_CAPTURE, true)
                .putString(Prefs.PREF_HTTP_SERVER_PORT, "8081")
                .putString(Prefs.PREF_API_KEY, "s3cr3t")
                .putString(Prefs.PREF_SOCKS5_PASSWORD_KEY, "p4ssw0rd")
                .putInt(Prefs.PREF_FIREWALL_WHITELIST_INIT_VER, 1)
                .putStringSet(Prefs.PREF_APP_FILTER, setOf("com.foo", "com.bar"))
                .commit();

        String json = SettingsBackup.serialize(prefs);
        prefs.edit().clear().commit();

        SettingsBackup backup = SettingsBackup.fromJson(json);
        assertNotNull(backup);
        backup.apply(prefs);

        assertTrue(prefs.getBoolean(Prefs.PREF_ROOT_CAPTURE, false));
        assertEquals("8081", prefs.getString(Prefs.PREF_HTTP_SERVER_PORT, ""));
        assertEquals("s3cr3t", Prefs.getApiKey(prefs));
        assertEquals("p4ssw0rd", Prefs.getSocks5Password(prefs));
        assertEquals(1, prefs.getInt(Prefs.PREF_FIREWALL_WHITELIST_INIT_VER, 0));
        assertEquals(setOf("com.foo", "com.bar"), Prefs.getAppFilterRaw(prefs));
    }

    @Test
    public void testExcludedKeysNotExported() {
        prefs.edit()
                .putBoolean(Prefs.PREF_TLS_DECRYPTION_SETUP_DONE, true)
                .putBoolean(Prefs.PREF_CA_INSTALLATION_SKIPPED, true)
                .putInt(Prefs.PREF_APP_VERSION, 42)
                .putString(PersistableUriPermission.PREF_KEY, "key|content://foo")
                .putString(Blacklists.PREF_BLACKLISTS_STATUS, "{}")
                .putStringSet("peer_skus", setOf(Billing.PCAPNG_SKU))
                .putString("available_skus", "{}")
                .putString("unlock_token", "token")
                .putLong(Billing.SKU_PREF_PREFIX + Billing.PCAPNG_SKU, 1234)
                .putBoolean(Prefs.PREF_ROOT_CAPTURE, true)
                .commit();

        String json = SettingsBackup.serialize(prefs);

        assertFalse(json.contains(Prefs.PREF_TLS_DECRYPTION_SETUP_DONE));
        assertFalse(json.contains(Prefs.PREF_CA_INSTALLATION_SKIPPED));
        assertFalse(json.contains(Prefs.PREF_APP_VERSION));
        assertFalse(json.contains(PersistableUriPermission.PREF_KEY));
        assertFalse(json.contains(Blacklists.PREF_BLACKLISTS_STATUS));
        assertFalse(json.contains("peer_skus"));
        assertFalse(json.contains("available_skus"));
        assertFalse(json.contains("unlock_token"));
        assertFalse(json.contains(Billing.SKU_PREF_PREFIX));
        assertTrue(json.contains(Prefs.PREF_ROOT_CAPTURE));
    }

    // the excluded keys describe this installation, they must survive an import
    @Test
    public void testApplyKeepsExcludedKeys() {
        prefs.edit().putBoolean(Prefs.PREF_ROOT_CAPTURE, true).commit();
        String json = SettingsBackup.serialize(prefs);

        prefs.edit()
                .putBoolean(Prefs.PREF_TLS_DECRYPTION_SETUP_DONE, true)
                .putInt(Prefs.PREF_APP_VERSION, 42)
                .putLong(Billing.SKU_PREF_PREFIX + Billing.PCAPNG_SKU, 1234)
                .putString(Prefs.PREF_API_KEY, "local")
                .commit();

        SettingsBackup backup = SettingsBackup.fromJson(json);
        assertNotNull(backup);
        backup.apply(prefs);

        assertTrue(Prefs.isTLSDecryptionSetupDone(prefs));
        assertEquals(42, Prefs.getAppVersion(prefs));
        assertEquals(1234, prefs.getLong(Billing.SKU_PREF_PREFIX + Billing.PCAPNG_SKU, 0));

        // a key which is not in the bundle is dropped, as the import replaces the settings
        assertEquals("", Prefs.getApiKey(prefs));
    }

    @Test
    public void testLicenseIsExported() {
        prefs.edit().putString(SettingsBackup.LICENSE_KEY, "MYLICENSE").commit();

        SettingsBackup backup = SettingsBackup.fromJson(SettingsBackup.serialize(prefs));
        assertNotNull(backup);
        assertEquals("MYLICENSE", backup.getString(SettingsBackup.LICENSE_KEY));
    }

    @Test
    public void testCaptureListNotApplied() {
        prefs.edit().putString(Prefs.PREF_CAPTURE_LIST, "[{\"startTime\": 1}]").commit();
        String json = SettingsBackup.serialize(prefs);

        prefs.edit().putString(Prefs.PREF_CAPTURE_LIST, "[{\"startTime\": 2}]").commit();

        SettingsBackup backup = SettingsBackup.fromJson(json);
        assertNotNull(backup);
        backup.apply(prefs);

        assertEquals("[{\"startTime\": 1}]", backup.getString(Prefs.PREF_CAPTURE_LIST));
        assertEquals("[{\"startTime\": 2}]", prefs.getString(Prefs.PREF_CAPTURE_LIST, ""));
    }

    @Test
    public void testInvalidBackup() {
        assertNull(SettingsBackup.fromJson(""));
        assertNull(SettingsBackup.fromJson("not json"));
        assertNull(SettingsBackup.fromJson("{}"));
        assertNull(SettingsBackup.fromJson("{\"version\": 1}"));

        // no settings to restore
        assertNull(SettingsBackup.fromJson("{\"version\": 1, \"app_version\": 1, \"created\": 1, \"settings\": {}}"));

        // future format
        assertNull(SettingsBackup.fromJson("{\"version\": " + (SettingsBackup.VERSION + 1) +
                ", \"app_version\": 1, \"created\": 1, \"settings\": {\"a\": {\"type\": \"int\", \"value\": 1}}}"));
    }
}
