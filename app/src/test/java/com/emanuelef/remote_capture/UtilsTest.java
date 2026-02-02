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
import static org.junit.Assert.assertEquals;

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

    @Test
    public void testTimezoneConversionLogic() {
        // Test the RFC 822 -> ISO 8601 timezone conversion logic used in formatMillisIso8601
        // This simulates what the code does on Android < N

        // Test positive timezone (was already working)
        String positiveInput = "2026-01-16T17:15:15.123+0100";
        String positiveExpected = "2026-01-16T17:15:15.123+01:00";
        assertEquals(positiveExpected, convertTimezone(positiveInput));

        // Test negative timezone (was broken before the fix)
        String negativeInput = "2026-01-16T10:15:15.123-0500";
        String negativeExpected = "2026-01-16T10:15:15.123-05:00";
        assertEquals(negativeExpected, convertTimezone(negativeInput));

        // Test UTC (edge case with +0000)
        String utcInput = "2026-01-16T15:15:15.123+0000";
        String utcExpected = "2026-01-16T15:15:15.123+00:00";
        assertEquals(utcExpected, convertTimezone(utcInput));

        // Test negative offset at international date line
        String idlInput = "2026-01-16T03:15:15.123-1200";
        String idlExpected = "2026-01-16T03:15:15.123-12:00";
        assertEquals(idlExpected, convertTimezone(idlInput));
    }

    // Helper that replicates the conversion logic from Utils.formatMillisIso8601
    private String convertTimezone(String rv) {
        int l = rv.length();
        if ((l > 5) && ((rv.charAt(l - 5) == '+') || (rv.charAt(l - 5) == '-')))
            rv = rv.substring(0, l - 2) + ":" + rv.substring(l - 2);
        return rv;
    }
}