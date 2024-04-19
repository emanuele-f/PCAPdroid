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

package com.emanuelef.remote_capture.model;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.BuildConfig;
import com.emanuelef.remote_capture.MitmAddon;
import com.emanuelef.remote_capture.Utils;

import java.util.HashSet;
import java.util.Set;

public class Prefs {
    public static final String DUMP_NONE = "none";
    public static final String DUMP_HTTP_SERVER = "http_server";
    public static final String DUMP_UDP_EXPORTER = "udp_exporter";
    public static final String DUMP_PCAP_FILE = "pcap_file";
    public static final String DEFAULT_DUMP_MODE = DUMP_NONE;

    public static final String IP_MODE_IPV4_ONLY = "ipv4";
    public static final String IP_MODE_IPV6_ONLY = "ipv6";
    public static final String IP_MODE_BOTH = "both";
    public static final String IP_MODE_DEFAULT = IP_MODE_IPV4_ONLY;

    public static final String BLOCK_QUIC_MODE_NEVER = "never";
    public static final String BLOCK_QUIC_MODE_ALWAYS = "always";
    public static final String BLOCK_QUIC_MODE_TO_DECRYPT = "to_decrypt";
    public static final String BLOCK_QUIC_MODE_DEFAULT = BLOCK_QUIC_MODE_NEVER;

    public static final String PAYLOAD_MODE_NONE = "none";
    public static final String PAYLOAD_MODE_MINIMAL = "minimal";
    public static final String PAYLOAD_MODE_FULL = "full";
    public static final String DEFAULT_PAYLOAD_MODE = PAYLOAD_MODE_MINIMAL;

    // used to initialize the whitelist with some safe defaults
    public static final int FIREWALL_WHITELIST_INIT_VER = 1;

    public static final String PREF_COLLECTOR_IP_KEY = "collector_ip_address";
    public static final String PREF_COLLECTOR_PORT_KEY = "collector_port";
    public static final String PREF_SOCKS5_PROXY_IP_KEY = "socks5_proxy_ip_address";
    public static final String PREF_SOCKS5_PROXY_PORT_KEY = "socks5_proxy_port";
    public static final String PREF_CAPTURE_INTERFACE = "capture_interface";
    public static final String PREF_MALWARE_DETECTION = "malware_detection";
    public static final String PREF_FIREWALL = "firewall";
    public static final String PREF_TLS_DECRYPTION_KEY = "tls_decryption";
    public static final String PREF_APP_FILTER = "app_filter";
    public static final String PREF_HTTP_SERVER_PORT = "http_server_port";
    public static final String PREF_PCAP_DUMP_MODE = "pcap_dump_mode_v2";
    public static final String PREF_IP_MODE = "ip_mode";
    public static final String PREF_APP_LANGUAGE = "app_language";
    public static final String PREF_APP_THEME = "app_theme";
    public static final String PREF_ROOT_CAPTURE = "root_capture";
    public static final String PREF_VISUALIZATION_MASK = "vis_mask";
    public static final String PREF_MALWARE_WHITELIST = "malware_whitelist";
    public static final String PREF_PCAPDROID_TRAILER = "pcapdroid_trailer";
    public static final String PREF_BLOCKLIST = "bl";
    public static final String PREF_FIREWALL_WHITELIST_MODE = "firewall_wl_mode";
    public static final String PREF_FIREWALL_WHITELIST_INIT_VER = "firewall_wl_init";
    public static final String PREF_FIREWALL_WHITELIST = "firewall_whitelist";
    public static final String PREF_DECRYPTION_LIST = "decryption_list";
    public static final String PREF_START_AT_BOOT = "start_at_boot";
    public static final String PREF_SNAPLEN = "snaplen";
    public static final String PREF_MAX_PKTS_PER_FLOW = "max_pkts_per_flow";
    public static final String PREF_MAX_DUMP_SIZE = "max_dump_size";
    public static final String PREF_SOCKS5_ENABLED_KEY = "socks5_enabled";
    public static final String PREF_SOCKS5_AUTH_ENABLED_KEY = "socks5_auth_enabled";
    public static final String PREF_SOCKS5_USERNAME_KEY = "socks5_username";
    public static final String PREF_SOCKS5_PASSWORD_KEY = "socks5_password";
    public static final String PREF_TLS_DECRYPTION_SETUP_DONE = "tls_decryption_setup_ok";
    public static final String PREF_CA_INSTALLATION_SKIPPED = "ca_install_skipped";
    public static final String PREF_FULL_PAYLOAD = "full_payload";
    public static final String PREF_BLOCK_QUIC = "block_quic_mode";
    public static final String PREF_AUTO_BLOCK_PRIVATE_DNS = "auto_block_private_dns";
    public static final String PREF_APP_VERSION = "appver";
    public static final String PREF_LOCKDOWN_VPN_NOTICE_SHOWN = "vpn_lockdown_notice";
    public static final String PREF_VPN_EXCEPTIONS = "vpn_exceptions";
    public static final String PREF_PORT_MAPPING = "port_mapping";
    public static final String PREF_PORT_MAPPING_ENABLED = "port_mapping_enabled";
    public static final String PREF_BLOCK_NEW_APPS = "block_new_apps";
    public static final String PREF_PAYLOAD_NOTICE_ACK = "payload_notice";
    public static final String PREF_REMOTE_COLLECTOR_ACK = "remote_collector_notice";
    public static final String PREF_MITMPROXY_OPTS = "mitmproxy_opts";
    public static final String PREF_DNS_SERVER_V4 = "dns_v4";
    public static final String PREF_DNS_SERVER_V6 = "dns_v6";
    public static final String PREF_USE_SYSTEM_DNS = "system_dns";
    public static final String PREF_PCAPNG_ENABLED = "pcapng_format";
    public static final String PREF_RESTART_ON_DISCONNECT = "restart_on_disconnect";

