package com.emanuelef.remote_capture;

import android.content.SharedPreferences;

public class Prefs {
    static final String DUMP_HTTP_SERVER = "http_server";
    static final String DUMP_UDP_EXPORTER = "udp_exporter";
    static final String PREF_COLLECTOR_IP_KEY = "collector_ip_address";
    static final String PREF_COLLECTOR_PORT_KEY = "collector_port";
    static final String PREF_UID_FILTER = "uid_filter";
    static final String PREF_CAPTURE_UNKNOWN_APP_TRAFFIC = "capture_unknown_app";
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
    static boolean getCaptureUnknownAppTraffic(SharedPreferences p) { return(p.getBoolean(PREF_CAPTURE_UNKNOWN_APP_TRAFFIC, true)); }
    static DumpMode getDumpMode(SharedPreferences p)  { return(getDumpMode(p.getString(PREF_PCAP_DUMP_MODE, DUMP_HTTP_SERVER))); }
    static int getHttpServerPort(SharedPreferences p) { return(Integer.parseInt(p.getString(Prefs.PREF_HTTP_SERVER_PORT, "8080"))); }
}
