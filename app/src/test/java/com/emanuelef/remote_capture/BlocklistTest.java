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

import com.emanuelef.remote_capture.model.Blocklist;
import com.emanuelef.remote_capture.model.MatchList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class BlocklistTest {
    Context ctx;
    String pkg;

    @Before
    public void setup() {
        ctx = ApplicationProvider.getApplicationContext();
        pkg = ctx.getPackageName();
    }

    private Blocklist newBlocklist() {
        Blocklist blocklist = Blocklist.load(ctx);
        blocklist.clear();
        return blocklist;
    }

    @Test
    public void testAppAllowlistRoundtrip() {
        Blocklist blocklist = newBlocklist();
        assertTrue(blocklist.addApp(pkg));

        MatchList allowlist = blocklist.getAppAllowlist(pkg);
        allowlist.addHost("example.org");
        allowlist.addIp("1.2.3.4");

        // The allowlist is embedded in the owning blocklist JSON
        String json = blocklist.toJson(false);
        assertTrue(json.contains("allowlist"));
        assertTrue(json.contains("example.org"));

        Blocklist restored = newBlocklist();
        restored.fromJson(json);

        MatchList restoredAllowlist = restored.getAppAllowlist(pkg);
        assertEquals(2, restoredAllowlist.getSize());
        assertTrue(restoredAllowlist.matchesExactHost("example.org"));
        assertTrue(restoredAllowlist.matchesExactIP("1.2.3.4"));
    }

    @Test
    public void testAllowlistPersistedToPreference() {
        Blocklist blocklist = newBlocklist();
        assertTrue(blocklist.addApp(pkg));

        blocklist.getAppAllowlist(pkg).addHost("example.org");
        blocklist.getAppAllowlist(pkg).addIp("1.2.3.4");

        // Mimic the editor save flow (ListInfo.save for APP_ALLOWLIST): the allowlist has no
        // backing preference of its own, so it is persisted as part of the owning blocklist.
        blocklist.save();

        // A fresh blocklist reloads its rules (and embedded allowlists) from the preference
        Blocklist reloaded = Blocklist.load(ctx);
        MatchList reloadedAllowlist = reloaded.getAppAllowlist(pkg);
        assertEquals(2, reloadedAllowlist.getSize());
        assertTrue(reloadedAllowlist.matchesExactHost("example.org"));
        assertTrue(reloadedAllowlist.matchesExactIP("1.2.3.4"));
    }

    @Test
    public void testAllowlistImportMerge() {
        Blocklist src = newBlocklist();
        src.addApp(pkg);
        src.getAppAllowlist(pkg).addHost("example.org");
        String json = src.toJson(false);

        // Mimic the import flow: parse into a buffer, then merge into the destination blocklist
        Blocklist dst = newBlocklist();
        MatchList buffer = dst.newEmptyList();
        buffer.fromJson(json);
        dst.addRules(buffer);

        MatchList merged = dst.getAppAllowlist(pkg);
        assertEquals(1, merged.getSize());
        assertTrue(merged.matchesExactHost("example.org"));

        // the merged allowlist survives a serialize/reload of the destination
        Blocklist reloaded = newBlocklist();
        reloaded.fromJson(dst.toJson(false));
        assertTrue(reloaded.getAppAllowlist(pkg).matchesExactHost("example.org"));
    }

    @Test
    public void testAllowlistDroppedOnAppRemoval() {
        Blocklist blocklist = newBlocklist();
        blocklist.addApp(pkg);
        blocklist.getAppAllowlist(pkg).addHost("example.org");

        blocklist.removeApp(pkg);
        assertTrue(blocklist.getAppAllowlist(pkg).isEmpty());

        // a re-added app starts with no allowlist, so nothing is serialized
        blocklist.addApp(pkg);
        assertFalse(blocklist.toJson(false).contains("allowlist"));
    }

    @Test
    public void testAllowlistClearedWithBlocklist() {
        Blocklist blocklist = newBlocklist();
        blocklist.addApp(pkg);
        blocklist.getAppAllowlist(pkg).addHost("example.org");

        blocklist.clear();
        assertTrue(blocklist.getAppAllowlist(pkg).isEmpty());
    }

    @Test
    public void testEmptyAllowlistNotSerialized() {
        Blocklist blocklist = newBlocklist();
        blocklist.addApp(pkg);

        // touching getAppAllowlist creates an (empty) entry, which must not be serialized
        blocklist.getAppAllowlist(pkg);
        assertFalse(blocklist.toJson(false).contains("allowlist"));
    }
}
