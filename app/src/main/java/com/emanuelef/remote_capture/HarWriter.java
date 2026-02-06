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
import android.util.Base64;

import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.PayloadChunk;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Writes HTTP log data to HAR 1.2 format using streaming output.
 * See: http://www.softwareishard.com/blog/har-12-spec/
 */
public class HarWriter {
    private static final String TAG = "HarWriter";
    private final Context mContext;
    private final List<HttpLog.HttpRequest> mRequests;

    public HarWriter(Context context, List<HttpLog.HttpRequest> requests) {
        mContext = context;
        mRequests = requests;
    }

    public HarWriter(Context context, HttpLog.HttpRequest request) {
        mContext = context;
        mRequests = Collections.singletonList(request);
    }

    public void write(OutputStream out) throws IOException {
        JsonWriter writer = new JsonWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.setIndent("  ");

        writer.beginObject();
        writer.name("log");
        writeLog(writer);
        writer.endObject();

        writer.flush();
    }

    private void writeLog(JsonWriter writer) throws IOException {
        writer.beginObject();

        writer.name("version").value("1.2");

        writer.name("creator");
        writeCreator(writer);

        writer.name("entries");
        writeEntries(writer);

        writer.endObject();
    }

    private void writeCreator(JsonWriter writer) throws IOException {
        writer.beginObject();
        writer.name("name").value("PCAPdroid");
        writer.name("version").value(Utils.getAppVersion(mContext));
        writer.endObject();
    }

    private void writeEntries(JsonWriter writer) throws IOException {
        writer.beginArray();

        for (int i = 0; i < mRequests.size(); i++) {
            if (Thread.interrupted())
                throw new InterruptedIOException("Export cancelled");

            HttpLog.HttpRequest req = mRequests.get(i);
            if (req == null)
                continue;

            try {
                writeEntry(writer, req);
            } catch (Exception e) {
                Log.w(TAG, "Failed to serialize entry " + i + ": " + e.getMessage());
                e.printStackTrace();
            }
        }

        writer.endArray();
    }

    private void writeEntry(JsonWriter writer, HttpLog.HttpRequest req) throws IOException {
        ConnectionDescriptor conn = req.conn;

        writer.beginObject();
        writer.name("startedDateTime").value(Utils.formatMillisIso8601(mContext, req.timestamp));

        // time (total elapsed time in ms)
        long time = -1;
        if (req.reply != null) {
            PayloadChunk replyChunk = conn.getHttpResponseChunk(req.reply.firstChunkPos);
            if (replyChunk != null)
                time = replyChunk.timestamp - req.timestamp;
        }
        writer.name("time").value(time);

        writer.name("serverIPAddress").value(conn.dst_ip);
        writer.name("connection").value(String.valueOf(conn.incr_id));

        writer.name("request");
        writeRequest(writer, req);

        writer.name("response");
        writeResponse(writer, req);

        // cache (required, but empty as we don't track caching)
        writer.name("cache");
        writer.beginObject();
        writer.endObject();

        writer.name("timings");
        writeTimings(writer);

        writeWebSocketMessages(writer, conn, req);

        writer.endObject();
    }

    private void writeRequest(JsonWriter writer, HttpLog.HttpRequest req) throws IOException {
        ConnectionDescriptor conn = req.conn;

        writer.beginObject();
        writer.name("method").value(req.method != null ? req.method : "");
        writer.name("url").value(req.getUrl());

        PayloadChunk reqChunk = conn.getHttpRequestChunk(req.firstChunkPos);
        String httpVersion = "HTTP/1.1";
        List<String[]> requestHeaders = new ArrayList<>();
        int headersSize = -1;

        if (reqChunk != null) {
            if (!reqChunk.httpVersion.isEmpty())
                httpVersion = reqChunk.httpVersion;

            if (reqChunk.payload != null) {
                String httpText = new String(reqChunk.payload, StandardCharsets.UTF_8);
                requestHeaders = parseHeaders(httpText);
                headersSize = getHeadersSize(reqChunk.payload);
            }
        }

        writer.name("httpVersion").value(httpVersion);

        writer.name("cookies");
        writeRequestCookies(writer, requestHeaders);

        writer.name("headers");
        writeHeaders(writer, requestHeaders);

        writer.name("queryString");
        writeQueryString(writer, req.query);

        if ((req.method != null) && (req.method.equals("POST") || req.method.equals("PUT") || req.method.equals("PATCH"))) {
            writer.name("postData");
            writePostData(writer, reqChunk, requestHeaders);
        }

        writer.name("headersSize").value(headersSize);
        writer.name("bodySize").value(req.bodyLength);
        writer.endObject();
    }

