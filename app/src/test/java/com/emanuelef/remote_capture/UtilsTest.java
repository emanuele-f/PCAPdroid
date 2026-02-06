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
 * Copyright 2022-26 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
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

    @Test
    public void testCleanDomain() {
        assertEquals("example.org", Utils.cleanDomain("www.example.org"));
        assertEquals("example.org", Utils.cleanDomain("example.org"));
        assertEquals("", Utils.cleanDomain(""));
        assertEquals("sub.example.org", Utils.cleanDomain("www.sub.example.org"));
        assertEquals("www", Utils.cleanDomain("www.www"));
    }

    @Test
    public void testGetSecondLevelDomain() {
        assertEquals("example.org", Utils.getSecondLevelDomain("a.example.org"));
        assertEquals("example.org", Utils.getSecondLevelDomain("a.b.example.org"));
        assertEquals("example.org", Utils.getSecondLevelDomain("example.org"));
        assertEquals("org", Utils.getSecondLevelDomain("org"));
        assertEquals("", Utils.getSecondLevelDomain(""));
        assertEquals("co.uk", Utils.getSecondLevelDomain("www.example.co.uk"));
    }

    @Test
    public void testValidatePort() {
        assertTrue(Utils.validatePort("1"));
        assertTrue(Utils.validatePort("80"));
        assertTrue(Utils.validatePort("443"));
        assertTrue(Utils.validatePort("65534"));

        assertFalse(Utils.validatePort("0"));
        assertFalse(Utils.validatePort("65535"));
        assertFalse(Utils.validatePort("-1"));
        assertFalse(Utils.validatePort("abc"));
        assertFalse(Utils.validatePort(""));
    }

    @Test
    public void testValidateIpv4Address() {
        assertTrue(Utils.validateIpv4Address("0.0.0.0"));
        assertTrue(Utils.validateIpv4Address("192.168.1.1"));
        assertTrue(Utils.validateIpv4Address("255.255.255.255"));
        assertTrue(Utils.validateIpv4Address("10.0.0.1"));

        assertFalse(Utils.validateIpv4Address("256.1.1.1"));
        assertFalse(Utils.validateIpv4Address("1.2.3"));
        assertFalse(Utils.validateIpv4Address("1.2.3.4.5"));
        assertFalse(Utils.validateIpv4Address(""));
        assertFalse(Utils.validateIpv4Address("abc"));
    }

    @Test
    public void testValidateIpv6Address() {
        assertTrue(Utils.validateIpv6Address("2001:db8::1"));
        assertTrue(Utils.validateIpv6Address("::1"));
        assertTrue(Utils.validateIpv6Address("fe80::1"));
        assertTrue(Utils.validateIpv6Address("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));

        assertFalse(Utils.validateIpv6Address("192.168.1.1"));
        assertFalse(Utils.validateIpv6Address(""));
        assertFalse(Utils.validateIpv6Address("zzzz::1"));
    }

    @Test
    public void testValidateCidr() {
        assertTrue(Utils.validateCidr("192.168.1.0/24"));
        assertTrue(Utils.validateCidr("10.0.0.0/8"));
        assertTrue(Utils.validateCidr("192.168.1.1/32"));
        assertTrue(Utils.validateCidr("0.0.0.0/0"));
        assertTrue(Utils.validateCidr("2001:db8::/32"));
        assertTrue(Utils.validateCidr("::1/128"));

        // plain IP (no slash) is valid
        assertTrue(Utils.validateCidr("192.168.1.1"));

        assertFalse(Utils.validateCidr("192.168.1.0/33"));
        assertFalse(Utils.validateCidr("::1/129"));
        assertFalse(Utils.validateCidr("abc/24"));
        assertFalse(Utils.validateCidr("192.168.1.0/abc"));
    }

    @Test
    public void testValidateHost() {
        assertTrue(Utils.validateHost("example.org"));
        assertTrue(Utils.validateHost("sub.example.org"));
        assertTrue(Utils.validateHost("a.b.c.example.org"));
        assertTrue(Utils.validateHost("example123.org"));

        assertFalse(Utils.validateHost("a"));
        assertFalse(Utils.validateHost("-example.org"));
        assertFalse(Utils.validateHost("example.org-"));
        assertFalse(Utils.validateHost("Example.org"));
        assertFalse(Utils.validateHost("example .org"));
        assertFalse(Utils.validateHost("example?.org"));
    }

    @Test
    public void testGetEndOfHTTPHeaders() {
        byte[] withHeaders = "GET / HTTP/1.1\r\nHost: example.org\r\n\r\nbody".getBytes();
        int end = Utils.getEndOfHTTPHeaders(withHeaders);
        assertTrue(end > 0);
        assertEquals("body", new String(withHeaders, end, withHeaders.length - end));

        byte[] noEnd = "GET / HTTP/1.1\r\nHost: example.org\r\n".getBytes();
        assertEquals(0, Utils.getEndOfHTTPHeaders(noEnd));

        byte[] minimal = "\r\n\r\n".getBytes();
        assertEquals(4, Utils.getEndOfHTTPHeaders(minimal));

        byte[] tooShort = "\r\n\r".getBytes();
        assertEquals(0, Utils.getEndOfHTTPHeaders(tooShort));

        assertEquals(0, Utils.getEndOfHTTPHeaders(new byte[0]));
    }

    // Helper that replicates the conversion logic from Utils.formatMillisIso8601
    private String convertTimezone(String rv) {
        int l = rv.length();
        if ((l > 5) && ((rv.charAt(l - 5) == '+') || (rv.charAt(l - 5) == '-')))
            rv = rv.substring(0, l - 2) + ":" + rv.substring(l - 2);
        return rv;
    }
}