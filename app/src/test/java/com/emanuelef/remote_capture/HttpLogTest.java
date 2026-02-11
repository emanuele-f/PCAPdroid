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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

@RunWith(RobolectricTestRunner.class)
public class HttpLogTest {
    HttpLog log;
    ConnectionDescriptor conn;
    ArrayList<String> events;

    @Before
    public void setup() {
        log = new HttpLog();
        events = new ArrayList<>();
        conn = new ConnectionDescriptor(0, 4, 6,
                "1.1.1.1", "2.2.2.2", "", 51234, 443,
                0, -1, 0, false, 0);
        conn.l7proto = "TLS";
    }

    @Test
    public void testAddAndGetRequest() {
        HttpLog.HttpRequest req = new HttpLog.HttpRequest(conn, 0);
        req.method = "GET";
        req.path = "/index.html";
        log.addHttpRequest(req);

        assertEquals(1, log.getSize());
        assertNotNull(log.getRequest(0));
        assertEquals("GET", log.getRequest(0).method);
        assertEquals(0, log.getRequest(0).getPosition());
    }

    @Test
    public void testGetRequestOutOfBounds() {
        assertNull(log.getRequest(0));
        assertNull(log.getRequest(-1));
    }

    @Test
    public void testAddHttpReply() {
        HttpLog.HttpRequest req = new HttpLog.HttpRequest(conn, 0);
        req.method = "GET";
        req.host = "example.org";
        log.addHttpRequest(req);

        HttpLog.HttpReply reply = new HttpLog.HttpReply(req, 1);
        reply.responseCode = 200;
        reply.contentType = "text/html";
        req.reply = reply;

        log.setListener(new HttpLog.Listener() {
            @Override public void onHttpRequestAdded(int pos) { events.add("added:" + pos); }
            @Override public void onHttpRequestUpdated(int pos) { events.add("updated:" + pos); }
            @Override public void onHttpRequestsClear() { events.add("clear"); }
        });

        log.addHttpReply(reply);
        assertEquals(1, events.size());
        assertEquals("updated:0", events.get(0));
    }

    @Test
    public void testListenerNotifications() {
        log.setListener(new HttpLog.Listener() {
            @Override public void onHttpRequestAdded(int pos) { events.add("added:" + pos); }
            @Override public void onHttpRequestUpdated(int pos) { events.add("updated:" + pos); }
            @Override public void onHttpRequestsClear() { events.add("clear"); }
        });

        HttpLog.HttpRequest req = new HttpLog.HttpRequest(conn, 0);
        log.addHttpRequest(req);
        assertEquals("added:0", events.get(0));

        log.clear();
        assertEquals("clear", events.get(1));
    }

    @Test
    public void testStartStopConnectionsUpdates() {
        log.setListener(new HttpLog.Listener() {
            @Override public void onHttpRequestAdded(int pos) { events.add("added:" + pos); }
            @Override public void onHttpRequestUpdated(int pos) { events.add("updated:" + pos); }
            @Override public void onHttpRequestsClear() { events.add("clear"); }
        });

        log.startConnectionsUpdates();

        // requests added during update should be deferred
        HttpLog.HttpRequest req1 = new HttpLog.HttpRequest(conn, 0);
        req1.timestamp = 200;
        HttpLog.HttpRequest req2 = new HttpLog.HttpRequest(conn, 1);
        req2.timestamp = 100;

        log.addHttpRequest(req1);
        log.addHttpRequest(req2);
        assertEquals(0, events.size());

        log.stopConnectionsUpdates();

        // should be sorted by timestamp and then dispatched
        assertEquals(2, events.size());
        assertEquals(2, log.getSize());
        // req2 (timestamp=100) should come first
        assertEquals(100, log.getRequest(0).timestamp);
        assertEquals(200, log.getRequest(1).timestamp);
    }

    @Test
    public void testDeferredRepliesDuringUpdate() {
        HttpLog.HttpRequest req = new HttpLog.HttpRequest(conn, 0);
        req.host = "example.org";
        log.addHttpRequest(req);

        HttpLog.HttpReply reply = new HttpLog.HttpReply(req, 1);
        reply.responseCode = 200;
        reply.contentType = "text/html";
        req.reply = reply;

        log.setListener(new HttpLog.Listener() {
            @Override public void onHttpRequestAdded(int pos) { events.add("added:" + pos); }
            @Override public void onHttpRequestUpdated(int pos) { events.add("updated:" + pos); }
            @Override public void onHttpRequestsClear() { events.add("clear"); }
        });

        log.startConnectionsUpdates();
        log.addHttpReply(reply);
        assertEquals(0, events.size());

        log.stopConnectionsUpdates();
        assertEquals(1, events.size());
        assertEquals("updated:0", events.get(0));
    }

    @Test
    public void testAddDecryptionError() {
        log.addDecryptionError(conn, 12345, "handshake failed");
        assertEquals(1, log.getSize());
        assertEquals("handshake failed", log.getRequest(0).decryptionError);
        assertEquals(12345, log.getRequest(0).timestamp);
    }

    @Test
    public void testHttpRequestGetUrl() {
        conn.l7proto = "HTTP";
        HttpLog.HttpRequest req = new HttpLog.HttpRequest(conn, 0);
        req.host = "example.org";
        req.path = "/path";
        req.query = "?q=1";
        assertEquals("http://example.org/path?q=1", req.getUrl());
    }

    @Test
    public void testHttpRequestGetUrlHttps() {
        conn.l7proto = "HTTPS";
        Whitebox.setInternalState(conn, "encrypted_l7", true);
        HttpLog.HttpRequest req = new HttpLog.HttpRequest(conn, 0);
        req.host = "secure.example.org";
        req.path = "/secure";
        req.query = "";
        assertEquals("https://secure.example.org/secure", req.getUrl());
    }

    @Test
    public void testHttpRequestGetProtoAndHostFallsBackToInfo() {
        conn.l7proto = "HTTP";
        conn.info = "fallback.example.org";
        HttpLog.HttpRequest req = new HttpLog.HttpRequest(conn, 0);
        // host is empty, should use conn.info
        assertEquals("http://fallback.example.org", req.getProtoAndHost());
    }

    @Test
    public void testHttpRequestMatches() {
        conn.l7proto = "HTTP";
        HttpLog.HttpRequest req = new HttpLog.HttpRequest(conn, 0);
        req.host = "example.org";
        req.path = "/api/data";
        req.query = "";

        assertTrue(req.matches("example"));
        assertTrue(req.matches("EXAMPLE"));
        assertTrue(req.matches("/api"));
        assertFalse(req.matches("notfound"));

        // match by content type
        HttpLog.HttpReply reply = new HttpLog.HttpReply(req, 1);
        reply.contentType = "application/json";
        req.reply = reply;
        assertTrue(req.matches("json"));
    }

    @Test
    public void testHttpRequestCompareTo() {
        HttpLog.HttpRequest req1 = new HttpLog.HttpRequest(conn, 0);
        req1.timestamp = 100;
        HttpLog.HttpRequest req2 = new HttpLog.HttpRequest(conn, 1);
        req2.timestamp = 200;
        HttpLog.HttpRequest req3 = new HttpLog.HttpRequest(conn, 2);
        req3.timestamp = 100;

        assertTrue(req1.compareTo(req2) < 0);
        assertTrue(req2.compareTo(req1) > 0);
        assertEquals(0, req1.compareTo(req3));
    }
}
