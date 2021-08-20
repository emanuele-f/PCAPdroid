package com.emanuelef.remote_capture.model;

import android.content.Intent;
import android.content.SharedPreferences;

import java.io.Serializable;

public class CaptureSettings implements Serializable {
    public final Prefs.DumpMode dump_mode;
    public final String app_filter;
    public final String collector_address;
    public final int collector_port;
    public final int http_server_port;
    public final boolean socks5_enabled;
    public final String socks5_proxy_address;
    public final int socks5_proxy_port;
    public final boolean ipv6_enabled;
    public final boolean root_capture;
    public final boolean pcapdroid_trailer;

    public CaptureSettings(SharedPreferences prefs) {
        dump_mode = Prefs.getDumpMode(prefs);
        app_filter = Prefs.getAppFilter(prefs);
        collector_address = Prefs.getCollectorIp(prefs);
        collector_port = Prefs.getCollectorPort(prefs);
        http_server_port = Prefs.getHttpServerPort(prefs);
        socks5_enabled = Prefs.getTlsDecryptionEnabled(prefs);
        socks5_proxy_address = Prefs.getSocks5ProxyAddress(prefs);
        socks5_proxy_port = Prefs.getSocks5ProxyPort(prefs);
        ipv6_enabled = Prefs.getIPv6Enabled(prefs);
        root_capture = Prefs.isRootCaptureEnabled(prefs);
        pcapdroid_trailer = Prefs.isPcapdroidTrailerEnabled(prefs);
    }

    public CaptureSettings(Intent intent) {
        dump_mode = Prefs.getDumpMode(getString(intent, Prefs.PREF_PCAP_DUMP_MODE, "none"));
        app_filter = getString(intent, Prefs.PREF_APP_FILTER, "");
        collector_address = getString(intent, Prefs.PREF_COLLECTOR_IP_KEY, "127.0.0.1");
        collector_port = intent.getIntExtra(Prefs.PREF_COLLECTOR_PORT_KEY, 1234);
        http_server_port = intent.getIntExtra(Prefs.PREF_HTTP_SERVER_PORT, 8080);
        socks5_enabled = intent.getBooleanExtra(Prefs.PREF_TLS_DECRYPTION_ENABLED_KEY, false);
        socks5_proxy_address = getString(intent, Prefs.PREF_SOCKS5_PROXY_IP_KEY, "0.0.0.0");
        socks5_proxy_port = intent.getIntExtra(Prefs.PREF_SOCKS5_PROXY_PORT_KEY, 8080);
        ipv6_enabled = intent.getBooleanExtra(Prefs.PREF_IPV6_ENABLED, false);
        root_capture = intent.getBooleanExtra(Prefs.PREF_ROOT_CAPTURE, false);
        pcapdroid_trailer = intent.getBooleanExtra(Prefs.PREF_PCAPDROID_TRAILER, false);
    }

    private static String getString(Intent intent, String key, String def_value) {
        String val = intent.getStringExtra(key);
        return (val != null) ? val : def_value;
    }
}
