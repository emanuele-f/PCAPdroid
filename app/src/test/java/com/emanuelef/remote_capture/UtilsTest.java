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
 * Copyright 2022 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

public class UtilsTest {
    @Test
    public void subnetContainsIpv4() {
        assertTrue(Utils.subnetContains("192.168.1.1", 24, "192.168.1.0"));
        assertTrue(Utils.subnetContains("192.168.1.20", 24, "192.168.1.255"));
        assertTrue(Utils.subnetContains("192.168.1.0", 24, "192.168.1.128"));
        assertTrue(Utils.subnetContains("10.0.0.0", 8, "10.0.2.0"));
        assertTrue(Utils.subnetContains("127.0.0.0", 8, "127.0.0.1"));
        assertTrue(Utils.subnetContains("192.168.1.123", 32, "192.168.1.123"));

        assertFalse(Utils.subnetContains("192.168.1.0", 24, "192.168.0.0"));
        assertFalse(Utils.subnetContains("192.168.1.0", 24, "192.168.0.255"));
    }

    @Test
    public void subnetContainsIpv6() {
        assertTrue(Utils.subnetContains("2001:0db8:85a3::8a2e:0370:7334", 128, "2001:0db8:85a3::8a2e:0370:7334"));
        assertTrue(Utils.subnetContains("2001:0db8:85a3::8a2e:0370:7334", 112, "2001:0db8:85a3::8a2e:0370:0001"));
        assertTrue(Utils.subnetContains("2001:0db8:85a3::8a2e:0370:7334", 64, "2001:0db8:85a3::8a2e:0370:0001"));

        assertFalse(Utils.subnetContains("2001:0db8:85a3::8a2e:0370:7334", 120, "2001:0db8:85a3::8a2e:0370:0001"));
    }
}