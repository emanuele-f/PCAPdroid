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

import org.junit.Test;

import java.net.UnknownHostException;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class CidrTest {
    @Test
    public void testIpv4Basic() throws Exception {
        Cidr cidr = new Cidr("192.168.1.0/24");
        assertEquals("192.168.1.0", cidr.getNetworkAddress());
        assertEquals("192.168.1.255", cidr.getBroadcastAddress());
    }

    @Test
    public void testIpv4IsInRange() throws Exception {
        Cidr cidr = new Cidr("10.0.0.0/8");
        assertTrue(cidr.isInRange("10.0.0.1"));
        assertTrue(cidr.isInRange("10.255.255.255"));
        assertTrue(cidr.isInRange("10.0.0.0"));
        assertFalse(cidr.isInRange("11.0.0.0"));
        assertFalse(cidr.isInRange("9.255.255.255"));
    }

    @Test
    public void testIpv4Slash32() throws Exception {
        Cidr cidr = new Cidr("192.168.1.100/32");
        assertTrue(cidr.isInRange("192.168.1.100"));
        assertFalse(cidr.isInRange("192.168.1.99"));
        assertFalse(cidr.isInRange("192.168.1.101"));
        assertEquals("192.168.1.100", cidr.getNetworkAddress());
        assertEquals("192.168.1.100", cidr.getBroadcastAddress());
    }

    @Test
    public void testIpv4Slash0() throws Exception {
        Cidr cidr = new Cidr("0.0.0.0/0");
        assertTrue(cidr.isInRange("0.0.0.0"));
        assertTrue(cidr.isInRange("255.255.255.255"));
        assertTrue(cidr.isInRange("10.20.30.40"));
    }

    @Test
    public void testIpv6Basic() throws Exception {
        Cidr cidr = new Cidr("2001:db8::/32");
        assertTrue(cidr.isInRange("2001:db8::1"));
        assertTrue(cidr.isInRange("2001:db8:ffff:ffff:ffff:ffff:ffff:ffff"));
        assertFalse(cidr.isInRange("2001:db9::1"));
    }

    @Test
    public void testIpv6Slash128() throws Exception {
        Cidr cidr = new Cidr("::1/128");
        assertTrue(cidr.isInRange("::1"));
        assertFalse(cidr.isInRange("::2"));
    }

    @Test(expected = RuntimeException.class)
    public void testNoSlashThrows() throws Exception {
        new Cidr("192.168.1.0");
    }

    @Test(expected = UnknownHostException.class)
    public void testBadIpThrows() throws Exception {
        new Cidr("invalid/24");
    }

    @Test
    public void testEquals() throws Exception {
        Cidr a = new Cidr("192.168.1.0/24");
        Cidr b = new Cidr("192.168.1.128/24");
        // same network range despite different host parts
        assertEquals(a, b);

        Cidr c = new Cidr("192.168.2.0/24");
        assertNotEquals(a, c);
    }

    @Test
    public void testIpv4FullRange() throws Exception {
        Cidr v4 = new Cidr("0.0.0.0/0");
        assertTrue(v4.isInRange("1.2.3.4"));
        assertTrue(v4.isInRange("255.255.255.255"));
    }

    @Test
    public void testToString() throws Exception {
        Cidr cidr = new Cidr("10.0.0.0/8");
        assertEquals("10.0.0.0/8", cidr.toString());
    }
}
