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
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;

import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppStats;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.ConnectionUpdate;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/* A container for the connections. This is used to store active/closed connections until the capture
 * is stopped. Active connections are also kept in the native side.
 *
 * The ConnectionsRegister can store up to _size items, after which rollover occurs and older
 * connections are replaced with the new ones. Via the addListener method it's possible to listen
 * for connections changes (connections added/removed/updated). The usual listener for such events
 * is the ConnectionsFragment, which then forwards them to the ConnectionsAdapter.
 *
 * Connections are added/updated by the CaptureService in a separate thread. The getter methods are
 * instead called on the UI thread, usually by the ConnectionsAdapter. Methods are synchronized to
 * provide threads safety on this class. Concurrent access to the ConnectionDescriptors fields can
 * occur during connectionsUpdates but it's not protected, check out the ConnectionDescriptor class
 * for more details.
 */
public class ConnectionsRegister {
    private static final String TAG = "ConnectionsRegister";

    private final ConnectionDescriptor[] mItemsRing;
    private int mTail;
    private final int mSize;
    private int mCurItems;
    private int mUntrackedItems; // number of old connections which were discarded due to the rollover
    private int mNumMalicious;
    private int mNumBlocked;
    private long mLastFirewallBlock;
    private final SparseArray<AppStats> mAppsStats;
    private final SparseIntArray mConnsByIface;
    private final ArrayList<ConnectionsListener> mListeners;
    private final Geolocation mGeo;
    private final AppsResolver mAppsResolver;

    public ConnectionsRegister(Context ctx, int _size) {
        mTail = 0;
        mCurItems = 0;
        mUntrackedItems = 0;
        mSize = _size;
        mGeo = new Geolocation(ctx);
        mItemsRing = new ConnectionDescriptor[mSize];
        mListeners = new ArrayList<>();
        mAppsStats = new SparseArray<>(); // uid -> AppStats
        mConnsByIface = new SparseIntArray();
        mAppsResolver = new AppsResolver(ctx);
    }

    // returns the position in mItemsRing of the oldest connection
    private synchronized int firstPos() {
        return (mCurItems < mSize) ? 0 : mTail;
    }

    // returns the position in mItemsRing of the newest connection
    private synchronized int lastPos() {
        return (mTail - 1 + mSize) % mSize;
    }

    private void processConnectionStatus(ConnectionDescriptor conn, AppStats stats) {
        boolean is_blacklisted = conn.isBlacklisted();

        if(!conn.alerted && is_blacklisted) {
            CaptureService.requireInstance().notifyBlacklistedConnection(conn);
            conn.alerted = true;
            mNumMalicious++;
        } else if(conn.alerted && !is_blacklisted) {
            // the connection was whitelisted
            conn.alerted = false;
            mNumMalicious--;
        }

        if(!conn.block_accounted && conn.is_blocked) {
            mNumBlocked++;
            stats.numBlockedConnections++;
            conn.block_accounted = true;
        } else if(conn.block_accounted && !conn.is_blocked) {
            mNumBlocked--;
            stats.numBlockedConnections--;
            conn.block_accounted = false;
        }

        if(conn.is_blocked)
            mLastFirewallBlock = Math.max(conn.last_seen, mLastFirewallBlock);
    }

