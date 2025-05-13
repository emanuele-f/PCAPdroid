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
 * Copyright 2020-25 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.pcap_dump;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.interfaces.PcapDumper;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Iterator;

public class TCPDumper implements PcapDumper {
    private static final String TAG = "TCPDumper";
    private final InetSocketAddress mServer;
    private final boolean mPcapngFormat;
    private boolean mSendHeader;
    private Socket mSocket;
    private DataOutputStream mDataOut;

    public TCPDumper(InetSocketAddress server, boolean pcapngFormat) {
        mServer = server;
        mSendHeader = true;
        mPcapngFormat = pcapngFormat;
    }

    @Override
    public void startDumper() throws IOException {
        mSocket = new Socket();
        boolean ok = false;

        try {
            mSocket.connect(mServer, 1000);
            mDataOut = new DataOutputStream(mSocket.getOutputStream());
            ok = true;
        } finally {
            if (!ok)
                mSocket.close();
        }

        CaptureService.requireInstance().protect(mSocket);
    }

    @Override
    public void stopDumper() throws IOException {
        try {
            mDataOut.close();
        } finally {
            mSocket.close();
        }
    }

    @Override
    public String getBpf() {
        return "not (host " + mServer.getAddress().getHostAddress() + " and tcp port " + mServer.getPort() + ")";
    }

    @Override
    public void dumpData(byte[] data) throws IOException {
        if(mSendHeader) {
            mSendHeader = false;

            byte[] hdr = CaptureService.getPcapHeader();
            mDataOut.write(hdr);
        }

        Iterator<Integer> it = Utils.iterPcapRecords(data, mPcapngFormat);
        int pos = 0;

        while(it.hasNext()) {
            int rec_len = it.next();
            mDataOut.write(data, pos, rec_len);
            pos += rec_len;
        }
    }
}
