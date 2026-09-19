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

package com.emanuelef.remote_capture.adapters;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Whitebox;
import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.PayloadChunk;
import com.emanuelef.remote_capture.model.PayloadChunk.ChunkType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;
import java.util.ArrayList;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PayloadAdapterTest {
    private Context context;
    private CaptureService service;
    private PayloadAdapter adapter;

    @Before
    public void setup() {
        context = ApplicationProvider.getApplicationContext();

        service = new CaptureService();
        Whitebox.setInternalState(service, "INSTANCE", service);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        CaptureSettings settings = new CaptureSettings(context, prefs);
        settings.full_payload = true;
        Whitebox.setInternalState(service, "mSettings", settings);

        ConnectionDescriptor conn = new ConnectionDescriptor(1, 4, 6,
                "192.168.1.100", "93.184.216.34", "US",
                54321, 443, 0, 1000, 0, false, System.currentTimeMillis());

        adapter = new PayloadAdapter(context, conn, ChunkType.HTTP, true);
    }

    @After
    public void tearDown() {
        Whitebox.setInternalState(service, "INSTANCE", null);
    }

    private PayloadChunk makeHttpRequest(int streamId) {
        byte[] payload = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes();
        return new PayloadChunk(payload, ChunkType.HTTP, true, System.currentTimeMillis(), streamId);
    }

    private PayloadChunk makeHttpResponse(int streamId) {
        byte[] payload = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes();
        return new PayloadChunk(payload, ChunkType.HTTP, false, System.currentTimeMillis(), streamId);
    }

    private PayloadChunk makeHttp2Rst(int streamId) {
        PayloadChunk chunk = new PayloadChunk(new byte[0], ChunkType.HTTP, false, System.currentTimeMillis(), streamId);
        chunk.setHttpRst();
        return chunk;
    }

    @SuppressWarnings("unchecked")
    private ArrayList<?> getChunks() {
        return (ArrayList<?>) Whitebox.getInternalState(adapter, "mChunks");
    }

    private PayloadChunk getChunkPayload(int index) {
        Object adapterChunk = getChunks().get(index);
        return (PayloadChunk) Whitebox.getInternalState(adapterChunk, "mChunk");
    }

    // ========== HTTP/1 sequential matching ==========

    @Test
    public void testHttp1SequentialMatching() {
        PayloadChunk req = makeHttpRequest(0);
        PayloadChunk res = makeHttpResponse(0);

        adapter.onChunkReassembled(req);
        adapter.onChunkReassembled(res);

        assertEquals(2, getChunks().size());
        // reply should be placed right after request
        assertTrue(getChunkPayload(0).is_sent);
        assertFalse(getChunkPayload(1).is_sent);
    }

    @Test
    public void testHttp1MultipleRequestsInOrder() {
        PayloadChunk req1 = makeHttpRequest(0);
        PayloadChunk req2 = makeHttpRequest(0);
        PayloadChunk res1 = makeHttpResponse(0);
        PayloadChunk res2 = makeHttpResponse(0);

        adapter.onChunkReassembled(req1);
        adapter.onChunkReassembled(req2);
        adapter.onChunkReassembled(res1);
        adapter.onChunkReassembled(res2);

        assertEquals(4, getChunks().size());
        // expect: req1, res1, req2, res2
        assertSame(req1, getChunkPayload(0));
        assertSame(res1, getChunkPayload(1));
        assertSame(req2, getChunkPayload(2));
        assertSame(res2, getChunkPayload(3));
    }

    // ========== HTTP/2 stream-based matching ==========

    @Test
    public void testHttp2OutOfOrderReplies() {
        PayloadChunk reqA = makeHttpRequest(1);
        PayloadChunk reqB = makeHttpRequest(3);
        // reply to stream 3 arrives before reply to stream 1
        PayloadChunk resB = makeHttpResponse(3);
        PayloadChunk resA = makeHttpResponse(1);

        adapter.onChunkReassembled(reqA);
        adapter.onChunkReassembled(reqB);
        adapter.onChunkReassembled(resB);
        adapter.onChunkReassembled(resA);

        assertEquals(4, getChunks().size());
        // expect: reqA, resA, reqB, resB — each reply right after its request
        assertSame(reqA, getChunkPayload(0));
        assertSame(resA, getChunkPayload(1));
        assertSame(reqB, getChunkPayload(2));
        assertSame(resB, getChunkPayload(3));
    }

    @Test
    public void testHttp2ThreeStreamsOutOfOrder() {
        PayloadChunk req1 = makeHttpRequest(1);
        PayloadChunk req3 = makeHttpRequest(3);
        PayloadChunk req5 = makeHttpRequest(5);
        // replies arrive in reverse order
        PayloadChunk res5 = makeHttpResponse(5);
        PayloadChunk res1 = makeHttpResponse(1);
        PayloadChunk res3 = makeHttpResponse(3);

        adapter.onChunkReassembled(req1);
        adapter.onChunkReassembled(req3);
        adapter.onChunkReassembled(req5);
        adapter.onChunkReassembled(res5);
        adapter.onChunkReassembled(res1);
        adapter.onChunkReassembled(res3);

        assertEquals(6, getChunks().size());
        // expect: req1, res1, req3, res3, req5, res5
        assertSame(req1, getChunkPayload(0));
        assertSame(res1, getChunkPayload(1));
        assertSame(req3, getChunkPayload(2));
        assertSame(res3, getChunkPayload(3));
        assertSame(req5, getChunkPayload(4));
        assertSame(res5, getChunkPayload(5));
    }

    @Test
    public void testHttp2RstMatchesByStream() {
        PayloadChunk req1 = makeHttpRequest(1);
        PayloadChunk req3 = makeHttpRequest(3);
        // RST for stream 3
        PayloadChunk rst3 = makeHttp2Rst(3);
        PayloadChunk res1 = makeHttpResponse(1);

        adapter.onChunkReassembled(req1);
        adapter.onChunkReassembled(req3);
        adapter.onChunkReassembled(rst3);
        adapter.onChunkReassembled(res1);

        // RST is not inserted into the list, so only 3 items
        assertEquals(3, getChunks().size());
        assertSame(req1, getChunkPayload(0));
        assertSame(res1, getChunkPayload(1));
        assertSame(req3, getChunkPayload(2));
    }

    // ========== Edge cases ==========

    @Test
    public void testUnmatchedReply() {
        // reply arrives with no pending request
        PayloadChunk res = makeHttpResponse(1);
        adapter.onChunkReassembled(res);

        // should be appended at the end
        assertEquals(1, getChunks().size());
        assertSame(res, getChunkPayload(0));
    }

    @Test
    public void testRequestOnly() {
        PayloadChunk req = makeHttpRequest(1);
        adapter.onChunkReassembled(req);

        assertEquals(1, getChunks().size());
        assertSame(req, getChunkPayload(0));
    }

    // ========== JSON pretty-printing ==========

    @Test
    public void testJsonBodyIsPrettyPrinted() {
        String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n";
        String raw = headers + "{\"name\":\"test\",\"value\":42}";
        String result = PayloadAdapter.formatHttpPayload(raw, "application/json");

        String expected = headers + "{\n  \"name\": \"test\",\n  \"value\": 42\n}";
        assertEquals(expected, result);
    }

    @Test
    public void testPostJsonBodyIsPrettyPrinted() {
        String headers = "POST /api/data HTTP/1.1\r\nHost: example.com\r\nContent-Type: application/json\r\n\r\n";
        String raw = headers + "{\"name\":\"test\",\"value\":42}";
        String result = PayloadAdapter.formatHttpPayload(raw, "application/json");

        String expected = headers + "{\n  \"name\": \"test\",\n  \"value\": 42\n}";
        assertEquals(expected, result);
    }

    @Test
    public void testJsonArrayIsPrettyPrinted() {
        String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n";
        String raw = headers + "[{\"id\":1},{\"id\":2}]";
        String result = PayloadAdapter.formatHttpPayload(raw, "application/json");

        String expected = headers + "[\n  {\n    \"id\": 1\n  },\n  {\n    \"id\": 2\n  }\n]";
        assertEquals(expected, result);
    }

    @Test
    public void testNonJsonContentTypeIsUnchanged() {
        String headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n";
        String raw = headers + "{\"name\":\"test\"}";
        assertEquals(raw, PayloadAdapter.formatHttpPayload(raw, "text/html"));
    }

    @Test
    public void testNullContentTypeIsUnchanged() {
        String headers = "HTTP/1.1 200 OK\r\n\r\n";
        String raw = headers + "{\"name\":\"test\"}";
        assertEquals(raw, PayloadAdapter.formatHttpPayload(raw, null));
    }

    @Test
    public void testInvalidJsonFallsBackToRaw() {
        String headers = "HTTP/1.1 200 OK\r\n\r\n";
        String raw = headers + "{\"name\":\"test\", truncated";
        assertEquals(raw, PayloadAdapter.formatHttpPayload(raw, "application/json"));
    }

    @Test
    public void testHeadersOnlyIsUnchanged() {
        String raw = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n";
        assertEquals(raw, PayloadAdapter.formatHttpPayload(raw, "application/json"));
    }

    @Test
    public void testLargePayloadIsNotFormatted() {
        String headers = "HTTP/1.1 200 OK\r\n\r\n";
        StringBuilder sb = new StringBuilder(headers);
        sb.append("{\"data\":\"");
        for (int i = 0; i < PayloadAdapter.MAX_JSON_FORMAT_SIZE; i++)
            sb.append('x');
        sb.append("\"}");

        String raw = sb.toString();
        assertEquals(raw, PayloadAdapter.formatHttpPayload(raw, "application/json"));
    }

    @Test
    public void testNonJsonBodyIsUnchanged() {
        String headers = "HTTP/1.1 200 OK\r\n\r\n";
        String raw = headers + "plain text body, not json";
        assertEquals(raw, PayloadAdapter.formatHttpPayload(raw, "application/json"));
    }

    @Test
    public void testHttp2InterleavedRequestsAndReplies() {
        PayloadChunk req1 = makeHttpRequest(1);
        PayloadChunk res1 = makeHttpResponse(1);
        PayloadChunk req3 = makeHttpRequest(3);
        PayloadChunk res3 = makeHttpResponse(3);

        adapter.onChunkReassembled(req1);
        adapter.onChunkReassembled(res1);
        adapter.onChunkReassembled(req3);
        adapter.onChunkReassembled(res3);

        assertEquals(4, getChunks().size());
        assertSame(req1, getChunkPayload(0));
        assertSame(res1, getChunkPayload(1));
        assertSame(req3, getChunkPayload(2));
        assertSame(res3, getChunkPayload(3));
    }

    // ========== Paging ==========

    private String getPageText(Object aChunk, int pageIdx) throws Exception {
        Method getPage = aChunk.getClass().getDeclaredMethod("getPage", int.class);
        getPage.setAccessible(true);
        Object page = getPage.invoke(aChunk, pageIdx);

        Method getText = page.getClass().getDeclaredMethod("getText");
        getText.setAccessible(true);
        return (String) getText.invoke(page);
    }

    private String getPagesText(Object aChunk) throws Exception {
        int numPages = (int) Whitebox.getInternalState(aChunk, "mNumPages");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < numPages; i++)
            sb.append(getPageText(aChunk, i));
        return sb.toString();
    }

    private Object expandChunk(PayloadChunk chunk, boolean as_printable) throws Exception {
        ConnectionDescriptor conn = new ConnectionDescriptor(3, 4, 6,
                "192.168.1.100", "93.184.216.34", "US",
                54321, 80, 0, 1000, 0, false, 0);

        ArrayList<PayloadChunk> chunks = new ArrayList<>();
        chunks.add(chunk);

        com.emanuelef.remote_capture.model.ConnectionUpdate update =
                new com.emanuelef.remote_capture.model.ConnectionUpdate(conn.incr_id);
        update.setPayload(chunks, 0);
        conn.processUpdate(update);

        adapter = new PayloadAdapter(context, conn, ChunkType.RAW, as_printable);
        Object aChunk = getChunks().get(0);

        Method expand = aChunk.getClass().getDeclaredMethod("expand");
        expand.setAccessible(true);
        expand.invoke(aChunk);

        assertTrue((int) Whitebox.getInternalState(aChunk, "mNumPages") > 1);
        return aChunk;
    }

    @Test
    public void testExpandedPagesKeepAllText() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3 * PayloadAdapter.VISUAL_PAGE_SIZE; i++)
            sb.append((char) ('a' + (i % 26)));
        String text = sb.toString();

        Object aChunk = expandChunk(new PayloadChunk(text.getBytes(), ChunkType.RAW, true, 0, 0), true);
        assertEquals(text, getPagesText(aChunk));
    }

    @Test
    public void testExpandedHexdumpPagesSkipPageBreakNewline() throws Exception {
        byte[] payload = new byte[4 * PayloadAdapter.VISUAL_PAGE_SIZE];
        Object aChunk = expandChunk(new PayloadChunk(payload, ChunkType.RAW, true, 0, 0), false);
        String firstPage = getPageText(aChunk, 0);

        // the page break replaces the newline of the last hexdump line
        assertEquals(PayloadAdapter.VISUAL_PAGE_SIZE - 1, firstPage.length());
        assertFalse(firstPage.endsWith("\n"));
    }
}
