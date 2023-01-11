package com.emanuelef.remote_capture.pcap_dump;

import android.content.Context;
import android.net.Uri;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.interfaces.PcapDumper;

import java.io.IOException;
import java.io.OutputStream;

public class FileDumper implements PcapDumper {
    public static final String TAG = "FileDumper";
    private final Context mContext;
    private final Uri mPcapUri;
    private boolean mSendHeader;
    private OutputStream mOutputStream;

    public FileDumper(Context ctx, Uri pcap_uri) {
        mContext = ctx;
        mPcapUri = pcap_uri;
        mSendHeader = true;
    }

    @Override
    public void startDumper() throws IOException {
        Log.d(TAG, "PCAP URI: " + mPcapUri);
        mOutputStream = mContext.getContentResolver().openOutputStream(mPcapUri, "rwt");
    }

    @Override
    public void stopDumper() throws IOException {
        mOutputStream.close();
    }

    @Override
    public String getBpf() {
        return "";
    }

    @Override
    public void dumpData(byte[] data) throws IOException {
        if(mSendHeader) {
            mSendHeader = false;
            mOutputStream.write(CaptureService.getPcapHeader());
        }

        mOutputStream.write(data);
    }
}
