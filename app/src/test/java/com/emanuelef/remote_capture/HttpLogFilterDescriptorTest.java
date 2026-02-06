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

package com.emanuelef.remote_capture;

import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.HttpLogFilterDescriptor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class HttpLogFilterDescriptorTest {
    HttpLogFilterDescriptor filter;
    ConnectionDescriptor conn;

    @Before
    public void setup() {
        filter = new HttpLogFilterDescriptor();
        conn = new ConnectionDescriptor(0, 4, 6,
                "1.1.1.1", "2.2.2.2", "", 51234, 443,
                0, -1, 0, false, 0);
        conn.l7proto = "TLS";
    }

    private HttpLog.HttpRequest makeRequest(String method) {
        HttpLog.HttpRequest req = new HttpLog.HttpRequest(conn, 0);
        req.method = method;
        req.host = "example.org";
        req.path = "/path";
        return req;
    }

    private HttpLog.HttpReply makeReply(HttpLog.HttpRequest req, int code, String contentType, int bodyLength) {
        HttpLog.HttpReply reply = new HttpLog.HttpReply(req, 1);
        reply.responseCode = code;
        reply.contentType = contentType;
        reply.bodyLength = bodyLength;
        req.reply = reply;
        return reply;
    }

    @Test
    public void testInitialState() {
        assertFalse(filter.isSet());
    }

    @Test
    public void testMethodFilter() {
        HttpLog.HttpRequest req = makeRequest("GET");
        filter.method = "GET";
        assertTrue(filter.isSet());
        assertTrue(filter.matches(req));

        filter.method = "POST";
        assertFalse(filter.matches(req));

        // case insensitive
        filter.method = "get";
        assertTrue(filter.matches(req));
    }

    @Test
    public void testContentTypeFilter() {
        HttpLog.HttpRequest req = makeRequest("GET");
        makeReply(req, 200, "text/html", 100);

        filter.contentType = "text/html";
        assertTrue(filter.matches(req));

        filter.contentType = "application/json";
        assertFalse(filter.matches(req));
    }

    @Test
    public void testContentTypeFilterNoReply() {
        HttpLog.HttpRequest req = makeRequest("GET");
        filter.contentType = "text/html";
        assertFalse(filter.matches(req));
    }

    @Test
    public void testHttpStatusFilter() {
        HttpLog.HttpRequest req = makeRequest("GET");
        makeReply(req, 200, "text/html", 100);

        filter.httpStatus = 200;
        assertTrue(filter.matches(req));

        filter.httpStatus = 404;
        assertFalse(filter.matches(req));
    }

    @Test
    public void testHttpStatusFilterNoReply() {
        HttpLog.HttpRequest req = makeRequest("GET");
        filter.httpStatus = 200;
        assertFalse(filter.matches(req));
    }

    @Test
    public void testMinPayloadSizeFilter() {
        HttpLog.HttpRequest req = makeRequest("POST");
        req.bodyLength = 50;
        makeReply(req, 200, "text/html", 100);

        filter.minPayloadSize = 100;
        assertTrue(filter.matches(req));

        filter.minPayloadSize = 200;
        assertFalse(filter.matches(req));
    }

    @Test
    public void testDecryptionErrorFilter() {
        HttpLog.HttpRequest req = makeRequest("GET");
        req.decryptionError = "handshake failed";

        filter.decryptionError = true;
        assertTrue(filter.matches(req));

        filter.decryptionError = false;
        assertFalse(filter.matches(req));

        // no error
        HttpLog.HttpRequest req2 = makeRequest("GET");
        filter.decryptionError = true;
        assertFalse(filter.matches(req2));

        filter.decryptionError = false;
        assertTrue(filter.matches(req2));
    }

    @Test
    public void testCombinedFilters() {
        HttpLog.HttpRequest req = makeRequest("GET");
        makeReply(req, 200, "text/html", 100);

        filter.method = "GET";
        filter.httpStatus = 200;
        assertTrue(filter.matches(req));

        filter.httpStatus = 404;
        assertFalse(filter.matches(req));
    }

    @Test
    public void testClear() {
        filter.method = "GET";
        filter.contentType = "text/html";
        filter.httpStatus = 200;
        filter.minPayloadSize = 100;
        filter.decryptionError = true;
        assertTrue(filter.isSet());

        filter.clear();
        assertFalse(filter.isSet());
    }

    @Test
    public void testClearById() {
        filter.method = "POST";
        filter.clear(R.id.http_method_filter);
        assertFalse(filter.isSet());

        filter.contentType = "text/html";
        filter.clear(R.id.http_content_type_filter);
        assertFalse(filter.isSet());

        filter.httpStatus = 200;
        filter.clear(R.id.http_status_filter);
        assertFalse(filter.isSet());

        filter.decryptionError = true;
        filter.clear(R.id.decryption_status);
        assertFalse(filter.isSet());
    }
}
