package com.emanuelef.remote_capture;

public class Prefs {
    static final String DUMP_HTTP_SERVER = "http_server";
    static final String DUMP_UDP_EXPORTER = "udp_exporter";
    static final String PREF_COLLECTOR_IP_KEY = "collector_ip_address";
    static final String PREF_COLLECTOR_PORT_KEY = "collector_port";
    static final String PREF_UID_FILTER = "uid_filter";
    static final String PREF_CAPTURE_UNKNOWN_APP_TRAFFIC = "capture_unknown_app";
    static final String PREF_HTTP_SERVER_PORT = "http_server_port";

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
}
