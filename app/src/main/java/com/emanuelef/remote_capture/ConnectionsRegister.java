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

import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppStats;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConnectionsRegister {
    private final ConnectionDescriptor[] items_ring;
    private int tail;
    private final int size;
    private int num_items;
    private int untracked_items;
    private final Map<Integer, AppStats> mAppsStats;
    private final ArrayList<ConnectionsListener> mListeners;
    private static final String TAG = "ConnectionsRegister";

    public ConnectionsRegister(int _size) {
        tail = 0;
        num_items = 0;
        untracked_items = 0;
        size = _size;
        items_ring = new ConnectionDescriptor[size];
        mListeners = new ArrayList<>();
        mAppsStats = new HashMap<>(); // uid -> AppStats
    }

    private int firstPos() {
        return (num_items < size) ? 0 : tail;
    }

    private int lastPos() {
        return (tail - 1 + size) % size;
    }

    public synchronized void newConnections(ConnectionDescriptor[] conns) {
        if(conns.length > size) {
            // take the most recent
            untracked_items += conns.length - size;
            conns = Arrays.copyOfRange(conns, conns.length - size, conns.length);
        }

        int in_items = Math.min((size - num_items), conns.length);
        int out_items = conns.length - in_items;
        int insert_pos = num_items;

        if(out_items > 0) {
            int pos = tail;

            // update the apps stats
            for(int i=0; i<out_items; i++) {
                ConnectionDescriptor conn = items_ring[pos];

                if(conn != null) {
                    int uid = conn.uid;
                    AppStats stats = mAppsStats.get(uid);
                    stats.bytes -= conn.rcvd_bytes + conn.sent_bytes;

                    if(--stats.num_connections <= 0)
                        mAppsStats.remove(uid);
                }

                pos = (pos + 1) % size;
            }
        }

        for(ConnectionDescriptor conn: conns) {
            items_ring[tail] = conn;
            tail = (tail + 1) % size;
            num_items = Math.min(num_items + 1, size);

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

        untracked_items += out_items;

        for(ConnectionsListener listener: mListeners) {
            if(out_items > 0)
                listener.connectionsRemoved(0, out_items);

            if(conns.length > 0)
                listener.connectionsAdded(insert_pos - out_items, conns.length);
        }
    }

    public synchronized void connectionsUpdates(ConnectionDescriptor[] conns) {
        int first_pos = firstPos();
        int first_id = items_ring[first_pos].incr_id;
        int last_id = items_ring[lastPos()].incr_id;
        int []changed_pos = new int[conns.length];
        int k = 0;

        Log.d(TAG, "connectionsUpdates: items=" + num_items + ", first_id=" + first_id + ", last_id=" + last_id);

        for(ConnectionDescriptor conn: conns) {
            int id = conn.incr_id;

            // ignore updates for untracked items
            if((id >= first_id) && (id <= last_id)) {
                int pos = ((id - first_id) + first_pos) % size;
                ConnectionDescriptor old = items_ring[pos];

                assert(old.incr_id == id);
                items_ring[pos] = conn;

                // update the apps stats
                long old_bytes = old.rcvd_bytes + old.sent_bytes;
                long bytes_delta = (conn.rcvd_bytes + conn.sent_bytes) - old_bytes;
                AppStats stats = mAppsStats.get(conn.uid);
                stats.bytes += bytes_delta;

                changed_pos[k++] = (pos + size - first_pos) % size;
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
        for(int i=0; i<size; i++)
            items_ring[i] = null;

        num_items = 0;
        untracked_items = 0;
        tail = 0;
        mAppsStats.clear();

        for(ConnectionsListener listener: mListeners)
            listener.connectionsChanges(num_items);
    }

    public synchronized void addListener(ConnectionsListener listener) {
        mListeners.add(listener);

        // Send the first update to sync it
        listener.connectionsChanges(num_items);
    }

    public synchronized void removeListener(ConnectionsListener listener) {
        mListeners.remove(listener);
    }

    public int getConnCount() {
        return num_items;
    }

    public int getUntrackedConnCount() {
        return untracked_items;
    }

    public synchronized ConnectionDescriptor getConn(int i) {
        if(i >= num_items)
            return null;

        int pos = (firstPos() + i) % size;
        return items_ring[pos];
    }

    public synchronized ConnectionDescriptor getUidConn(int uid, int target_pos) {
        // pos is relative to the connections matching the provided uid
        int first = firstPos();
        int virt_pos = 0;

        for(int i = 0; i < num_items; i++) {
            int pos = (first + i) % size;
            ConnectionDescriptor item = items_ring[pos];

            if((item != null) && (item.uid == uid)) {
                if(virt_pos == target_pos)
                    return item;

                virt_pos++;
            }
        }

        return null;
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

    public synchronized int getUidConnCount(int uid) {
        AppStats stats = mAppsStats.get(uid);

        if(stats == null)
            return 0;
        return stats.num_connections;
    }

    public synchronized String dumpConnectionsCsv(Context context, Map<Integer, AppDescriptor> apps, int uidFilter) {
        StringBuilder builder = new StringBuilder();
        String statusOpen = context.getString(R.string.conn_status_open);
        String statusClosed = context.getString(R.string.conn_status_closed);

        // Header
        builder.append(context.getString(R.string.connections_csv_fields_v1));
        builder.append("\n");

        // Contents
        for(int i=0; i<getConnCount(); i++) {
            ConnectionDescriptor conn = getConn(i);
            AppDescriptor app = apps.get(conn.uid);

            if((conn != null) && ((uidFilter == Utils.UID_NO_FILTER) || (conn.uid == uidFilter))) {
                builder.append(conn.ipproto);                               builder.append(",");
                builder.append(conn.src_ip);                                builder.append(",");
                builder.append(conn.src_port);                              builder.append(",");
                builder.append(conn.dst_ip);                                builder.append(",");
                builder.append(conn.dst_port);                              builder.append(",");
                builder.append(conn.uid);                                   builder.append(",");
                builder.append((app != null) ? app.getName() : "");         builder.append(",");
                builder.append(conn.l7proto);                               builder.append(",");
                builder.append(conn.closed ? statusClosed : statusOpen);    builder.append(",");
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
}
