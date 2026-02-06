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

import com.emanuelef.remote_capture.model.PayloadChunk;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.zip.GZIPOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class HTTPReassemblyTest {
    private final ArrayList<PayloadChunk> reassembled = new ArrayList<>();

    private HTTPReassembly newReassembly() {
        reassembled.clear();
        return new HTTPReassembly(true, reassembled::add);
    }

    private PayloadChunk makeChunk(String data, boolean is_sent) {
        return new PayloadChunk(data.getBytes(), PayloadChunk.ChunkType.HTTP, is_sent, 0, 0);
    }

    @Test
    public void testSimpleGetRequest() {
        HTTPReassembly ra = newReassembly();
        ra.handleChunk(makeChunk("GET /index.html?q=1 HTTP/1.1\r\nHost: example.org\r\n\r\n", true));

        assertEquals(1, reassembled.size());
        PayloadChunk c = reassembled.get(0);
        assertEquals("GET", c.httpMethod);
        assertEquals("/index.html", c.httpPath);
        assertEquals("?q=1", c.httpQuery);
        assertEquals("example.org", c.httpHost);
        assertEquals("HTTP/1.1", c.httpVersion);
    }

    @Test
    public void testSimpleResponse() {
        HTTPReassembly ra = newReassembly();
        String body = "Hello";
        String resp = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        ra.handleChunk(makeChunk(resp, false));

        assertEquals(1, reassembled.size());
        PayloadChunk c = reassembled.get(0);
        assertEquals(200, c.httpResponseCode);
        assertEquals("OK", c.httpResponseStatus);
        assertEquals("HTTP/1.1", c.httpVersion);
        assertEquals(body.length(), c.httpBodyLength);
    }

    @Test
    public void testResponseNoReasonPhrase() {
        HTTPReassembly ra = newReassembly();
        ra.handleChunk(makeChunk("HTTP/1.1 204\r\n\r\n", false));

        assertEquals(1, reassembled.size());
        assertEquals(204, reassembled.get(0).httpResponseCode);
    }

    @Test
    public void testContentTypeExtraction() {
        HTTPReassembly ra = newReassembly();
        ra.handleChunk(makeChunk("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n\r\n", false));

        assertEquals(1, reassembled.size());
        assertEquals("text/html", reassembled.get(0).httpContentType);
    }

    @Test
    public void testHostFromRequestLine() {
        HTTPReassembly ra = newReassembly();
        ra.handleChunk(makeChunk("GET http://proxy.example.com/path HTTP/1.1\r\n\r\n", true));

        assertEquals(1, reassembled.size());
        PayloadChunk c = reassembled.get(0);
        assertEquals("proxy.example.com", c.httpHost);
        assertEquals("/path", c.httpPath);
    }

    @Test
    public void testHostHeaderOverridesRequestLine() {
        HTTPReassembly ra = newReassembly();
        ra.handleChunk(makeChunk("GET /path HTTP/1.1\r\nHost: real.example.com\r\n\r\n", true));

        assertEquals(1, reassembled.size());
        assertEquals("real.example.com", reassembled.get(0).httpHost);
    }

    @Test
    public void testChunkedTransferEncoding() {
        HTTPReassembly ra = newReassembly();
        String data = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "5\r\nHello\r\n0\r\n\r\n";
        ra.handleChunk(makeChunk(data, false));

        assertEquals(1, reassembled.size());
    }

    @Test
    public void testGzipContentEncoding() throws IOException {
        HTTPReassembly ra = newReassembly();

        String body = "Hello, gzipped world!";
        byte[] compressed;
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             GZIPOutputStream gos = new GZIPOutputStream(bos)) {
            gos.write(body.getBytes());
            gos.finish();
            compressed = bos.toByteArray();
        }

        String headers = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: "
                + compressed.length + "\r\n\r\n";
        byte[] headersBytes = headers.getBytes();
        byte[] full = new byte[headersBytes.length + compressed.length];
        System.arraycopy(headersBytes, 0, full, 0, headersBytes.length);
        System.arraycopy(compressed, 0, full, headersBytes.length, compressed.length);

        ra.handleChunk(new PayloadChunk(full, PayloadChunk.ChunkType.HTTP, false, 0, 0));

        assertEquals(1, reassembled.size());
        String payload = new String(reassembled.get(0).payload);
        assertTrue(payload.contains(body));
    }

    @Test
    public void testWebSocketUpgrade() {
        HTTPReassembly ra = newReassembly();
        ra.handleChunk(makeChunk("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n\r\n", false));

        assertEquals(1, reassembled.size());
        assertEquals(101, reassembled.get(0).httpResponseCode);

        reassembled.clear();
        PayloadChunk wsChunk = makeChunk("websocket data", false);
        ra.handleChunk(wsChunk);
        assertEquals(PayloadChunk.ChunkType.WEBSOCKET, wsChunk.type);
        // no reassembled output because the raw bytes aren't a valid WebSocket frame
        assertEquals(0, reassembled.size());
    }

    @Test
    public void testNonHttpDetection() {
        HTTPReassembly ra = newReassembly();

        // feed data larger than MAX_HEADERS_SIZE (1024) without \r\n\r\n
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1100; i++)
            sb.append('X');
        byte[] data = sb.toString().getBytes();

        ra.handleChunk(new PayloadChunk(data, PayloadChunk.ChunkType.HTTP, true, 0, 0));

        assertEquals(1, reassembled.size());
        assertEquals(PayloadChunk.ChunkType.RAW, reassembled.get(0).type);
    }

    @Test
    public void testHeadersSplitAcrossChunks() {
        HTTPReassembly ra = newReassembly();

        // first chunk: partial headers (no \r\n\r\n yet)
        ra.handleChunk(makeChunk("GET /path HTTP/1.1\r\n", true));
        assertEquals(0, reassembled.size());

        // second chunk: remaining headers + end marker in same chunk
        ra.handleChunk(makeChunk("Host: example.org\r\n\r\n", true));
        assertEquals(1, reassembled.size());
        assertEquals("/path", reassembled.get(0).httpPath);
    }

    @Test
    public void testSequentialRequestResponse() {
        HTTPReassembly txRa = newReassembly();

        // first request
        txRa.handleChunk(makeChunk("GET /first HTTP/1.1\r\nHost: a.com\r\n\r\n", true));
        assertEquals(1, reassembled.size());
        assertEquals("/first", reassembled.get(0).httpPath);

        // second request on same reassembly (reset happens automatically)
        txRa.handleChunk(makeChunk("POST /second HTTP/1.1\r\nHost: b.com\r\n\r\n", true));
        assertEquals(2, reassembled.size());
        assertEquals("POST", reassembled.get(1).httpMethod);
        assertEquals("/second", reassembled.get(1).httpPath);
    }

    @Test
    public void testRequestWithBody() {
        HTTPReassembly ra = newReassembly();
        String body = "{\"key\":\"value\"}";
        String req = "POST /api HTTP/1.1\r\nHost: api.example.com\r\n" +
                "Content-Length: " + body.length() + "\r\n" +
                "Content-Type: application/json\r\n\r\n" + body;
        ra.handleChunk(makeChunk(req, true));

        assertEquals(1, reassembled.size());
        PayloadChunk c = reassembled.get(0);
        assertEquals("POST", c.httpMethod);
        assertEquals("/api", c.httpPath);
        assertEquals("application/json", c.httpContentType);
        assertEquals(body.length(), c.httpBodyLength);
        assertTrue(new String(c.payload).contains(body));
    }

    @Test
    public void testNoReassemblyMode() {
        reassembled.clear();
        HTTPReassembly ra = new HTTPReassembly(false, reassembled::add);

        ra.handleChunk(makeChunk("GET /path HTTP/1.1\r\nHost: example.org\r\n\r\nbody", true));
        // in non-reassembly mode, each chunk is passed through
        assertFalse(reassembled.isEmpty());
    }
}
