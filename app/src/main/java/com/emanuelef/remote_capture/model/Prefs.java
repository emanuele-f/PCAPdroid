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

import android.content.SharedPreferences;

import com.emanuelef.remote_capture.Utils;

public class Prefs {
    public static final String DUMP_HTTP_SERVER = "http_server";
    public static final String DUMP_UDP_EXPORTER = "udp_exporter";
    public static final String DUMP_PCAP_FILE = "pcap_file";
    public static final String PREF_COLLECTOR_IP_KEY = "collector_ip_address";
    public static final String PREF_COLLECTOR_PORT_KEY = "collector_port";
    public static final String PREF_SOCKS5_PROXY_IP_KEY = "socks5_proxy_ip_address";
    public static final String PREF_SOCKS5_PROXY_PORT_KEY = "socks5_proxy_port";
    public static final String PREF_TLS_DECRYPTION_ENABLED_KEY = "tls_decryption_enabled";
    public static final String PREF_APP_FILTER = "app_filter";
    public static final String PREF_HTTP_SERVER_PORT = "http_server_port";
    public static final String PREF_PCAP_DUMP_MODE = "pcap_dump_mode";
    public static final String PREF_PCAP_URI = "pcap_path";
    public static final String DEFAULT_DUMP_MODE = DUMP_HTTP_SERVER;
    public static final String PREF_IPV6_ENABLED = "ipv6_enabled";
    public static final String PREF_APP_LANGUAGE = "app_language";
    public static final String PREF_APP_THEME = "app_theme";

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
}
