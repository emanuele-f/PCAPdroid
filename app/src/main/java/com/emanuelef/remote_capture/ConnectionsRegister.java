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

package com.emanuelef.remote_capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppStats;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.ConnectionsMatcher;
import com.emanuelef.remote_capture.model.Prefs;
import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConnectionsRegister {
    private static final String TAG = "ConnectionsRegister";

    private final ConnectionDescriptor[] mItemsRing;
    private int mTail;
    private final int mSize;
    private int mNumItems;
    private int mUntrackedItems;
    private final Map<Integer, AppStats> mAppsStats;
    private final ArrayList<ConnectionsListener> mListeners;
    private final SharedPreferences mPrefs;
    public final ConnectionsMatcher mExclusions;
    public boolean mExclusionsEnabled;

    public ConnectionsRegister(int _size, SharedPreferences prefs) {
        mTail = 0;
        mNumItems = 0;
        mUntrackedItems = 0;
        mSize = _size;
        mItemsRing = new ConnectionDescriptor[mSize];
        mListeners = new ArrayList<>();
        mAppsStats = new HashMap<>(); // uid -> AppStats
        mExclusionsEnabled = true;
        mPrefs = prefs;
        mExclusions = new ConnectionsMatcher();

        // Try to restore the exclusions
        String serialized = prefs.getString(Prefs.PREF_EXCLUSIONS, "");

        if(!serialized.isEmpty())
            mExclusions.fromJson(serialized);
    }

    private int firstPos() {
        return (mNumItems < mSize) ? 0 : mTail;
    }

    private int lastPos() {
        return (mTail - 1 + mSize) % mSize;
    }

    public synchronized void newConnections(ConnectionDescriptor[] conns) {
        if(conns.length > mSize) {
            // take the most recent
            mUntrackedItems += conns.length - mSize;
            conns = Arrays.copyOfRange(conns, conns.length - mSize, conns.length);
        }

        int in_items = Math.min((mSize - mNumItems), conns.length);
        int out_items = conns.length - in_items;
        int insert_pos = mNumItems;

        if(out_items > 0) {
            int pos = mTail;

            // update the apps stats
            for(int i=0; i<out_items; i++) {
                ConnectionDescriptor conn = mItemsRing[pos];

                if(conn != null) {
                    int uid = conn.uid;
                    AppStats stats = mAppsStats.get(uid);
                    stats.bytes -= conn.rcvd_bytes + conn.sent_bytes;

                    if(--stats.num_connections <= 0)
                        mAppsStats.remove(uid);
                }

                pos = (pos + 1) % mSize;
            }
        }

        for(ConnectionDescriptor conn: conns) {
            mItemsRing[mTail] = conn;
            mTail = (mTail + 1) % mSize;
            mNumItems = Math.min(mNumItems + 1, mSize);

            // update the apps stats
            int uid = conn.uid;
            AppStats stats = mAppsStats.get(uid);

            if(stats == null) {
                stats = new AppStats(uid);
                mAppsStats.put(uid, stats);
            }

            stats.num_connections++;
            stats.bytes += conn.rcvd_bytes + conn.sent_bytes;
        }

        mUntrackedItems += out_items;

        for(ConnectionsListener listener: mListeners) {
            if(out_items > 0)
                listener.connectionsRemoved(0, out_items);

            if(conns.length > 0)
                listener.connectionsAdded(insert_pos - out_items, conns.length);
        }
    }

    public synchronized void connectionsUpdates(ConnectionDescriptor[] conns) {
        int first_pos = firstPos();
        int first_id = mItemsRing[first_pos].incr_id;
        int last_id = mItemsRing[lastPos()].incr_id;
        int []changed_pos = new int[conns.length];
        int k = 0;

        Log.d(TAG, "connectionsUpdates: items=" + mNumItems + ", first_id=" + first_id + ", last_id=" + last_id);

        for(ConnectionDescriptor conn: conns) {
            int id = conn.incr_id;

            // ignore updates for untracked items
            if((id >= first_id) && (id <= last_id)) {
                int pos = ((id - first_id) + first_pos) % mSize;
                ConnectionDescriptor old = mItemsRing[pos];

                assert(old.incr_id == id);
                mItemsRing[pos] = conn;

                // update the apps stats
                long old_bytes = old.rcvd_bytes + old.sent_bytes;
                long bytes_delta = (conn.rcvd_bytes + conn.sent_bytes) - old_bytes;
                AppStats stats = mAppsStats.get(conn.uid);
                stats.bytes += bytes_delta;

                changed_pos[k++] = (pos + mSize - first_pos) % mSize;
            }
        }

        for(ConnectionsListener listener: mListeners) {
            if(k != conns.length) {
                // some untracked items where skipped, shrink the array
                changed_pos = Arrays.copyOf(changed_pos, k);
                k = conns.length;
            }

            listener.connectionsUpdated(changed_pos);
        }
    }

    public synchronized void reset() {
        for(int i = 0; i< mSize; i++)
            mItemsRing[i] = null;

        mNumItems = 0;
        mUntrackedItems = 0;
        mTail = 0;
        mAppsStats.clear();

        for(ConnectionsListener listener: mListeners)
            listener.connectionsChanges(mNumItems);
    }

    public synchronized void addListener(ConnectionsListener listener) {
        mListeners.add(listener);

        // Send the first update to sync it
        listener.connectionsChanges(mNumItems);

        Log.d(TAG, "(add) new connections listeners size: " + mListeners.size());
    }

    public synchronized void removeListener(ConnectionsListener listener) {
        mListeners.remove(listener);

        Log.d(TAG, "(remove) new connections listeners size: " + mListeners.size());
    }

    public int getConnCount() {
        return mNumItems;
    }

    public int getUntrackedConnCount() {
        return mUntrackedItems;
    }

    private synchronized ConnectionDescriptor getConnSimple(int i) {
        if(i >= mNumItems)
            return null;

        int pos = (firstPos() + i) % mSize;
        return mItemsRing[pos];
    }

    private synchronized ConnectionDescriptor getConnWithFilter(int uidFilter, int target_pos) {
        // pos is relative to the connections matching the provided uid / exclusions
        int first = firstPos();
        int virt_pos = 0;

        for(int i = 0; i < mNumItems; i++) {
            int pos = (first + i) % mSize;
            ConnectionDescriptor item = mItemsRing[pos];

            if(matches(item, uidFilter)) {
                if(virt_pos == target_pos)
                    return item;

                virt_pos++;
            }
        }

        return null;
    }

    public boolean hasExclusionFilter() {
        return(mExclusionsEnabled && !mExclusions.isEmpty());
    }

    private boolean matches(ConnectionDescriptor conn, int uidFilter) {
        return((conn != null)
                && ((uidFilter == Utils.UID_NO_FILTER) || (conn.uid == uidFilter))
                && (!mExclusionsEnabled || !mExclusions.matches(conn)));
    }

    public ConnectionDescriptor getConn(int pos, int uidFilter) {
        if((uidFilter == Utils.UID_NO_FILTER) && !hasExclusionFilter())
            return getConnSimple(pos);
        else
            return getConnWithFilter(uidFilter, pos);
    }

    public synchronized int getConnPositionByIncrId(int incr_id) {
        int first = firstPos();

        for(int i = 0; i < mNumItems; i++) {
            int pos = (first + i) % mSize;
            ConnectionDescriptor item = mItemsRing[pos];

            if((item != null) && (item.incr_id == incr_id)) {
                return pos;
            }
        }

        return -1;
    }

    public synchronized List<AppStats> getAppsStats() {
        ArrayList<AppStats> rv = new ArrayList<>(mAppsStats.size());

        for (Map.Entry<Integer, AppStats> pair : mAppsStats.entrySet()) {
            // Create a clone to avoid concurrency issues
            AppStats stats = pair.getValue().clone();

            rv.add(stats);
        }

        return rv;
    }

    public synchronized Set<Integer> getSeenUids() {
        HashSet<Integer> rv = new HashSet<>();

        for (Map.Entry<Integer, AppStats> pair : mAppsStats.entrySet()) {
            rv.add(pair.getKey());
        }

        return rv;
    }

    public synchronized int getFilteredConnCount(int uidFilter) {
        if(!hasExclusionFilter() && (uidFilter != Utils.UID_NO_FILTER)) {
            // Optimized
            AppStats stats = mAppsStats.get(uidFilter);

            if(stats == null)
                return 0;
            return stats.num_connections;
        } else {
            // TODO optimize
            int count = 0;

            for(int i = 0; i < mNumItems; i++) {
                ConnectionDescriptor item = mItemsRing[i];

                if(matches(item, uidFilter))
                    count++;
            }

            return count;
        }
    }

    public synchronized String dumpConnectionsCsv(Context context, int uidFilter) {
        StringBuilder builder = new StringBuilder();
        AppsResolver resolver = new AppsResolver(context);

        // Header
        builder.append(context.getString(R.string.connections_csv_fields_v1));
        builder.append("\n");

        // Contents
        for(int i=0; i<getConnCount(); i++) {
            ConnectionDescriptor conn = getConn(i, uidFilter);

            if(conn != null) {
                AppDescriptor app = resolver.get(conn.uid);

                builder.append(conn.ipproto);                               builder.append(",");
                builder.append(conn.src_ip);                                builder.append(",");
                builder.append(conn.src_port);                              builder.append(",");
                builder.append(conn.dst_ip);                                builder.append(",");
                builder.append(conn.dst_port);                              builder.append(",");
                builder.append(conn.uid);                                   builder.append(",");
                builder.append((app != null) ? app.getName() : "");         builder.append(",");
                builder.append(conn.l7proto);                               builder.append(",");
                builder.append(conn.getStatusLabel(context));               builder.append(",");
                builder.append((conn.info != null) ? conn.info : "");       builder.append(",");
                builder.append(conn.sent_bytes);                            builder.append(",");
                builder.append(conn.rcvd_bytes);                            builder.append(",");
                builder.append(conn.sent_pkts);                             builder.append(",");
                builder.append(conn.rcvd_pkts);                             builder.append(",");
                builder.append(conn.first_seen);                            builder.append(",");
                builder.append(conn.last_seen);                             builder.append("\n");
            }
        }

        return builder.toString();
    }

    public void saveExclusions() {
        mPrefs.edit()
                .putString(Prefs.PREF_EXCLUSIONS, mExclusions.toJson())
                .apply();
    }
}