    private void writeResponse(JsonWriter writer, HttpLog.HttpRequest req) throws IOException {
        HttpLog.HttpReply reply = req.reply;
        ConnectionDescriptor conn = req.conn;

        writer.beginObject();

        if ((reply == null) || req.httpRst) {
            // No response available - return minimal response object
            writer.name("status").value(0);
            writer.name("statusText").value("");
            writer.name("httpVersion").value("");
            writer.name("cookies");
            writer.beginArray();
            writer.endArray();
            writer.name("headers");
            writer.beginArray();
            writer.endArray();
            writer.name("content");
            writer.beginObject();
            writer.endObject();
            writer.name("redirectURL").value("");
            writer.name("headersSize").value(-1);
            writer.name("bodySize").value(-1);
            writer.endObject();
            return;
        }

        writer.name("status").value(reply.responseCode);
        writer.name("statusText").value(reply.responseStatus != null ? reply.responseStatus : "");

        PayloadChunk respChunk = conn.getHttpResponseChunk(reply.firstChunkPos);
        String httpVersion = "HTTP/1.1";
        List<String[]> responseHeaders = new ArrayList<>();
        int headersSize = -1;

        if (respChunk != null) {
            if (!respChunk.httpVersion.isEmpty())
                httpVersion = respChunk.httpVersion;

            if (respChunk.payload != null) {
                String httpText = new String(respChunk.payload, StandardCharsets.UTF_8);
                responseHeaders = parseHeaders(httpText);
                headersSize = getHeadersSize(respChunk.payload);
            }
        }

        writer.name("httpVersion").value(httpVersion);

        writer.name("cookies");
        writeResponseCookies(writer, responseHeaders);

        writer.name("headers");
        writeHeaders(writer, responseHeaders);

        writer.name("content");
        writeContent(writer, reply, respChunk);

        String redirectURL = getHeaderValue(responseHeaders, "location");
        writer.name("redirectURL").value(redirectURL != null ? redirectURL : "");
        writer.name("headersSize").value(headersSize);
        writer.name("bodySize").value(reply.bodyLength);
        writer.endObject();
    }

    private void writeContent(JsonWriter writer, HttpLog.HttpReply reply, PayloadChunk respChunk) throws IOException {
        writer.beginObject();
        writer.name("size").value(reply.bodyLength);

        String mimeType = reply.contentType != null ? reply.contentType : "application/octet-stream";
        writer.name("mimeType").value(mimeType);

        if ((respChunk != null) && (respChunk.payload != null)) {
            byte[] body = extractBody(respChunk.payload);
            if ((body != null) && (body.length > 0)) {
                if (isTextContent(body, reply.contentType))
                    writer.name("text").value(new String(body, StandardCharsets.UTF_8));
                else {
                    writer.name("text").value(Base64.encodeToString(body, Base64.NO_WRAP));
                    writer.name("encoding").value("base64");
                }
            }
        }

        writer.endObject();
    }

    private void writePostData(JsonWriter writer, PayloadChunk reqChunk, List<String[]> requestHeaders) throws IOException {
        writer.beginObject();

        String contentType = getHeaderValue(requestHeaders, "content-type");
        writer.name("mimeType").value(contentType != null ? contentType : "");

        if ((reqChunk != null) && (reqChunk.payload != null)) {
            byte[] body = extractBody(reqChunk.payload);
            if ((body != null) && (body.length > 0))
                writer.name("text").value(new String(body, StandardCharsets.UTF_8));
        }

        // params empty - we don't parse form data
        writer.name("params");
        writer.beginArray();
        writer.endArray();

        writer.endObject();
    }

    private void writeTimings(JsonWriter writer) throws IOException {
        writer.beginObject();

        writer.name("send").value(-1);
        writer.name("wait").value(-1);
        writer.name("receive").value(-1);

        // Optional fields
        writer.name("blocked").value(-1);
        writer.name("dns").value(-1);
        writer.name("connect").value(-1);
        writer.name("ssl").value(-1);

        writer.endObject();
    }

    private void writeHeaders(JsonWriter writer, List<String[]> headers) throws IOException {
        writer.beginArray();
        for (String[] header : headers) {
            writer.beginObject();
            writer.name("name").value(header[0]);
            writer.name("value").value(header[1]);
            writer.endObject();
        }
        writer.endArray();
    }

