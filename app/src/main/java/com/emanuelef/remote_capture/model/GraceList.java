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

import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.Log;

import java.util.Iterator;
import java.util.Map;

public class GraceList {
    private static final String TAG = "GraceList";
    private final ArrayMap<Integer, Long> mUidToGrace = new ArrayMap<>();

    // returns true if this is a new unblock
    public synchronized boolean unblockAppForMinutes(int uid, long minutes) {
        Long old_val = mUidToGrace.put(uid, SystemClock.uptimeMillis() + (minutes * 60_000));
        Log.d(TAG, "Grace app: " + uid + " for " + minutes + " minutes (old: " + old_val + ")");
        return (old_val == null);
    }

    public synchronized boolean checkGracePeriods() {
        long now = SystemClock.uptimeMillis();
        boolean changed = false;
        Iterator<Map.Entry<Integer,Long>> iter = mUidToGrace.entrySet().iterator();

        while(iter.hasNext()) {
            Map.Entry<Integer, Long> entry = iter.next();

            if(now >= entry.getValue()) {
                Log.d(TAG, "Grace period ended for app: " + entry.getKey());
                iter.remove();
                changed = true;
            }
        }

        return changed;
    }

    public synchronized boolean containsApp(int uid) {
        return mUidToGrace.containsKey(uid);
    }

    public synchronized void removeApp(int uid) {
        Log.d(TAG, "Manually remove app: " + uid);
        mUidToGrace.remove(uid);
    }
}
