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
 * Copyright 2022 - Emanuele Faranda
 */

package com.pcapdroid.mitm;

import java.io.Serializable;

/* API to integrate MitmAddon */
public class MitmAPI {
    public static final String PACKAGE_NAME = "com.pcapdroid.mitm";

    public static final String MITM_SERVICE = PACKAGE_NAME + ".MitmService";
    public static final int MSG_ERROR = -1;
    public static final int MSG_START_MITM = 1;
    public static final int MSG_GET_CA_CERTIFICATE = 2;
    public static final int MSG_STOP_MITM = 3;
    public static final String MITM_CONFIG = "mitm_config";
    public static final String CERTIFICATE_RESULT = "certificate";
    public static final String SSLKEYLOG_RESULT = "sslkeylog";

    public static final class MitmConfig implements Serializable {
        public int proxyPort;              // the SOCKS5 port to use to accept mitm-ed connections
        public boolean transparentMode;    // true to use transparent proxy mode, false to use SOCKS5 proxy mode
        public boolean sslInsecure;        // true to disable upstream certificate check
        public boolean dumpMasterSecrets;  // true to enable the TLS master secrets dump messages (similar to SSLKEYLOG)
        public boolean shortPayload;       // if true, only the initial portion of the payload will be sent
        public String proxyAuth;           // SOCKS5 proxy authentication, "user:pass"
        public String additionalOptions;   // provide additional options to mitmproxy
    }
}
