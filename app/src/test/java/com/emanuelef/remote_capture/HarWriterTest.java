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

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import androidx.test.core.app.ApplicationProvider;

import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.PayloadChunk;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class HarWriterTest {
    private Context context;
    private CaptureService service;
    private HttpLog httpLog;

    @Before
    public void setup() {
        context = ApplicationProvider.getApplicationContext();
        httpLog = new HttpLog();

        // Mock CaptureService
        service = new CaptureService();
        Whitebox.setInternalState(service, "INSTANCE", service);
        Whitebox.setInternalState(service, "mHttpLog", httpLog);

        // Create mock settings with full payload mode
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        CaptureSettings settings = new CaptureSettings(context, prefs);
        settings.full_payload = true;
        Whitebox.setInternalState(service, "mSettings", settings);
    }

    @After
    public void tearDown() {
        Whitebox.setInternalState(service, "INSTANCE", null);
    }

    /**
     * Create a ConnectionDescriptor for testing
     */
    private ConnectionDescriptor createConnection(int incrId, String dstIp, int dstPort) {
        return new ConnectionDescriptor(incrId, 4, 6,
                "192.168.1.100", dstIp, "US",
                54321, dstPort, 0, 1000, 0, false, System.currentTimeMillis());
    }

    /**
     * Create a PayloadChunk with HTTP data
     */
    private PayloadChunk createHttpChunk(String httpText, boolean isSent, long timestamp) {
        byte[] payload = httpText.getBytes(StandardCharsets.UTF_8);
        return new PayloadChunk(payload, PayloadChunk.ChunkType.HTTP, isSent, timestamp, 0);
    }

    /**
     * Add a payload chunk directly to the connection without triggering HTTP logging.
     * This allows us to control test data precisely.
     */
    @SuppressWarnings("unchecked")
    private void addChunkDirect(ConnectionDescriptor conn, PayloadChunk chunk) {
        try {
            java.lang.reflect.Field field = ConnectionDescriptor.class.getDeclaredField("payload_chunks");
            field.setAccessible(true);
            java.util.ArrayList<PayloadChunk> chunks = (java.util.ArrayList<PayloadChunk>) field.get(conn);
            synchronized (conn) {
                chunks.add(chunk);
            }

            if (chunk.type == PayloadChunk.ChunkType.WEBSOCKET) {
                java.lang.reflect.Field wsField = ConnectionDescriptor.class.getDeclaredField("has_websocket_data");
                wsField.setAccessible(true);
                wsField.setBoolean(conn, true);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to add chunk directly", e);
        }
    }

    /**
     * Write HAR to string using HarWriter
     */
    private String writeHarToString() throws IOException {
        HarWriter writer = new HarWriter(context, httpLog);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(out);
        return out.toString(StandardCharsets.UTF_8.name());
    }

    /**
     * Parse HAR JSON and return the root object
     */
    private JsonObject parseHar(String harJson) {
        return JsonParser.parseString(harJson).getAsJsonObject();
    }

    @Test
    public void testBasicHarStructure() throws IOException {
        // Create a simple HTTP request/response
        ConnectionDescriptor conn = createConnection(1, "93.184.216.34", 80);
        conn.info = "example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /index.html HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "User-Agent: TestAgent/1.0\r\n" +
                "\r\n";

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: 13\r\n" +
                "\r\n" +
                "Hello, World!";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 100;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "example.com";
        httpReq.path = "/index.html";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "text/html";
        httpReply.bodyLength = 13;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        // Write HAR
        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);

        // Verify basic structure
        assertTrue("HAR should have 'log' object", har.has("log"));
        JsonObject log = har.getAsJsonObject("log");

        // Verify version
        assertEquals("1.2", log.get("version").getAsString());

        // Verify creator
        assertTrue("log should have 'creator' object", log.has("creator"));
        JsonObject creator = log.getAsJsonObject("creator");
        assertEquals("PCAPdroid", creator.get("name").getAsString());
        assertNotNull("creator should have version", creator.get("version"));

        // Verify entries array exists
        assertTrue("log should have 'entries' array", log.has("entries"));
        JsonArray entries = log.getAsJsonArray("entries");
        assertEquals(1, entries.size());

        JsonObject harEntry = entries.get(0).getAsJsonObject();

        // Verify serverIPAddress
        assertEquals("93.184.216.34", harEntry.get("serverIPAddress").getAsString());

        // Verify connection ID
        assertEquals("1", harEntry.get("connection").getAsString());

        // Verify cache object exists
        assertTrue("entry should have 'cache' object", harEntry.has("cache"));
        JsonObject cache = harEntry.getAsJsonObject("cache");
        assertNotNull("cache object should not be null", cache);

        // Verify startedDateTime
        assertTrue("entry should have 'startedDateTime'", harEntry.has("startedDateTime"));
        String startedDateTime = harEntry.get("startedDateTime").getAsString();
        assertNotNull("startedDateTime should not be null", startedDateTime);
        assertTrue("startedDateTime should contain 'T'", startedDateTime.contains("T"));
    }

    @Test
    public void testRequestFields() throws IOException {
        ConnectionDescriptor conn = createConnection(2, "10.0.0.1", 8080);
        conn.info = "api.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /api/users?page=1&limit=10 HTTP/1.1\r\n" +
                "Host: api.example.com\r\n" +
                "Accept: application/json\r\n" +
                "Cookie: session=abc123; user=john\r\n" +
                "\r\n";

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "\r\n" +
                "[]";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "api.example.com";
        httpReq.path = "/api/users";
        httpReq.query = "?page=1&limit=10";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "application/json";
        httpReply.bodyLength = 2;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");

        // Verify request method
        assertEquals("GET", request.get("method").getAsString());

        // Verify URL
        String url = request.get("url").getAsString();
        assertTrue("URL should contain host", url.contains("api.example.com"));
        assertTrue("URL should contain path", url.contains("/api/users"));

        // Verify HTTP version
        assertEquals("HTTP/1.1", request.get("httpVersion").getAsString());

        // Verify headers
        JsonArray headers = request.getAsJsonArray("headers");
        assertNotNull("headers should not be null", headers);
        assertTrue("should have headers", headers.size() > 0);

        // Check for Host header
        boolean hasHost = false;
        for (int i = 0; i < headers.size(); i++) {
            JsonObject header = headers.get(i).getAsJsonObject();
            if (header.get("name").getAsString().equals("Host")) {
                assertEquals("api.example.com", header.get("value").getAsString());
                hasHost = true;
            }
        }
        assertTrue("should have Host header", hasHost);

        // Verify query string
        JsonArray queryString = request.getAsJsonArray("queryString");
        assertNotNull("queryString should not be null", queryString);
        assertEquals(2, queryString.size());

        // Verify query parameters
        boolean hasPage = false, hasLimit = false;
        for (int i = 0; i < queryString.size(); i++) {
            JsonObject param = queryString.get(i).getAsJsonObject();
            String name = param.get("name").getAsString();
            String value = param.get("value").getAsString();
            if (name.equals("page") && value.equals("1")) hasPage = true;
            if (name.equals("limit") && value.equals("10")) hasLimit = true;
        }
        assertTrue("should have 'page' query param", hasPage);
        assertTrue("should have 'limit' query param", hasLimit);

        // Verify cookies
        JsonArray cookies = request.getAsJsonArray("cookies");
        assertNotNull("cookies should not be null", cookies);
        assertEquals(2, cookies.size());

        boolean hasSession = false, hasUser = false;
        for (int i = 0; i < cookies.size(); i++) {
            JsonObject cookie = cookies.get(i).getAsJsonObject();
            String name = cookie.get("name").getAsString();
            String value = cookie.get("value").getAsString();
            if (name.equals("session") && value.equals("abc123")) hasSession = true;
            if (name.equals("user") && value.equals("john")) hasUser = true;
        }
        assertTrue("should have 'session' cookie", hasSession);
        assertTrue("should have 'user' cookie", hasUser);

        // Verify headersSize and bodySize
        assertTrue("headersSize should be > 0", request.get("headersSize").getAsInt() > 0);
        assertEquals(0, request.get("bodySize").getAsInt());
    }

    @Test
    public void testResponseFields() throws IOException {
        ConnectionDescriptor conn = createConnection(3, "203.0.113.50", 443);
        conn.info = "secure.example.com";
        conn.l7proto = "TLS.HTTP";

        String httpRequest = "GET /secure HTTP/1.1\r\n" +
                "Host: secure.example.com\r\n" +
                "\r\n";

        String httpResponse = "HTTP/1.1 301 Moved Permanently\r\n" +
                "Content-Type: text/html\r\n" +
                "Location: https://secure.example.com/new-path\r\n" +
                "Set-Cookie: tracking=xyz789; Path=/; HttpOnly; Secure\r\n" +
                "\r\n" +
                "<html>Redirecting...</html>";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 75;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "secure.example.com";
        httpReq.path = "/secure";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 301;
        httpReply.responseStatus = "Moved Permanently";
        httpReply.contentType = "text/html";
        httpReply.bodyLength = 27;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject response = entry.getAsJsonObject("response");

        // Verify status
        assertEquals(301, response.get("status").getAsInt());
        assertEquals("Moved Permanently", response.get("statusText").getAsString());

        // Verify HTTP version
        assertEquals("HTTP/1.1", response.get("httpVersion").getAsString());

        // Verify redirect URL
        assertEquals("https://secure.example.com/new-path", response.get("redirectURL").getAsString());

        // Verify response headers
        JsonArray headers = response.getAsJsonArray("headers");
        assertNotNull("response headers should not be null", headers);
        assertTrue("should have response headers", headers.size() > 0);

        // Check Location header
        boolean hasLocation = false;
        for (int i = 0; i < headers.size(); i++) {
            JsonObject header = headers.get(i).getAsJsonObject();
            if (header.get("name").getAsString().equals("Location")) {
                assertEquals("https://secure.example.com/new-path", header.get("value").getAsString());
                hasLocation = true;
            }
        }
        assertTrue("should have Location header", hasLocation);

        // Verify response cookies (Set-Cookie parsing)
        JsonArray cookies = response.getAsJsonArray("cookies");
        assertNotNull("response cookies should not be null", cookies);
        assertEquals(1, cookies.size());

        JsonObject cookie = cookies.get(0).getAsJsonObject();
        assertEquals("tracking", cookie.get("name").getAsString());
        assertEquals("xyz789", cookie.get("value").getAsString());
        assertEquals("/", cookie.get("path").getAsString());
        assertTrue("cookie should be httpOnly", cookie.get("httpOnly").getAsBoolean());
        assertTrue("cookie should be secure", cookie.get("secure").getAsBoolean());

        // Verify content
        JsonObject content = response.getAsJsonObject("content");
        assertNotNull("content should not be null", content);
        assertEquals(27, content.get("size").getAsInt());
        assertEquals("text/html", content.get("mimeType").getAsString());

        // Verify headersSize and bodySize
        assertTrue("response headersSize should be > 0", response.get("headersSize").getAsInt() > 0);
        assertEquals(27, response.get("bodySize").getAsInt());
    }

    @Test
    public void testPostRequestWithBody() throws IOException {
        ConnectionDescriptor conn = createConnection(4, "10.0.0.2", 80);
        conn.info = "api.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "POST /api/login HTTP/1.1\r\n" +
                "Host: api.example.com\r\n" +
                "Content-Type: application/x-www-form-urlencoded\r\n" +
                "Content-Length: 30\r\n" +
                "\r\n" +
                "username=admin&password=secret";

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "\r\n" +
                "{\"success\":true}";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 100;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "POST";
        httpReq.host = "api.example.com";
        httpReq.path = "/api/login";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 30;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "application/json";
        httpReply.bodyLength = 16;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");

        // Verify method
        assertEquals("POST", request.get("method").getAsString());

        // Verify postData
        assertTrue("POST request should have postData", request.has("postData"));
        JsonObject postData = request.getAsJsonObject("postData");

        assertEquals("application/x-www-form-urlencoded", postData.get("mimeType").getAsString());
        String text = postData.get("text").getAsString();
        // Verify the exact POST body is extracted
        assertEquals("username=admin&password=secret", text);

        // Verify bodySize
        assertEquals(30, request.get("bodySize").getAsInt());
    }

    @Test
    public void testNoResponseEntry() throws IOException {
        // Test entry where no response was received
        ConnectionDescriptor conn = createConnection(8, "10.0.0.5", 80);
        conn.info = "timeout.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /timeout HTTP/1.1\r\n" +
                "Host: timeout.example.com\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        addChunkDirect(conn, reqChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "timeout.example.com";
        httpReq.path = "/timeout";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;
        // No reply set

        httpLog.addHttpRequest(httpReq);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject response = entry.getAsJsonObject("response");

        // Verify response with no actual data
        assertEquals(0, response.get("status").getAsInt());
        assertEquals("", response.get("statusText").getAsString());
        assertEquals(-1, response.get("headersSize").getAsInt());
        assertEquals(-1, response.get("bodySize").getAsInt());
        assertEquals("", response.get("redirectURL").getAsString());
    }

    @Test
    public void testMultipleEntries() throws IOException {
        // Test with multiple HTTP requests
        for (int i = 0; i < 3; i++) {
            ConnectionDescriptor conn = createConnection(10 + i, "10.0.0." + (10 + i), 80);
            conn.info = "multi" + i + ".example.com";
            conn.l7proto = "HTTP";

            String httpRequest = "GET /page" + i + " HTTP/1.1\r\n" +
                    "Host: multi" + i + ".example.com\r\n" +
                    "\r\n";

            String httpResponse = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "\r\n" +
                    "page" + i;

            long reqTimestamp = System.currentTimeMillis() + i * 100;
            long respTimestamp = reqTimestamp + 50;

            PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
            PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

            addChunkDirect(conn, reqChunk);
            addChunkDirect(conn, respChunk);

            HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
            httpReq.method = "GET";
            httpReq.host = "multi" + i + ".example.com";
            httpReq.path = "/page" + i;
            httpReq.query = "";
            httpReq.timestamp = reqTimestamp;
            httpReq.bodyLength = 0;

            HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
            httpReply.responseCode = 200;
            httpReply.responseStatus = "OK";
            httpReply.contentType = "text/plain";
            httpReply.bodyLength = 5;
            httpReq.reply = httpReply;

            httpLog.addHttpRequest(httpReq);
            httpLog.addHttpReply(httpReply);
        }

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonArray entries = har.getAsJsonObject("log").getAsJsonArray("entries");

        // Verify we have 3 entries
        assertEquals(3, entries.size());

        // Verify each entry has the expected path
        for (int i = 0; i < 3; i++) {
            JsonObject entry = entries.get(i).getAsJsonObject();
            JsonObject request = entry.getAsJsonObject("request");
            String url = request.get("url").getAsString();
            assertTrue("Entry " + i + " should have correct path", url.contains("/page" + i));
        }
    }

    @Test
    public void testBinaryContentEncoding() throws IOException {
        ConnectionDescriptor conn = createConnection(13, "10.0.0.9", 80);
        conn.info = "binary.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /image.png HTTP/1.1\r\n" +
                "Host: binary.example.com\r\n" +
                "\r\n";

        // Create a response with binary content (non-printable bytes)
        String httpResponseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/png\r\n" +
                "\r\n";
        byte[] headerBytes = httpResponseHeaders.getBytes(StandardCharsets.UTF_8);
        byte[] binaryBody = new byte[] {(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}; // PNG signature
        byte[] fullResponse = new byte[headerBytes.length + binaryBody.length];
        System.arraycopy(headerBytes, 0, fullResponse, 0, headerBytes.length);
        System.arraycopy(binaryBody, 0, fullResponse, headerBytes.length, binaryBody.length);

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 100;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = new PayloadChunk(fullResponse, PayloadChunk.ChunkType.HTTP, false, respTimestamp, 0);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "binary.example.com";
        httpReq.path = "/image.png";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "image/png";
        httpReply.bodyLength = binaryBody.length;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject response = entry.getAsJsonObject("response");
        JsonObject content = response.getAsJsonObject("content");

        // Verify binary content is base64 encoded
        assertEquals("image/png", content.get("mimeType").getAsString());
        assertTrue("binary content should have encoding field", content.has("encoding"));
        assertEquals("base64", content.get("encoding").getAsString());

        // Verify the actual base64 text matches the binary body
        assertTrue("content should have text field", content.has("text"));
        String base64Text = content.get("text").getAsString();
        byte[] decoded = Base64.decode(base64Text, Base64.NO_WRAP);
        assertEquals("decoded length should match binary body", binaryBody.length, decoded.length);
        for (int i = 0; i < binaryBody.length; i++) {
            assertEquals("byte " + i + " should match", binaryBody[i], decoded[i]);
        }
    }

    @Test
    public void testTextContentExtraction() throws IOException {
        ConnectionDescriptor conn = createConnection(14, "10.0.0.10", 80);
        conn.info = "text.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /data.json HTTP/1.1\r\n" +
                "Host: text.example.com\r\n" +
                "\r\n";

        String responseBody = "{\"name\":\"test\",\"value\":123}";
        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + responseBody.length() + "\r\n" +
                "\r\n" +
                responseBody;

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "text.example.com";
        httpReq.path = "/data.json";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "application/json";
        httpReply.bodyLength = responseBody.length();
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject response = entry.getAsJsonObject("response");
        JsonObject content = response.getAsJsonObject("content");

        // Verify text content is NOT base64 encoded
        assertEquals("application/json", content.get("mimeType").getAsString());
        assertFalse("text content should NOT have encoding field", content.has("encoding"));

        // Verify the actual text content matches the response body
        assertTrue("content should have text field", content.has("text"));
        String textContent = content.get("text").getAsString();
        assertEquals("text content should match response body", responseBody, textContent);
    }

    @Test
    public void testGzipCompressedContent() throws IOException {
        ConnectionDescriptor conn = createConnection(15, "10.0.0.11", 80);
        conn.info = "gzip.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /app.js HTTP/1.1\r\n" +
                "Host: gzip.example.com\r\n" +
                "Accept-Encoding: gzip\r\n" +
                "\r\n";

        // Original JavaScript content
        String originalJs = "function hello() { console.log('Hello, World!'); }";

        // Gzip compress the JavaScript
        ByteArrayOutputStream gzipOut = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(gzipOut)) {
            gzos.write(originalJs.getBytes(StandardCharsets.UTF_8));
        }
        byte[] gzippedBody = gzipOut.toByteArray();

        // Build HTTP response with gzipped content
        String httpResponseHeaders = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/javascript\r\n" +
                "Content-Encoding: gzip\r\n" +
                "Content-Length: " + gzippedBody.length + "\r\n" +
                "\r\n";
        byte[] headerBytes = httpResponseHeaders.getBytes(StandardCharsets.UTF_8);
        byte[] fullResponse = new byte[headerBytes.length + gzippedBody.length];
        System.arraycopy(headerBytes, 0, fullResponse, 0, headerBytes.length);
        System.arraycopy(gzippedBody, 0, fullResponse, headerBytes.length, gzippedBody.length);

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = new PayloadChunk(fullResponse, PayloadChunk.ChunkType.HTTP, false, respTimestamp, 0);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "gzip.example.com";
        httpReq.path = "/app.js";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "application/javascript";
        httpReply.bodyLength = gzippedBody.length;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject response = entry.getAsJsonObject("response");
        JsonObject content = response.getAsJsonObject("content");

        // Verify content type
        assertEquals("application/javascript", content.get("mimeType").getAsString());

        // Since the data is now decompressed before being written to HAR,
        // the content should be plain text (not base64 encoded)
        assertFalse("decompressed text content should NOT have encoding field", content.has("encoding"));

        // Verify the actual content is the original JavaScript (decompressed)
        assertTrue("content should have text field", content.has("text"));
        String textContent = content.get("text").getAsString();
        assertEquals("content should match original JavaScript", originalJs, textContent);
    }

    @Test
    public void testWebSocketMessages() throws IOException {
        ConnectionDescriptor conn = createConnection(20, "10.0.0.20", 80);
        conn.info = "ws.example.com";
        conn.l7proto = "HTTP";

        // HTTP upgrade request/response
        String httpRequest = "GET /websocket HTTP/1.1\r\n" +
                "Host: ws.example.com\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "\r\n";

        String httpResponse = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        // Add WebSocket text message (sent)
        byte[] wsTextPayload = "Hello WebSocket".getBytes(StandardCharsets.UTF_8);
        PayloadChunk wsSend = new PayloadChunk(wsTextPayload, PayloadChunk.ChunkType.WEBSOCKET, true, respTimestamp + 100, 0);
        addChunkDirect(conn, wsSend);

        // Add WebSocket text message (received)
        byte[] wsTextPayload2 = "Hello from server".getBytes(StandardCharsets.UTF_8);
        PayloadChunk wsRecv = new PayloadChunk(wsTextPayload2, PayloadChunk.ChunkType.WEBSOCKET, false, respTimestamp + 200, 0);
        addChunkDirect(conn, wsRecv);

        // Add WebSocket binary message
        byte[] wsBinaryPayload = new byte[] {(byte)0x89, 0x50, 0x4E, 0x47}; // Binary data
        PayloadChunk wsBinary = new PayloadChunk(wsBinaryPayload, PayloadChunk.ChunkType.WEBSOCKET, false, respTimestamp + 300, 0);
        addChunkDirect(conn, wsBinary);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "ws.example.com";
        httpReq.path = "/websocket";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 101;
        httpReply.responseStatus = "Switching Protocols";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();

        // Verify WebSocket resource type
        assertTrue("entry should have _resourceType", entry.has("_resourceType"));
        assertEquals("websocket", entry.get("_resourceType").getAsString());

        // Verify WebSocket messages array
        assertTrue("entry should have _webSocketMessages", entry.has("_webSocketMessages"));
        JsonArray wsMessages = entry.getAsJsonArray("_webSocketMessages");
        assertEquals(3, wsMessages.size());

        // Verify first message (text, sent)
        JsonObject msg1 = wsMessages.get(0).getAsJsonObject();
        assertEquals("send", msg1.get("type").getAsString());
        assertEquals(1, msg1.get("opcode").getAsInt()); // Text
        assertEquals("Hello WebSocket", msg1.get("data").getAsString());

        // Verify second message (text, received)
        JsonObject msg2 = wsMessages.get(1).getAsJsonObject();
        assertEquals("receive", msg2.get("type").getAsString());
        assertEquals(1, msg2.get("opcode").getAsInt()); // Text
        assertEquals("Hello from server", msg2.get("data").getAsString());

        // Verify third message (binary, received)
        JsonObject msg3 = wsMessages.get(2).getAsJsonObject();
        assertEquals("receive", msg3.get("type").getAsString());
        assertEquals(2, msg3.get("opcode").getAsInt()); // Binary
        // Binary data should be base64 encoded
        assertNotNull(msg3.get("data").getAsString());
    }

    @Test
    public void testWebSocketEmptyPayload() throws IOException {
        ConnectionDescriptor conn = createConnection(21, "10.0.0.21", 80);
        conn.info = "ws.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /ws HTTP/1.1\r\n" +
                "Host: ws.example.com\r\n" +
                "\r\n";

        String httpResponse = "HTTP/1.1 101 Switching Protocols\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        // Add WebSocket message with empty payload
        PayloadChunk wsEmpty = new PayloadChunk(new byte[0], PayloadChunk.ChunkType.WEBSOCKET, true, respTimestamp + 100, 0);
        addChunkDirect(conn, wsEmpty);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "ws.example.com";
        httpReq.path = "/ws";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 101;
        httpReply.responseStatus = "Switching Protocols";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();

        JsonArray wsMessages = entry.getAsJsonArray("_webSocketMessages");
        assertEquals(1, wsMessages.size());

        JsonObject msg = wsMessages.get(0).getAsJsonObject();
        assertEquals("", msg.get("data").getAsString()); // Empty payload
    }

    @Test
    public void testHttpRstHandling() throws IOException {
        ConnectionDescriptor conn = createConnection(22, "10.0.0.22", 80);
        conn.info = "rst.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /reset HTTP/1.1\r\n" +
                "Host: rst.example.com\r\n" +
                "\r\n";

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "data";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "rst.example.com";
        httpReq.path = "/reset";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;
        httpReq.httpRst = true; // HTTP RST flag

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "text/plain";
        httpReply.bodyLength = 4;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject response = entry.getAsJsonObject("response");

        // When httpRst is true, response should be empty like no-response case
        assertEquals(0, response.get("status").getAsInt());
        assertEquals("", response.get("statusText").getAsString());
        assertEquals(-1, response.get("headersSize").getAsInt());
        assertEquals(-1, response.get("bodySize").getAsInt());
        assertEquals("", response.get("redirectURL").getAsString());
    }

    @Test
    public void testPutAndPatchMethodsWithBody() throws IOException {
        // Test that PUT and PATCH methods include postData (like POST)
        String[] methods = {"PUT", "PATCH"};
        String[] bodies = {"{\"name\":\"updated\"}", "{\"field\":\"new\"}"};

        for (int i = 0; i < methods.length; i++) {
            httpLog = new HttpLog(); // Reset for each iteration

            ConnectionDescriptor conn = createConnection(23 + i, "10.0.0." + (23 + i), 80);
            conn.info = "api.example.com";
            conn.l7proto = "HTTP";

            String body = bodies[i];
            String httpRequest = methods[i] + " /api/resource/1 HTTP/1.1\r\n" +
                    "Host: api.example.com\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: " + body.length() + "\r\n" +
                    "\r\n" +
                    body;

            String httpResponse = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "\r\n" +
                    "{}";

            long reqTimestamp = System.currentTimeMillis();
            long respTimestamp = reqTimestamp + 100;

            PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
            PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

            addChunkDirect(conn, reqChunk);
            addChunkDirect(conn, respChunk);

            HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
            httpReq.method = methods[i];
            httpReq.host = "api.example.com";
            httpReq.path = "/api/resource/1";
            httpReq.query = "";
            httpReq.timestamp = reqTimestamp;
            httpReq.bodyLength = body.length();

            HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
            httpReply.responseCode = 200;
            httpReply.responseStatus = "OK";
            httpReply.contentType = "application/json";
            httpReply.bodyLength = 2;
            httpReq.reply = httpReply;

            httpLog.addHttpRequest(httpReq);
            httpLog.addHttpReply(httpReply);

            HarWriter writer = new HarWriter(context, httpLog);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.write(out);
            String harJson = out.toString(StandardCharsets.UTF_8.name());
            JsonObject har = parseHar(harJson);
            JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
            JsonObject request = entry.getAsJsonObject("request");

            assertEquals(methods[i], request.get("method").getAsString());
            assertTrue(methods[i] + " request should have postData", request.has("postData"));
            JsonObject postData = request.getAsJsonObject("postData");
            assertEquals("application/json", postData.get("mimeType").getAsString());
            assertEquals(body, postData.get("text").getAsString());
        }
    }

    @Test
    public void testNullFieldsFallbacks() throws IOException {
        // Test that null fields fall back to appropriate defaults
        ConnectionDescriptor conn = createConnection(25, "10.0.0.25", 80);
        conn.info = "null.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /path HTTP/1.1\r\n" +
                "Host: null.example.com\r\n" +
                "\r\n";

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "\r\n" +
                "OK";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = null; // Null method
        httpReq.host = "null.example.com";
        httpReq.path = "/path";
        httpReq.query = null; // Null query
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = null; // Null response status
        httpReply.contentType = null; // Null content type
        httpReply.bodyLength = 2;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");
        JsonObject response = entry.getAsJsonObject("response");
        JsonObject content = response.getAsJsonObject("content");

        // Verify null fallbacks
        assertEquals("method should fallback to empty", "", request.get("method").getAsString());
        assertTrue("null query should result in empty queryString", request.getAsJsonArray("queryString").isEmpty());
        assertEquals("statusText should fallback to empty", "", response.get("statusText").getAsString());
        assertEquals("null contentType should fallback to octet-stream", "application/octet-stream", content.get("mimeType").getAsString());
    }

    @Test
    public void testMissingChunks() throws IOException {
        // Test missing request chunk
        ConnectionDescriptor conn1 = createConnection(26, "10.0.0.26", 80);
        conn1.info = "nochunk.example.com";
        conn1.l7proto = "HTTP";

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "OK";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);
        addChunkDirect(conn1, respChunk);

        HttpLog.HttpRequest httpReq1 = new HttpLog.HttpRequest(conn1, 0);
        httpReq1.method = "GET";
        httpReq1.host = "nochunk.example.com";
        httpReq1.path = "/path";
        httpReq1.query = "";
        httpReq1.timestamp = reqTimestamp;
        httpReq1.bodyLength = 0;

        HttpLog.HttpReply httpReply1 = new HttpLog.HttpReply(httpReq1, 0);
        httpReply1.responseCode = 200;
        httpReply1.responseStatus = "OK";
        httpReply1.contentType = "text/plain";
        httpReply1.bodyLength = 2;
        httpReq1.reply = httpReply1;

        httpLog.addHttpRequest(httpReq1);
        httpLog.addHttpReply(httpReply1);

        // Test missing response chunk
        ConnectionDescriptor conn2 = createConnection(29, "10.0.0.29", 80);
        conn2.info = "missing.example.com";
        conn2.l7proto = "HTTP";

        String httpRequest = "GET /path HTTP/1.1\r\n" +
                "Host: missing.example.com\r\n" +
                "\r\n";

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        addChunkDirect(conn2, reqChunk);

        HttpLog.HttpRequest httpReq2 = new HttpLog.HttpRequest(conn2, 0);
        httpReq2.method = "GET";
        httpReq2.host = "missing.example.com";
        httpReq2.path = "/path";
        httpReq2.query = "";
        httpReq2.timestamp = reqTimestamp;
        httpReq2.bodyLength = 0;

        HttpLog.HttpReply httpReply2 = new HttpLog.HttpReply(httpReq2, 5); // Invalid chunk position
        httpReply2.responseCode = 200;
        httpReply2.responseStatus = "OK";
        httpReply2.contentType = "text/plain";
        httpReply2.bodyLength = 10;
        httpReq2.reply = httpReply2;

        httpLog.addHttpRequest(httpReq2);
        httpLog.addHttpReply(httpReply2);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonArray entries = har.getAsJsonObject("log").getAsJsonArray("entries");

        // Verify missing request chunk defaults
        JsonObject entry1 = entries.get(0).getAsJsonObject();
        JsonObject request1 = entry1.getAsJsonObject("request");
        assertEquals("HTTP/1.1", request1.get("httpVersion").getAsString());
        assertEquals(-1, request1.get("headersSize").getAsInt());
        assertTrue(request1.getAsJsonArray("headers").isEmpty());

        // Verify missing response chunk defaults
        JsonObject entry2 = entries.get(1).getAsJsonObject();
        JsonObject response2 = entry2.getAsJsonObject("response");
        assertEquals("HTTP/1.1", response2.get("httpVersion").getAsString());
        assertEquals(-1, response2.get("headersSize").getAsInt());
        assertTrue(response2.getAsJsonArray("headers").isEmpty());
        assertFalse("content should NOT have text when chunk is missing",
                entry2.getAsJsonObject("response").getAsJsonObject("content").has("text"));
    }

    @Test
    public void testRedirectURLNotFound() throws IOException {
        // Response without Location header should have empty redirectURL
        ConnectionDescriptor conn = createConnection(30, "10.0.0.30", 80);
        conn.info = "noredirect.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /path HTTP/1.1\r\n" +
                "Host: noredirect.example.com\r\n" +
                "\r\n";

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "OK";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "noredirect.example.com";
        httpReq.path = "/path";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "text/plain";
        httpReply.bodyLength = 2;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject harEntry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject response = harEntry.getAsJsonObject("response");

        assertEquals("", response.get("redirectURL").getAsString());
    }

    @Test
    public void testEmptyResponseBody() throws IOException {
        ConnectionDescriptor conn = createConnection(31, "10.0.0.31", 80);
        conn.info = "empty.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /empty HTTP/1.1\r\n" +
                "Host: empty.example.com\r\n" +
                "\r\n";

        // Response with no body (just headers)
        String httpResponse = "HTTP/1.1 204 No Content\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "empty.example.com";
        httpReq.path = "/empty";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 204;
        httpReply.responseStatus = "No Content";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject response = entry.getAsJsonObject("response");
        JsonObject content = response.getAsJsonObject("content");

        // When body is empty, no text or encoding fields should be added
        assertFalse("content should NOT have text when body is empty", content.has("text"));
        assertFalse("content should NOT have encoding when body is empty", content.has("encoding"));
    }

    @Test
    public void testNullContentTypeInPostData() throws IOException {
        ConnectionDescriptor conn = createConnection(32, "10.0.0.32", 80);
        conn.info = "post.example.com";
        conn.l7proto = "HTTP";

        // POST request without Content-Type header
        String httpRequest = "POST /submit HTTP/1.1\r\n" +
                "Host: post.example.com\r\n" +
                "Content-Length: 11\r\n" +
                "\r\n" +
                "raw content";

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "POST";
        httpReq.host = "post.example.com";
        httpReq.path = "/submit";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 11;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");
        JsonObject postData = request.getAsJsonObject("postData");

        assertEquals("", postData.get("mimeType").getAsString()); // Fallback to ""
    }

    @Test
    public void testQueryParamWithoutValue() throws IOException {
        ConnectionDescriptor conn = createConnection(34, "10.0.0.34", 80);
        conn.info = "query.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /path?flag&key=value&another HTTP/1.1\r\n" +
                "Host: query.example.com\r\n" +
                "\r\n";

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "query.example.com";
        httpReq.path = "/path";
        httpReq.query = "?flag&key=value&another"; // Params without '='
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");
        JsonArray queryString = request.getAsJsonArray("queryString");

        assertEquals(3, queryString.size());

        // First param: flag (no value)
        JsonObject param1 = queryString.get(0).getAsJsonObject();
        assertEquals("flag", param1.get("name").getAsString());
        assertEquals("", param1.get("value").getAsString());

        // Second param: key=value
        JsonObject param2 = queryString.get(1).getAsJsonObject();
        assertEquals("key", param2.get("name").getAsString());
        assertEquals("value", param2.get("value").getAsString());

        // Third param: another (no value)
        JsonObject param3 = queryString.get(2).getAsJsonObject();
        assertEquals("another", param3.get("name").getAsString());
        assertEquals("", param3.get("value").getAsString());
    }

    @Test
    public void testMalformedUrlEncoding() throws IOException {
        ConnectionDescriptor conn = createConnection(35, "10.0.0.35", 80);
        conn.info = "query.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /path?valid=ok&bad=%ZZ&good=yes HTTP/1.1\r\n" +
                "Host: query.example.com\r\n" +
                "\r\n";

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "query.example.com";
        httpReq.path = "/path";
        httpReq.query = "?valid=ok&bad=%ZZ&good=yes"; // Invalid %ZZ encoding
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");
        JsonArray queryString = request.getAsJsonArray("queryString");

        // Only valid params should be present (malformed one skipped)
        assertEquals(2, queryString.size());
    }

    @Test
    public void testInvalidRequestCookie() throws IOException {
        ConnectionDescriptor conn = createConnection(36, "10.0.0.36", 80);
        conn.info = "cookie.example.com";
        conn.l7proto = "HTTP";

        // Cookie with invalid format (no '=')
        String httpRequest = "GET /path HTTP/1.1\r\n" +
                "Host: cookie.example.com\r\n" +
                "Cookie: valid=value; invalid; another=ok\r\n" +
                "\r\n";

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "cookie.example.com";
        httpReq.path = "/path";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");
        JsonArray cookies = request.getAsJsonArray("cookies");

        // Only valid cookies should be present
        assertEquals(2, cookies.size());
    }

    @Test
    public void testInvalidSetCookie() throws IOException {
        ConnectionDescriptor conn = createConnection(37, "10.0.0.37", 80);
        conn.info = "cookie.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /path HTTP/1.1\r\n" +
                "Host: cookie.example.com\r\n" +
                "\r\n";

        // Set-Cookie with invalid format (no '=' in name=value part)
        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Set-Cookie: invalidcookie; Path=/\r\n" +
                "Set-Cookie: valid=ok; Path=/\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "cookie.example.com";
        httpReq.path = "/path";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject response = entry.getAsJsonObject("response");
        JsonArray cookies = response.getAsJsonArray("cookies");

        // Only valid cookie should be present
        assertEquals(1, cookies.size());
        assertEquals("valid", cookies.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    public void testSetCookieAllAttributes() throws IOException {
        ConnectionDescriptor conn = createConnection(38, "10.0.0.38", 80);
        conn.info = "cookie.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /path HTTP/1.1\r\n" +
                "Host: cookie.example.com\r\n" +
                "\r\n";

        // Set-Cookie with all attributes
        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Set-Cookie: session=abc123; Path=/app; Domain=.example.com; HttpOnly; Secure; SameSite=Lax; Expires=Wed, 09 Jun 2027 10:18:14 GMT\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "cookie.example.com";
        httpReq.path = "/path";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject response = entry.getAsJsonObject("response");
        JsonArray cookies = response.getAsJsonArray("cookies");

        assertEquals(1, cookies.size());
        JsonObject cookie = cookies.get(0).getAsJsonObject();
        assertEquals("session", cookie.get("name").getAsString());
        assertEquals("abc123", cookie.get("value").getAsString());
        assertEquals("/app", cookie.get("path").getAsString());
        assertEquals(".example.com", cookie.get("domain").getAsString());
        assertTrue(cookie.get("httpOnly").getAsBoolean());
        assertTrue(cookie.get("secure").getAsBoolean());
        assertEquals("Lax", cookie.get("sameSite").getAsString());
        assertEquals("2027-06-09T10:18:14Z", cookie.get("expires").getAsString());
    }

    @Test
    public void testEmptyHttpText() throws IOException {
        ConnectionDescriptor conn = createConnection(39, "10.0.0.39", 80);
        conn.info = "empty.example.com";
        conn.l7proto = "HTTP";

        // Empty request chunk payload
        PayloadChunk reqChunk = new PayloadChunk(new byte[0], PayloadChunk.ChunkType.HTTP, true, System.currentTimeMillis(), 0);
        PayloadChunk respChunk = new PayloadChunk(new byte[0], PayloadChunk.ChunkType.HTTP, false, System.currentTimeMillis() + 50, 0);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "empty.example.com";
        httpReq.path = "/path";
        httpReq.query = "";
        httpReq.timestamp = System.currentTimeMillis();
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");
        JsonObject response = entry.getAsJsonObject("response");

        // With empty payload, defaults should be used
        assertEquals("HTTP/1.1", request.get("httpVersion").getAsString());
        assertEquals("HTTP/1.1", response.get("httpVersion").getAsString());
    }

    @Test
    public void testMalformedHttpLines() throws IOException {
        // Test malformed request line (no space/HTTP version)
        ConnectionDescriptor conn1 = createConnection(40, "10.0.0.40", 80);
        conn1.info = "malformed.example.com";
        conn1.l7proto = "HTTP";

        String httpRequest1 = "GET/path\r\n" +
                "Host: malformed.example.com\r\n" +
                "\r\n";
        String httpResponse1 = "HTTP/1.1 200 OK\r\n\r\n";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        addChunkDirect(conn1, createHttpChunk(httpRequest1, true, reqTimestamp));
        addChunkDirect(conn1, createHttpChunk(httpResponse1, false, respTimestamp));

        HttpLog.HttpRequest httpReq1 = new HttpLog.HttpRequest(conn1, 0);
        httpReq1.method = "GET";
        httpReq1.host = "malformed.example.com";
        httpReq1.path = "/path";
        httpReq1.query = "";
        httpReq1.timestamp = reqTimestamp;
        httpReq1.bodyLength = 0;

        HttpLog.HttpReply httpReply1 = new HttpLog.HttpReply(httpReq1, 1);
        httpReply1.responseCode = 200;
        httpReply1.responseStatus = "OK";
        httpReply1.contentType = "";
        httpReply1.bodyLength = 0;
        httpReq1.reply = httpReply1;

        httpLog.addHttpRequest(httpReq1);
        httpLog.addHttpReply(httpReply1);

        // Test malformed response line (no space)
        ConnectionDescriptor conn2 = createConnection(41, "10.0.0.41", 80);
        conn2.info = "malformed.example.com";
        conn2.l7proto = "HTTP";

        String httpRequest2 = "GET /path HTTP/1.1\r\n" +
                "Host: malformed.example.com\r\n" +
                "\r\n";
        String httpResponse2 = "200OK\r\n\r\n";

        addChunkDirect(conn2, createHttpChunk(httpRequest2, true, reqTimestamp));
        addChunkDirect(conn2, createHttpChunk(httpResponse2, false, respTimestamp));

        HttpLog.HttpRequest httpReq2 = new HttpLog.HttpRequest(conn2, 0);
        httpReq2.method = "GET";
        httpReq2.host = "malformed.example.com";
        httpReq2.path = "/path";
        httpReq2.query = "";
        httpReq2.timestamp = reqTimestamp;
        httpReq2.bodyLength = 0;

        HttpLog.HttpReply httpReply2 = new HttpLog.HttpReply(httpReq2, 1);
        httpReply2.responseCode = 200;
        httpReply2.responseStatus = "OK";
        httpReply2.contentType = "";
        httpReply2.bodyLength = 0;
        httpReq2.reply = httpReply2;

        httpLog.addHttpRequest(httpReq2);
        httpLog.addHttpReply(httpReply2);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonArray entries = har.getAsJsonObject("log").getAsJsonArray("entries");

        // Both should default to HTTP/1.1 for malformed lines
        JsonObject entry1 = entries.get(0).getAsJsonObject();
        assertEquals("HTTP/1.1", entry1.getAsJsonObject("request").get("httpVersion").getAsString());

        JsonObject entry2 = entries.get(1).getAsJsonObject();
        assertEquals("HTTP/1.1", entry2.getAsJsonObject("response").get("httpVersion").getAsString());
    }

    @Test
    public void testInvalidHeaders() throws IOException {
        ConnectionDescriptor conn = createConnection(42, "10.0.0.42", 80);
        conn.info = "header.example.com";
        conn.l7proto = "HTTP";

        // Request with invalid headers (missing colon, colon at position 0)
        String httpRequest = "GET /path HTTP/1.1\r\n" +
                "Valid-Header: value\r\n" +
                "InvalidHeaderNoColon\r\n" +
                ":InvalidColonAtStart\r\n" +
                "Another-Valid: ok\r\n" +
                "\r\n";

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "header.example.com";
        httpReq.path = "/path";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");
        JsonArray headers = request.getAsJsonArray("headers");

        // Only valid headers should be present (invalid ones skipped)
        assertEquals(2, headers.size());

        boolean hasValid = false, hasAnother = false;
        for (int i = 0; i < headers.size(); i++) {
            String name = headers.get(i).getAsJsonObject().get("name").getAsString();
            if (name.equals("Valid-Header")) hasValid = true;
            if (name.equals("Another-Valid")) hasAnother = true;
        }
        assertTrue("Should have Valid-Header", hasValid);
        assertTrue("Should have Another-Valid", hasAnother);
    }

    @Test
    public void testXmlContentType() throws IOException {
        ConnectionDescriptor conn = createConnection(43, "10.0.0.43", 80);
        conn.info = "xml.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /data.xml HTTP/1.1\r\n" +
                "Host: xml.example.com\r\n" +
                "\r\n";

        String xmlBody = "<?xml version=\"1.0\"?><root><item>value</item></root>";
        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/xml\r\n" +
                "\r\n" +
                xmlBody;

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "xml.example.com";
        httpReq.path = "/data.xml";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "application/xml";
        httpReply.bodyLength = xmlBody.length();
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject response = entry.getAsJsonObject("response");
        JsonObject content = response.getAsJsonObject("content");

        // XML is text content, should not be base64 encoded
        assertFalse("xml content should NOT have encoding", content.has("encoding"));
        assertEquals(xmlBody, content.get("text").getAsString());
    }

    @Test
    public void testBinaryContentTypesAreBase64Encoded() throws IOException {
        // Test that audio, video, and octet-stream content types result in base64 encoding
        String[] contentTypes = {"audio/mpeg", "video/mp4", "application/octet-stream"};
        byte[] binaryData = new byte[] {0x01, 0x02, 0x03, 0x04};

        for (int i = 0; i < contentTypes.length; i++) {
            httpLog = new HttpLog(); // Reset for each iteration

            ConnectionDescriptor conn = createConnection(44 + i, "10.0.0." + (44 + i), 80);
            conn.info = "binary.example.com";
            conn.l7proto = "HTTP";

            String httpRequest = "GET /file HTTP/1.1\r\n" +
                    "Host: binary.example.com\r\n" +
                    "\r\n";

            String httpResponseHeaders = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentTypes[i] + "\r\n" +
                    "\r\n";
            byte[] headerBytes = httpResponseHeaders.getBytes(StandardCharsets.UTF_8);
            byte[] fullResponse = new byte[headerBytes.length + binaryData.length];
            System.arraycopy(headerBytes, 0, fullResponse, 0, headerBytes.length);
            System.arraycopy(binaryData, 0, fullResponse, headerBytes.length, binaryData.length);

            long reqTimestamp = System.currentTimeMillis();
            long respTimestamp = reqTimestamp + 50;

            PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
            PayloadChunk respChunk = new PayloadChunk(fullResponse, PayloadChunk.ChunkType.HTTP, false, respTimestamp, 0);

            addChunkDirect(conn, reqChunk);
            addChunkDirect(conn, respChunk);

            HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
            httpReq.method = "GET";
            httpReq.host = "binary.example.com";
            httpReq.path = "/file";
            httpReq.query = "";
            httpReq.timestamp = reqTimestamp;
            httpReq.bodyLength = 0;

            HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
            httpReply.responseCode = 200;
            httpReply.responseStatus = "OK";
            httpReply.contentType = contentTypes[i];
            httpReply.bodyLength = binaryData.length;
            httpReq.reply = httpReply;

            httpLog.addHttpRequest(httpReq);
            httpLog.addHttpReply(httpReply);

            HarWriter writer = new HarWriter(context, httpLog);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.write(out);
            String harJson = out.toString(StandardCharsets.UTF_8.name());
            JsonObject har = parseHar(harJson);
            JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
            JsonObject response = entry.getAsJsonObject("response");
            JsonObject content = response.getAsJsonObject("content");

            assertTrue(contentTypes[i] + " content should have encoding", content.has("encoding"));
            assertEquals("base64", content.get("encoding").getAsString());
        }
    }

    @Test
    public void testByteBasedContentDetection() throws IOException {
        // Test text detection: printable bytes with null content type
        ConnectionDescriptor conn1 = createConnection(47, "10.0.0.47", 80);
        conn1.info = "detect.example.com";
        conn1.l7proto = "HTTP";

        String textBody = "This is plain text content";
        String httpResponse1 = "HTTP/1.1 200 OK\r\n\r\n" + textBody;

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        String httpRequest = "GET /data HTTP/1.1\r\n" +
                "Host: detect.example.com\r\n" +
                "\r\n";

        addChunkDirect(conn1, createHttpChunk(httpRequest, true, reqTimestamp));
        addChunkDirect(conn1, createHttpChunk(httpResponse1, false, respTimestamp));

        HttpLog.HttpRequest httpReq1 = new HttpLog.HttpRequest(conn1, 0);
        httpReq1.method = "GET";
        httpReq1.host = "detect.example.com";
        httpReq1.path = "/data";
        httpReq1.query = "";
        httpReq1.timestamp = reqTimestamp;
        httpReq1.bodyLength = 0;

        HttpLog.HttpReply httpReply1 = new HttpLog.HttpReply(httpReq1, 1);
        httpReply1.responseCode = 200;
        httpReply1.responseStatus = "OK";
        httpReply1.contentType = null; // Forces byte-based detection
        httpReply1.bodyLength = textBody.length();
        httpReq1.reply = httpReply1;

        httpLog.addHttpRequest(httpReq1);
        httpLog.addHttpReply(httpReply1);

        // Test binary detection: non-printable bytes with null content type
        ConnectionDescriptor conn2 = createConnection(48, "10.0.0.48", 80);
        conn2.info = "detect.example.com";
        conn2.l7proto = "HTTP";

        byte[] binaryData = new byte[] {(byte)0x00, (byte)0x01, (byte)0xFF, (byte)0xFE};
        String httpResponseHeaders = "HTTP/1.1 200 OK\r\n\r\n";
        byte[] headerBytes = httpResponseHeaders.getBytes(StandardCharsets.UTF_8);
        byte[] fullResponse = new byte[headerBytes.length + binaryData.length];
        System.arraycopy(headerBytes, 0, fullResponse, 0, headerBytes.length);
        System.arraycopy(binaryData, 0, fullResponse, headerBytes.length, binaryData.length);

        addChunkDirect(conn2, createHttpChunk(httpRequest, true, reqTimestamp));
        addChunkDirect(conn2, new PayloadChunk(fullResponse, PayloadChunk.ChunkType.HTTP, false, respTimestamp, 0));

        HttpLog.HttpRequest httpReq2 = new HttpLog.HttpRequest(conn2, 0);
        httpReq2.method = "GET";
        httpReq2.host = "detect.example.com";
        httpReq2.path = "/data";
        httpReq2.query = "";
        httpReq2.timestamp = reqTimestamp;
        httpReq2.bodyLength = 0;

        HttpLog.HttpReply httpReply2 = new HttpLog.HttpReply(httpReq2, 1);
        httpReply2.responseCode = 200;
        httpReply2.responseStatus = "OK";
        httpReply2.contentType = null; // Forces byte-based detection
        httpReply2.bodyLength = binaryData.length;
        httpReq2.reply = httpReply2;

        httpLog.addHttpRequest(httpReq2);
        httpLog.addHttpReply(httpReply2);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonArray entries = har.getAsJsonObject("log").getAsJsonArray("entries");

        // Verify text detection (first entry)
        JsonObject content1 = entries.get(0).getAsJsonObject().getAsJsonObject("response").getAsJsonObject("content");
        assertFalse("text-detected content should NOT have encoding", content1.has("encoding"));
        assertEquals(textBody, content1.get("text").getAsString());

        // Verify binary detection (second entry)
        JsonObject content2 = entries.get(1).getAsJsonObject().getAsJsonObject("response").getAsJsonObject("content");
        assertTrue("binary-detected content should have encoding", content2.has("encoding"));
        assertEquals("base64", content2.get("encoding").getAsString());
    }

    @Test
    public void testEntryTimeWithMissingReplyChunk() throws IOException {
        ConnectionDescriptor conn = createConnection(50, "10.0.0.50", 80);
        conn.info = "time.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /path HTTP/1.1\r\n" +
                "Host: time.example.com\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        addChunkDirect(conn, reqChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "time.example.com";
        httpReq.path = "/path";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 10); // Invalid chunk position
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();

        // With missing reply chunk, time should be -1
        assertEquals(-1, entry.get("time").getAsInt());
    }

    @Test
    public void testPostWithMissingChunk() throws IOException {
        ConnectionDescriptor conn = createConnection(51, "10.0.0.51", 80);
        conn.info = "post.example.com";
        conn.l7proto = "HTTP";

        // Don't add request chunk
        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "\r\n";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 5); // Invalid chunk position
        httpReq.method = "POST";
        httpReq.host = "post.example.com";
        httpReq.path = "/submit";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 100;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 0);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "";
        httpReply.bodyLength = 0;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");

        // POST should have postData even with missing chunk
        assertTrue("POST should have postData", request.has("postData"));
        JsonObject postData = request.getAsJsonObject("postData");
        assertEquals("", postData.get("mimeType").getAsString());
        // text field should not be present when chunk is missing
        assertFalse("postData should NOT have text when chunk is missing", postData.has("text"));
    }

    @Test
    public void testMimeTypeWithCharset() throws IOException {
        ConnectionDescriptor conn = createConnection(53, "10.0.0.53", 80);
        conn.info = "charset.example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /page HTTP/1.1\r\n" +
                "Host: charset.example.com\r\n" +
                "\r\n";

        String htmlBody = "<html><body>Hello</body></html>";
        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "\r\n" +
                htmlBody;

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "charset.example.com";
        httpReq.path = "/page";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "text/html; charset=utf-8"; // With charset
        httpReply.bodyLength = htmlBody.length();
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject response = entry.getAsJsonObject("response");
        JsonObject content = response.getAsJsonObject("content");

        // MimeType should include the full content-type with charset
        assertEquals("text/html; charset=utf-8", content.get("mimeType").getAsString());

        // Content should be detected as text (not base64)
        assertFalse("text/html with charset should NOT have encoding", content.has("encoding"));
        assertEquals(htmlBody, content.get("text").getAsString());
    }

    @Test
    public void testPostDataMimeTypeWithCharset() throws IOException {
        ConnectionDescriptor conn = createConnection(54, "10.0.0.54", 80);
        conn.info = "form.example.com";
        conn.l7proto = "HTTP";

        String postBody = "name=test&value=123";
        String httpRequest = "POST /submit HTTP/1.1\r\n" +
                "Host: form.example.com\r\n" +
                "Content-Type: application/x-www-form-urlencoded; charset=utf-8\r\n" +
                "Content-Length: " + postBody.length() + "\r\n" +
                "\r\n" +
                postBody;

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "\r\n" +
                "{}";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "POST";
        httpReq.host = "form.example.com";
        httpReq.path = "/submit";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = postBody.length();

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "application/json";
        httpReply.bodyLength = 2;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");
        JsonObject postData = request.getAsJsonObject("postData");

        // PostData mimeType should include the full content-type with charset
        assertEquals("application/x-www-form-urlencoded; charset=utf-8", postData.get("mimeType").getAsString());
        assertEquals(postBody, postData.get("text").getAsString());
    }

    @Test
    public void testMimeTypeCaseInsensitive() throws IOException {
        ConnectionDescriptor conn = createConnection(55, "10.0.0.55", 80);
        conn.info = "case.example.com";
        conn.l7proto = "HTTP";

        String jsonBody = "{\"test\": true}";
        String httpRequest = "POST /api HTTP/1.1\r\n" +
                "Host: case.example.com\r\n" +
                "CONTENT-TYPE: APPLICATION/JSON\r\n" + // Uppercase header
                "Content-Length: " + jsonBody.length() + "\r\n" +
                "\r\n" +
                jsonBody;

        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "content-type: TEXT/PLAIN\r\n" + // Lowercase header name, uppercase value
                "\r\n" +
                "OK";

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 50;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "POST";
        httpReq.host = "case.example.com";
        httpReq.path = "/api";
        httpReq.query = "";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = jsonBody.length();

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "TEXT/PLAIN"; // Uppercase
        httpReply.bodyLength = 2;
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();
        JsonObject har = parseHar(harJson);
        JsonObject entry = har.getAsJsonObject("log").getAsJsonArray("entries").get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");
        JsonObject response = entry.getAsJsonObject("response");

        // PostData mimeType should be found with case-insensitive header lookup
        JsonObject postData = request.getAsJsonObject("postData");
        assertEquals("APPLICATION/JSON", postData.get("mimeType").getAsString());

        // Response mimeType
        JsonObject content = response.getAsJsonObject("content");
        assertEquals("TEXT/PLAIN", content.get("mimeType").getAsString());
    }

    @Test
    public void testHtmlEntitiesNotEscaped() throws IOException {
        // Create a connection with HTML content containing special characters
        ConnectionDescriptor conn = createConnection(1, "93.184.216.34", 80);
        conn.info = "example.com";
        conn.l7proto = "HTTP";

        String httpRequest = "GET /page?foo=1&bar=2 HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "\r\n";

        String htmlBody = "<html><body>Hello & goodbye</body></html>";
        String httpResponse = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html\r\n" +
                "Content-Length: " + htmlBody.length() + "\r\n" +
                "\r\n" +
                htmlBody;

        long reqTimestamp = System.currentTimeMillis();
        long respTimestamp = reqTimestamp + 100;

        PayloadChunk reqChunk = createHttpChunk(httpRequest, true, reqTimestamp);
        PayloadChunk respChunk = createHttpChunk(httpResponse, false, respTimestamp);

        addChunkDirect(conn, reqChunk);
        addChunkDirect(conn, respChunk);

        HttpLog.HttpRequest httpReq = new HttpLog.HttpRequest(conn, 0);
        httpReq.method = "GET";
        httpReq.host = "example.com";
        httpReq.path = "/page";
        httpReq.query = "foo=1&bar=2";
        httpReq.timestamp = reqTimestamp;
        httpReq.bodyLength = 0;

        HttpLog.HttpReply httpReply = new HttpLog.HttpReply(httpReq, 1);
        httpReply.responseCode = 200;
        httpReply.responseStatus = "OK";
        httpReply.contentType = "text/html";
        httpReply.bodyLength = htmlBody.length();
        httpReq.reply = httpReply;

        httpLog.addHttpRequest(httpReq);
        httpLog.addHttpReply(httpReply);

        String harJson = writeHarToString();

        // Verify that HTML entities are NOT escaped (disableHtmlEscaping is working)
        // Without disableHtmlEscaping, Gson would convert:
        //   < to \u003c
        //   > to \u003e
        //   & to \u0026
        //   = to \u003d
        assertFalse("HTML entities should not be escaped: \\u003c found",
                harJson.contains("\\u003c"));
        assertFalse("HTML entities should not be escaped: \\u003e found",
                harJson.contains("\\u003e"));
        assertFalse("HTML entities should not be escaped: \\u0026 found",
                harJson.contains("\\u0026"));
        assertFalse("HTML entities should not be escaped: \\u003d found",
                harJson.contains("\\u003d"));

        // Verify the actual characters are present
        assertTrue("Should contain literal < character", harJson.contains("<html>"));
        assertTrue("Should contain literal > character", harJson.contains("</html>"));
        assertTrue("Should contain literal & character", harJson.contains("&bar"));
        assertTrue("Should contain literal = in query", harJson.contains("foo=1"));
    }

    @Test
    public void testSingleRequestExport() throws IOException {
        // Create two HTTP requests to verify that only the specified one is exported
        ConnectionDescriptor conn1 = createConnection(1, "93.184.216.34", 80);
        conn1.info = "example1.com";
        conn1.l7proto = "HTTP";

        ConnectionDescriptor conn2 = createConnection(2, "93.184.216.35", 80);
        conn2.info = "example2.com";
        conn2.l7proto = "HTTP";

        String httpRequest1 = "GET /first HTTP/1.1\r\n" +
                "Host: example1.com\r\n" +
                "\r\n";

        String httpResponse1 = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "First response";

        String httpRequest2 = "GET /second HTTP/1.1\r\n" +
                "Host: example2.com\r\n" +
                "\r\n";

        String httpResponse2 = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "Second response";

        long reqTimestamp1 = System.currentTimeMillis();
        long respTimestamp1 = reqTimestamp1 + 50;
        long reqTimestamp2 = reqTimestamp1 + 100;
        long respTimestamp2 = reqTimestamp2 + 50;

        // Add chunks for first request
        PayloadChunk reqChunk1 = createHttpChunk(httpRequest1, true, reqTimestamp1);
        PayloadChunk respChunk1 = createHttpChunk(httpResponse1, false, respTimestamp1);
        addChunkDirect(conn1, reqChunk1);
        addChunkDirect(conn1, respChunk1);

        // Add chunks for second request
        PayloadChunk reqChunk2 = createHttpChunk(httpRequest2, true, reqTimestamp2);
        PayloadChunk respChunk2 = createHttpChunk(httpResponse2, false, respTimestamp2);
        addChunkDirect(conn2, reqChunk2);
        addChunkDirect(conn2, respChunk2);

        // Create first HTTP request
        HttpLog.HttpRequest httpReq1 = new HttpLog.HttpRequest(conn1, 0);
        httpReq1.method = "GET";
        httpReq1.host = "example1.com";
        httpReq1.path = "/first";
        httpReq1.query = "";
        httpReq1.timestamp = reqTimestamp1;
        httpReq1.bodyLength = 0;

        HttpLog.HttpReply httpReply1 = new HttpLog.HttpReply(httpReq1, 1);
        httpReply1.responseCode = 200;
        httpReply1.responseStatus = "OK";
        httpReply1.contentType = "text/plain";
        httpReply1.bodyLength = 14;
        httpReq1.reply = httpReply1;

        // Create second HTTP request
        HttpLog.HttpRequest httpReq2 = new HttpLog.HttpRequest(conn2, 0);
        httpReq2.method = "GET";
        httpReq2.host = "example2.com";
        httpReq2.path = "/second";
        httpReq2.query = "";
        httpReq2.timestamp = reqTimestamp2;
        httpReq2.bodyLength = 0;

        HttpLog.HttpReply httpReply2 = new HttpLog.HttpReply(httpReq2, 1);
        httpReply2.responseCode = 200;
        httpReply2.responseStatus = "OK";
        httpReply2.contentType = "text/plain";
        httpReply2.bodyLength = 15;
        httpReq2.reply = httpReply2;

        // Add both requests to the HTTP log
        httpLog.addHttpRequest(httpReq1);
        httpLog.addHttpReply(httpReply1);
        httpLog.addHttpRequest(httpReq2);
        httpLog.addHttpReply(httpReply2);

        // Export only the second request using single request constructor
        HarWriter singleWriter = new HarWriter(context, httpReq2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        singleWriter.write(out);
        String harJson = out.toString(StandardCharsets.UTF_8.name());
        JsonObject har = parseHar(harJson);

        // Verify basic structure
        assertTrue("HAR should have 'log' object", har.has("log"));
        JsonObject log = har.getAsJsonObject("log");
        assertEquals("1.2", log.get("version").getAsString());

        // Verify only one entry is present
        JsonArray entries = log.getAsJsonArray("entries");
        assertEquals("Should have exactly one entry", 1, entries.size());

        // Verify it's the second request
        JsonObject entry = entries.get(0).getAsJsonObject();
        JsonObject request = entry.getAsJsonObject("request");
        String url = request.get("url").getAsString();
        assertTrue("URL should contain example2.com", url.contains("example2.com"));
        assertTrue("URL should contain /second", url.contains("/second"));
        assertFalse("URL should NOT contain example1.com", url.contains("example1.com"));
        assertFalse("URL should NOT contain /first", url.contains("/first"));

        // Verify response content
        JsonObject response = entry.getAsJsonObject("response");
        assertEquals(200, response.get("status").getAsInt());
        JsonObject content = response.getAsJsonObject("content");
        assertEquals("text/plain", content.get("mimeType").getAsString());
        assertEquals("Second response", content.get("text").getAsString());

        // Verify the full log still has both requests
        String fullHarJson = writeHarToString();
        JsonObject fullHar = parseHar(fullHarJson);
        JsonArray fullEntries = fullHar.getAsJsonObject("log").getAsJsonArray("entries");
        assertEquals("Full log should have 2 entries", 2, fullEntries.size());
    }
}
