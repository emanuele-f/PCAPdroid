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
    public void testZstdContentEncoding() {
        HTTPReassembly ra = newReassembly();

        // real zstd-compressed response from jsonplaceholder.typicode.com
        byte[] compressed = new byte[] {
            0x28, (byte)0xb5, 0x2f, (byte)0xfd, 0x00, 0x58, 0x25, 0x02,
            0x00, 0x42, 0x44, 0x0e, 0x14, (byte)0xa0, (byte)0xb5, 0x39,
            0x47, 0x20, (byte)0xb9, (byte)0xab, 0x33, 0x47, (byte)0xd7, 0x49,
            (byte)0xf0, (byte)0xf6, (byte)0xbd, (byte)0xdd, 0x14, 0x12, 0x55, (byte)0xc2,
            0x60, 0x01, (byte)0xe2, 0x65, (byte)0x86, 0x60, 0x29, (byte)0xf2,
            0x6d, (byte)0x86, 0x2a, 0x4e, (byte)0x95, 0x7f, (byte)0x86, 0x3f,
            (byte)0xc3, 0x75, 0x3f, 0x36, (byte)0xa3, (byte)0xb2, (byte)0xd4, (byte)0xf6,
            (byte)0xf4, 0x54, (byte)0x92, 0x04, 0x02, 0x4b, (byte)0xd1, 0x70,
            0x78, 0x5d, 0x49, 0x02, 0x08, 0x02, 0x00, 0x3c,
            0x25, 0x5c, 0x6f, (byte)0x85, 0x09
        };

        String headers = "HTTP/1.1 200 OK\r\nContent-Encoding: zstd\r\nContent-Length: "
                + compressed.length + "\r\n\r\n";
        byte[] headersBytes = headers.getBytes();
        byte[] full = new byte[headersBytes.length + compressed.length];
        System.arraycopy(headersBytes, 0, full, 0, headersBytes.length);
        System.arraycopy(compressed, 0, full, headersBytes.length, compressed.length);

        ra.handleChunk(new PayloadChunk(full, PayloadChunk.ChunkType.HTTP, false, 0, 0));

        assertEquals(1, reassembled.size());
        String payload = new String(reassembled.get(0).payload);
        assertTrue(payload.contains("delectus aut autem"));
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
