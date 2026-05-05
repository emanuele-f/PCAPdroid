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

package com.emanuelef.remote_capture.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class CaptureList {
    private static final String TAG = "CaptureList";
    private final SharedPreferences mPrefs;
    private final Context mContext;
    private ArrayList<Capture> mCaptures;

    public record TargetApp(int uid, String packageName, String name) {}

    public static class Capture {
        public final String uri;
        public String name;
        public final long startTime;
        public final long duration;
        public final long size;
        public final long bytesCaptured;
        public boolean decrypted;
        public List<TargetApp> targetApps;

        public Capture(String uri, String name, long startTime, long duration,
                       long size, long bytesCaptured, boolean decrypted,
                       List<TargetApp> targetApps)
        {
            this.uri = uri;
            this.name = name;
            this.startTime = startTime;
            this.duration = duration;
            this.size = size;
            this.bytesCaptured = bytesCaptured;
            this.decrypted = decrypted;
            this.targetApps = (targetApps != null) ? targetApps : new ArrayList<>();
        }
    }

    public CaptureList(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        reload();
    }

    public void reload() {
        String serialized = mPrefs.getString(Prefs.PREF_CAPTURE_LIST, "");
        if (!serialized.isEmpty())
            fromJson(serialized);
        else
            mCaptures = new ArrayList<>();

        boolean changed = false;
        Iterator<Capture> it = mCaptures.iterator();
        while (it.hasNext()) {
            Capture c = it.next();
            try {
                String path = Utils.uriToFilePath(mContext, Uri.parse(c.uri));
                if ((path == null) || !new File(path).exists()) {
                    it.remove();
                    Log.i(TAG, "removing deleted capture: " + c.uri);
                    changed = true;
                    continue;
                }
                if (c.decrypted && !path.endsWith(".pcapng") && (Utils.findSiblingKeylog(path) == null)) {
                    Log.i(TAG, "clearing decrypted flag for " + c.uri + " (sibling keylog missing)");
                    c.decrypted = false;
                    changed = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "reload: check failed for " + c.uri + ": " + e.getMessage());
            }
        }
        if (changed)
            save();
    }

    public void save() {
        mPrefs.edit()
                .putString(Prefs.PREF_CAPTURE_LIST, toJson())
                .apply();
    }

    public boolean fromJson(String json_str) {
        try {
            Type listType = new TypeToken<ArrayList<Capture>>() {}.getType();
            Gson gson = new Gson();
            mCaptures = gson.fromJson(json_str, listType);
            if (mCaptures == null)
                mCaptures = new ArrayList<>();
            for (Capture c: mCaptures) {
                if (c.targetApps == null)
                    c.targetApps = new ArrayList<>();
            }
            return true;
        } catch (JsonParseException e) {
            Log.e(TAG, "fromJson: " + e.getMessage());
            mCaptures = new ArrayList<>();
            return false;
        }
    }

    public String toJson() {
        Gson gson = new GsonBuilder().create();
        return gson.toJson(mCaptures);
    }

    public void add(Capture capture) {
        mCaptures.add(0, capture);
        save();
    }

    public void remove(Collection<Capture> captures) {
        mCaptures.removeAll(captures);
        save();
    }

    public void rename(Capture capture, String newName) {
        capture.name = newName;
        save();
    }

    public List<Capture> getCaptures() {
        return Collections.unmodifiableList(mCaptures);
    }

    public int size() {
        return mCaptures.size();
    }

    public long getTotalSize() {
        long total = 0;
        for (Capture c: mCaptures)
            total += c.size;
        return total;
    }

    public static String formatTargetApps(Context context, List<CaptureList.TargetApp> targetApps) {
        if ((targetApps == null) || targetApps.isEmpty())
            return context.getString(R.string.no_apps);
        if (targetApps.size() == 1)
            return targetApps.get(0).name();
        return context.getString(R.string.app_and_n_more, targetApps.get(0).name(), targetApps.size() - 1);
    }
}