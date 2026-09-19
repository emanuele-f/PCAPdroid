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

package com.emanuelef.remote_capture.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.Blacklists;
import com.emanuelef.remote_capture.PersistableUriPermission;
import com.emanuelef.remote_capture.Utils;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/* The type of each preference of the app, both from the preferences XML files and set from the
 * code. This is used to reject invalid imported preferences values, which may crash the app */
public class PrefsSchema {
    private static final ArrayMap<String, Spec> SCHEMA = new ArrayMap<>();

    private enum Type {
        BOOLEAN,
        INT,
        STRING,
        STRING_SET
    }

    public interface StringValidator {
        boolean isValid(String value);
    }

    private static class Spec {
        final Type type;
        final @Nullable StringValidator validator;
        boolean backup = true;
        boolean merge = false;

        Spec(Type type, @Nullable StringValidator validator) {
            this.type = type;
            this.validator = validator;
        }

        void noBackup() {
            backup = false;
        }

        void mergeOnRestore() {
            merge = true;
        }
    }

    static {
        // Traffic inspection
        bool(Prefs.PREF_AUTO_BLOCK_PRIVATE_DNS);
        bool(Prefs.PREF_TLS_DECRYPTION_KEY);
        bool(Prefs.PREF_FULL_PAYLOAD);
        string(Prefs.PREF_MITMPROXY_OPTS);
        string(Prefs.PREF_BLOCK_QUIC, oneOf(
                Prefs.BLOCK_QUIC_MODE_NEVER,
                Prefs.BLOCK_QUIC_MODE_ALWAYS,
                Prefs.BLOCK_QUIC_MODE_TO_DECRYPT));
        string(Prefs.PREF_CONNECTIONS_LOG_SIZE, PrefsSchema::isPositiveInt);
        bool(Prefs.PREF_TLS_DECRYPTION_SETUP_DONE).noBackup();
        bool(Prefs.PREF_CA_INSTALLATION_SKIPPED).noBackup();
        string(Prefs.PREF_IGNORED_MITM_VERSION).noBackup();
        string(Prefs.PREF_DECRYPTION_LIST);

        // Dump
        string(Prefs.PREF_PCAP_DUMP_MODE, oneOf(
                Prefs.DUMP_NONE, Prefs.DUMP_HTTP_SERVER,
                Prefs.DUMP_UDP_EXPORTER, Prefs.DUMP_TCP_EXPORTER, Prefs.DUMP_PCAP_FILE));
        string(Prefs.PREF_HTTP_SERVER_PORT, Utils::validatePort);
        string(Prefs.PREF_COLLECTOR_HOST_KEY, Utils::validateHostOrIp);
        string(Prefs.PREF_COLLECTOR_PORT_KEY, Utils::validatePort);
        bool(Prefs.PREF_PCAPNG_ENABLED);
        bool(Prefs.PREF_DUMP_EXTENSIONS);
        string(Prefs.PREF_FILENAME_PREFIX);
        bool(Prefs.PREF_REMOTE_COLLECTOR_ACK).noBackup();
        string(Prefs.PREF_CAPTURE_LIST).mergeOnRestore();

        // Capture
        bool(Prefs.PREF_ROOT_CAPTURE);
        string(Prefs.PREF_CAPTURE_INTERFACE, PrefsSchema::isNotEmpty);
        string(Prefs.PREF_IP_MODE, oneOf(
                Prefs.IP_MODE_IPV4_ONLY,
                Prefs.IP_MODE_IPV6_ONLY,
                Prefs.IP_MODE_BOTH));
        bool(Prefs.PREF_START_AT_BOOT);
        bool(Prefs.PREF_RESTART_ON_DISCONNECT);
        stringSet(Prefs.PREF_APP_FILTER);
        bool(Prefs.PREF_APP_FILTER_ENABLED);
        stringSet(Prefs.PREF_VPN_EXCEPTIONS);
        bool(Prefs.PREF_LOCKDOWN_VPN_NOTICE_SHOWN).noBackup();
        bool(Prefs.PREF_LOCAL_NETWORK_NOTICE_SHOWN).noBackup();
        bool(Prefs.PREF_PAYLOAD_NOTICE_ACK).noBackup();
        string(Prefs.PREF_VISUALIZATION_MASK);

        // DNS
        bool(Prefs.PREF_USE_SYSTEM_DNS);
        string(Prefs.PREF_DNS_SERVER_V4, Utils::validateIpv4Address);
        string(Prefs.PREF_DNS_SERVER_V6, value -> !value.equals("::") && Utils.validateIpv6Address(value));

        // SOCKS5
        bool(Prefs.PREF_SOCKS5_ENABLED_KEY);
        string(Prefs.PREF_SOCKS5_PROXY_IP_KEY, Utils::validateHost);
        string(Prefs.PREF_SOCKS5_PROXY_PORT_KEY, Utils::validatePort);
        bool(Prefs.PREF_SOCKS5_AUTH_ENABLED_KEY);
        string(Prefs.PREF_SOCKS5_USERNAME_KEY);
        string(Prefs.PREF_SOCKS5_PASSWORD_KEY);

        // Port mapping
        string(Prefs.PREF_PORT_MAPPING);
        bool(Prefs.PREF_PORT_MAPPING_ENABLED);
        stringSet(Prefs.PREF_PORT_MAPPING_EXEMPTIONS);

        // Security
        bool(Prefs.PREF_MALWARE_DETECTION);
        string(Prefs.PREF_MALWARE_WHITELIST);
        bool(Prefs.PREF_FIREWALL);
        bool(Prefs.PREF_BLOCK_NEW_APPS);
        bool(Prefs.PREF_FIREWALL_WHITELIST_MODE);
        integer(Prefs.PREF_FIREWALL_WHITELIST_INIT_VER);
        string(Prefs.PREF_FIREWALL_WHITELIST);
        string(Prefs.PREF_BLOCKLIST);
        string(Blacklists.PREF_BLACKLISTS_STATUS).noBackup();

        // Other
        string(Prefs.PREF_APP_LANGUAGE, PrefsSchema::isNotEmpty);
        integer(Prefs.PREF_APP_VERSION).noBackup();
        string(Prefs.PREF_API_KEY);
        string(CtrlPermissions.PREF_NAME);
        string(PersistableUriPermission.PREF_KEY).noBackup();
        string(SettingsBackup.LICENSE_KEY);

        /* Billing state: the play build re-validates the purchases via the billing library, while
         * the peer skus are advertised by the mitm addon at runtime */
        stringSet(Billing.PEER_SKU_KEY).noBackup();

        // only written by the play build
        string(Prefs.PREF_AVAILABLE_SKUS).noBackup();
        string(Prefs.PREF_UNLOCK_TOKEN).noBackup();
    }