    // called by the CaptureService in a separate thread when new connections should be added to the register
    public synchronized void newConnections(ConnectionDescriptor[] conns) {
        if(conns.length > mSize) {
            // this should only occur while testing with small register sizes
            // take the most recent connections
            mUntrackedItems += conns.length - mSize;
            conns = Arrays.copyOfRange(conns, conns.length - mSize, conns.length);
        }

        int out_items = conns.length - Math.min((mSize - mCurItems), conns.length);
        int insert_pos = mCurItems;
        ConnectionDescriptor []removedItems = null;

        //Log.d(TAG, "newConnections[" + mNumItems + "/" + mSize +"]: insert " + conns.length +
        //        " items at " + mTail + " (removed: " + out_items + " at " + firstPos() + ")");

        // Remove old connections
        if(out_items > 0) {
            int pos = firstPos();
            removedItems = new ConnectionDescriptor[out_items];

            for(int i=0; i<out_items; i++) {
                ConnectionDescriptor conn = mItemsRing[pos];

                if(conn != null) {
                    if(conn.ifidx > 0) {
                        int num_conn = mConnsByIface.get(conn.ifidx);
                        if(--num_conn <= 0)
                            mConnsByIface.delete(conn.ifidx);
                        else
                            mConnsByIface.put(conn.ifidx, num_conn);
                    }
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
            mCurItems = Math.min(mCurItems + 1, mSize);

            // update the apps stats
            int uid = conn.uid;
            AppStats stats = getAppsStatsOrCreate(uid);

            if(conn.ifidx > 0) {
                int num_conn = mConnsByIface.get(conn.ifidx);
                mConnsByIface.put(conn.ifidx, num_conn + 1);
            }

            // Geolocation
            InetAddress dstAddr = conn.getDstAddr();
            conn.country = mGeo.getCountryCode(dstAddr);
            conn.asn = mGeo.getASN(dstAddr);
            //Log.d(TAG, "IP geolocation: IP=" + conn.dst_ip + " -> country=" + conn.country + ", ASN: " + conn.asn);

            AppDescriptor app = mAppsResolver.getAppByUid(conn.uid, 0);
            if(app != null)
                conn.encrypted_payload = Utils.hasEncryptedPayload(app, conn);

            processConnectionStatus(conn, stats);

            stats.numConnections++;
            stats.rcvdBytes += conn.rcvd_bytes;
            stats.sentBytes += conn.sent_bytes;
        }

        mUntrackedItems += out_items;

        for(ConnectionsListener listener: mListeners) {
            if(out_items > 0)
                listener.connectionsRemoved(0, removedItems);

            if(conns.length > 0)
                listener.connectionsAdded(insert_pos - out_items, conns);
        }
    }

    // called by the CaptureService in a separate thread when connections should be updated
    public synchronized void connectionsUpdates(ConnectionUpdate[] updates) {
        if(mCurItems == 0)
            return;

        int first_pos = firstPos();
        int last_pos = lastPos();
        int first_id = mItemsRing[first_pos].incr_id;
        int last_id = mItemsRing[last_pos].incr_id;
        int []changed_pos = new int[updates.length];
        int k = 0;

        Log.d(TAG, "connectionsUpdates: items=" + mCurItems + ", first_id=" + first_id + ", last_id=" + last_id);

        for(ConnectionUpdate update: updates) {
            int id = update.incr_id;

            // ignore updates for untracked items
            if((id >= first_id) && (id <= last_id)) {
                int pos = ((id - first_id) + first_pos) % mSize;
                ConnectionDescriptor conn = mItemsRing[pos];
                assert(conn.incr_id == id);

                // update the app stats
                AppStats stats = getAppsStatsOrCreate(conn.uid);
                stats.sentBytes += update.sent_bytes - conn.sent_bytes;
                stats.rcvdBytes += update.rcvd_bytes - conn.rcvd_bytes;

                //Log.d(TAG, "update " + update.incr_id + " -> " + update.update_type);
                conn.processUpdate(update);
                processConnectionStatus(conn, stats);

                changed_pos[k++] = (pos + mSize - first_pos) % mSize;
            }
        }

        if(k == 0)
            // no updates for items in the ring
            return;

        if(k != updates.length)
            // some untracked items where skipped, shrink the array
            changed_pos = Arrays.copyOf(changed_pos, k);

        for(ConnectionsListener listener: mListeners)
            listener.connectionsUpdated(changed_pos);
    }

    public synchronized void reset() {
        for(int i = 0; i< mSize; i++)
            mItemsRing[i] = null;

        mCurItems = 0;
        mUntrackedItems = 0;
        mTail = 0;
        mAppsStats.clear();

        for(ConnectionsListener listener: mListeners)
            listener.connectionsChanges(mCurItems);
    }

    public synchronized void addListener(ConnectionsListener listener) {
        mListeners.add(listener);

        // Send the first update to sync it
        listener.connectionsChanges(mCurItems);

        Log.d(TAG, "(add) new connections listeners size: " + mListeners.size());
    }

    public synchronized void removeListener(ConnectionsListener listener) {
        mListeners.remove(listener);

        Log.d(TAG, "(remove) new connections listeners size: " + mListeners.size());
    }

    public int getConnCount() {
        return mCurItems;
    }

    public int getUntrackedConnCount() {
        return mUntrackedItems;
    }

    // get the i-th oldest connection
    public synchronized @Nullable ConnectionDescriptor getConn(int i) {
        if((i < 0) || (i >= mCurItems))
            return null;

        int pos = (firstPos() + i) % mSize;
        return mItemsRing[pos];
    }

    public synchronized int getConnPositionById(int incr_id) {
        if(mCurItems <= 0)
            return -1;

        ConnectionDescriptor first = mItemsRing[firstPos()];
        ConnectionDescriptor last = mItemsRing[lastPos()];

        if((incr_id < first.incr_id) || (incr_id > last.incr_id))
            return -1;

        return(incr_id - first.incr_id);
    }

    public synchronized @Nullable ConnectionDescriptor getConnById(int incr_id) {
        int pos = getConnPositionById(incr_id);
        if(pos < 0)
            return null;

        return getConn(pos);
    }

    public synchronized AppStats getAppStats(int uid) {
        return mAppsStats.get(uid);
    }

    public synchronized List<AppStats> getAppsStats() {
        ArrayList<AppStats> rv = new ArrayList<>(mAppsStats.size());

        for(int i=0; i<mAppsStats.size(); i++) {
            // Create a clone to avoid concurrency issues
            AppStats stats = mAppsStats.valueAt(i).clone();

            rv.add(stats);
        }

        return rv;
    }

    private synchronized AppStats getAppsStatsOrCreate(int uid) {
        AppStats stats = mAppsStats.get(uid);
        if(stats == null) {
            stats = new AppStats(uid);
            mAppsStats.put(uid, stats);
        }

        return stats;
    }

    public synchronized void resetAppsStats() {
        mAppsStats.clear();
    }

    public synchronized Set<Integer> getSeenUids() {
        ArraySet<Integer> rv = new ArraySet<>();

        for(int i=0; i<mAppsStats.size(); i++)
            rv.add(mAppsStats.keyAt(i));

        return rv;
    }

    public int getNumMaliciousConnections() {
        return mNumMalicious;
    }

    public int getNumBlockedConnections() {
        return mNumBlocked;
    }

    public long getLastFirewallBlock() {
        return mLastFirewallBlock;
    }

    public synchronized boolean hasSeenMultipleInterfaces() {
        return(mConnsByIface.size() > 1);
    }

    // Returns a sorted list of seen network interfaces
    public synchronized List<String> getSeenInterfaces() {
        List<String> rv = new ArrayList<>();

        for(int i=0; i<mConnsByIface.size(); i++) {
            int ifidx = mConnsByIface.keyAt(i);
            String ifname = CaptureService.getInterfaceName(ifidx);

            if(!ifname.isEmpty())
                rv.add(ifname);
        }

        Collections.sort(rv);
        return rv;
    }

    public synchronized void releasePayloadMemory() {
        Log.i(TAG, "releaseFullPayloadMemory called");

        for(int i=0; i<mCurItems; i++) {
            ConnectionDescriptor conn = mItemsRing[i];
            conn.dropPayload();
        }
    }
}
