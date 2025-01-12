package com.emanuelef.remote_capture.model;

import android.content.Context;
import android.os.SystemClock;
import android.util.ArrayMap;

import androidx.appcompat.app.AlertDialog;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Geolocation;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;

import java.util.Iterator;
import java.util.Map;

public class Blocklist extends MatchList {
    private static final String TAG = "Blocklist";
    private boolean mGeoWarningShown = false;

    // access to mUidToGrace must be synchronized as it can happen either from the UI thread or from
    // the CaptureService.connUpdateWork thread
    private final ArrayMap<Integer, Long> mUidToGrace = new ArrayMap<>();

    public Blocklist(Context ctx) {
        super(ctx, Prefs.PREF_BLOCKLIST);
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
