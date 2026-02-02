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

import android.util.Log;

import com.emanuelef.remote_capture.model.PayloadChunk;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

// RFC 6455 WebSocket frame decoder
public class WebSocketDecoder {
    private static final String TAG = "WebSocketDecoder";

    // RFC 6455 Section 5.2
    public static final int OPCODE_CONTINUATION = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    private static final int MAX_PENDING_CHUNKS = 100;
    private static final int MAX_FRAME_SIZE = 16 * 1024 * 1024;
    private static final int MAX_FRAGMENT_SIZE = 64 * 1024 * 1024;

    public enum ParseStatus {
        INCOMPLETE,
        SUCCESS,
        ERROR
    }

    public static class FrameParseResult {
        public ParseStatus status;
        public int opcode;
        public boolean fin;
        public byte[] payload;
        public int bytesConsumed;
        public String errorMessage;

        static FrameParseResult incomplete() {
            FrameParseResult r = new FrameParseResult();
            r.status = ParseStatus.INCOMPLETE;
            return r;
        }

        static FrameParseResult success(int opcode, boolean fin, byte[] payload, int bytesConsumed) {
            FrameParseResult r = new FrameParseResult();
            r.status = ParseStatus.SUCCESS;
            r.opcode = opcode;
            r.fin = fin;
            r.payload = payload;
            r.bytesConsumed = bytesConsumed;
            return r;
        }

        static FrameParseResult error(String message) {
            FrameParseResult r = new FrameParseResult();
            r.status = ParseStatus.ERROR;
            r.errorMessage = message;
            return r;
        }
    }

    public interface DecodedFrameListener {
        void onWebSocketFrame(PayloadChunk decoded);
    }

    private final DecodedFrameListener mListener;

    // Pending chunks waiting to form a complete frame
    private final ArrayList<PayloadChunk> mPendingChunks = new ArrayList<>();
    private int mPendingOffset;

    // Fragment reassembly state (for FIN=0 messages)
    private ByteArrayOutputStream mFragmentBuffer;
    private int mFragmentOpcode = -1;
    private long mFragmentTimestamp;
    private int mFragmentStreamId;

    public WebSocketDecoder(DecodedFrameListener listener) {
        mListener = listener;
    }

    public void handleChunk(PayloadChunk chunk) {
        if ((chunk.payload == null) || (chunk.payload.length == 0))
            return;

        if (mPendingChunks.size() >= MAX_PENDING_CHUNKS) {
            Log.w(TAG, "Too many pending chunks, emitting as raw");
            emitPendingAsRaw();
        }

        int pendingBytes = getTotalPendingBytes();
        if ((pendingBytes + chunk.payload.length) > MAX_FRAME_SIZE) {
            Log.w(TAG, "Pending bytes limit exceeded, emitting as raw");
            emitPendingAsRaw();
        }

        mPendingChunks.add(chunk);

        while (true) {
            FrameParseResult result = parseFrame(chunk.is_sent);

            if (result.status == ParseStatus.INCOMPLETE)
                break;

            if (result.status == ParseStatus.ERROR) {
                Log.w(TAG, "Frame parse error: " + result.errorMessage);
                emitPendingAsRaw();
                break;
            }

            consumeBytes(result.bytesConsumed);
            handleParsedFrame(result, chunk);
        }
    }

    private FrameParseResult parseFrame(boolean isSent) {
        int available = getTotalPendingBytes();

        if (available < 2)
            return FrameParseResult.incomplete();

        int byte0 = readByte(0) & 0xFF;
        int byte1 = readByte(1) & 0xFF;

        boolean fin = (byte0 & 0x80) != 0;
        int rsv = (byte0 >> 4) & 0x07;
        int opcode = byte0 & 0x0F;
        boolean masked = (byte1 & 0x80) != 0;
        int payloadLen = byte1 & 0x7F;

        if (rsv != 0)
            Log.d(TAG, "RSV bits set: " + rsv + " (might be extension)");

        if (isSent && !masked)
            Log.w(TAG, "Client frame should be masked but isn't");
        else if (!isSent && masked)
            Log.w(TAG, "Server frame should not be masked but is");

        int headerSize = 2;
        long actualPayloadLen = payloadLen;

        if (payloadLen == 126) {
            headerSize += 2;
            if (available < headerSize)
                return FrameParseResult.incomplete();
            actualPayloadLen = ((readByte(2) & 0xFF) << 8) | (readByte(3) & 0xFF);
        } else if (payloadLen == 127) {
            headerSize += 8;
            if (available < headerSize)
                return FrameParseResult.incomplete();
            actualPayloadLen = 0;
            for (int i = 0; i < 8; i++)
                actualPayloadLen = (actualPayloadLen << 8) | (readByte(2 + i) & 0xFF);
            if (actualPayloadLen < 0)
                return FrameParseResult.error("Payload length overflow");
        }

        if (actualPayloadLen > MAX_FRAME_SIZE)
            return FrameParseResult.error("Payload too large: " + actualPayloadLen);

        // RFC 6455 Section 5.5: Control frame validation
        if (opcode >= OPCODE_CLOSE) {
            if (!fin)
                Log.w(TAG, "Control frame with FIN=0 (RFC violation: control frames MUST NOT be fragmented)");
            if (actualPayloadLen > 125)
                Log.w(TAG, "Control frame payload " + actualPayloadLen + " > 125 bytes (RFC violation)");
        }

        byte[] maskingKey = null;
        if (masked) {
            if (available < (headerSize + 4))
                return FrameParseResult.incomplete();
            maskingKey = new byte[4];
            for (int i = 0; i < 4; i++)
                maskingKey[i] = readByte(headerSize + i);
            headerSize += 4;
        }

        int totalFrameSize = headerSize + (int) actualPayloadLen;
        if (available < totalFrameSize)
            return FrameParseResult.incomplete();

        byte[] payload = new byte[(int) actualPayloadLen];
        for (int i = 0; i < actualPayloadLen; i++)
            payload[i] = readByte(headerSize + i);

        if (masked)
            unmaskPayload(payload, maskingKey);

        return FrameParseResult.success(opcode, fin, payload, totalFrameSize);
    }

