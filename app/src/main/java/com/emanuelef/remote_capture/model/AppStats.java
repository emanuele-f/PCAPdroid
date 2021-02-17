package com.emanuelef.remote_capture.model;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;

public class AppStats implements Cloneable {
    private final int uid;
    public long bytes;
    public int num_connections;

    public AppStats(int _uid) {
        uid = _uid;
        bytes = 0;
        num_connections = 0;
    }

    public int getUid() {
        return uid;
    }

    @NonNull
    public AppStats clone() {
        AppStats rv = new AppStats(uid);
        rv.bytes = bytes;
        rv.num_connections = num_connections;

        return rv;
    }
}
