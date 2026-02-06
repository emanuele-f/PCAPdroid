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
 * Copyright 2026 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.AppStats;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.ConnectionUpdate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class ConnectionsRegisterTest {
    static final int MAX_CONNECTIONS = 8;
    ConnectionsRegister reg;
    CaptureService service;
    int incrId = 0;
    ArrayList<String> events = new ArrayList<>();

    @Before
    public void setup() {
        incrId = 0;
        events.clear();

        Context context = ApplicationProvider.getApplicationContext();
        reg = new ConnectionsRegister(context, MAX_CONNECTIONS);

        // Mock CaptureService (needed for processConnectionStatus)
        service = new CaptureService();
        Whitebox.setInternalState(service, "INSTANCE", service);
        Whitebox.setInternalState(service, "conn_reg", reg);
    }

    @After
    public void tearDown() {
        Whitebox.setInternalState(service, "INSTANCE", null);
    }

    private ConnectionDescriptor newConn(int uid) {
        ConnectionDescriptor conn = new ConnectionDescriptor(incrId++, 4, 6,
                "1.1.1.1", "2.2.2.2", "", 51234, 80,
                0, uid, 0, false, 0);
        conn.status = ConnectionDescriptor.CONN_STATUS_CONNECTED;
        conn.l7proto = "";
        return conn;
    }

    @Test
    public void testInsert() {
        reg.newConnections(new ConnectionDescriptor[]{newConn(1000), newConn(1000)});
        assertEquals(2, reg.getConnCount());
        assertEquals(0, reg.getUntrackedConnCount());
        assertNotNull(reg.getConn(0));
        assertNotNull(reg.getConn(1));
        assertEquals(0, reg.getConn(0).incr_id);
        assertEquals(1, reg.getConn(1).incr_id);
    }

    @Test
    public void testRollover() {
        // fill to capacity
        ConnectionDescriptor[] conns = new ConnectionDescriptor[MAX_CONNECTIONS];
        for (int i = 0; i < MAX_CONNECTIONS; i++)
            conns[i] = newConn(1000);
        reg.newConnections(conns);
        assertEquals(MAX_CONNECTIONS, reg.getConnCount());

        // add 2 more, causing rollover of first 2
        reg.newConnections(new ConnectionDescriptor[]{newConn(1000), newConn(1000)});
        assertEquals(MAX_CONNECTIONS, reg.getConnCount());
        assertEquals(2, reg.getUntrackedConnCount());

        // oldest should be id=2
        assertEquals(2, reg.getConn(0).incr_id);
        // newest should be id=9
        assertEquals(9, reg.getConn(MAX_CONNECTIONS - 1).incr_id);
    }

    @Test
    public void testGetConnById() {
        reg.newConnections(new ConnectionDescriptor[]{newConn(1000), newConn(1000), newConn(1000)});

        assertNotNull(reg.getConnById(0));
        assertNotNull(reg.getConnById(2));
        assertNull(reg.getConnById(3));
        assertNull(reg.getConnById(-1));
    }

    @Test
    public void testGetConnPositionById() {
        reg.newConnections(new ConnectionDescriptor[]{newConn(1000), newConn(1000), newConn(1000)});

        assertEquals(0, reg.getConnPositionById(0));
        assertEquals(1, reg.getConnPositionById(1));
        assertEquals(2, reg.getConnPositionById(2));
        assertEquals(-1, reg.getConnPositionById(3));
        assertEquals(-1, reg.getConnPositionById(-1));
    }

    @Test
    public void testGetConnPositionAfterRollover() {
        ConnectionDescriptor[] conns = new ConnectionDescriptor[MAX_CONNECTIONS];
        for (int i = 0; i < MAX_CONNECTIONS; i++)
            conns[i] = newConn(1000);
        reg.newConnections(conns);
        reg.newConnections(new ConnectionDescriptor[]{newConn(1000), newConn(1000)});

        // first connection is now id=2
        assertEquals(-1, reg.getConnPositionById(0));
        assertEquals(-1, reg.getConnPositionById(1));
        assertEquals(0, reg.getConnPositionById(2));
        assertEquals(7, reg.getConnPositionById(9));
    }

    @Test
    public void testGetConnOutOfBounds() {
        reg.newConnections(new ConnectionDescriptor[]{newConn(1000)});
        assertNull(reg.getConn(-1));
        assertNull(reg.getConn(1));
        assertNotNull(reg.getConn(0));
    }

    @Test
    public void testReset() {
        reg.newConnections(new ConnectionDescriptor[]{newConn(1000), newConn(1000)});
        reg.reset();
        assertEquals(0, reg.getConnCount());
        assertEquals(0, reg.getUntrackedConnCount());
        assertNull(reg.getConn(0));
    }

    @Test
    public void testAppStats() {
        reg.newConnections(new ConnectionDescriptor[]{
                newConn(1000), newConn(1000), newConn(2000)
        });

        AppStats stats1000 = reg.getAppStats(1000);
        assertNotNull(stats1000);
        assertEquals(2, stats1000.numConnections);

        AppStats stats2000 = reg.getAppStats(2000);
        assertNotNull(stats2000);
        assertEquals(1, stats2000.numConnections);
    }

    @Test
    public void testAppStatsBytes() {
        ConnectionDescriptor conn = newConn(1000);
        conn.sent_bytes = 100;
        conn.rcvd_bytes = 200;
        reg.newConnections(new ConnectionDescriptor[]{conn});

        AppStats stats = reg.getAppStats(1000);
        assertNotNull(stats);
        assertEquals(100, stats.sentBytes);
        assertEquals(200, stats.rcvdBytes);
    }

    @Test
    public void testConnectionsUpdates() {
        reg.newConnections(new ConnectionDescriptor[]{newConn(1000), newConn(1000)});

        ConnectionUpdate update = new ConnectionUpdate(1);
        update.setStats(0, 0, 50, 100, 5, 10,
                0, 0, ConnectionDescriptor.CONN_STATUS_CONNECTED);
        reg.connectionsUpdates(new ConnectionUpdate[]{update});

        ConnectionDescriptor conn = reg.getConnById(1);
        assertNotNull(conn);
        assertEquals(50, conn.sent_bytes);
        assertEquals(100, conn.rcvd_bytes);
        assertEquals(5, conn.sent_pkts);
        assertEquals(10, conn.rcvd_pkts);
    }

    @Test
    public void testConnectionsUpdatesIgnoresUntracked() {
        reg.newConnections(new ConnectionDescriptor[]{newConn(1000)});

        // update for non-existent id should not crash
        ConnectionUpdate update = new ConnectionUpdate(999);
        update.setStats(0, 0, 50, 100, 5, 10,
                0, 0, ConnectionDescriptor.CONN_STATUS_CONNECTED);
        reg.connectionsUpdates(new ConnectionUpdate[]{update});

        // original unchanged
        assertEquals(0, reg.getConn(0).sent_bytes);
    }

    @Test
    public void testBlockedConnectionCounting() {
        ConnectionDescriptor conn = newConn(1000);
        conn.is_blocked = true;
        reg.newConnections(new ConnectionDescriptor[]{conn});

        assertEquals(1, reg.getNumBlockedConnections());
    }

    @Test
    public void testMaxBytes() {
        ConnectionDescriptor c1 = newConn(1000);
        c1.sent_bytes = 100;
        c1.rcvd_bytes = 200;

        ConnectionDescriptor c2 = newConn(1000);
        c2.sent_bytes = 50;
        c2.rcvd_bytes = 50;

        reg.newConnections(new ConnectionDescriptor[]{c1, c2});
        assertEquals(300, reg.getMaxBytes());
    }

    @Test
    public void testListenerNotifications() {
        ConnectionsListener listener = new ConnectionsListener() {
            @Override public void connectionsChanges(int num) { events.add("changes:" + num); }
            @Override public void connectionsAdded(int start, ConnectionDescriptor[] conns) { events.add("added:" + start + ":" + conns.length); }
            @Override public void connectionsRemoved(int start, ConnectionDescriptor[] conns) { events.add("removed:" + start + ":" + conns.length); }
            @Override public void connectionsUpdated(int[] positions) { events.add("updated:" + positions.length); }
        };

        reg.addListener(listener);
        // addListener sends initial sync
        assertEquals(1, events.size());
        assertEquals("changes:0", events.get(0));
        events.clear();

        // add connections
        reg.newConnections(new ConnectionDescriptor[]{newConn(1000), newConn(1000)});
        assertEquals(1, events.size());
        assertEquals("added:0:2", events.get(0));
        events.clear();

        // update
        ConnectionUpdate update = new ConnectionUpdate(0);
        update.setStats(0, 0, 10, 10, 1, 1,
                0, 0, ConnectionDescriptor.CONN_STATUS_CONNECTED);
        reg.connectionsUpdates(new ConnectionUpdate[]{update});
        assertEquals(1, events.size());
        assertEquals("updated:1", events.get(0));

        reg.removeListener(listener);
    }

    @Test
    public void testListenerNotificationsOnRollover() {
        ConnectionsListener listener = new ConnectionsListener() {
            @Override public void connectionsChanges(int num) { events.add("changes:" + num); }
            @Override public void connectionsAdded(int start, ConnectionDescriptor[] conns) { events.add("added:" + start + ":" + conns.length); }
            @Override public void connectionsRemoved(int start, ConnectionDescriptor[] conns) { events.add("removed:" + start + ":" + conns.length); }
            @Override public void connectionsUpdated(int[] positions) { events.add("updated:" + positions.length); }
        };

        // fill to capacity
        ConnectionDescriptor[] conns = new ConnectionDescriptor[MAX_CONNECTIONS];
        for (int i = 0; i < MAX_CONNECTIONS; i++)
            conns[i] = newConn(1000);
        reg.newConnections(conns);

        reg.addListener(listener);
        events.clear();

        // add 2 more, causing rollover
        reg.newConnections(new ConnectionDescriptor[]{newConn(1000), newConn(1000)});

        // should get removed then added
        assertTrue(events.size() >= 2);
        assertEquals("removed:0:2", events.get(0));
        assertEquals("added:6:2", events.get(1));

        reg.removeListener(listener);
    }

    @Test
    public void testSeenUids() {
        reg.newConnections(new ConnectionDescriptor[]{
                newConn(1000), newConn(2000), newConn(1000)
        });

        assertEquals(2, reg.getSeenUids().size());
        assertTrue(reg.getSeenUids().contains(1000));
        assertTrue(reg.getSeenUids().contains(2000));
    }
}
