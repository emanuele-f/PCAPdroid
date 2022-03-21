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
 * Copyright 2022 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.util.Log;

import com.emanuelef.remote_capture.model.PayloadChunk;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.zip.GZIPInputStream;

public class HTTPReassembly {
    private static final String TAG = "HTTPReassembly";
    private static final int MAX_HEADERS_SIZE = 1024;
    private boolean mReadingHeaders;
    private boolean mGzipEncoding;
    private boolean mChunkedEncoding;
    private int mContentLength;
    private int mHeadersSize;
    private final ArrayList<PayloadChunk> mHeaders = new ArrayList<>();
    private final ArrayList<PayloadChunk> mBody = new ArrayList<>();
    private final ReassemblyListener mListener;
    private boolean mReassembleChunks;
    private boolean mInvalidHttp;

    public HTTPReassembly(boolean reassembleChunks, ReassemblyListener listener) {
        mListener = listener;
        mReassembleChunks = reassembleChunks;
        reset();
    }

    private void reset() {
        mReadingHeaders = true;
        mGzipEncoding = false;
        mChunkedEncoding = false;
        mContentLength = -1;
        mHeadersSize = 0;
        mHeaders.clear();
        mBody.clear();

        // Do not reset, these affects the whole connection
        //upgradeFound = false;
        //mInvalidHttp = false;
    }

    public interface ReassemblyListener {
        void onChunkReassembled(PayloadChunk chunk);
    }

    /* The request/response tab shows reassembled HTTP chunks.
     * Reassembling chunks is requires when using a content-encoding like gzip since we can only
     * decode the data when we have the full chunk and we cannot determine data bounds.
     * When reading data via the MitmReceiver, mitmproxy already performs chunks reassembly and
     * also handles HTTP/2 so that we only get the payload. */
    public void handleChunk(PayloadChunk chunk) {
        int body_start = 0;
        byte[] payload = chunk.payload;
        boolean chunked_complete = false;

        if(mReadingHeaders) {
            // Reading the HTTP headers
            int headers_end = Utils.getEndOfHTTPHeaders(payload);
            int headers_size = (headers_end == 0) ? payload.length : headers_end;
            mHeadersSize += headers_size;

            try(BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(payload, 0, headers_size)))) {
                String line = reader.readLine();
                while((line != null) && (line.length() > 0)) {
                    line = line.toLowerCase();
                    //Log.d(TAG, "[HEADER] " + line);

                    if(line.startsWith("content-encoding: ")) {
                        String contentEncoding = line.substring(18);
                        Log.d(TAG, "Content-Encoding: " + contentEncoding);

                        mGzipEncoding = contentEncoding.equals("gzip");
                    } else if(line.startsWith("content-type: ")) {
                        String contentType = line.substring(14);
                        Log.d(TAG, "Content-Type: " + contentType);
                    } else if(line.startsWith("content-length: ")) {
                        try {
                            int contentLength = Integer.parseInt(line.substring(16));
                            Log.d(TAG, "Content-Length: " + contentLength);
                        } catch (NumberFormatException ignored) {}
                    } else if(line.startsWith("upgrade: ")) {
                        Log.d(TAG, "Upgrade found, stop parsing");
                        mReassembleChunks = false;
                    } else if(line.equals("transfer-encoding: chunked")) {
                        Log.d(TAG, "Detected chunked encoding");
                        mChunkedEncoding = true;
                    }

                    line = reader.readLine();
                }
            } catch (IOException ignored) {}

