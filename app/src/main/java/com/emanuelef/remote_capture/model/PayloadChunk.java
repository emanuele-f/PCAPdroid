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

package com.emanuelef.remote_capture.model;

import java.io.Serializable;

// A piece of payload. It may or may not correspond to a packet
public class PayloadChunk implements Serializable {
    public byte[] payload;
    public boolean is_sent;
    public long timestamp;
    public ChunkType type;
    public int stream_id;

    // PCAP file loading: the payload is not in memory, it must be read from the file (see PayloadIndex)
    public long file_offset = -1;
    public int file_len;
    public boolean file_printable;

    // HTTP
    public int httpResponseCode = 0;
    public String httpResponseStatus = "";
    public String httpMethod = "";
    public String httpHost = "";
    public String httpPath = "";
    public String httpQuery = "";
    public String httpContentType = "";
    public String httpVersion = "";
    public int httpBodyLength = 0;
    private boolean mHttpRst = false;

    // WebSocketDecoder data (when loading PCAP file)
    public int wsOpcode = -1;           // -1 = raw/undecoded, else opcode value
    public boolean wsIsFinal = true;    // FIN bit
    public boolean wsWasFragmented = false;  // True if reassembled from fragments

    // Serializable need in ConnectionPayload fragment
    public enum ChunkType implements Serializable {
        RAW,
        HTTP,
        WEBSOCKET
    }

    // the stream_id is the HTTP/2 stream ID; use 0 for HTTP/1
    public PayloadChunk(byte[] _payload, ChunkType _type, boolean _is_sent, long _timestamp, int _stream_id) {
        payload = _payload;
        type = _type;
        is_sent = _is_sent;
        timestamp = _timestamp;
        stream_id = _stream_id;
    }

    // NOTE: invoked from JNI
    public PayloadChunk(ChunkType _type, boolean _is_sent, long _timestamp, int _stream_id,
                        long _file_offset, int _len, boolean _printable) {
        this(null, _type, _is_sent, _timestamp, _stream_id);
        file_offset = _file_offset;
        file_len = _len;
        file_printable = _printable;
    }

    public PayloadChunk subchunk(int start, int size) {
        if (payload == null)
            return this;

        byte[] subarr = new byte[size];
        System.arraycopy(payload, start, subarr, 0, size);
        return new PayloadChunk(subarr, type, is_sent, timestamp, stream_id);
    }

    public PayloadChunk withPayload(byte[] the_payload) {
        return new PayloadChunk(the_payload, type, is_sent, timestamp, stream_id);
    }

    public void setHttpRst() {
        mHttpRst = true;
    }

    public boolean isHttp2Rst() {
        // http2.c uses a 0 length payload to indicate HTTP2 reset messages
        return mHttpRst || ((type == PayloadChunk.ChunkType.HTTP) &&
                (payload != null) && (payload.length == 0));
    }
}
