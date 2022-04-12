package com.emanuelef.remote_capture.model;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

import java.io.Serializable;

public class CaptureSettings implements Serializable {
    public Prefs.DumpMode dump_mode;
    public String app_filter;
    public String collector_address;
    public int collector_port;
    public int http_server_port;
    public boolean socks5_enabled;
    public boolean tls_decryption;
    public String socks5_proxy_address;
    public int socks5_proxy_port;
    public boolean ipv6_enabled;
    public boolean root_capture;
    public boolean pcapdroid_trailer;
    public boolean full_payload;
    public String capture_interface;
    public String pcap_uri;
    public int snaplen = 0;
    public int max_pkts_per_flow = 0;
    public int max_dump_size = 0;

    public CaptureSettings(SharedPreferences prefs) {
        dump_mode = Prefs.getDumpMode(prefs);
        app_filter = Prefs.getAppFilter(prefs);
        collector_address = Prefs.getCollectorIp(prefs);
        collector_port = Prefs.getCollectorPort(prefs);
        http_server_port = Prefs.getHttpServerPort(prefs);
        socks5_enabled = Prefs.getSocks5Enabled(prefs);
        socks5_proxy_address = Prefs.getSocks5ProxyAddress(prefs);
        socks5_proxy_port = Prefs.getSocks5ProxyPort(prefs);
        ipv6_enabled = Prefs.getIPv6Enabled(prefs);
        root_capture = Prefs.isRootCaptureEnabled(prefs);
        pcapdroid_trailer = Prefs.isPcapdroidTrailerEnabled(prefs);
        capture_interface = Prefs.getCaptureInterface(prefs);
        pcap_uri = Prefs.getPCAPUri(prefs);
        tls_decryption = Prefs.getTlsDecryptionEnabled(prefs);
        full_payload = Prefs.getFullPayloadMode(prefs);
    }

    public CaptureSettings(Intent intent) {
        dump_mode = Prefs.getDumpMode(getString(intent, "pcap_dump_mode", "none"));
        app_filter = getString(intent, Prefs.PREF_APP_FILTER, "");
        collector_address = getString(intent, Prefs.PREF_COLLECTOR_IP_KEY, "127.0.0.1");
        collector_port = getInt(intent, Prefs.PREF_COLLECTOR_PORT_KEY, 1234);
        http_server_port = getInt(intent, Prefs.PREF_HTTP_SERVER_PORT, 8080);
        socks5_enabled = getBool(intent, Prefs.PREF_SOCKS5_ENABLED_KEY, false);
        socks5_proxy_address = getString(intent, Prefs.PREF_SOCKS5_PROXY_IP_KEY, "0.0.0.0");
        socks5_proxy_port = getInt(intent, Prefs.PREF_SOCKS5_PROXY_PORT_KEY, 8080);
        ipv6_enabled = getBool(intent, Prefs.PREF_IPV6_ENABLED, false);
        root_capture = getBool(intent, Prefs.PREF_ROOT_CAPTURE, false);
        pcapdroid_trailer = getBool(intent, Prefs.PREF_PCAPDROID_TRAILER, false);
        capture_interface = getString(intent, Prefs.PREF_CAPTURE_INTERFACE, "@inet");
        pcap_uri = getString(intent, Prefs.PREF_PCAP_URI, "");
        snaplen = getInt(intent, Prefs.PREF_SNAPLEN, 0);
        max_pkts_per_flow = getInt(intent, Prefs.PREF_MAX_PKTS_PER_FLOW, 0);
        max_dump_size = getInt(intent, Prefs.PREF_MAX_DUMP_SIZE, 0);
        tls_decryption = getBool(intent, Prefs.PREF_TLS_DECRYPTION_KEY, false);
        full_payload = false;
    }

    private static String getString(Intent intent, String key, String def_value) {
        String val = intent.getStringExtra(key);
        return (val != null) ? val : def_value;
    }

    // get a integer value from the bundle. The value may be represented as an int or as a string.
    private static int getInt(Intent intent, String key, int def_value) {
        Bundle bundle = intent.getExtras();
        Object o = bundle.get(key);

        if(o != null)
            return Integer.parseInt(o.toString());
        return def_value;
    }

    // get a boolean value from the bundle. The value may be represented as a bool or as a string.
    private static boolean getBool(Intent intent, String key, boolean def_value) {
        Bundle bundle = intent.getExtras();
        Object o = bundle.get(key);

        if(o != null)
            return Boolean.parseBoolean(o.toString());
        return def_value;
    }
}