    private void writeQueryString(JsonWriter writer, String query) throws IOException {
        writer.beginArray();

        if ((query != null) && !query.isEmpty()) {
            // Remove leading '?'
            if (query.startsWith("?"))
                query = query.substring(1);

            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int eqPos = pair.indexOf('=');

                // Decode first, then write - to avoid leaving JSON in invalid state on exception
                String name, value;
                try {
                    if (eqPos > 0) {
                        name = URLDecoder.decode(pair.substring(0, eqPos), "UTF-8");
                        value = URLDecoder.decode(pair.substring(eqPos + 1), "UTF-8");
                    } else {
                        name = URLDecoder.decode(pair, "UTF-8");
                        value = "";
                    }
                } catch (Exception e) {
                    // Skip malformed parameter
                    continue;
                }

                writer.beginObject();
                writer.name("name").value(name);
                writer.name("value").value(value);
                writer.endObject();
            }
        }

        writer.endArray();
    }

    private void writeRequestCookies(JsonWriter writer, List<String[]> headers) throws IOException {
        writer.beginArray();

        for (String[] header : headers) {
            if (header[0].equalsIgnoreCase("Cookie")) {
                String value = header[1];

                // Parse: name1=value1; name2=value2
                String[] pairs = value.split(";");
                for (String pair : pairs) {
                    pair = pair.trim();

                    int eqPos = pair.indexOf('=');
                    if (eqPos > 0) {
                        writer.beginObject();
                        writer.name("name").value(pair.substring(0, eqPos));
                        writer.name("value").value(pair.substring(eqPos + 1));
                        writer.endObject();
                    }
                }
            }
        }

        writer.endArray();
    }

    private void writeResponseCookies(JsonWriter writer, List<String[]> headers) throws IOException {
        writer.beginArray();

        for (String[] header : headers) {
            if (header[0].equalsIgnoreCase("Set-Cookie")) {
                String value = header[1];

                // Parse: name=value; Path=/; Domain=.example.com; HttpOnly; Secure; SameSite=Lax
                String[] parts = value.split(";");

                // First part is name=value
                if (parts.length > 0) {
                    int eqPos = parts[0].indexOf('=');
                    if (eqPos > 0) {
                        writer.beginObject();
                        writer.name("name").value(parts[0].substring(0, eqPos).trim());
                        writer.name("value").value(parts[0].substring(eqPos + 1).trim());

                        // Parse attributes with defaults
                        String path = "/";
                        String domain = "";
                        boolean httpOnly = false;
                        boolean secure = false;
                        String sameSite = null;
                        String expires = null;

                        for (int i = 1; i < parts.length; i++) {
                            String attr = parts[i].trim();
                            String attrLower = attr.toLowerCase();

                            if (attrLower.startsWith("path="))
                                path = attr.substring(5);
                            else if (attrLower.startsWith("domain="))
                                domain = attr.substring(7);
                            else if (attrLower.equals("httponly"))
                                httpOnly = true;
                            else if (attrLower.equals("secure"))
                                secure = true;
                            else if (attrLower.startsWith("samesite="))
                                sameSite = attr.substring(9);
                            else if (attrLower.startsWith("expires="))
                                expires = attr.substring(8);
                        }

                        writer.name("path").value(path);
                        writer.name("domain").value(domain);
                        writer.name("httpOnly").value(httpOnly);
                        writer.name("secure").value(secure);
                        if (sameSite != null)
                            writer.name("sameSite").value(sameSite);
                        if (expires != null) {
                            String isoExpires = Utils.httpDateToIso8601(expires);
                            if (isoExpires != null)
                                writer.name("expires").value(isoExpires);
                        }

                        writer.endObject();
                    }
                }
            }
        }

        writer.endArray();
    }

    private List<String[]> parseHeaders(String httpText) {
        List<String[]> headers = new ArrayList<>();
        int headerEnd = Utils.getEndOfHTTPHeaders(httpText.getBytes(StandardCharsets.UTF_8));
        if (headerEnd == 0) headerEnd = httpText.length();

        String headerSection = httpText.substring(0, Math.min(headerEnd, httpText.length()));
        String[] lines = headerSection.split("\r\n");

        // Skip first line (request line or status line)
        for (int i = 1; i < lines.length; i++) {
            int colonPos = lines[i].indexOf(':');
            if (colonPos > 0) {
                headers.add(new String[]{
                        lines[i].substring(0, colonPos),
                        lines[i].substring(colonPos + 1).trim()
                });
            }
        }

        return headers;
    }

    private byte[] extractBody(byte[] payload) {
        int headerEnd = Utils.getEndOfHTTPHeaders(payload);
        if ((headerEnd <= 0) || (headerEnd >= payload.length))
            return null;

        byte[] body = new byte[payload.length - headerEnd];
        System.arraycopy(payload, headerEnd, body, 0, body.length);
        return body;
    }

    private int getHeadersSize(byte[] payload) {
        int headerEnd = Utils.getEndOfHTTPHeaders(payload);
        return headerEnd > 0 ? headerEnd : -1;
    }

    private boolean isTextContent(byte[] body, String contentType) {
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            if (ct.startsWith("text/") || ct.contains("json") ||
                ct.contains("xml") || ct.contains("javascript") || ct.contains("html"))
                return true;
            if (ct.startsWith("image/") || ct.startsWith("audio/") ||
                ct.startsWith("video/") || ct.equals("application/octet-stream"))
                return false;
        }

        // Check first bytes using Utils.isPrintable()
        int checkLen = Math.min(body.length, 16);
        for (int i = 0; i < checkLen; i++) {
            if (!Utils.isPrintable(body[i]))
                return false;
        }
        return true;
    }

    private String getHeaderValue(List<String[]> headers, String name) {
        for (String[] header : headers) {
            if (header[0].equalsIgnoreCase(name))
                return header[1];
        }
        return null;
    }

    /**
     * Add WebSocket messages to entry if this is a WebSocket connection.
     * Uses Chrome DevTools extension format.
     */
    private void writeWebSocketMessages(JsonWriter writer, ConnectionDescriptor conn, HttpLog.HttpRequest req) throws IOException {
        if ((req.reply == null) || !req.hasWebsocketData())
            return;

        ArrayList<PayloadChunk> wsChunks;

        if (CaptureService.isReadingFromPcapFile()) {
            // When reading from PCAP, chunks contain raw data that must be processed
            // through HTTPReassembly to decode WebSocket frames
            wsChunks = new ArrayList<>();
            HTTPReassembly.ReassemblyListener listener = chunk -> {
                if ((chunk.type == PayloadChunk.ChunkType.WEBSOCKET) &&
                        !WebSocketDecoder.isControlOpcode(chunk.wsOpcode))
                    wsChunks.add(chunk);
            };
            HTTPReassembly httpReq = new HTTPReassembly(true, listener);
            HTTPReassembly httpRes = new HTTPReassembly(true, listener);

            int startPos = req.firstChunkPos;
            synchronized (conn) {
                for (int i = startPos; i < conn.getNumPayloadChunks(); i++) {
                    PayloadChunk chunk = conn.getPayloadChunk(i);
                    if ((chunk == null) || (chunk.type == PayloadChunk.ChunkType.RAW))
                        continue;

                    if (chunk.is_sent)
                        httpReq.handleChunk(chunk);
                    else
                        httpRes.handleChunk(chunk);
                }
            }
        } else {
            // Live capture: chunks are already decoded by mitmproxy
            wsChunks = new ArrayList<>();
            int startPos = req.reply.firstChunkPos + 1;

            synchronized (conn) {
                for (int i = startPos; i < conn.getNumPayloadChunks(); i++) {
                    PayloadChunk chunk = conn.getPayloadChunk(i);
                    if ((chunk != null) && (chunk.type == PayloadChunk.ChunkType.WEBSOCKET))
                        wsChunks.add(chunk);
                }
            }
        }

        if (wsChunks.isEmpty())
            return;

        // Chrome DevTools extension
        writer.name("_resourceType").value("websocket");
        writer.name("_webSocketMessages");
        writer.beginArray();

        for (PayloadChunk chunk : wsChunks) {
            writer.beginObject();
            writer.name("type").value(chunk.is_sent ? "send" : "receive");
            writer.name("time").value(chunk.timestamp / 1000.0);

            // Use wsOpcode if available, otherwise guess from content
            int opcode;
            if (chunk.wsOpcode > 0)
                opcode = chunk.wsOpcode;
            else {
                boolean isText = (chunk.payload == null) || (chunk.payload.length == 0) ||
                        isTextContent(chunk.payload, null);
                opcode = isText ? WebSocketDecoder.OPCODE_TEXT : WebSocketDecoder.OPCODE_BINARY;
            }
            writer.name("opcode").value(opcode);

            if ((chunk.payload != null) && (chunk.payload.length > 0)) {
                if (opcode == WebSocketDecoder.OPCODE_TEXT)
                    writer.name("data").value(new String(chunk.payload, StandardCharsets.UTF_8));
                else
                    writer.name("data").value(Base64.encodeToString(chunk.payload, Base64.NO_WRAP));
            } else
                writer.name("data").value("");

            writer.endObject();
        }

        writer.endArray();
    }
}
