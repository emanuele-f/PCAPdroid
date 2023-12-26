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
 * Copyright 2020-21 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.annotation.SuppressLint;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.ArrayMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.AsyncTaskLoader;
import androidx.loader.content.Loader;

import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.model.AppDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppsLoader implements LoaderManager.LoaderCallbacks<ArrayList<AppDescriptor>> {
    private static final String TAG = "AppsLoader";
    private static final int OPERATION_LOAD_APPS_INFO = 23;
    private AppsLoadListener mListener;
    private final AppCompatActivity mContext;
    private static final String TERMUX_PACKAGE = "com.termux";

    public AppsLoader(AppCompatActivity context) {
        mContext = context;
    }

    public AppsLoader setAppsLoadListener(AppsLoadListener listener) {
        mListener = listener;
        return this;
    }

    @SuppressLint("QueryPermissionsNeeded")
    private ArrayList<AppDescriptor> asyncLoadAppsInfo() {
        final PackageManager pm = mContext.getPackageManager();
        ArrayList<AppDescriptor> apps = new ArrayList<>();
        ArrayMap<Integer, Integer> uid_to_pos = new ArrayMap<>();

        Log.d(TAG, "Loading APPs...");
        List<PackageInfo> packs = Utils.getInstalledPackages(pm, 0);

        String app_package = mContext.getApplicationContext().getPackageName();

        Log.d(TAG, "num apps (system+user): " + packs.size());
        long tstart = Utils.now();

        PackageInfo termuxPkgInfo = null;

        // NOTE: a single uid can correspond to multiple packages, only take the first package found.
        // The VPNService in android works with UID, so this choice is not restrictive.
        for (int i = 0; i < packs.size(); i++) {
            PackageInfo p = packs.get(i);
            String package_name = p.applicationInfo.packageName;

            if (package_name.equals(TERMUX_PACKAGE))
                termuxPkgInfo = p;

            if(!uid_to_pos.containsKey(p.applicationInfo.uid) && !package_name.equals(app_package)) {
                int uid = p.applicationInfo.uid;
                AppDescriptor app = new AppDescriptor(pm, p);

                uid_to_pos.put(uid, apps.size());
                apps.add(app);

                //Log.d(TAG, appName + " - " + package_name + " [" + uid + "]" + (is_system ? " - SYS" : " - USR"));
            }
        }

        if (termuxPkgInfo != null) {
            // termux packages share the same UID. Use the main package if available. See #253
            int uid = termuxPkgInfo.applicationInfo.uid;
            Integer pos = uid_to_pos.get(uid);

            if (pos != null) {
                apps.remove(pos.intValue());
                apps.add(new AppDescriptor(pm, termuxPkgInfo));
            }
        }

        Collections.sort(apps);

        Log.d(TAG, packs.size() + " apps loaded in " + (Utils.now() - tstart) +" seconds");
        return apps;
    }

    @NonNull
    @Override
    public Loader<ArrayList<AppDescriptor>> onCreateLoader(int opid, @Nullable Bundle args) {
        return new AsyncTaskLoader<ArrayList<AppDescriptor>>(mContext) {
            @NonNull
            @Override
            public ArrayList<AppDescriptor> loadInBackground() {
                ArrayList<AppDescriptor> empty_res = new ArrayList<>();

                if(opid == OPERATION_LOAD_APPS_INFO)
                    return asyncLoadAppsInfo();

                Log.e(TAG, "unknown loader op: " + opid);
                return empty_res;
            }
        };
    }

    @Override
    public void onLoadFinished(@NonNull Loader<ArrayList<AppDescriptor>> loader, ArrayList<AppDescriptor> data) {
        if(mListener != null)
            mListener.onAppsInfoLoaded(data);

        finishLoader();
    }

    @Override
    public void onLoaderReset(@NonNull Loader<ArrayList<AppDescriptor>> loader) {
        Log.d(TAG, "onLoaderReset");
    }

    private void runLoader(int opid, ArrayList<AppDescriptor> data) {
        LoaderManager lm = LoaderManager.getInstance(mContext);
        Loader<ArrayList<AppDescriptor>> loader = lm.getLoader(opid);

        Bundle bundle = new Bundle();
        bundle.putSerializable("apps", data);

        Log.d(TAG, "Existing loader " + opid + "? " + (loader != null));

        loader = lm.initLoader(opid, bundle, this);
        loader.forceLoad();
    }

    private void finishLoader() {
        // Destroy the loader to reduce the memory usage and also to possibly load new apps on next run
        LoaderManager lm = LoaderManager.getInstance(mContext);
        lm.destroyLoader(OPERATION_LOAD_APPS_INFO);
    }

    public AppsLoader loadAllApps() {
        // IMPORTANT: loading all the icons is not a good idea, as they consume much memory
        runLoader(OPERATION_LOAD_APPS_INFO, null);
        return this;
    }

    public void abort() {
        finishLoader();
    }
}
