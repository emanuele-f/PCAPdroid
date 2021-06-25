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

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

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
import java.util.HashSet;
import java.util.List;

public class AppsLoader implements LoaderManager.LoaderCallbacks<ArrayList<AppDescriptor>> {
    private static final String TAG = "AppsLoader";
    private static final int OPERATION_LOAD_APPS_INFO = 23;
    private static final int OPERATION_LOAD_APPS_ICONS = 24;
    private AppsLoadListener mListener;
    private final AppCompatActivity mContext;

    public AppsLoader(AppCompatActivity context) {
        mContext = context;
    }

    public AppsLoader setAppsLoadListener(AppsLoadListener listener) {
        mListener = listener;
        return this;
    }

    private ArrayList<AppDescriptor> asyncLoadAppsInfo() {
        final PackageManager pm = mContext.getPackageManager();
        ArrayList<AppDescriptor> apps = new ArrayList<>();
        HashSet<Integer> uids = new HashSet<>();

        Log.d(TAG, "Loading APPs...");
        List<PackageInfo> packs = pm.getInstalledPackages(0);
        String app_package = mContext.getApplicationContext().getPackageName();

        Log.d(TAG, "num apps (system+user): " + packs.size());
        long tstart = Utils.now();

        // NOTE: a single uid can correspond to multiple packages, only take the first package found.
        // The VPNService in android works with UID, so this choice is not restrictive.
        for (int i = 0; i < packs.size(); i++) {
            PackageInfo p = packs.get(i);
            String package_name = p.applicationInfo.packageName;

            if(!uids.contains(p.applicationInfo.uid) && !package_name.equals(app_package)) {
                int uid = p.applicationInfo.uid;
                AppDescriptor app = new AppDescriptor(pm, p);

                apps.add(app);
                uids.add(uid);

                //Log.d(TAG, appName + " - " + package_name + " [" + uid + "]" + (is_system ? " - SYS" : " - USR"));
            }
        }

        Collections.sort(apps);

        Log.d(TAG, packs.size() + " apps loaded in " + (Utils.now() - tstart) +" seconds");
        return apps;
    }

    private void asyncLoadAppsIcons(ArrayList<AppDescriptor> apps) {
        final PackageManager pm = mContext.getPackageManager();
        long tstart = Utils.now();

        Log.d(TAG, "Loading " + apps.size() + " app icons...");

        for (AppDescriptor app : apps) {
            // Force icon load
            app.getIcon();
        }

        Log.d(TAG, apps.size() + " apps icons loaded in " + (Utils.now() - tstart) +" seconds");
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
                else if (opid == OPERATION_LOAD_APPS_ICONS) {
                    if(args == null) {
                        Log.e(TAG, "Bad bundle");
                        return empty_res;
                    }

                    ArrayList<AppDescriptor> apps = (ArrayList<AppDescriptor>) args.getSerializable("apps");

                    if(apps == null) {
                        Log.e(TAG, "Bad apps");
                        return empty_res;
                    }

                    asyncLoadAppsIcons(apps);
                    return apps;
                }

                Log.e(TAG, "unknown loader op: " + opid);
                return empty_res;
            }
        };
    }

    @Override
    public void onLoadFinished(@NonNull Loader<ArrayList<AppDescriptor>> loader, ArrayList<AppDescriptor> data) {
        boolean load_finished = (loader.getId() == OPERATION_LOAD_APPS_ICONS);

        if(mListener != null) {
            if(load_finished)
                mListener.onAppsIconsLoaded();
            else
                mListener.onAppsInfoLoaded(data);
        }

        if(!load_finished)
            runLoader(OPERATION_LOAD_APPS_ICONS, data);
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

    public AppsLoader loadAllApps() {
        // will run OPERATION_LOAD_APPS_ICONS when finished
        runLoader(OPERATION_LOAD_APPS_INFO, null);
        return this;
    }
}