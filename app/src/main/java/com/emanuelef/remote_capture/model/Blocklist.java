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

package com.emanuelef.remote_capture.model;

import android.content.Context;
import android.os.SystemClock;
import android.util.ArrayMap;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Geolocation;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;

public class Blocklist extends MatchList {
    private static final String TAG = "Blocklist";
    private boolean mGeoWarningShown = false;

    // access to mUidToGrace must be synchronized as it can happen either from the UI thread or from
    // the CaptureService.connUpdateWork thread
    private final ArrayMap<Integer, Long> mUidToGrace = new ArrayMap<>();

    // Per-app allowlists, keyed by package name
    private final ArrayMap<String, MatchList> mAppAllowlists = new ArrayMap<>();

    public Blocklist(Context ctx) {
        this(ctx, Prefs.PREF_BLOCKLIST);
    }

    private Blocklist(Context ctx, @NotNull String pref_name) {
        super(ctx, pref_name);
    }

    public static Blocklist load(Context ctx) {
        Blocklist blocklist = new Blocklist(ctx);
        blocklist.reload();
        return blocklist;
    }

    /* Returns the per-app allowlist for the given package, creating an empty one if needed */
    @NotNull
    public MatchList getAppAllowlist(String pkg) {
        MatchList list = mAppAllowlists.get(pkg);
        if(list == null) {
            list = new MatchList(getContext(), "");
            mAppAllowlists.put(pkg, list);
        }
        return list;
    }

    @Nullable
    public MatchList findAppAllowlist(String pkg) {
        return mAppAllowlists.get(pkg);
    }

    @Override
    public MatchList newEmptyList() {
        return new Blocklist(getContext(), "");
    }

    @Override
    protected JsonArray serializeAllowlist(Rule rule) {
        if(rule.getType() != RuleType.APP)
            return null;

        MatchList allowlist = mAppAllowlists.get(rule.getValue().toString());
        if((allowlist == null) || allowlist.isEmpty())
            return null;

        JsonArray arr = new JsonArray();
        for(Iterator<Rule> it = allowlist.iterRules(); it.hasNext(); ) {
            Rule alRule = it.next();
            JsonObject obj = new JsonObject();

            obj.add("type", new JsonPrimitive(alRule.getType().name()));
            obj.add("value", new JsonPrimitive(alRule.getValue().toString()));
            arr.add(obj);
        }

        return arr;
    }

    @Override
    public ListDescriptor toListDescriptor() {
        ListDescriptor rv = super.toListDescriptor();

        for(int i = 0; i < mAppAllowlists.size(); i++) {
            MatchList allowlist = mAppAllowlists.valueAt(i);
            if(allowlist.isEmpty())
                continue;

            Integer uid = getAppUid(mAppAllowlists.keyAt(i));
            if(uid == null)
                continue;

            ListDescriptor descr = allowlist.toListDescriptor();
            descr.uid = uid;
            rv.allowlists.add(descr);
        }

        return rv;
    }

    @Override
    protected void deserializeAllowlist(Rule rule, JsonObject ruleObj) {
        if((rule.getType() != RuleType.APP) || !ruleObj.has("allowlist"))
            return;

        MatchList allowlist = getAppAllowlist(rule.getValue().toString());

        try {
            for(JsonElement el : ruleObj.getAsJsonArray("allowlist")) {
                JsonObject obj = el.getAsJsonObject();
                RuleType type;

                try {
                    type = RuleType.valueOf(obj.get("type").getAsString());
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Skipping unknown allowlist rule type: " + obj.get("type").getAsString());
                    continue;
                }

                allowlist.addRule(new Rule(type, obj.get("value").getAsString()), false);
            }
        } catch (IllegalStateException | ClassCastException e) {
            Log.w(TAG, "invalid allowlist for " + rule.getValue() + ": " + e.getMessage());
        }
    }

    // Transfer the per-app allowlists parsed into the import buffer (see newEmptyList) to this
    // list. Persistence is left to the caller, which saves this list after the merge.
    @Override
    public int addRules(MatchList to_add) {
        int num_added = super.addRules(to_add);

        if(to_add instanceof Blocklist) {
            ArrayMap<String, MatchList> imported = ((Blocklist) to_add).mAppAllowlists;

            for(int i = 0; i < imported.size(); i++) {
                MatchList dst = getAppAllowlist(imported.keyAt(i));

                for(Iterator<Rule> it = imported.valueAt(i).iterRules(); it.hasNext(); )
                    dst.addRule(it.next(), false);
            }
        }

        return num_added;
    }

    @Override
    public void removeRule(Rule rule) {
        super.removeRule(rule);

        // An allowlist only makes sense while its app is blocked, so drop it
        // whenever the corresponding APP rule is removed from the blocklist
        if(rule.getType() == RuleType.APP)
            mAppAllowlists.remove(rule.getValue().toString());
    }

    @Override
    public void clear(boolean notify) {
        super.clear(notify);

        mAppAllowlists.clear();
    }

    public synchronized boolean unblockAppForMinutes(int uid, int minutes) {
        Long old_val = mUidToGrace.put(uid, SystemClock.elapsedRealtime() + (minutes * 60_000L));
        Log.i(TAG, "Grace app: " + uid + " for " + minutes + " minutes (old: " + old_val + ")");
        return (old_val == null);
    }

    public synchronized boolean checkGracePeriods() {
        long now = SystemClock.elapsedRealtime();
        boolean changed = false;
        Iterator<Map.Entry<Integer,Long>> iter = mUidToGrace.entrySet().iterator();

        while(iter.hasNext()) {
            Map.Entry<Integer, Long> entry = iter.next();

            if(now >= entry.getValue()) {
                Log.i(TAG, "Grace period ended for app: " + entry.getKey());
                iter.remove();
                changed = true;
            }
        }

        return changed;
    }

    @Override
    public synchronized boolean isExemptedApp(int uid) {
        return mUidToGrace.containsKey(uid);
    }

    @Override
    public boolean matchesApp(int uid) {
        if(!super.matchesApp(uid))
            return false;

        synchronized (this) {
            return !isExemptedApp(uid);
        }
    }

    @Override
    public synchronized void removeApp(int uid) {
        mUidToGrace.remove(uid);
        super.removeApp(uid);
    }

    @Override
    public synchronized boolean addApp(int uid) {
        mUidToGrace.remove(uid);
        return super.addApp(uid);
    }

    public void saveAndReload() {
        save();

        if(CaptureService.isServiceActive())
            CaptureService.requireInstance().reloadBlocklist();
    }

    public synchronized boolean hasCountryRules() {
        Iterator<MatchList.Rule> it = iterRules();
        while(it.hasNext()) {
            MatchList.Rule rule = it.next();

            if (rule.getType() == MatchList.RuleType.COUNTRY)
                return true;
        }

        return false;
    }

    public void showNoticeIfGeoMissing(Context ctx) {
        if (mGeoWarningShown)
            return;

        if (!Geolocation.isAvailable(ctx)) {
            new AlertDialog.Builder(ctx)
                    .setTitle(R.string.geo_db_missing)
                    .setMessage(R.string.country_rules_warning)
                    .setNeutralButton(R.string.ok, (dialog, whichButton) -> {})
                    .show();

            mGeoWarningShown = true;
        }
    }
}
