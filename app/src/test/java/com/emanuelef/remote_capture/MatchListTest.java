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

import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.MatchList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class MatchListTest {
    static final String PREF_NAME = "test_matchlist";
    MatchList list;

    @Before
    public void setup() {
        Context ctx = ApplicationProvider.getApplicationContext();
        list = new MatchList(ctx, PREF_NAME);
        list.clear();
    }

    private ConnectionDescriptor makeConn(String dstIp, String info, String l7proto, String country) {
        ConnectionDescriptor conn = new ConnectionDescriptor(0, 4, 6,
                "1.1.1.1", dstIp, country, 51234, 80,
                0, -1, 0, false, 0);
        conn.info = info;
        conn.l7proto = l7proto;
        return conn;
    }

    @Test
    public void testMatchesExactIP() {
        list.addIp("10.0.0.1");
        assertTrue(list.matchesExactIP("10.0.0.1"));
        assertFalse(list.matchesExactIP("10.0.0.2"));
    }

    @Test
    public void testMatchesCidr() {
        list.addIp("192.168.1.0/24");
        assertNotNull(list.matchesCidr("192.168.1.100"));
        assertNull(list.matchesCidr("192.168.2.1"));
    }

    @Test
    public void testMatchesExactHost() {
        list.addHost("example.org");
        assertTrue(list.matchesExactHost("example.org"));
        assertFalse(list.matchesExactHost("other.org"));
    }

    @Test
    public void testMatchesHostSecondLevel() {
        list.addHost("example.org");
        assertTrue(list.matchesHost("sub.example.org"));
        assertTrue(list.matchesHost("a.b.example.org"));
        assertFalse(list.matchesHost("notexample.org"));
    }

    @Test
    public void testMatchesHostWithWww() {
        list.addHost("www.example.org");
        // "www." is cleaned, so it matches "example.org"
        assertTrue(list.matchesHost("example.org"));
        assertTrue(list.matchesHost("sub.example.org"));
    }

    @Test
    public void testMatchesProto() {
        list.addProto("DNS");
        assertTrue(list.matchesProto("DNS"));
        assertFalse(list.matchesProto("HTTP"));
    }

    @Test
    public void testMatchesCountry() {
        list.addCountry("US");
        assertTrue(list.matchesCountry("US"));
        assertFalse(list.matchesCountry("DE"));
    }

    @Test
    public void testMatchesConnection() {
        list.addIp("2.2.2.2");

        ConnectionDescriptor conn = makeConn("2.2.2.2", "example.org", "TLS", "US");
        assertTrue(list.matches(conn));

        ConnectionDescriptor conn2 = makeConn("3.3.3.3", "other.org", "TLS", "US");
        assertFalse(list.matches(conn2));
    }

    @Test
    public void testMatchesConnectionByHost() {
        list.addHost("example.org");

        ConnectionDescriptor conn = makeConn("2.2.2.2", "sub.example.org", "TLS", "US");
        assertTrue(list.matches(conn));
    }

    @Test
    public void testMatchesConnectionByProto() {
        list.addProto("DNS");

        ConnectionDescriptor conn = makeConn("2.2.2.2", "", "DNS", "US");
        assertTrue(list.matches(conn));
    }

    @Test
    public void testMatchesConnectionByCountry() {
        list.addCountry("DE");

        ConnectionDescriptor conn = makeConn("2.2.2.2", "", "TLS", "DE");
        assertTrue(list.matches(conn));

        ConnectionDescriptor conn2 = makeConn("2.2.2.2", "", "TLS", "US");
        assertFalse(list.matches(conn2));
    }

    @Test
    public void testMatchesEmptyList() {
        ConnectionDescriptor conn = makeConn("2.2.2.2", "example.org", "TLS", "US");
        assertFalse(list.matches(conn));
    }

    @Test
    public void testDuplicateIpRejection() {
        assertTrue(list.addIp("10.0.0.1"));
        assertFalse(list.addIp("10.0.0.1"));
        assertEquals(1, list.getSize());
    }

    @Test
    public void testDuplicateHostRejection() {
        assertTrue(list.addHost("example.org"));
        assertFalse(list.addHost("example.org"));
        assertEquals(1, list.getSize());
    }

    @Test
    public void testRemoveIp() {
        list.addIp("10.0.0.1");
        list.removeIp("10.0.0.1");
        assertFalse(list.matchesExactIP("10.0.0.1"));
        assertTrue(list.isEmpty());
    }

    @Test
    public void testRemoveHost() {
        list.addHost("example.org");
        list.removeHost("example.org");
        assertFalse(list.matchesHost("example.org"));
        assertTrue(list.isEmpty());
    }

    @Test
    public void testRemoveCidr() {
        list.addIp("192.168.1.0/24");
        list.removeIp("192.168.1.0/24");
        assertNull(list.matchesCidr("192.168.1.100"));
        assertTrue(list.isEmpty());
    }

    @Test
    public void testClear() {
        list.addIp("10.0.0.1");
        list.addHost("example.org");
        list.addProto("DNS");
        list.addCountry("US");
        assertFalse(list.isEmpty());

        list.clear();
        assertTrue(list.isEmpty());
        assertEquals(0, list.getSize());
    }

    @Test
    public void testJsonRoundtrip() {
        list.addIp("10.0.0.1");
        list.addHost("example.org");
        list.addProto("DNS");
        list.addCountry("US");
        list.addIp("192.168.0.0/16");

        String json = list.toJson(false);

        list.clear();
        assertTrue(list.isEmpty());

        int numRules = list.fromJson(json);
        assertEquals(5, numRules);
        assertEquals(5, list.getSize());

        assertTrue(list.matchesExactIP("10.0.0.1"));
        assertTrue(list.matchesHost("example.org"));
        assertTrue(list.matchesProto("DNS"));
        assertTrue(list.matchesCountry("US"));
        assertNotNull(list.matchesCidr("192.168.1.1"));
    }

    @Test
    public void testAddRulesMerge() {
        list.addIp("10.0.0.1");

        Context ctx = ApplicationProvider.getApplicationContext();
        MatchList other = new MatchList(ctx, PREF_NAME + "_other");
        other.clear();
        other.addIp("10.0.0.2");
        other.addIp("10.0.0.1"); // duplicate

        int added = list.addRules(other);
        assertEquals(1, added);
        assertEquals(2, list.getSize());
    }

    @Test
    public void testListenerNotifications() {
        ArrayList<String> events = new ArrayList<>();
        list.addListChangeListener(() -> events.add("changed"));

        list.addIp("10.0.0.1");
        assertEquals(1, events.size());

        list.removeIp("10.0.0.1");
        assertEquals(2, events.size());

        list.addHost("example.org");
        list.clear();
        assertEquals(4, events.size());
    }

    @Test
    public void testListenerRemoval() {
        ArrayList<String> events = new ArrayList<>();
        MatchList.ListChangeListener listener = () -> events.add("changed");
        list.addListChangeListener(listener);

        list.addIp("10.0.0.1");
        assertEquals(1, events.size());

        list.removeListChangeListener(listener);
        list.addIp("10.0.0.2");
        assertEquals(1, events.size());
    }

    @Test
    public void testFromJsonMalformed() {
        assertEquals(-1, list.fromJson("not json"));
        assertEquals(-1, list.fromJson("[]")); // not a json object
    }

    @Test
    public void testToListDescriptor() {
        list.addIp("10.0.0.1");
        list.addHost("example.org");
        list.addProto("DNS");
        list.addCountry("US");

        MatchList.ListDescriptor desc = list.toListDescriptor();
        assertEquals(1, desc.ips.size());
        assertEquals("10.0.0.1", desc.ips.get(0));
        assertEquals(1, desc.hosts.size());
        assertEquals("example.org", desc.hosts.get(0));
        assertEquals(0, desc.apps.size());
        assertEquals(1, desc.countries.size());
    }
}
