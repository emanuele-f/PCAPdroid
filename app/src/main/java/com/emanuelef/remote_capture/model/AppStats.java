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
 * Copyright 2020-21 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.model;

import androidx.annotation.NonNull;

public class AppStats implements Cloneable {
    private final int uid;
    public long sentBytes;
    public long rcvdBytes;
    public int numConnections;
    public int numBlockedConnections;

    public AppStats(int _uid) {
        uid = _uid;
    }

    public int getUid() {
        return uid;
    }

    @NonNull
    public AppStats clone() {
        AppStats rv = new AppStats(uid);
        rv.sentBytes = sentBytes;
        rv.rcvdBytes = rcvdBytes;
        rv.numConnections = numConnections;
        rv.numBlockedConnections = numBlockedConnections;

        return rv;
    }
}
