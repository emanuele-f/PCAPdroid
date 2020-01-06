/*
    This file is part of PCAPdroid.

    PCAPdroid is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    PCAPdroid is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2020 by Emanuele Faranda
*/

package com.emanuelef.remote_capture;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class HTTPServer extends NanoHTTPD {
    private static final String PCAP_MIME = "application/vnd.tcpdump.pcap";
    private final DateFormat mFmt = new SimpleDateFormat("HH_mm_ss");
    private boolean firstStart = true;
    private boolean mAcceptConnections = false;
    private Context mContext;

    /* NOTE: access to mActiveResponses must be synchronized */
    private ArrayList<Response> mActiveResponses = new ArrayList<>();

    public HTTPServer(Context context, int port) {
        super(port);
        mContext = context;
    }

    private Response redirectToPcap() {
        String fname = "PCAPdroid_" + mFmt.format(new Date()) + ".pcap";
        Response r = newFixedLengthResponse(Status.TEMPORARY_REDIRECT, MIME_HTML, "");
        r.addHeader("Location", "/" + fname);
        return(r);
    }

    /* Creates a new Response and add it to the active responses. */
    private synchronized Response newPcapStream() {
        /* NOTE: response length is unknown */
        Response res = newChunkedResponse(Status.OK, PCAP_MIME, new ChunkedInputStream());

        mActiveResponses.add(res);

        return res;
    }

    @Override
    public void stop() {
        super.stop();
        firstStart = true;
    }

    public void startConnections() throws IOException {
        mAcceptConnections = true;

        if(firstStart) {
            start();
            firstStart = false;
        }
    }

    /* Marks data end on all the active connections */
    public synchronized void endConnections() {
        for(int i=mActiveResponses.size()-1; i >= 0; i--) {
            Response res = mActiveResponses.get(i);

            if(res.isCloseConnection()) {
                /* Cleanup closed connections */
                mActiveResponses.remove(i);
                continue;
            }

            ((ChunkedInputStream) res.getData()).stop();
        }

        mActiveResponses.clear();
        mAcceptConnections = false;
    }

    /* Dispatch PCAP data to the active connections */
    public synchronized void pushData(byte[] data) {
        for(int i=mActiveResponses.size()-1; i >= 0; i--) {
            Response res = mActiveResponses.get(i);

            if(res.isCloseConnection()) {
                /* Cleanup closed connections */
                mActiveResponses.remove(i);
                continue;
            }

            ((ChunkedInputStream) res.getData()).produceData(data);
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        if(!mAcceptConnections)
            return newFixedLengthResponse(Status.FORBIDDEN, MIME_PLAINTEXT,
                    mContext.getString(R.string.capture_not_started));

        if(session.getUri().endsWith("/")) {
            /* Use a redirect to provide a file name */
            return redirectToPcap();
        }

        return newPcapStream();
    }
}
