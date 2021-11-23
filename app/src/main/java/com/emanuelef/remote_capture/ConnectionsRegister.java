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
import android.util.Log;

import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.AppStats;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.ConnectionUpdate;
import com.emanuelef.remote_capture.model.MatchList;

import java.net.InetAddress;
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
    private int mNumMalicious;
    private final Map<Integer, AppStats> mAppsStats;
    private final ArrayList<ConnectionsListener> mListeners;
    private final MatchList mWhitelist;
    private final Geolocation mGeo;

    public ConnectionsRegister(Context ctx, int _size) {
        mTail = 0;
        mNumItems = 0;
        mUntrackedItems = 0;
        mSize = _size;
        mGeo = new Geolocation(ctx);
        mItemsRing = new ConnectionDescriptor[mSize];
        mListeners = new ArrayList<>();
        mAppsStats = new HashMap<>(); // uid -> AppStats
        mWhitelist = PCAPdroid.getInstance().getMalwareWhitelist();
    }

    private int firstPos() {
        return (mNumItems < mSize) ? 0 : mTail;
    }

    private int lastPos() {
        return (mTail - 1 + mSize) % mSize;
    }

    private void processConnectionStatus(ConnectionDescriptor conn) {
        if(!conn.alerted && conn.isBlacklisted()) {
            CaptureService.requireInstance().notifyBlacklistedConnection(conn);
            conn.alerted = true;
            mNumMalicious++;
        }
    }

    public synchronized void newConnections(ConnectionDescriptor[] conns) {
        if(conns.length > mSize) {
            // take the most recent
            mUntrackedItems += conns.length - mSize;
            conns = Arrays.copyOfRange(conns, conns.length - mSize, conns.length);
        }

        int out_items = conns.length - Math.min((mSize - mNumItems), conns.length);
        int insert_pos = mNumItems;
        ConnectionDescriptor []removedItems = null;

        //Log.d(TAG, "newConnections[" + mNumItems + "/" + mSize +"]: insert " + conns.length +
        //        " items at " + mTail + " (removed: " + out_items + " at " + firstPos() + ")");

        // Remove old connections
        if(out_items > 0) {
            int pos = firstPos();
            removedItems = new ConnectionDescriptor[out_items];

            // update the apps stats
            for(int i=0; i<out_items; i++) {
                ConnectionDescriptor conn = mItemsRing[pos];

                if(conn != null) {
                    int uid = conn.uid;
                    AppStats stats = mAppsStats.get(uid);
                    stats.bytes -= conn.rcvd_bytes + conn.sent_bytes;

                    if(--stats.num_connections <= 0)
                        mAppsStats.remove(uid);

                    if(conn.isBlacklisted())
                        mNumMalicious--;
                }

                removedItems[i] = conn;
                pos = (pos + 1) % mSize;
            }
        }

        // Add new connections
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

            // Geolocation
            InetAddress dstAddr = conn.getDstAddr();
            conn.country = mGeo.getCountryCode(dstAddr);
            conn.asn = mGeo.getASN(dstAddr);
            //Log.d(TAG, "IP geolocation: IP=" + conn.dst_ip + " -> country=" + conn.country + ", ASN: " + conn.asn);

            conn.updateWhitelist(mWhitelist);
            processConnectionStatus(conn);

            stats.num_connections++;
            stats.bytes += conn.rcvd_bytes + conn.sent_bytes;
        }

        mUntrackedItems += out_items;

        for(ConnectionsListener listener: mListeners) {
            if(out_items > 0)
                listener.connectionsRemoved(0, removedItems);

            if(conns.length > 0)
                listener.connectionsAdded(insert_pos - out_items, conns);
        }
    }

    public synchronized void connectionsUpdates(ConnectionUpdate[] updates) {
        int first_pos = firstPos();
        int first_id = mItemsRing[first_pos].incr_id;
        int last_id = mItemsRing[lastPos()].incr_id;
        int []changed_pos = new int[updates.length];
        int k = 0;

        Log.d(TAG, "connectionsUpdates: items=" + mNumItems + ", first_id=" + first_id + ", last_id=" + last_id);

        for(ConnectionUpdate update: updates) {
            int id = update.incr_id;

            // ignore updates for untracked items
            if((id >= first_id) && (id <= last_id)) {
                int pos = ((id - first_id) + first_pos) % mSize;
                ConnectionDescriptor conn = mItemsRing[pos];
                assert(conn.incr_id == id);

                // update the app stats
                long bytes_delta = (update.rcvd_bytes + update.sent_bytes) - (conn.rcvd_bytes + conn.sent_bytes);
                AppStats stats = mAppsStats.get(conn.uid);
                stats.bytes += bytes_delta;

                //Log.d(TAG, "update " + update.incr_id + " -> " + update.update_type);
                boolean host_changed = (update.info != null) && (!update.info.equals(conn.info));
                conn.processUpdate(update);
                if(host_changed)
                    conn.updateWhitelist(mWhitelist);
                processConnectionStatus(conn);

                changed_pos[k++] = (pos + mSize - first_pos) % mSize;
            }
        }

        for(ConnectionsListener listener: mListeners) {
            if(k != updates.length) {
                // some untracked items where skipped, shrink the array
                changed_pos = Arrays.copyOf(changed_pos, k);
                k = updates.length;
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

    public synchronized void refreshConnectionsWhitelist() {
        ArrayList<Integer>changed_pos = new ArrayList<>();

        for(int i = 0; i< mNumItems; i++) {
            ConnectionDescriptor conn = mItemsRing[i];

            if(conn != null) {
                boolean was_blacklisted = conn.isBlacklisted();

                conn.updateWhitelist(mWhitelist);
                if(conn.isBlacklisted() != was_blacklisted) {
                    if(was_blacklisted)
                        mNumMalicious--;
                    else
                        mNumMalicious++;
                    changed_pos.add(i);
                }
            }
        }

        // Notify listeners
        if(changed_pos.size() > 0) {
            int[] changed = new int[changed_pos.size()];
            int i = 0;

            for(Integer item: changed_pos)
                changed[i++] = item;

            for(ConnectionsListener listener: mListeners)
                listener.connectionsUpdated(changed);
        }
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

    public @Nullable ConnectionDescriptor getConn(int i) {
        if(i >= mNumItems)
            return null;

        int pos = (firstPos() + i) % mSize;
        return mItemsRing[pos];
    }

    public synchronized int getConnPositionById(int incr_id) {
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

    public int getNumMaliciousConnections() {
        return mNumMalicious;
    }
}
