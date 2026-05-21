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
import android.os.Handler;
import android.os.Looper;

import androidx.collection.ArraySet;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CaptureList {
    private static final String TAG = "CaptureList";
    private final SharedPreferences mPrefs;
    private final Context mContext;
    private ArrayList<Capture> mCaptures;
    private ExecutorService mScanExecutor;
    private Set<String> mRenamedDuringScan;

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
    }

    private record ScanDiff(Set<String> missing, Set<String> clearDecrypted) {}

    public interface OnScanDoneListener {
        void onScanDone(boolean changed);
    }

    /**
     * Check the files actually on disk and update the model when done.
     * Use abortScan() to cancel the scan if the listener is deallocated.
     *
     * @param listener onScanDone is invoked when done
     * @return true if scan started, false otherwise
     */
    public boolean scanForDeletedFiles(@NotNull OnScanDoneListener listener) {
        if (mScanExecutor != null)
            return false;

        final Handler handler = new Handler(Looper.getMainLooper());

        if (mCaptures.isEmpty()) {
            handler.post(() -> listener.onScanDone(false));
            return true;
        }

        ArrayList<String> uris = new ArrayList<>(mCaptures.size());
        ArrayList<Boolean> decryptedFlags = new ArrayList<>(mCaptures.size());
        for (Capture c: mCaptures) {
            uris.add(c.uri);
            decryptedFlags.add(c.decrypted);
        }

        mRenamedDuringScan = new ArraySet<>();
        mScanExecutor = Executors.newSingleThreadExecutor();
        mScanExecutor.submit(() -> {
            ScanDiff diff = scan(uris, decryptedFlags);
            handler.post(() -> onScanResult(diff, listener));
        });

        return true;
    }

    public void abortScan() {
        if (mScanExecutor != null) {
            mScanExecutor.shutdownNow();
            mScanExecutor = null;
            mRenamedDuringScan = null;
        }
    }

    private ScanDiff scan(List<String> uris, List<Boolean> decryptedFlags) {
        Set<String> missing = new HashSet<>();
        Set<String> clearDecrypted = new HashSet<>();

        for (int i = 0; i < uris.size(); i++) {
            String uri = uris.get(i);
            try {
                String path = Utils.uriToFilePath(mContext, Uri.parse(uri));
                if ((path == null) || !new File(path).exists()) {
                    missing.add(uri);
                    continue;
                }

                if (decryptedFlags.get(i) && !path.endsWith(".pcapng") &&
                        (Utils.findSiblingKeylog(path) == null))
                {
                    clearDecrypted.add(uri);
                }
            } catch (Exception e) {
                Log.w(TAG, "scan: check failed for " + uri + ": " + e.getMessage());
            }
        }

        return new ScanDiff(missing, clearDecrypted);
    }

    private void onScanResult(ScanDiff diff, @NotNull OnScanDoneListener listener) {
        if (mScanExecutor == null)
            // scan was aborted
            return;

        boolean changed = false;
        Iterator<Capture> it = mCaptures.iterator();
        while (it.hasNext()) {
            Capture c = it.next();

            if (mRenamedDuringScan.contains(c.uri))
                // ignore renamed captures, as they may have changed name on disk
                continue;

            if (diff.missing.contains(c.uri)) {
                Log.i(TAG, "removing deleted capture: " + c.uri);
                it.remove();
                changed = true;
            } else if (c.decrypted && diff.clearDecrypted.contains(c.uri)) {
                Log.i(TAG, "clearing decrypted flag for " + c.uri + " (sibling keylog missing)");
                c.decrypted = false;
                changed = true;
            }
        }

        mScanExecutor = null;
        mRenamedDuringScan = null;

        if (changed)
            save();

        listener.onScanDone(changed);
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

        if (mRenamedDuringScan != null)
            mRenamedDuringScan.add(capture.uri);

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