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

import com.emanuelef.remote_capture.model.Blocklist;
import com.emanuelef.remote_capture.model.CaptureList;
import com.emanuelef.remote_capture.model.CtrlPermissions;
import com.emanuelef.remote_capture.model.MatchList;
import com.emanuelef.remote_capture.model.PortMapping;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.model.PrefsSchema;
import com.emanuelef.remote_capture.model.SettingsBackup;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class SettingsBackupTest {
    Context context;
    SharedPreferences prefs;

    @Before
    public void setup() {
        context = ApplicationProvider.getApplicationContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().clear().commit();
    }

    private static Set<String> setOf(String... items) {
        ArraySet<String> rv = new ArraySet<>();
        for (String item: items)
            rv.add(item);
        return rv;
    }

    private static String backupJson(String settings) {
        return "{\"version\": 1, \"app_version\": 1, \"created\": 1, \"settings\": {" + settings +
                ", \"" + Prefs.PREF_START_AT_BOOT + "\": {\"type\": \"boolean\", \"value\": true}}}";
    }

    private static String entry(String key, String type, String value) {
        return "\"" + key + "\": {\"type\": \"" + type + "\", \"value\": " + value + "}";
    }

    private void assertSkipped(String settings, String key) {
        SettingsBackup backup = SettingsBackup.fromJson(backupJson(settings));
        assertNotNull(backup);
        backup.apply(prefs);

        assertFalse(key, prefs.contains(key));
        assertTrue(Prefs.startAtBoot(prefs));
    }

    @Test
    public void testRoundTrip() {
        prefs.edit()
                .putBoolean(Prefs.PREF_ROOT_CAPTURE, true)
                .putString(Prefs.PREF_HTTP_SERVER_PORT, "8081")
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
                .putString(Prefs.PREF_API_KEY, "s3cr3t")
                .putString(CtrlPermissions.PREF_NAME, "{\"rules\": []}")
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
        assertFalse(json.contains(Prefs.PREF_API_KEY));
        assertFalse(json.contains(CtrlPermissions.PREF_NAME));
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
                .putString(Prefs.PREF_SOCKS5_PASSWORD_KEY, "p4ssw0rd")
                .commit();

        SettingsBackup backup = SettingsBackup.fromJson(json);
        assertNotNull(backup);
        backup.apply(prefs);

        assertTrue(Prefs.isTLSDecryptionSetupDone(prefs));
        assertEquals(42, Prefs.getAppVersion(prefs));
        assertEquals(1234, prefs.getLong(Billing.SKU_PREF_PREFIX + Billing.PCAPNG_SKU, 0));
        assertEquals("local", Prefs.getApiKey(prefs));

        // a key which is not in the bundle is dropped, as the import replaces the settings
        assertEquals("", Prefs.getSocks5Password(prefs));
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
        prefs.edit().putString(Prefs.PREF_CAPTURE_LIST, "[{\"uri\": \"a\", \"name\": \"a\", \"startTime\": 1}]").commit();
        String json = SettingsBackup.serialize(prefs);

        prefs.edit().putString(Prefs.PREF_CAPTURE_LIST, "[{\"uri\": \"b\", \"name\": \"b\", \"startTime\": 2}]").commit();

        SettingsBackup backup = SettingsBackup.fromJson(json);
        assertNotNull(backup);
        backup.apply(prefs);

        assertEquals("[{\"uri\": \"a\", \"name\": \"a\", \"startTime\": 1}]", backup.getString(Prefs.PREF_CAPTURE_LIST));
        assertEquals("[{\"uri\": \"b\", \"name\": \"b\", \"startTime\": 2}]", prefs.getString(Prefs.PREF_CAPTURE_LIST, ""));
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

    @Test
    public void testWrongTypeSkipped() {
        assertSkipped(entry(Prefs.PREF_ROOT_CAPTURE, "string", "\"true\""), Prefs.PREF_ROOT_CAPTURE);
        assertSkipped(entry(Prefs.PREF_ROOT_CAPTURE, "boolean", "\"abc\""), Prefs.PREF_ROOT_CAPTURE);
        assertSkipped(entry(Prefs.PREF_HTTP_SERVER_PORT, "int", "8080"), Prefs.PREF_HTTP_SERVER_PORT);
        assertSkipped(entry(Prefs.PREF_HTTP_SERVER_PORT, "string", "8080"), Prefs.PREF_HTTP_SERVER_PORT);
        assertSkipped(entry(Prefs.PREF_FIREWALL_WHITELIST_INIT_VER, "int", "\"abc\""), Prefs.PREF_FIREWALL_WHITELIST_INIT_VER);
        assertSkipped(entry(Prefs.PREF_FIREWALL_WHITELIST_INIT_VER, "int", "1.5"), Prefs.PREF_FIREWALL_WHITELIST_INIT_VER);
        assertSkipped(entry(Prefs.PREF_FIREWALL_WHITELIST_INIT_VER, "int", "4294967296"), Prefs.PREF_FIREWALL_WHITELIST_INIT_VER);
        assertSkipped(entry(Prefs.PREF_APP_FILTER, "string", "\"com.foo\""), Prefs.PREF_APP_FILTER);
        assertSkipped(entry(Prefs.PREF_APP_FILTER, "string_set", "[\"com.foo\", 1]"), Prefs.PREF_APP_FILTER);
        assertSkipped(entry(Prefs.PREF_VPN_EXCEPTIONS, "string_set", "\"com.foo\""), Prefs.PREF_VPN_EXCEPTIONS);
        assertSkipped("\"" + Prefs.PREF_ROOT_CAPTURE + "\": true", Prefs.PREF_ROOT_CAPTURE);
    }

    @Test
    public void testInvalidValueSkipped() {
        assertSkipped(entry(Prefs.PREF_HTTP_SERVER_PORT, "string", "\"abc\""), Prefs.PREF_HTTP_SERVER_PORT);
        assertSkipped(entry(Prefs.PREF_COLLECTOR_PORT_KEY, "string", "\"70000\""), Prefs.PREF_COLLECTOR_PORT_KEY);
        assertSkipped(entry(Prefs.PREF_SOCKS5_PROXY_PORT_KEY, "string", "\"0\""), Prefs.PREF_SOCKS5_PROXY_PORT_KEY);
        assertSkipped(entry(Prefs.PREF_COLLECTOR_HOST_KEY, "string", "\"bad host!\""), Prefs.PREF_COLLECTOR_HOST_KEY);
        assertSkipped(entry(Prefs.PREF_DNS_SERVER_V4, "string", "\"1.2.3.256\""), Prefs.PREF_DNS_SERVER_V4);
        assertSkipped(entry(Prefs.PREF_DNS_SERVER_V6, "string", "\"1.1.1.1\""), Prefs.PREF_DNS_SERVER_V6);
        assertSkipped(entry(Prefs.PREF_IP_MODE, "string", "\"ipv5\""), Prefs.PREF_IP_MODE);
        assertSkipped(entry(Prefs.PREF_PCAP_DUMP_MODE, "string", "\"foo\""), Prefs.PREF_PCAP_DUMP_MODE);
        assertSkipped(entry(Prefs.PREF_CONNECTIONS_LOG_SIZE, "string", "\"-1\""), Prefs.PREF_CONNECTIONS_LOG_SIZE);
        assertSkipped(entry("unknown_pref", "boolean", "true"), "unknown_pref");
    }

    @Test
    public void testValidValuesApplied() {
        SettingsBackup backup = SettingsBackup.fromJson(backupJson(
                entry(Prefs.PREF_COLLECTOR_HOST_KEY, "string", "\"example.org\"") + ", " +
                entry(Prefs.PREF_SOCKS5_PROXY_IP_KEY, "string", "\"::1\"") + ", " +
                entry(Prefs.PREF_DNS_SERVER_V6, "string", "\"2001:db8::1\"") + ", " +
                entry(Prefs.PREF_BLOCK_QUIC, "string", "\"always\"") + ", " +
                entry(Prefs.PREF_PORT_MAPPING, "string", "\"[]\"") + ", " +
                entry(Prefs.PREF_FIREWALL_WHITELIST, "string", "\"\"")));
        assertNotNull(backup);
        backup.apply(prefs);

        assertEquals("example.org", Prefs.getCollectorHost(prefs));
        assertEquals("::1", Prefs.getSocks5ProxyHost(prefs));
        assertEquals("2001:db8::1", Prefs.getDnsServerV6(prefs));
        assertEquals(Prefs.BlockQuicMode.ALWAYS, Prefs.getBlockQuicMode(prefs));
        assertEquals("[]", prefs.getString(Prefs.PREF_PORT_MAPPING, null));
        assertEquals("", prefs.getString(Prefs.PREF_FIREWALL_WHITELIST, null));
    }

    // invalid rules are skipped by the list loader, without rejecting the whole list
    @Test
    public void testInvalidRulesSkipped() {
        SettingsBackup backup = SettingsBackup.fromJson(backupJson(
                entry(Prefs.PREF_MALWARE_WHITELIST, "string",
                        "\"{\\\"rules\\\": [{}, 1, {\\\"type\\\": \\\"HOST\\\"}, {\\\"type\\\": \\\"HOST\\\", \\\"value\\\": \\\"example.org\\\"}]}\"")));
        assertNotNull(backup);
        backup.apply(prefs);

        assertEquals(1, MatchList.load(context, Prefs.PREF_MALWARE_WHITELIST).getSize());
    }

    @Test
    public void testInvalidRuleValuesSkipped() {
        prefs.edit()
                .putString(Prefs.PREF_MALWARE_WHITELIST, "{\"rules\": [" +
                        "{\"type\": \"IP\", \"value\": \"1.2.3.4/33\"}, " +
                        "{\"type\": \"IP\", \"value\": \"not an ip\"}, " +
                        "{\"type\": \"COUNTRY\", \"value\": \"\"}, " +
                        "{\"type\": \"IP\", \"value\": \"1.2.3.0/24\"}, " +
                        "{\"type\": \"COUNTRY\", \"value\": \"IT\"}]}")
                .commit();

        assertEquals(2, MatchList.load(context, Prefs.PREF_MALWARE_WHITELIST).getSize());
    }

    @Test
    public void testInvalidCapturesSkipped() {
        prefs.edit().putString(Prefs.PREF_CAPTURE_LIST, "[null, {\"uri\": \"a\"}, " +
                "{\"uri\": \"b\", \"name\": \"b\", \"apps\": [null, {\"uid\": 1}, " +
                "{\"uid\": 2, \"packageName\": \"com.foo\", \"name\": \"Foo\"}]}]").commit();

        CaptureList list = new CaptureList(context);
        assertEquals(1, list.size());

        CaptureList.Capture capture = list.getCaptures().get(0);
        assertEquals("b", capture.uri);
        assertEquals(1, capture.apps.size());
        assertEquals("com.foo", capture.apps.get(0).packageName());
    }

    @Test
    public void testInvalidPortMapSkipped() {
        prefs.edit().putString(Prefs.PREF_PORT_MAPPING, "[null, " +
                "{\"ipproto\": 1, \"orig_port\": 80, \"redirect_port\": 8080, \"redirect_ip\": \"1.2.3.4\"}, " +
                "{\"ipproto\": 6, \"orig_port\": 0, \"redirect_port\": 8080, \"redirect_ip\": \"1.2.3.4\"}, " +
                "{\"ipproto\": 17, \"orig_port\": 80, \"redirect_port\": 65536, \"redirect_ip\": \"1.2.3.4\"}, " +
                "{\"ipproto\": 6, \"orig_port\": 80, \"redirect_port\": 8080}, " +
                "{\"ipproto\": 6, \"orig_port\": 80, \"redirect_port\": 8080, \"redirect_ip\": \"bad host!\"}, " +
                "{\"ipproto\": 6, \"orig_port\": 80, \"redirect_port\": 65535, \"redirect_ip\": \"1.2.3.4\"}]").commit();

        Iterator<PortMapping.PortMap> it = new PortMapping(context).iter();
        assertTrue(it.hasNext());
        assertEquals(65535, it.next().redirect_port);
        assertFalse(it.hasNext());
    }

    @Test
    public void testInvalidAllowlistRuleSkipped() {
        // the APP rule requires an installed package
        String pkg = context.getPackageName();
        prefs.edit().putString(Prefs.PREF_BLOCKLIST, "{\"rules\": [{\"type\": \"APP\", \"value\": \"" + pkg + "\", " +
                "\"allowlist\": [1, {\"type\": \"HOST\", \"value\": \"example.org\"}]}]}").commit();

        Blocklist blocklist = Blocklist.load(context);
        assertEquals(1, blocklist.getAppAllowlist(pkg).getSize());
    }

    @Test
    public void testInvalidListsLoadedEmpty() {
        prefs.edit()
                .putString(Prefs.PREF_PORT_MAPPING, "[null]")
                .putString(Prefs.PREF_CAPTURE_LIST, "[null]")
                .putString(CtrlPermissions.PREF_NAME, "{\"rules\": []}")
                .putString(Prefs.PREF_FIREWALL_WHITELIST, "{not json")
                .commit();

        assertFalse(new PortMapping(context).iter().hasNext());
        assertEquals(0, new CaptureList(context).size());
        assertFalse(new CtrlPermissions(context).hasRules());
        assertEquals(0, MatchList.load(context, Prefs.PREF_FIREWALL_WHITELIST).getSize());
    }

    // a preference missing from the schema would be dropped on import
    @Test
    public void testSchemaCoversXmlPrefs() {
        Pattern prefPattern = Pattern.compile("<(SwitchPreference|EditTextPreference|DropDownPreference|ListPreference|CheckBoxPreference)\\b[^>]*?:key=\"([^\"]+)\"");
        File[] files = new File("src/main/res/xml").listFiles((dir, name) -> name.endsWith("_preferences.xml"));
        assertNotNull(files);
        assertTrue(files.length > 0);

        int numKeys = 0;
        for (File f: files) {
            String xml = readFile(f);
            Matcher m = prefPattern.matcher(xml);

            while (m.find()) {
                assertTrue(f.getName() + ": " + m.group(2), PrefsSchema.isKnown(m.group(2)));
                numKeys++;
            }
        }

        assertTrue(numKeys > 0);
    }

    private static String readFile(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