    public enum DumpMode {
        NONE,
        HTTP_SERVER,
        PCAP_FILE,
        UDP_EXPORTER
    }

    public enum IpMode {
        IPV4_ONLY,
        IPV6_ONLY,
        BOTH,
    }

    public enum BlockQuicMode {
        NEVER,
        ALWAYS,
        TO_DECRYPT
    }

    public enum PayloadMode {
        NONE,
        MINIMAL,
        FULL
    }

    public static DumpMode getDumpMode(String pref) {
        switch (pref) {
            case DUMP_HTTP_SERVER:      return DumpMode.HTTP_SERVER;
            case DUMP_PCAP_FILE:        return DumpMode.PCAP_FILE;
            case DUMP_UDP_EXPORTER:     return DumpMode.UDP_EXPORTER;
            default:                    return DumpMode.NONE;
        }
    }

    public static IpMode getIPMode(String pref) {
        switch (pref) {
            case IP_MODE_IPV6_ONLY:     return IpMode.IPV6_ONLY;
            case IP_MODE_BOTH:          return IpMode.BOTH;
            default:                    return IpMode.IPV4_ONLY;
        }
    }

    public static BlockQuicMode getBlockQuicMode(String pref) {
        switch (pref) {
            case BLOCK_QUIC_MODE_ALWAYS:        return BlockQuicMode.ALWAYS;
            case BLOCK_QUIC_MODE_TO_DECRYPT:    return BlockQuicMode.TO_DECRYPT;
            default:                            return BlockQuicMode.NEVER;
        }
    }

    public static PayloadMode getPayloadMode(String pref) {
        switch (pref) {
            case PAYLOAD_MODE_MINIMAL:  return PayloadMode.MINIMAL;
            case PAYLOAD_MODE_FULL:     return PayloadMode.FULL;
            default:                    return PayloadMode.NONE;
        }
    }

    public static int getAppVersion(SharedPreferences p) {
        return p.getInt(PREF_APP_VERSION, 0);
    }

    public static void refreshAppVersion(SharedPreferences p) {
        p.edit().putInt(PREF_APP_VERSION, BuildConfig.VERSION_CODE).apply();
    }

    public static void setLockdownVpnNoticeShown(SharedPreferences p) {
        p.edit().putBoolean(PREF_LOCKDOWN_VPN_NOTICE_SHOWN, true).apply();
    }

    public static void setFirewallWhitelistInitialized(SharedPreferences p) {
        p.edit().putInt(PREF_FIREWALL_WHITELIST_INIT_VER, FIREWALL_WHITELIST_INIT_VER).apply();
    }

