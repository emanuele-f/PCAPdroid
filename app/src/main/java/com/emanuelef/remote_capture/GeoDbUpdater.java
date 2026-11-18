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
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.model.Prefs;

import java.util.Calendar;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/* Manages the geolocation DB downloads.
 * No background job is scheduled: the update only runs while the app process is alive. If the
 * capture is running, it reloads the new DB automatically (see Geolocation.checkReload). */
public class GeoDbUpdater {
    private static final String TAG = "GeoDbUpdater";
    private static final long HOUR_MS = 3600 * 1000L;

    // spread the downloads over the first days of the month, to avoid excessive load on the server
    private static final long MIN_UPDATE_DELAY_MS = 6 * HOUR_MS;
    private static final long MAX_UPDATE_DELAY_MS = 2 * 24 * HOUR_MS;
    private static final long MIN_RETRY_DELAY_MS = HOUR_MS;
    private static final long MAX_RETRY_DELAY_MS = 24 * HOUR_MS;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mCheckRunnable = this::check;
    private final Random mRandom = new Random();
    private final MutableLiveData<Boolean> mDownloading = new MutableLiveData<>(false);
    private long mRetryDelayMs = MIN_RETRY_DELAY_MS;

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener = (prefs, key) -> {
        if(Prefs.PREF_GEO_DB_AUTO_UPDATE.equals(key)) {
            Prefs.setGeoDbNextUpdate(prefs, 0);
            check();
        }
    };

    public GeoDbUpdater(Context ctx) {
        mContext = ctx.getApplicationContext();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mContext);
    }

    // must be called from the main thread
    public void start() {
        mPrefs.registerOnSharedPreferenceChangeListener(mPrefsListener);
        check();
    }

    public void observeDownloading(LifecycleOwner lifecycleOwner, Observer<Boolean> observer) {
        mDownloading.observe(lifecycleOwner, observer);
    }

    private static long getNextMonthStart() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.MONTH, 1);
        return cal.getTimeInMillis();
    }

    // Downloads the DB on user request. Must be called from the main thread
    public void download(Consumer<Boolean> onComplete) {
        if(Boolean.TRUE.equals(mDownloading.getValue()))
            return;

        Log.i(TAG, "Manual DB download");
        startDownload(true, onComplete);
    }

    private void check() {
        mHandler.removeCallbacks(mCheckRunnable);

        if(Boolean.TRUE.equals(mDownloading.getValue()) || !Prefs.isGeoDbAutoUpdateEnabled(mPrefs))
            return;

        mHandler.postDelayed(mCheckRunnable, HOUR_MS);

        long now = System.currentTimeMillis();
        long nextUpdate = Prefs.getGeoDbNextUpdate(mPrefs);

        // also consider possible wall clock adjustments
        if((nextUpdate > 0) &&
                (nextUpdate <= getNextMonthStart() + MAX_UPDATE_DELAY_MS) &&
                (now < nextUpdate))
            // retry later
            return;

        if(Geolocation.isDbUpToDate(mContext)) {
            Log.d(TAG, "DB is up to date");
            setNextUpdate(false);
            return;
        }

        Log.i(TAG, "Downloading the DB");
        startDownload(false,null);
    }

    private void startDownload(boolean force_overwrite, @Nullable Consumer<Boolean> onComplete) {
        mDownloading.setValue(true);

        new Thread(() -> {
            boolean success = force_overwrite ? Geolocation.downloadDb(mContext) : Geolocation.downloadDbIfOutdated(mContext);

            mHandler.post(() -> {
                mDownloading.setValue(false);

                if(onComplete != null)
                    onComplete.accept(success);
                else if(success) {
                    Log.i(TAG, "Geolocation DB updated");
                    setNextUpdate(false);
                } else {
                    Log.e(TAG, "Geolocation DB update failed, retrying in " + (mRetryDelayMs / HOUR_MS) + " h");
                    setNextUpdate(true);
                }

                check();
            });
        }, "GeoDbUpdater").start();
    }

    private void setNextUpdate(boolean backoff) {
        long when;

        if(backoff) {
            when = System.currentTimeMillis() + mRetryDelayMs;
            mRetryDelayMs = Math.min(mRetryDelayMs * 2, MAX_RETRY_DELAY_MS);
        } else {
            long delay = MIN_UPDATE_DELAY_MS +
                    (long) (mRandom.nextDouble() * (MAX_UPDATE_DELAY_MS - MIN_UPDATE_DELAY_MS));

            when = getNextMonthStart() + delay;
            mRetryDelayMs = MIN_RETRY_DELAY_MS;
        }

        Log.d(TAG, "Next update in " + ((when - System.currentTimeMillis()) / 1000) + " s");
        Prefs.setGeoDbNextUpdate(mPrefs, when);
    }
}
