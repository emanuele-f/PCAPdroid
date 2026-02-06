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

import com.emanuelef.remote_capture.model.PortMapping;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Iterator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class PortMappingTest {
    PortMapping mapping;

    @Before
    public void setup() {
        Context ctx = ApplicationProvider.getApplicationContext();
        mapping = new PortMapping(ctx);
        mapping.clear();
    }

    @Test
    public void testAddAndIterate() {
        PortMapping.PortMap m = new PortMapping.PortMap(6, 80, 8080, "127.0.0.1");
        assertTrue(mapping.add(m));

        Iterator<PortMapping.PortMap> it = mapping.iter();
        assertTrue(it.hasNext());
        PortMapping.PortMap got = it.next();
        assertEquals(6, got.ipproto);
        assertEquals(80, got.orig_port);
        assertEquals(8080, got.redirect_port);
        assertEquals("127.0.0.1", got.redirect_host);
    }

    @Test
    public void testDuplicateRejection() {
        PortMapping.PortMap m1 = new PortMapping.PortMap(6, 80, 8080, "127.0.0.1");
        assertTrue(mapping.add(m1));

        PortMapping.PortMap m2 = new PortMapping.PortMap(6, 80, 8080, "127.0.0.1");
        assertFalse(mapping.add(m2));
    }

    @Test
    public void testDifferentProtoNotDuplicate() {
        PortMapping.PortMap tcp = new PortMapping.PortMap(6, 80, 8080, "127.0.0.1");
        PortMapping.PortMap udp = new PortMapping.PortMap(17, 80, 8080, "127.0.0.1");
        assertTrue(mapping.add(tcp));
        assertTrue(mapping.add(udp));
    }

    @Test
    public void testRemove() {
        PortMapping.PortMap m = new PortMapping.PortMap(6, 443, 8443, "10.0.0.1");
        mapping.add(m);
        assertTrue(mapping.remove(m));
        assertFalse(mapping.iter().hasNext());
    }

    @Test
    public void testRemoveNonExistent() {
        PortMapping.PortMap m = new PortMapping.PortMap(6, 443, 8443, "10.0.0.1");
        assertFalse(mapping.remove(m));
    }

    @Test
    public void testJsonRoundtrip() {
        mapping.add(new PortMapping.PortMap(6, 80, 8080, "127.0.0.1"));
        mapping.add(new PortMapping.PortMap(17, 53, 5353, "10.0.0.1"));

        String json = mapping.toJson(false);

        // reload from json
        mapping.clear();
        assertTrue(mapping.fromJson(json));

        int count = 0;
        Iterator<PortMapping.PortMap> it = mapping.iter();
        while (it.hasNext()) {
            it.next();
            count++;
        }
        assertEquals(2, count);
    }

    @Test
    public void testBackwardCompatibility() {
        // "redirect_ip" is the old JSON key that maps to redirect_host
        String json = "[{\"ipproto\":6,\"orig_port\":80,\"redirect_port\":8080,\"redirect_ip\":\"192.168.1.1\"}]";
        assertTrue(mapping.fromJson(json));

        Iterator<PortMapping.PortMap> it = mapping.iter();
        assertTrue(it.hasNext());
        assertEquals("192.168.1.1", it.next().redirect_host);
    }

    @Test
    public void testMalformedJson() {
        assertFalse(mapping.fromJson("not json"));
    }

    @Test
    public void testPortMapEquals() {
        PortMapping.PortMap a = new PortMapping.PortMap(6, 80, 8080, "127.0.0.1");
        PortMapping.PortMap b = new PortMapping.PortMap(6, 80, 8080, "127.0.0.1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    public void testPortMapNotEquals() {
        PortMapping.PortMap a = new PortMapping.PortMap(6, 80, 8080, "127.0.0.1");
        PortMapping.PortMap b = new PortMapping.PortMap(6, 80, 9090, "127.0.0.1");
        assertFalse(a.equals(b));
    }
}