    private void handleParsedFrame(FrameParseResult result, PayloadChunk originalChunk) {
        int opcode = result.opcode;
        boolean fin = result.fin;
        byte[] payload = result.payload;

        // Control frames are always complete and can be interleaved with fragments
        if (opcode >= OPCODE_CLOSE) {
            emitFrame(opcode, fin, payload, false, originalChunk);
            return;
        }

        if ((opcode != OPCODE_CONTINUATION) && !fin) {
            if (mFragmentBuffer != null)
                Log.w(TAG, "New fragment started while previous incomplete");
            mFragmentBuffer = new ByteArrayOutputStream();
            mFragmentOpcode = opcode;
            mFragmentTimestamp = originalChunk.timestamp;
            mFragmentStreamId = originalChunk.stream_id;
            try {
                mFragmentBuffer.write(payload);
            } catch (Exception e) {
                Log.e(TAG, "Error writing to fragment buffer", e);
            }
            return;
        }

        if (opcode == OPCODE_CONTINUATION) {
            if (mFragmentBuffer == null) {
                Log.w(TAG, "Continuation frame without start");
                emitFrame(opcode, fin, payload, false, originalChunk);
                return;
            }

            if ((mFragmentBuffer.size() + payload.length) > MAX_FRAGMENT_SIZE) {
                Log.w(TAG, "Fragment size limit exceeded");
                mFragmentBuffer = null;
                return;
            }

            try {
                mFragmentBuffer.write(payload);
            } catch (Exception e) {
                Log.e(TAG, "Error writing to fragment buffer", e);
            }

            if (fin) {
                byte[] reassembled = mFragmentBuffer.toByteArray();
                PayloadChunk chunk = createDecodedChunk(
                    mFragmentOpcode, true, reassembled, true,
                    originalChunk.is_sent, mFragmentTimestamp, mFragmentStreamId
                );
                mFragmentBuffer = null;
                mFragmentOpcode = -1;
                mListener.onWebSocketFrame(chunk);
            }
            return;
        }

        emitFrame(opcode, fin, payload, false, originalChunk);
    }

    private void emitFrame(int opcode, boolean fin, byte[] payload, boolean wasFragmented, PayloadChunk originalChunk) {
        PayloadChunk chunk = createDecodedChunk(
            opcode, fin, payload, wasFragmented,
            originalChunk.is_sent, originalChunk.timestamp, originalChunk.stream_id
        );
        mListener.onWebSocketFrame(chunk);
    }

    private PayloadChunk createDecodedChunk(int opcode, boolean fin, byte[] payload, boolean wasFragmented,
                                            boolean isSent, long timestamp, int streamId) {
        PayloadChunk chunk = new PayloadChunk(payload, PayloadChunk.ChunkType.WEBSOCKET, isSent, timestamp, streamId);
        chunk.wsOpcode = opcode;
        chunk.wsIsFinal = fin;
        chunk.wsWasFragmented = wasFragmented;
        return chunk;
    }

    private void emitPendingAsRaw() {
        for (PayloadChunk chunk : mPendingChunks) {
            chunk.type = PayloadChunk.ChunkType.RAW;
            chunk.wsOpcode = -1;
            mListener.onWebSocketFrame(chunk);
        }
        mPendingChunks.clear();
        mPendingOffset = 0;
    }

    private int getTotalPendingBytes() {
        int total = -mPendingOffset;
        for (PayloadChunk c : mPendingChunks)
            total += c.payload.length;
        return total;
    }

    private byte readByte(int pos) {
        int remaining = pos;
        boolean first = true;
        for (PayloadChunk c : mPendingChunks) {
            int start = first ? mPendingOffset : 0;
            int available = c.payload.length - start;
            if (remaining < available)
                return c.payload[start + remaining];
            remaining -= available;
            first = false;
        }
        throw new IndexOutOfBoundsException("Position " + pos + " out of bounds");
    }

    private void consumeBytes(int count) {
        while ((count > 0) && !mPendingChunks.isEmpty()) {
            PayloadChunk first = mPendingChunks.get(0);
            int available = first.payload.length - mPendingOffset;
            if (count >= available) {
                mPendingChunks.remove(0);
                mPendingOffset = 0;
                count -= available;
            } else {
                mPendingOffset += count;
                count = 0;
            }
        }
    }

    private static void unmaskPayload(byte[] payload, byte[] maskingKey) {
        for (int i = 0; i < payload.length; i++)
            payload[i] = (byte) (payload[i] ^ maskingKey[i % 4]);
    }

    public static boolean isControlOpcode(int opcode) {
        return opcode >= OPCODE_CLOSE;
    }

    public static boolean isValidOpcode(int opcode) {
        return (opcode == OPCODE_CONTINUATION) ||
               (opcode == OPCODE_TEXT) ||
               (opcode == OPCODE_BINARY) ||
               (opcode == OPCODE_CLOSE) ||
               (opcode == OPCODE_PING) ||
               (opcode == OPCODE_PONG);
    }
}