    public static void setPortMappingEnabled(SharedPreferences p, boolean enabled) {
        p.edit().putBoolean(PREF_PORT_MAPPING_ENABLED, enabled).apply();
    }

    /* Prefs with defaults */
    public static String getCollectorIp(SharedPreferences p) { return(p.getString(PREF_COLLECTOR_IP_KEY, "127.0.0.1")); }
    public static int getCollectorPort(SharedPreferences p)  { return(Integer.parseInt(p.getString(PREF_COLLECTOR_PORT_KEY, "1234"))); }
    public static DumpMode getDumpMode(SharedPreferences p)  { return(getDumpMode(p.getString(PREF_PCAP_DUMP_MODE, DEFAULT_DUMP_MODE))); }
    public static int getHttpServerPort(SharedPreferences p) { return(Integer.parseInt(p.getString(Prefs.PREF_HTTP_SERVER_PORT, "8080"))); }
    public static boolean getTlsDecryptionEnabled(SharedPreferences p) { return(p.getBoolean(PREF_TLS_DECRYPTION_KEY, false)); }
    public static boolean getSocks5Enabled(SharedPreferences p)     { return(p.getBoolean(PREF_SOCKS5_ENABLED_KEY, false)); }
    public static String getSocks5ProxyHost(SharedPreferences p)    { return(p.getString(PREF_SOCKS5_PROXY_IP_KEY, "0.0.0.0")); }
    public static int getSocks5ProxyPort(SharedPreferences p)       { return(Integer.parseInt(p.getString(Prefs.PREF_SOCKS5_PROXY_PORT_KEY, "8080"))); }
    public static boolean isSocks5AuthEnabled(SharedPreferences p)  { return(p.getBoolean(PREF_SOCKS5_AUTH_ENABLED_KEY, false)); }
    public static String getSocks5Username(SharedPreferences p)     { return(p.getString(PREF_SOCKS5_USERNAME_KEY, "")); }
    public static String getSocks5Password(SharedPreferences p)     { return(p.getString(PREF_SOCKS5_PASSWORD_KEY, "")); }
    public static Set<String> getAppFilter(SharedPreferences p)     { return(getStringSet(p, PREF_APP_FILTER)); }
    public static IpMode getIPMode(SharedPreferences p)          { return(getIPMode(p.getString(PREF_IP_MODE, IP_MODE_DEFAULT))); }
    public static BlockQuicMode getBlockQuicMode(SharedPreferences p) { return(getBlockQuicMode(p.getString(PREF_BLOCK_QUIC, BLOCK_QUIC_MODE_DEFAULT))); }
    public static boolean useEnglishLanguage(SharedPreferences p){ return("english".equals(p.getString(PREF_APP_LANGUAGE, "system")));}
    public static boolean isRootCaptureEnabled(SharedPreferences p) { return(Utils.isRootAvailable() && p.getBoolean(PREF_ROOT_CAPTURE, false)); }
    public static boolean isPcapdroidTrailerEnabled(SharedPreferences p) { return(p.getBoolean(PREF_PCAPDROID_TRAILER, false)); }
    public static String getCaptureInterface(SharedPreferences p) { return(p.getString(PREF_CAPTURE_INTERFACE, "@inet")); }
    public static boolean isMalwareDetectionEnabled(Context ctx, SharedPreferences p) {
        return(Billing.newInstance(ctx).isPurchased(Billing.MALWARE_DETECTION_SKU)
                && p.getBoolean(PREF_MALWARE_DETECTION, true));
    }
    public static boolean isFirewallEnabled(Context ctx, SharedPreferences p) {
        // NOTE: firewall can be disabled at runtime
        return(Billing.newInstance(ctx).isFirewallVisible()
                && p.getBoolean(PREF_FIREWALL, true));
    }
    public static boolean isPcapngEnabled(Context ctx, SharedPreferences p)  {
        return(Billing.newInstance(ctx).isPurchased(Billing.PCAPNG_SKU)
                && p.getBoolean(PREF_PCAPNG_ENABLED, true));
    }
    public static boolean startAtBoot(SharedPreferences p)        { return(p.getBoolean(PREF_START_AT_BOOT, false)); }
    public static boolean restartOnDisconnect(SharedPreferences p)        { return(p.getBoolean(PREF_RESTART_ON_DISCONNECT, false)); }
    public static boolean isTLSDecryptionSetupDone(SharedPreferences p)     { return(p.getBoolean(PREF_TLS_DECRYPTION_SETUP_DONE, false)); }
    public static boolean getFullPayloadMode(SharedPreferences p) { return(p.getBoolean(PREF_FULL_PAYLOAD, false)); }
    public static boolean isPrivateDnsBlockingEnabled(SharedPreferences p) { return(p.getBoolean(PREF_AUTO_BLOCK_PRIVATE_DNS, true)); }
    public static boolean lockdownVpnNoticeShown(SharedPreferences p)      { return(p.getBoolean(PREF_LOCKDOWN_VPN_NOTICE_SHOWN, false)); }
    public static boolean blockNewApps(SharedPreferences p)       { return(p.getBoolean(PREF_BLOCK_NEW_APPS, false)); }
    public static boolean isFirewallWhitelistMode(SharedPreferences p)     { return(p.getBoolean(PREF_FIREWALL_WHITELIST_MODE, false)); }
    public static boolean isFirewallWhitelistInitialized(SharedPreferences p) { return(p.getInt(PREF_FIREWALL_WHITELIST_INIT_VER, 0) == FIREWALL_WHITELIST_INIT_VER); }
    public static String getMitmproxyOpts(SharedPreferences p)    { return(p.getString(PREF_MITMPROXY_OPTS, "")); }
    public static boolean isPortMappingEnabled(SharedPreferences p) { return(p.getBoolean(PREF_PORT_MAPPING_ENABLED, true)); }
    public static boolean useSystemDns(SharedPreferences p)     { return(p.getBoolean(PREF_USE_SYSTEM_DNS, true)); }
    public static String getDnsServerV4(SharedPreferences p)    { return(p.getString(PREF_DNS_SERVER_V4, "1.1.1.1")); }
    public static String getDnsServerV6(SharedPreferences p)    { return(p.getString(PREF_DNS_SERVER_V6, "2606:4700:4700::1111")); }

