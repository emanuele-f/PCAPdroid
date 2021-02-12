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
 * Copyright 2020 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.util.Log;

public class ConnectionsRegister {
    private final ConnDescriptor[] items_ring;
    private int tail;
    private final int size;
    private int num_items;
    private int untracked_items;
    private ConnectionsListener mListener;
    private static final String TAG = "ConnectionsRegister";

    public ConnectionsRegister(int _size) {
        tail = 0;
        num_items = 0;
        untracked_items = 0;
        size = _size;
        items_ring = new ConnDescriptor[size];
        mListener = null;
    }

    private int firstPos() {
        return (num_items < size) ? 0 : tail;
    }

    private int lastPos() {
        return (tail - 1 + size) % size;
    }

    private void newConnections(ConnDescriptor[] conns) {
        int in_items = Math.min((size - num_items), conns.length);
        int out_items = conns.length - in_items;

        for(ConnDescriptor conn: conns) {
            items_ring[tail] = conn;
            tail = (tail + 1) % size;
            num_items = Math.min(num_items + 1, size);
        }

        untracked_items += out_items;
    }

    private void connectionsUpdates(ConnDescriptor[] conns) {
        int first_pos = firstPos();
        int first_id = items_ring[first_pos].incr_id;
        int last_id = items_ring[lastPos()].incr_id;

        Log.d(TAG, "connectionsUpdates: items=" + num_items + ", first_id=" + first_id + ", last_id=" + last_id);

        for(ConnDescriptor conn: conns) {
            int id = conn.incr_id;

            // ignore updates for untracked items
            if((id >= first_id) && (id <= last_id)) {
                int pos = ((id - first_id) + first_pos) % size;

                assert(items_ring[pos].incr_id == id);
                items_ring[pos] = conn;
            }
        }
    }

    public synchronized void reset() {
        for(int i=0; i<size; i++)
            items_ring[i] = null;

        num_items = 0;
        untracked_items = 0;
        tail = 0;

        if(mListener != null)
            mListener.connectionsChanges();
    }

    public synchronized void updateConnections(ConnDescriptor[] new_conns, ConnDescriptor[] conns_updates) {
        newConnections(new_conns);
        connectionsUpdates(conns_updates);

        if(mListener != null)
            mListener.connectionsChanges();
    }

    public synchronized void setListener(ConnectionsListener listener) {
        mListener = listener;
    }

    public int getConnCount() {
        return num_items;
    }

    public int getUntrackedConnCount() {
        return untracked_items;
    }

    public synchronized ConnDescriptor getConn(int i) {
        if(i >= num_items)
            return null;

        int pos = (firstPos() + i) % size;
        return items_ring[pos];
    }
}