    private static Spec register(String key, Spec spec) {
        SCHEMA.put(key, spec);
        return spec;
    }

    private static Spec bool(String key) {
        return register(key, new Spec(Type.BOOLEAN, null));
    }

    private static Spec integer(String key) {
        return register(key, new Spec(Type.INT, null));
    }

    private static Spec string(String key) {
        return register(key, new Spec(Type.STRING, null));
    }

    private static Spec string(String key, StringValidator validator) {
        return register(key, new Spec(Type.STRING, validator));
    }

    private static Spec stringSet(String key) {
        return register(key, new Spec(Type.STRING_SET, null));
    }

    public static boolean isKnown(String key) {
        return SCHEMA.containsKey(key);
    }

    // prefs which must not be exported, nor restored from a settings backup
    public static boolean isExcludedFromBackup(String key) {
        // dynamic keys, not in the schema
        if (key.startsWith(Billing.SKU_PREF_PREFIX))
            return true;

        Spec spec = SCHEMA.get(key);
        return (spec != null) && !spec.backup;
    }

    // prefs which must be merged with the current value when restoring a settings backup
    public static boolean requiresBackupMerge(String key) {
        Spec spec = SCHEMA.get(key);
        return (spec != null) && spec.merge;
    }

    /* Returns null if the value can be stored into the given preference, otherwise the reason why
     * it cannot be stored */
    public static @Nullable String validate(String key, @NonNull Object value) {
        Spec spec = SCHEMA.get(key);
        if (spec == null)
            return "unknown preference";

        if (!hasType(value, spec.type))
            return "expected type " + spec.type + ", got " + value.getClass().getSimpleName();

        if ((spec.validator != null) && !spec.validator.isValid((String) value))
            return "invalid value";

        return null;
    }

    private static boolean hasType(Object value, Type type) {
        switch (type) {
            case BOOLEAN:       return (value instanceof Boolean);
            case INT:           return (value instanceof Integer);
            case STRING:        return (value instanceof String);
            case STRING_SET:    return (value instanceof Set);
        }

        return false;
    }

    private static StringValidator oneOf(String... allowed) {
        List<String> values = Arrays.asList(allowed);
        return values::contains;
    }

    private static boolean isNotEmpty(String value) {
        return !value.isEmpty();
    }

    private static boolean isPositiveInt(String value) {
        try {
            return (Integer.parseInt(value) > 0);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
