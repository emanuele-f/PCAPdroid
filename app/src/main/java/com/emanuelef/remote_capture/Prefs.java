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
 * Copyright 2020 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.content.SharedPreferences;

public class Prefs {
    static final String DUMP_HTTP_SERVER = "http_server";
    static final String DUMP_UDP_EXPORTER = "udp_exporter";
    static final String PREF_COLLECTOR_IP_KEY = "collector_ip_address";
    static final String PREF_COLLECTOR_PORT_KEY = "collector_port";
    static final String PREF_TLS_PROXY_IP_KEY = "tls_proxy_ip_address";
    static final String PREF_TLS_PROXY_PORT_KEY = "tls_proxy_port";
    static final String PREF_TLS_DECRYPTION_ENABLED_KEY = "tls_decryption_enabled";
    static final String PREF_APP_FILTER = "app_filter";
    static final String PREF_HTTP_SERVER_PORT = "http_server_port";
    static final String PREF_PCAP_DUMP_MODE = "pcap_dump_mode";

    enum DumpMode {
        NONE,
        HTTP_SERVER,
        UDP_EXPORTER
    }

    static DumpMode getDumpMode(String pref) {
        if(pref.equals(DUMP_HTTP_SERVER))
            return(DumpMode.HTTP_SERVER);
        else if(pref.equals(DUMP_UDP_EXPORTER))
            return(DumpMode.UDP_EXPORTER);
        else
            return(DumpMode.NONE);
    }

    /* Prefs with defaults */
    static String getCollectorIp(SharedPreferences p) { return(p.getString(PREF_COLLECTOR_IP_KEY, "127.0.0.1")); }
    static int getCollectorPort(SharedPreferences p)  { return(Integer.parseInt(p.getString(PREF_COLLECTOR_PORT_KEY, "1234"))); }
    static DumpMode getDumpMode(SharedPreferences p)  { return(getDumpMode(p.getString(PREF_PCAP_DUMP_MODE, DUMP_HTTP_SERVER))); }
    static int getHttpServerPort(SharedPreferences p) { return(Integer.parseInt(p.getString(Prefs.PREF_HTTP_SERVER_PORT, "8080"))); }
    static boolean getTlsDecryptionEnabled(SharedPreferences p) { return(p.getBoolean(PREF_TLS_DECRYPTION_ENABLED_KEY, false)); }
    static String getTlsProxyAddress(SharedPreferences p) { return(p.getString(PREF_TLS_PROXY_IP_KEY, "0.0.0.0")); }
    static int getTlsProxyPort(SharedPreferences p)       { return(Integer.parseInt(p.getString(Prefs.PREF_TLS_PROXY_PORT_KEY, "8080"))); }
}
