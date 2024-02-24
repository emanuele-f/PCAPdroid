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
 * Copyright 2020-24 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.model.ConnectionDescriptor;

import java.util.ArrayList;

public class HttpLog {
    private final ArrayList<HttpRequest> mHttpRequests = new ArrayList<>();
    private Listener mListener;

    public static class HttpRequest {
        public final ConnectionDescriptor conn;
        public final int firstChunkPos;
        public String method = "";
        public String host = "";
        public String path = "";
        public String query = "";
        public HttpReply reply;
        public int bodyLength;
        public long timestamp;
        private int idx = -1;

        public HttpRequest(ConnectionDescriptor conn, int firstChunkPos) {
            this.conn = conn;
            this.firstChunkPos = firstChunkPos;
        }

        public int getPosition() {
            return idx;
        }

        public String getProtoAndHost() {
            String l7proto = conn.l7proto.toLowerCase();
            String proto = l7proto.startsWith("http") ? (l7proto + "://") : "";

            return proto + host;
        }

        public String getUrl() {
            return String.format("%s%s%s", getProtoAndHost(), path, query);
        }

        @Override
        @NonNull
        public String toString() {
            return "HTTP request: " + method + " " + getUrl();
        }
    }

    public static class HttpReply {
        public final HttpRequest request;
        public final int firstChunkPos;
        public String contentType;
        public String responseStatus;
        public int responseCode;
        public int bodyLength;

        public HttpReply(@NonNull HttpRequest in_reply_to, int firstChunkPos) {
            request = in_reply_to;
            this.firstChunkPos = firstChunkPos;
        }

        @Override
        @NonNull
        public String toString() {
            return "HTTP reply: " + responseCode + " " +
                    responseStatus + " - " + contentType + " - " +
                    bodyLength + " B";
        }
    }

    public interface Listener {
        void onHttpRequestAdded(int pos);
        void onHttpRequestUpdated(int pos);
        void onHttpRequestsClear();
    }

    public synchronized void setListener(Listener listener) {
        mListener = listener;
    }

    public synchronized void addHttpRequest(HttpRequest req) {
        req.idx = mHttpRequests.size();
        mHttpRequests.add(req);

        if (mListener != null)
            mListener.onHttpRequestAdded(req.idx);
    }

    public synchronized void addHttpReply(HttpReply reply) {
        assert (reply.request.reply == reply);

        if (mListener != null)
            // info from the HTTP reply is now available
            mListener.onHttpRequestUpdated(reply.request.idx);
    }

    public synchronized void clear() {
        mHttpRequests.clear();

        if (mListener != null)
            mListener.onHttpRequestsClear();
    }

    public synchronized @Nullable HttpRequest getRequest(int pos) {
        if ((pos < 0) || (pos >= mHttpRequests.size()))
            return null;
        return mHttpRequests.get(pos);
    }

    public synchronized int size() {
        return mHttpRequests.size();
    }
}