            if(headers_end > 0) {
                mReadingHeaders = false;
                body_start = headers_end;
                mHeaders.add(chunk.subchunk(0, body_start));
            } else {
                if(mHeadersSize > MAX_HEADERS_SIZE) {
                    // Assume this is not valid HTTP traffic
                    mReadingHeaders = false;
                    mReassembleChunks = false;
                    mInvalidHttp = true;
                }

                // Headers span all the packet
                mHeaders.add(chunk);
                body_start = payload.length;
            }
        }

        // If not Content-Length provided and not using chunked encoding, then we cannot determine
        // chunks bounds, so disable reassembly
        if(!mReadingHeaders && (mContentLength < 0) && (!mChunkedEncoding))
            mReassembleChunks = false;

        // When mReassembleChunks is false, each chunk should be passed to the mListener
        if(!mReassembleChunks)
            mReadingHeaders = false;

        if(!mReadingHeaders) {
            // Reading HTTP body
            int body_size = payload.length - body_start;
            int new_body_start = -1;

            if(mChunkedEncoding && (mContentLength < 0) && (body_size > 0)) {
                try(BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(payload, body_start, body_size)))) {
                    String line = reader.readLine();
                    if(line != null) {
                        try {
                            // Each chunk starts with the chunk length
                            mContentLength = Integer.parseInt(line, 16);
                            body_start += line.length() + 2;
                            body_size -= line.length() + 2;

                            Log.d(TAG, "Chunk length: " + mContentLength);

                            if(mContentLength == 0)
                                chunked_complete = true;
                        } catch (NumberFormatException ignored) {}
                    }
                } catch (IOException ignored) {}
            }

            // NOTE: Content-Length is optional in HTTP/2.0, mitmproxy reconstructs the entire message
            if(body_size > 0) {
                if(mContentLength > 0) {
                    if(body_size < mContentLength)
                        mContentLength -= body_size;
                    else {
                        body_size = mContentLength;
                        new_body_start = body_start + mContentLength;
                        mContentLength = -1;

                        // With chunked encoding, skip the trailing \r\n
                        if(mChunkedEncoding)
                            new_body_start += 2;
                    }
                }

                if((body_start == 0) && (body_size == chunk.payload.length))
                    mBody.add(chunk);
                else
                    mBody.add(chunk.subchunk(body_start, body_size));
            }

            if(chunked_complete || !mReassembleChunks)
                mChunkedEncoding = false;

            if(((mContentLength <= 0) || !mReassembleChunks) && !mChunkedEncoding) {
                // Reassemble the chunks (NOTE: gzip is applied only after all the chunks are collected)
                PayloadChunk headers = reassembleChunks(mHeaders);
                PayloadChunk body = mBody.size() > 0 ? reassembleChunks(mBody) : null;

                if((body != null) && mGzipEncoding) {
                    // Decode the body
                    try(GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(body.payload))) {
                        try(ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                            byte[] buf = new byte[1024];
                            int read;

                            while ((read = gzipInputStream.read(buf)) != -1)
                                bos.write(buf, 0, read);

                            body.payload = bos.toByteArray();
                        }
                    } catch (IOException ignored) {
                        Log.d(TAG, "GZIP decoding failed");
                    }
                }

                PayloadChunk to_add;

                if(body != null) {
                    // Reassemble headers and body into a single chunk
                    byte[] reassembly = new byte[headers.payload.length + body.payload.length];
                    System.arraycopy(headers.payload, 0, reassembly, 0, headers.payload.length);
                    System.arraycopy(body.payload, 0, reassembly, headers.payload.length, body.payload.length);

                    to_add = body.withPayload(reassembly);
                } else
                    to_add = headers;

                if(mInvalidHttp)
                    to_add.type = PayloadChunk.ChunkType.RAW;

                mListener.onChunkReassembled(to_add);
                reset(); // mReadingHeaders = true
            }

            if((new_body_start > 0) && (chunk.payload.length > new_body_start)) {
                // Part of this chunk should be processed as a new chunk
                Log.d(TAG, "Continue from " + new_body_start);
                handleChunk(chunk.subchunk(new_body_start, chunk.payload.length - new_body_start));
            }
        }
    }

    private PayloadChunk reassembleChunks(ArrayList<PayloadChunk> chunks) {
        if(chunks.size() == 1)
            return chunks.get(0);

        int size = 0;
        for(PayloadChunk chunk: chunks)
            size += chunk.payload.length;

        byte[] reassembly = new byte[size];
        int sofar = 0;

        for(PayloadChunk chunk: chunks) {
            System.arraycopy(chunk.payload, 0, reassembly, sofar, chunk.payload.length);
            sofar += chunk.payload.length;
        }

        return chunks.get(0).withPayload(reassembly);
    }
}