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

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.AsyncTaskLoader;
import androidx.loader.content.Loader;

import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.model.AppDescriptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppsLoader implements LoaderManager.LoaderCallbacks<HashMap<Integer, AppDescriptor>> {
    private static final String TAG = "AppsLoader";
    private static final int OPERATION_LOAD_APPS_INFO = 23;
    private static final int OPERATION_LOAD_APPS_ICONS = 24;
    private AppsLoadListener mListener;
    private final AppCompatActivity mContext;
    private final Drawable mVirtualAppIcon;

    public AppsLoader(AppCompatActivity context) {
        mContext = context;
        mVirtualAppIcon = ContextCompat.getDrawable(mContext, android.R.drawable.sym_def_app_icon);
    }

    public AppsLoader setAppsLoadListener(AppsLoadListener listener) {
        mListener = listener;
        return this;
    }

    private HashMap<Integer, AppDescriptor> asyncLoadAppsInfo() {
        final PackageManager pm = mContext.getPackageManager();
        HashMap<Integer, AppDescriptor> apps = new HashMap<>();

        Log.d(TAG, "Loading APPs...");
        List<PackageInfo> packs = pm.getInstalledPackages(0);
        String app_package = mContext.getApplicationContext().getPackageName();

        Log.d(TAG, "num apps (system+user): " + packs.size());
        long tstart = Utils.now();

        // https://android.googlesource.com/platform/system/core/+/master/libcutils/include/private/android_filesystem_config.h
        // NOTE: these virtual apps cannot be used as a permanent filter (via addAllowedApplication)
        // as they miss a valid package name
        apps.put(0, new AppDescriptor("Root",
                mVirtualAppIcon,"root", 0, true, true));
        apps.put(1000, new AppDescriptor("Android",
                mVirtualAppIcon,"android", 1000, true, true));
        apps.put(1013, new AppDescriptor("MediaServer",
                mVirtualAppIcon,"mediaserver", 1013, true, true));
        apps.put(1020, new AppDescriptor("MulticastDNSResponder",
                mVirtualAppIcon,"multicastdnsresponder", 1020, true, true));
        apps.put(1021, new AppDescriptor("GPS",
                mVirtualAppIcon,"gps", 1021, true, true));
        apps.put(1051, new AppDescriptor("netd",
                mVirtualAppIcon,"netd", 1051, true, true));
        apps.put(9999, new AppDescriptor("Nobody",
                mVirtualAppIcon,"nobody", 9999, true, true));

        // NOTE: a single uid can correspond to multiple apps, only take the first one
        for (int i = 0; i < packs.size(); i++) {
            PackageInfo p = packs.get(i);
            boolean is_system = (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

            String package_name = p.applicationInfo.packageName;

            if(!apps.containsKey(p.applicationInfo.uid) && !package_name.equals(app_package)) {
                String appName = p.applicationInfo.loadLabel(pm).toString();

                int uid = p.applicationInfo.uid;
                apps.put(uid, new AppDescriptor(appName, null, package_name, uid, is_system, false));

                //Log.d(TAG, appName + " - " + package_name + " [" + uid + "]" + (is_system ? " - SYS" : " - USR"));
            }
        }

        Log.d(TAG, packs.size() + " apps loaded in " + (Utils.now() - tstart) +" seconds");
        return apps;
    }

    private void asyncLoadAppsIcons(HashMap<Integer, AppDescriptor> apps) {
        final PackageManager pm = mContext.getPackageManager();
        long tstart = Utils.now();

        Log.d(TAG, "Loading " + apps.size() + " app icons...");

        for (Map.Entry<Integer, AppDescriptor> pair : apps.entrySet()) {
            AppDescriptor app = pair.getValue();
            PackageInfo p;

            if(app.getIcon() != null)
                continue;

            try {
                p = pm.getPackageInfo(app.getPackageName(), 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "could no retrieve package: " + app.getPackageName());
                continue;
            }

            // NOTE: this call is expensive
            Drawable icon = p.applicationInfo.loadIcon(pm);

            app.setIcon(icon);
        }

        Log.d(TAG, apps.size() + " apps icons loaded in " + (Utils.now() - tstart) +" seconds");
    }

    @NonNull
    @Override
    public Loader<HashMap<Integer, AppDescriptor>> onCreateLoader(int opid, @Nullable Bundle args) {
        return new AsyncTaskLoader<HashMap<Integer, AppDescriptor>>(mContext) {
            @NonNull
            @Override
            public HashMap<Integer, AppDescriptor> loadInBackground() {
                if(opid == OPERATION_LOAD_APPS_INFO)
                    return asyncLoadAppsInfo();
                else if (opid == OPERATION_LOAD_APPS_ICONS) {
                    if(args == null) {
                        Log.e(TAG, "Bad bundle");
                        return null;
                    }

                    HashMap<Integer, AppDescriptor> apps = (HashMap<Integer, AppDescriptor>) args.getSerializable("apps");

                    if(apps == null) {
                        Log.e(TAG, "Bad apps");
                        return null;
                    }

                    asyncLoadAppsIcons(apps);
                    return apps;
                }

                Log.e(TAG, "unknown loader op: " + opid);
                return null;
            }
        };
    }

    @Override
    public void onLoadFinished(@NonNull Loader<HashMap<Integer, AppDescriptor>> loader, HashMap<Integer, AppDescriptor> data) {
        boolean load_finished = (loader.getId() == OPERATION_LOAD_APPS_ICONS);

        if(mListener != null) {
            if(load_finished)
                mListener.onAppsIconsLoaded(data);
            else
                mListener.onAppsInfoLoaded(data);
        }

        if(!load_finished)
            runLoader(OPERATION_LOAD_APPS_ICONS, data);
    }

    @Override
    public void onLoaderReset(@NonNull Loader<HashMap<Integer, AppDescriptor>> loader) {}

    private void runLoader(int opid, HashMap<Integer, AppDescriptor> data) {
        LoaderManager lm = LoaderManager.getInstance(mContext);
        Loader<HashMap<Integer, AppDescriptor>> loader = lm.getLoader(opid);

        Bundle bundle = new Bundle();
        bundle.putSerializable("apps", data);

        Log.d(TAG, "Existing loader " + opid + "? " + (loader != null));

        if(loader==null)
            loader = lm.initLoader(opid, bundle, this);
        else
            loader = lm.restartLoader(opid, bundle, this);

        loader.forceLoad();
    }

    public void loadAllApps() {
        // will run OPERATION_LOAD_APPS_ICONS when finished
        runLoader(OPERATION_LOAD_APPS_INFO, null);
    }
}