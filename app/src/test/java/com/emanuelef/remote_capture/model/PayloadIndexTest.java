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

package com.emanuelef.remote_capture.model;

import com.emanuelef.remote_capture.model.PayloadChunk.ChunkType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class PayloadIndexTest {
    private static final int PCAP_HDR_LEN = 24;
    private static final int PCAP_REC_LEN = 16;
    private static final int ETH_IP_UDP_LEN = 14 + 20 + 8;

    private File mPcapFile;
    private FileChannel mChannel;

    @Before
    public void setup() throws IOException {
        mPcapFile = File.createTempFile("payload_index", ".pcap");
    }

    @After
    public void tearDown() throws IOException {
        if (mChannel != null)
            mChannel.close();

        //noinspection ResultOfMethodCallIgnored
        mPcapFile.delete();
    }

    private ConnectionDescriptor newConn() {
        return new ConnectionDescriptor(1, 4, 17,
                "192.168.1.100", "93.184.216.34", "US",
                54321, 53, 0, 1000, 0, false, 0);
    }

    private void addChunks(ConnectionDescriptor conn, PayloadChunk... chunks) {
        ConnectionUpdate update = new ConnectionUpdate(conn.incr_id);
        update.setPayload(new ArrayList<>(Arrays.asList(chunks)), 0);
        conn.processUpdate(update);
    }

    private static PayloadChunk memChunk(String data, ChunkType type, boolean is_sent, long ts) {
        return new PayloadChunk(data.getBytes(StandardCharsets.US_ASCII), type, is_sent, ts, 0);
    }

    private static PayloadChunk diskChunk(long offset, int len, boolean is_sent, long ts, boolean printable) {
        return new PayloadChunk(ChunkType.RAW, is_sent, ts, 0, offset, len, printable);
    }

    // Writes a minimal PCAP file with the given UDP payloads and returns their file offsets
    private long[] writePcap(byte[]... payloads) throws IOException {
        long[] offsets = new long[payloads.length];
        ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);

        buf.putInt(0xa1b2c3d4);
        buf.putShort((short) 2);
        buf.putShort((short) 4);
        buf.putInt(0);
        buf.putInt(0);
        buf.putInt(65535);
        buf.putInt(1 /* ethernet */);

        for (int i = 0; i < payloads.length; i++) {
            int pkt_len = ETH_IP_UDP_LEN + payloads[i].length;

            buf.putInt(i);
            buf.putInt(0);
            buf.putInt(pkt_len);
            buf.putInt(pkt_len);
            buf.put(new byte[ETH_IP_UDP_LEN]);

            offsets[i] = buf.position();
            buf.put(payloads[i]);
        }

        try (FileOutputStream out = new FileOutputStream(mPcapFile)) {
            out.write(buf.array(), 0, buf.position());
        }

        return offsets;
    }

    @Test
    public void testMixedChunksKeepPositions() {
        ConnectionDescriptor conn = newConn();

        addChunks(conn,
                diskChunk(100, 10, true, 1000, false),
                memChunk("GET / HTTP/1.1\r\n\r\n", ChunkType.HTTP, true, 2000),
                diskChunk(200, 20, false, 3000, true));
        addChunks(conn, memChunk("raw", ChunkType.RAW, false, 4000));

        assertEquals(4, conn.getNumPayloadChunks());

        assertTrue(conn.isChunkOnDisk(0));
        assertFalse(conn.isChunkOnDisk(1));
        assertTrue(conn.isChunkOnDisk(2));
        assertFalse(conn.isChunkOnDisk(3));

        // chunks on disk are not returned by getPayloadChunk
        assertNull(conn.getPayloadChunk(0));
        assertNotNull(conn.getPayloadChunk(1));
        assertNull(conn.getPayloadChunk(2));
        assertEquals("raw", new String(conn.getPayloadChunk(3).payload, StandardCharsets.US_ASCII));

        assertEquals(ChunkType.RAW, conn.getChunkType(0));
        assertEquals(ChunkType.HTTP, conn.getChunkType(1));
        assertTrue(conn.isChunkSent(0));
        assertFalse(conn.isChunkSent(2));
        assertEquals(10, conn.getChunkLength(0));
        assertEquals(18, conn.getChunkLength(1));
        assertEquals(20, conn.getChunkLength(2));
        assertEquals(3000, conn.getChunkTimestamp(2));
        assertEquals(4000, conn.getChunkTimestamp(3));
    }

    @Test
    public void testInMemoryChunksBeforeDisk() {
        ConnectionDescriptor conn = newConn();

        for (int i = 0; i < 10; i++)
            addChunks(conn, memChunk("m" + i, ChunkType.HTTP, (i % 2) == 0, 1000 + i));

        assertFalse(conn.isChunkOnDisk(5));
        assertEquals(1005, conn.getChunkTimestamp(5));
        assertFalse(conn.isChunkSent(5));

        addChunks(conn, diskChunk(100, 30, true, 2000, false));

        assertEquals(11, conn.getNumPayloadChunks());
        assertTrue(conn.isChunkOnDisk(10));
        assertEquals(30, conn.getChunkLength(10));
        assertEquals(2000, conn.getChunkTimestamp(10));

        for (int i = 0; i < 10; i++) {
            assertFalse(conn.isChunkOnDisk(i));
            assertEquals(ChunkType.HTTP, conn.getChunkType(i));
            assertEquals((i % 2) == 0, conn.isChunkSent(i));
            assertEquals(1000 + i, conn.getChunkTimestamp(i));
            assertEquals(2, conn.getChunkLength(i));
        }
    }

    @Test
    public void testGrowth() {
        ConnectionDescriptor conn = newConn();
        int num_chunks = 1000;

        for (int i = 0; i < num_chunks; i++)
            addChunks(conn, diskChunk(i * 100L, i, (i % 2) == 0, i, false));

        assertEquals(num_chunks, conn.getNumPayloadChunks());

        for (int i = 0; i < num_chunks; i++) {
            assertEquals(i, conn.getChunkLength(i));
            assertEquals(i, conn.getChunkTimestamp(i));
            assertEquals((i % 2) == 0, conn.isChunkSent(i));
        }
    }

    @Test
    public void testPrecomputedMetadata() {
        ConnectionDescriptor conn = newConn();

        assertFalse(conn.hasHttpChunks());
        assertFalse(conn.hasHttpRequest());
        assertFalse(conn.hasHttpResponse());

        addChunks(conn,
                diskChunk(0, 10, true, 0, true),
                memChunk("HTTP/1.1 200 OK\r\n\r\n", ChunkType.HTTP, false, 0));

        assertTrue(conn.hasHttpChunks());
        assertFalse(conn.hasHttpRequest()); // the first sent chunk is RAW
        assertTrue(conn.hasHttpResponse());
        assertTrue(conn.isFirstChunkPrintable());

        // the printable flag only depends on the first chunk
        addChunks(conn, diskChunk(0, 10, true, 0, false));
        assertTrue(conn.isFirstChunkPrintable());
    }

    @Test
    public void testFirstChunkPrintableInMemory() {
        ConnectionDescriptor conn = newConn();
        addChunks(conn, new PayloadChunk(new byte[]{'a', 0x01, 'b'}, ChunkType.RAW, true, 0, 0));
        assertFalse(conn.isFirstChunkPrintable());

        conn = newConn();
        addChunks(conn, memChunk("hello\r\n", ChunkType.RAW, true, 0));
        assertTrue(conn.isFirstChunkPrintable());
    }

    @Test
    public void testHttpChunksSkipChunksOnDisk() {
        ConnectionDescriptor conn = newConn();

        addChunks(conn,
                diskChunk(0, 10, true, 0, false),
                memChunk("GET /index.html HTTP/1.1\r\nHost: example.org\r\n\r\n", ChunkType.HTTP, true, 0));

        assertTrue(conn.getHttpRequest().startsWith("GET /index.html"));
        assertNotNull(conn.getHttpRequestChunk(0));
    }

    @Test
    public void testDropPayload() {
        // live capture: all the chunks in memory, the index is cleared
        ConnectionDescriptor conn = newConn();
        addChunks(conn, memChunk("a", ChunkType.RAW, true, 0), memChunk("b", ChunkType.WEBSOCKET, false, 0));
        assertTrue(conn.hasWebsocketData());

        conn.dropPayload();
        assertEquals(0, conn.getNumPayloadChunks());
        assertFalse(conn.hasWebsocketData());

        // PCAP file: the positions are preserved, only the in-memory chunks are dropped
        conn = newConn();
        addChunks(conn, diskChunk(0, 10, true, 0, false), memChunk("HTTP/1.1 200 OK\r\n\r\n", ChunkType.HTTP, false, 0));
        assertTrue(conn.hasHttpChunks());
        assertTrue(conn.hasHttpResponse());

        conn.dropPayload();
        assertEquals(2, conn.getNumPayloadChunks());
        assertTrue(conn.isChunkOnDisk(0));
        assertNull(conn.getPayloadChunk(1));

        // the dropped HTTP chunks must not be reported anymore
        assertFalse(conn.hasHttpChunks());
        assertFalse(conn.hasHttpResponse());
        assertEquals(ChunkType.RAW, conn.getChunkType(1));
        assertFalse(conn.isChunkSent(1));
    }

    @Test
    public void testReadPayloadChunk() throws IOException {
        byte[] p1 = "first packet payload".getBytes(StandardCharsets.US_ASCII);
        byte[] p2 = new byte[]{0x00, 0x01, (byte) 0xff, 0x7f};
        long[] offsets = writePcap(p1, p2);

        assertEquals(PCAP_HDR_LEN + PCAP_REC_LEN + ETH_IP_UDP_LEN, offsets[0]);

        ConnectionDescriptor conn = newConn();
        addChunks(conn,
                diskChunk(offsets[0], p1.length, true, 10, true),
                memChunk("in memory", ChunkType.RAW, false, 20),
                diskChunk(offsets[1], p2.length, false, 30, false),
                diskChunk(offsets[1], 100 /* past EOF */, false, 40, false));

        // no file set
        assertNull(conn.readPayloadChunk(0, null));

        mChannel = new FileInputStream(mPcapFile).getChannel();

        PayloadChunk chunk = conn.readPayloadChunk(0, mChannel);
        assertNotNull(chunk);
        assertArrayEquals(p1, chunk.payload);
        assertEquals(ChunkType.RAW, chunk.type);
        assertTrue(chunk.is_sent);
        assertEquals(10, chunk.timestamp);

        // in-memory chunks are returned as is
        assertSame(conn.getPayloadChunk(1), conn.readPayloadChunk(1, mChannel));

        chunk = conn.readPayloadChunk(2, mChannel);
        assertNotNull(chunk);
        assertArrayEquals(p2, chunk.payload);
        assertFalse(chunk.is_sent);

        // truncated read
        chunk = conn.readPayloadChunk(0, mChannel, 5);
        assertNotNull(chunk);
        assertArrayEquals(Arrays.copyOf(p1, 5), chunk.payload);

        assertNull(conn.readPayloadChunk(3, mChannel));
        assertNull(conn.readPayloadChunk(4, mChannel));
    }
}
