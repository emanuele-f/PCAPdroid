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
 * Copyright 2020-26 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import com.emanuelef.remote_capture.model.PayloadChunk;
import com.emanuelef.remote_capture.model.PayloadChunk.ChunkType;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class WebSocketDecoderTest {

    private WebSocketDecoder decoder;
    private List<PayloadChunk> receivedFrames;

    @Before
    public void setUp() {
        receivedFrames = new ArrayList<>();
        decoder = new WebSocketDecoder(decoded -> receivedFrames.add(decoded));
    }

    private static byte[] buildFrame(int opcode, boolean fin, boolean mask, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int byte0 = opcode & 0x0F;
        if (fin) byte0 |= 0x80;
        out.write(byte0);

        int payloadLen = (payload != null) ? payload.length : 0;
        int byte1 = mask ? 0x80 : 0x00;

        if (payloadLen <= 125) {
            byte1 |= payloadLen;
            out.write(byte1);
        } else if (payloadLen <= 65535) {
            byte1 |= 126;
            out.write(byte1);
            out.write((payloadLen >> 8) & 0xFF);
            out.write(payloadLen & 0xFF);
        } else {
            byte1 |= 127;
            out.write(byte1);
            for (int i = 7; i >= 0; i--) {
                out.write((int) (((long) payloadLen >> (i * 8)) & 0xFF));
            }
        }

        byte[] maskingKey = null;
        if (mask) {
            maskingKey = new byte[]{0x12, 0x34, 0x56, 0x78};
            out.write(maskingKey, 0, 4);
        }

        if ((payload != null) && (payload.length > 0)) {
            byte[] payloadToWrite = payload.clone();
            if (mask && (maskingKey != null)) {
                for (int i = 0; i < payloadToWrite.length; i++) {
                    payloadToWrite[i] = (byte) (payloadToWrite[i] ^ maskingKey[i % 4]);
                }
            }
            out.write(payloadToWrite, 0, payloadToWrite.length);
        }

        return out.toByteArray();
    }

    private static byte[] buildFrameWithRsv(int opcode, boolean fin, boolean mask, byte[] payload, int rsv) {
        byte[] frame = buildFrame(opcode, fin, mask, payload);
        frame[0] = (byte) ((frame[0] & 0x8F) | ((rsv & 0x07) << 4));
        return frame;
    }

    private PayloadChunk makeChunk(byte[] data, boolean isSent) {
        return new PayloadChunk(data, ChunkType.WEBSOCKET, isSent, System.currentTimeMillis(), 0);
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] arr : arrays) {
            out.write(arr, 0, arr.length);
        }
        return out.toByteArray();
    }

    // ========== Basic Frame Parsing ==========

    @Test
    public void testSingleTextFrame() {
        byte[] payload = "Hello".getBytes(StandardCharsets.UTF_8);
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        PayloadChunk result = receivedFrames.get(0);
        assertEquals(WebSocketDecoder.OPCODE_TEXT, result.wsOpcode);
        assertTrue(result.wsIsFinal);
        assertFalse(result.wsWasFragmented);
        assertArrayEquals(payload, result.payload);
    }

    @Test
    public void testSingleBinaryFrame() {
        byte[] payload = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_BINARY, true, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        PayloadChunk result = receivedFrames.get(0);
        assertEquals(WebSocketDecoder.OPCODE_BINARY, result.wsOpcode);
        assertArrayEquals(payload, result.payload);
    }

    @Test
    public void testMaskedFrame() {
        byte[] payload = "Masked message".getBytes(StandardCharsets.UTF_8);
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, true, payload);

        decoder.handleChunk(makeChunk(frame, true));

        assertEquals(1, receivedFrames.size());
        PayloadChunk result = receivedFrames.get(0);
        assertEquals(WebSocketDecoder.OPCODE_TEXT, result.wsOpcode);
        assertArrayEquals(payload, result.payload);
        assertEquals("Masked message", new String(result.payload, StandardCharsets.UTF_8));
    }

    @Test
    public void testEmptyPayload() {
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, new byte[0]);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        PayloadChunk result = receivedFrames.get(0);
        assertEquals(WebSocketDecoder.OPCODE_TEXT, result.wsOpcode);
        assertEquals(0, result.payload.length);
    }

    // ========== Extended Length ==========

    @Test
    public void testLength126() {
        byte[] payload = new byte[126];
        Arrays.fill(payload, (byte) 'A');
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertEquals(126, receivedFrames.get(0).payload.length);
    }

    @Test
    public void testLength127() {
        byte[] payload = new byte[70000];
        Arrays.fill(payload, (byte) 'B');
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_BINARY, true, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertEquals(70000, receivedFrames.get(0).payload.length);
    }

    @Test
    public void testLengthBoundary125() {
        byte[] payload = new byte[125];
        Arrays.fill(payload, (byte) 'C');
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertEquals(125, receivedFrames.get(0).payload.length);
    }

    @Test
    public void testLengthBoundary65535() {
        byte[] payload = new byte[65535];
        Arrays.fill(payload, (byte) 'D');
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_BINARY, true, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertEquals(65535, receivedFrames.get(0).payload.length);
    }

    // ========== Multiple Frames in Chunk ==========

    @Test
    public void testTwoFramesInChunk() {
        byte[] frame1 = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, "First".getBytes());
        byte[] frame2 = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, "Second".getBytes());
        byte[] combined = concat(frame1, frame2);

        decoder.handleChunk(makeChunk(combined, false));

        assertEquals(2, receivedFrames.size());
        assertEquals("First", new String(receivedFrames.get(0).payload, StandardCharsets.UTF_8));
        assertEquals("Second", new String(receivedFrames.get(1).payload, StandardCharsets.UTF_8));
    }

    @Test
    public void testThreeFramesInChunk() {
        byte[] frame1 = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, "Text".getBytes());
        byte[] frame2 = buildFrame(WebSocketDecoder.OPCODE_BINARY, true, false, new byte[]{1, 2, 3});
        byte[] frame3 = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, "More text".getBytes());
        byte[] combined = concat(frame1, frame2, frame3);

        decoder.handleChunk(makeChunk(combined, false));

        assertEquals(3, receivedFrames.size());
        assertEquals(WebSocketDecoder.OPCODE_TEXT, receivedFrames.get(0).wsOpcode);
        assertEquals(WebSocketDecoder.OPCODE_BINARY, receivedFrames.get(1).wsOpcode);
        assertEquals(WebSocketDecoder.OPCODE_TEXT, receivedFrames.get(2).wsOpcode);
    }

    @Test
    public void testMixedTextBinaryFrames() {
        byte[] text1 = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, "Hello".getBytes());
        byte[] binary = buildFrame(WebSocketDecoder.OPCODE_BINARY, true, false, new byte[]{(byte) 0xDE, (byte) 0xAD});
        byte[] text2 = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, "World".getBytes());
        byte[] combined = concat(text1, binary, text2);

        decoder.handleChunk(makeChunk(combined, false));

        assertEquals(3, receivedFrames.size());
        assertArrayEquals("Hello".getBytes(), receivedFrames.get(0).payload);
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD}, receivedFrames.get(1).payload);
        assertArrayEquals("World".getBytes(), receivedFrames.get(2).payload);
    }

    // ========== Frames Spanning Chunks ==========

    @Test
    public void testFrameSplitAtHeader() {
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, "Hello".getBytes());

        byte[] chunk1 = Arrays.copyOfRange(frame, 0, 1);
        byte[] chunk2 = Arrays.copyOfRange(frame, 1, frame.length);

        decoder.handleChunk(makeChunk(chunk1, false));
        assertEquals(0, receivedFrames.size());

        decoder.handleChunk(makeChunk(chunk2, false));
        assertEquals(1, receivedFrames.size());
        assertEquals("Hello", new String(receivedFrames.get(0).payload, StandardCharsets.UTF_8));
    }

    @Test
    public void testFrameSplitAtExtLength() {
        byte[] payload = new byte[200];
        Arrays.fill(payload, (byte) 'X');
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, payload);

        byte[] chunk1 = Arrays.copyOfRange(frame, 0, 3);
        byte[] chunk2 = Arrays.copyOfRange(frame, 3, frame.length);

        decoder.handleChunk(makeChunk(chunk1, false));
        assertEquals(0, receivedFrames.size());

        decoder.handleChunk(makeChunk(chunk2, false));
        assertEquals(1, receivedFrames.size());
        assertEquals(200, receivedFrames.get(0).payload.length);
    }

    @Test
    public void testFrameSplitAtMask() {
        byte[] payload = "Masked".getBytes();
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, true, payload);

        byte[] chunk1 = Arrays.copyOfRange(frame, 0, 4);
        byte[] chunk2 = Arrays.copyOfRange(frame, 4, frame.length);

        decoder.handleChunk(makeChunk(chunk1, true));
        assertEquals(0, receivedFrames.size());

        decoder.handleChunk(makeChunk(chunk2, true));
        assertEquals(1, receivedFrames.size());
        assertEquals("Masked", new String(receivedFrames.get(0).payload, StandardCharsets.UTF_8));
    }

    @Test
    public void testFrameSplitInPayload() {
        byte[] payload = "Hello World from WebSocket!".getBytes();
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, payload);

        int splitPoint = 2 + 10;
        byte[] chunk1 = Arrays.copyOfRange(frame, 0, splitPoint);
        byte[] chunk2 = Arrays.copyOfRange(frame, splitPoint, frame.length);

        decoder.handleChunk(makeChunk(chunk1, false));
        assertEquals(0, receivedFrames.size());

        decoder.handleChunk(makeChunk(chunk2, false));
        assertEquals(1, receivedFrames.size());
        assertArrayEquals(payload, receivedFrames.get(0).payload);
    }

    @Test
    public void testThreeChunkFrame() {
        byte[] payload = "This is a longer message split across three chunks".getBytes();
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, payload);

        int split1 = frame.length / 3;
        int split2 = 2 * frame.length / 3;

        byte[] chunk1 = Arrays.copyOfRange(frame, 0, split1);
        byte[] chunk2 = Arrays.copyOfRange(frame, split1, split2);
        byte[] chunk3 = Arrays.copyOfRange(frame, split2, frame.length);

        decoder.handleChunk(makeChunk(chunk1, false));
        assertEquals(0, receivedFrames.size());

        decoder.handleChunk(makeChunk(chunk2, false));
        assertEquals(0, receivedFrames.size());

        decoder.handleChunk(makeChunk(chunk3, false));
        assertEquals(1, receivedFrames.size());
        assertArrayEquals(payload, receivedFrames.get(0).payload);
    }

    // ========== Fragmented Messages ==========

    @Test
    public void testTwoFragments() {
        byte[] frag1 = buildFrame(WebSocketDecoder.OPCODE_TEXT, false, false, "Hello ".getBytes());
        byte[] frag2 = buildFrame(WebSocketDecoder.OPCODE_CONTINUATION, true, false, "World".getBytes());

        decoder.handleChunk(makeChunk(frag1, false));
        assertEquals(0, receivedFrames.size());

        decoder.handleChunk(makeChunk(frag2, false));
        assertEquals(1, receivedFrames.size());
        assertEquals("Hello World", new String(receivedFrames.get(0).payload, StandardCharsets.UTF_8));
        assertTrue(receivedFrames.get(0).wsWasFragmented);
        assertEquals(WebSocketDecoder.OPCODE_TEXT, receivedFrames.get(0).wsOpcode);
    }

    @Test
    public void testThreeFragments() {
        byte[] frag1 = buildFrame(WebSocketDecoder.OPCODE_BINARY, false, false, new byte[]{1, 2});
        byte[] frag2 = buildFrame(WebSocketDecoder.OPCODE_CONTINUATION, false, false, new byte[]{3, 4});
        byte[] frag3 = buildFrame(WebSocketDecoder.OPCODE_CONTINUATION, true, false, new byte[]{5, 6});

        decoder.handleChunk(makeChunk(frag1, false));
        decoder.handleChunk(makeChunk(frag2, false));
        assertEquals(0, receivedFrames.size());

        decoder.handleChunk(makeChunk(frag3, false));
        assertEquals(1, receivedFrames.size());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, receivedFrames.get(0).payload);
        assertTrue(receivedFrames.get(0).wsWasFragmented);
    }

    @Test
    public void testControlFrameBetweenFragments() {
        byte[] frag1 = buildFrame(WebSocketDecoder.OPCODE_TEXT, false, false, "Part1".getBytes());
        byte[] ping = buildFrame(WebSocketDecoder.OPCODE_PING, true, false, "ping-data".getBytes());
        byte[] frag2 = buildFrame(WebSocketDecoder.OPCODE_CONTINUATION, true, false, "Part2".getBytes());

        decoder.handleChunk(makeChunk(frag1, false));
        assertEquals(0, receivedFrames.size());

        decoder.handleChunk(makeChunk(ping, false));
        assertEquals(1, receivedFrames.size());
        assertEquals(WebSocketDecoder.OPCODE_PING, receivedFrames.get(0).wsOpcode);

        decoder.handleChunk(makeChunk(frag2, false));
        assertEquals(2, receivedFrames.size());
        assertEquals("Part1Part2", new String(receivedFrames.get(1).payload, StandardCharsets.UTF_8));
    }

    @Test
    public void testFragmentReassemblyPayload() {
        String part1 = "The quick brown fox ";
        String part2 = "jumps over ";
        String part3 = "the lazy dog.";

        byte[] frag1 = buildFrame(WebSocketDecoder.OPCODE_TEXT, false, false, part1.getBytes());
        byte[] frag2 = buildFrame(WebSocketDecoder.OPCODE_CONTINUATION, false, false, part2.getBytes());
        byte[] frag3 = buildFrame(WebSocketDecoder.OPCODE_CONTINUATION, true, false, part3.getBytes());

        decoder.handleChunk(makeChunk(frag1, false));
        decoder.handleChunk(makeChunk(frag2, false));
        decoder.handleChunk(makeChunk(frag3, false));

        assertEquals(1, receivedFrames.size());
        assertEquals(part1 + part2 + part3, new String(receivedFrames.get(0).payload, StandardCharsets.UTF_8));
    }

    // ========== Control Frames ==========

    @Test
    public void testPingFrame() {
        byte[] payload = "ping-test".getBytes();
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_PING, true, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertEquals(WebSocketDecoder.OPCODE_PING, receivedFrames.get(0).wsOpcode);
        assertArrayEquals(payload, receivedFrames.get(0).payload);
    }

    @Test
    public void testPongFrame() {
        byte[] payload = "pong-response".getBytes();
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_PONG, true, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertEquals(WebSocketDecoder.OPCODE_PONG, receivedFrames.get(0).wsOpcode);
        assertArrayEquals(payload, receivedFrames.get(0).payload);
    }

    @Test
    public void testCloseFrame() {
        byte[] payload = new byte[]{0x03, (byte) 0xE8};  // Status 1000 (normal closure)
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_CLOSE, true, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertEquals(WebSocketDecoder.OPCODE_CLOSE, receivedFrames.get(0).wsOpcode);
        assertEquals(2, receivedFrames.get(0).payload.length);
        int statusCode = ((receivedFrames.get(0).payload[0] & 0xFF) << 8) |
                         (receivedFrames.get(0).payload[1] & 0xFF);
        assertEquals(1000, statusCode);
    }

    @Test
    public void testCloseFrameWithReason() {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(0x03);
        payload.write((byte) 0xE8);
        byte[] reason = "Normal closure".getBytes(StandardCharsets.UTF_8);
        payload.write(reason, 0, reason.length);

        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_CLOSE, true, false, payload.toByteArray());

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertEquals(WebSocketDecoder.OPCODE_CLOSE, receivedFrames.get(0).wsOpcode);

        byte[] resultPayload = receivedFrames.get(0).payload;
        String reasonText = new String(resultPayload, 2, resultPayload.length - 2, StandardCharsets.UTF_8);
        assertEquals("Normal closure", reasonText);
    }

    // ========== Control Frame RFC Violations ==========

    @Test
    public void testControlFrameWithFinZero() {
        // RFC 6455 Section 5.5: Control frames MUST NOT be fragmented (FIN must be 1)
        // The decoder should still process it (for PCAP analysis) but this is a protocol violation
        byte[] payload = "ping".getBytes();
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_PING, false, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        // Frame should still be emitted (we're a PCAP analyzer, not a strict validator)
        assertEquals(1, receivedFrames.size());
        assertEquals(WebSocketDecoder.OPCODE_PING, receivedFrames.get(0).wsOpcode);
        assertFalse(receivedFrames.get(0).wsIsFinal);
        assertArrayEquals(payload, receivedFrames.get(0).payload);
    }

    @Test
    public void testControlFrameWithLargePayload() {
        // RFC 6455 Section 5.5: Control frames MUST have payload <= 125 bytes
        // The decoder should still process it (for PCAP analysis) but this is a protocol violation
        byte[] payload = new byte[130];
        Arrays.fill(payload, (byte) 'X');
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_PING, true, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        // Frame should still be emitted
        assertEquals(1, receivedFrames.size());
        assertEquals(WebSocketDecoder.OPCODE_PING, receivedFrames.get(0).wsOpcode);
        assertEquals(130, receivedFrames.get(0).payload.length);
    }

    @Test
    public void testCloseFrameWithFinZeroAndLargePayload() {
        // Both RFC violations combined
        byte[] payload = new byte[200];
        Arrays.fill(payload, (byte) 'Y');
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_CLOSE, false, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertEquals(WebSocketDecoder.OPCODE_CLOSE, receivedFrames.get(0).wsOpcode);
        assertFalse(receivedFrames.get(0).wsIsFinal);
        assertEquals(200, receivedFrames.get(0).payload.length);
    }

    // ========== Edge Cases ==========

    @Test
    public void testMaxLengthPayload() {
        byte[] payload = new byte[1024 * 1024];
        Arrays.fill(payload, (byte) 'Z');
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_BINARY, true, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertEquals(1024 * 1024, receivedFrames.get(0).payload.length);
    }

    @Test
    public void testInvalidOpcode() {
        byte[] payload = "test".getBytes();
        byte[] frame = buildFrame(0x03, true, false, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertEquals(0x03, receivedFrames.get(0).wsOpcode);
    }

    @Test
    public void testUnmaskedClientFrame() {
        byte[] payload = "Client unmasked".getBytes();
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, false, payload);

        decoder.handleChunk(makeChunk(frame, true));

        assertEquals(1, receivedFrames.size());
        assertArrayEquals(payload, receivedFrames.get(0).payload);
    }

    @Test
    public void testMaskedServerFrame() {
        byte[] payload = "Server masked".getBytes();
        byte[] frame = buildFrame(WebSocketDecoder.OPCODE_TEXT, true, true, payload);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertArrayEquals(payload, receivedFrames.get(0).payload);
    }

    @Test
    public void testNonZeroRsvBits() {
        byte[] payload = "RSV test".getBytes();
        byte[] frame = buildFrameWithRsv(WebSocketDecoder.OPCODE_TEXT, true, false, payload, 0x05);

        decoder.handleChunk(makeChunk(frame, false));

        assertEquals(1, receivedFrames.size());
        assertArrayEquals(payload, receivedFrames.get(0).payload);
    }

    @Test
    public void testEmptyChunk() {
        PayloadChunk emptyChunk = makeChunk(new byte[0], false);

        decoder.handleChunk(emptyChunk);

        assertEquals(0, receivedFrames.size());
    }

    // ========== Utility Methods ==========

    @Test
    public void testIsControlOpcode() {
        assertFalse(WebSocketDecoder.isControlOpcode(WebSocketDecoder.OPCODE_CONTINUATION));
        assertFalse(WebSocketDecoder.isControlOpcode(WebSocketDecoder.OPCODE_TEXT));
        assertFalse(WebSocketDecoder.isControlOpcode(WebSocketDecoder.OPCODE_BINARY));
        assertTrue(WebSocketDecoder.isControlOpcode(WebSocketDecoder.OPCODE_CLOSE));
        assertTrue(WebSocketDecoder.isControlOpcode(WebSocketDecoder.OPCODE_PING));
        assertTrue(WebSocketDecoder.isControlOpcode(WebSocketDecoder.OPCODE_PONG));
    }

    @Test
    public void testIsValidOpcode() {
        assertTrue(WebSocketDecoder.isValidOpcode(WebSocketDecoder.OPCODE_CONTINUATION));
        assertTrue(WebSocketDecoder.isValidOpcode(WebSocketDecoder.OPCODE_TEXT));
        assertTrue(WebSocketDecoder.isValidOpcode(WebSocketDecoder.OPCODE_BINARY));
        assertTrue(WebSocketDecoder.isValidOpcode(WebSocketDecoder.OPCODE_CLOSE));
        assertTrue(WebSocketDecoder.isValidOpcode(WebSocketDecoder.OPCODE_PING));
        assertTrue(WebSocketDecoder.isValidOpcode(WebSocketDecoder.OPCODE_PONG));
        assertFalse(WebSocketDecoder.isValidOpcode(0x03));
        assertFalse(WebSocketDecoder.isValidOpcode(0x0B));
    }
}