    // Gets a StringSet from the prefs
    // The preference should either be a StringSet or a String
    // An empty set is returned as the default value
    @SuppressLint("MutatingSharedPrefs")
    public static @NonNull Set<String> getStringSet(SharedPreferences p, String key) {
        Set<String> rv = null;

        try {
            rv = p.getStringSet(key, null);
        } catch (ClassCastException e) {
            // retry with string
            String s = p.getString(key, "");

            if (!s.isEmpty()) {
                rv = new HashSet<>();
                rv.add(s);
            }
        }

        if (rv == null)
            rv = new HashSet<>();

        return rv;
    }

    public static String asString(Context ctx) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(ctx);

        // NOTE: possibly sensitive info like the collector IP address not shown
        return "DumpMode: " + getDumpMode(p) +
                "\nFullPayload: " + getFullPayloadMode(p) +
                "\nTLSDecryption: " + getTlsDecryptionEnabled(p) +
                "\nTLSSetupOk: " + isTLSDecryptionSetupDone(p) +
                "\nCAInstallSkipped: " + MitmAddon.isCAInstallationSkipped(ctx) +
                "\nBlockQuic: " + getBlockQuicMode(p) +
                "\nRootCapture: " + isRootCaptureEnabled(p) +
                "\nSocks5: " + getSocks5Enabled(p) +
                "\nBlockPrivateDns: " + isPrivateDnsBlockingEnabled(p) +
                "\nCaptureInterface: " + getCaptureInterface(p) +
                "\nMalwareDetection: " + isMalwareDetectionEnabled(ctx, p) +
                "\nFirewall: " + isFirewallEnabled(ctx, p) +
                "\nPCAPNG: " + isPcapngEnabled(ctx, p) +
                "\nBlockNewApps: " + blockNewApps(p) +
                "\nTargetApps: " + getAppFilter(p) +
                "\nIpMode: " + getIPMode(p) +
                "\nTrailer: " + isPcapdroidTrailerEnabled(p) +
                "\nStartAtBoot: " + startAtBoot(p);
    }
}
