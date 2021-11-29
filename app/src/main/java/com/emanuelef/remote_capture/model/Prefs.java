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

import android.content.Context;
import android.content.SharedPreferences;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.Utils;

public class Prefs {
    public static final String DUMP_HTTP_SERVER = "http_server";
    public static final String DUMP_UDP_EXPORTER = "udp_exporter";
    public static final String DUMP_PCAP_FILE = "pcap_file";
    public static final String PREF_COLLECTOR_IP_KEY = "collector_ip_address";
    public static final String PREF_COLLECTOR_PORT_KEY = "collector_port";
    public static final String PREF_SOCKS5_PROXY_IP_KEY = "socks5_proxy_ip_address";
    public static final String PREF_SOCKS5_PROXY_PORT_KEY = "socks5_proxy_port";
    public static final String PREF_CAPTURE_INTERFACE = "capture_interface";
    public static final String PREF_MALWARE_DETECTION = "malware_detection";
    public static final String PREF_TLS_DECRYPTION_ENABLED_KEY = "tls_decryption_enabled";
    public static final String PREF_APP_FILTER = "app_filter";
    public static final String PREF_HTTP_SERVER_PORT = "http_server_port";
    public static final String PREF_PCAP_DUMP_MODE = "pcap_dump_mode";
    public static final String PREF_PCAP_URI = "pcap_uri";
    public static final String DEFAULT_DUMP_MODE = DUMP_HTTP_SERVER;
    public static final String PREF_IPV6_ENABLED = "ipv6_enabled";
    public static final String PREF_APP_LANGUAGE = "app_language";
    public static final String PREF_APP_THEME = "app_theme";
    public static final String PREF_ROOT_CAPTURE = "root_capture";
    public static final String PREF_VISUALIZATION_MASK = "vis_mask";
    public static final String PREF_MALWARE_WHITELIST = "maware_whitelist";
    public static final String PREF_PCAPDROID_TRAILER = "pcapdroid_trailer";
    public static final String PREF_BLOCKLIST = "blocklist";
    public static final String PREF_START_AT_BOOT = "start_at_boot";

    public enum DumpMode {
        NONE,
        HTTP_SERVER,
        PCAP_FILE,
        UDP_EXPORTER
    }

    public static DumpMode getDumpMode(String pref) {
        if(pref.equals(DUMP_HTTP_SERVER))
            return(DumpMode.HTTP_SERVER);
        else if(pref.equals(DUMP_PCAP_FILE))
            return(DumpMode.PCAP_FILE);
        else if(pref.equals(DUMP_UDP_EXPORTER))
            return(DumpMode.UDP_EXPORTER);
        else
            return(DumpMode.NONE);
    }

    /* Prefs with defaults */
    public static String getCollectorIp(SharedPreferences p) { return(p.getString(PREF_COLLECTOR_IP_KEY, "127.0.0.1")); }
    public static int getCollectorPort(SharedPreferences p)  { return(Integer.parseInt(p.getString(PREF_COLLECTOR_PORT_KEY, "1234"))); }
    public static DumpMode getDumpMode(SharedPreferences p)  { return(getDumpMode(p.getString(PREF_PCAP_DUMP_MODE, DEFAULT_DUMP_MODE))); }
    public static int getHttpServerPort(SharedPreferences p) { return(Integer.parseInt(p.getString(Prefs.PREF_HTTP_SERVER_PORT, "8080"))); }
    public static boolean getTlsDecryptionEnabled(SharedPreferences p) { return(p.getBoolean(PREF_TLS_DECRYPTION_ENABLED_KEY, false)); }
    public static String getSocks5ProxyAddress(SharedPreferences p) { return(p.getString(PREF_SOCKS5_PROXY_IP_KEY, "0.0.0.0")); }
    public static int getSocks5ProxyPort(SharedPreferences p)       { return(Integer.parseInt(p.getString(Prefs.PREF_SOCKS5_PROXY_PORT_KEY, "8080"))); }
    public static String getAppFilter(SharedPreferences p)       { return(p.getString(PREF_APP_FILTER, "")); }
    public static boolean getIPv6Enabled(SharedPreferences p)    { return(p.getBoolean(PREF_IPV6_ENABLED, false)); }
    public static boolean useEnglishLanguage(SharedPreferences p){ return("english".equals(p.getString(PREF_APP_LANGUAGE, "system")));}
    public static boolean isRootCaptureEnabled(SharedPreferences p) { return(Utils.isRootAvailable() && p.getBoolean(PREF_ROOT_CAPTURE, false)); }
    public static boolean isPcapdroidTrailerEnabled(SharedPreferences p) { return(p.getBoolean(PREF_PCAPDROID_TRAILER, false)); }
    public static String getCaptureInterface(SharedPreferences p) { return(p.getString(PREF_CAPTURE_INTERFACE, "@inet")); }
    public static boolean isMalwareDetectionEnabled(Context ctx, SharedPreferences p) {
        return(Billing.newInstance(ctx).isPurchased(Billing.MALWARE_DETECTION_SKU)
                && p.getBoolean(PREF_MALWARE_DETECTION, false));
    }
    public static boolean startAtBoot(SharedPreferences p)        { return(p.getBoolean(PREF_START_AT_BOOT, false)); }
    public static String getPCAPUri(SharedPreferences p)          { return(p.getString(PREF_PCAP_URI, "")); }
}
