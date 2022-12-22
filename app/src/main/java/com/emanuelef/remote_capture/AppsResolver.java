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
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;

import com.emanuelef.remote_capture.interfaces.DrawableLoader;
import com.emanuelef.remote_capture.model.AppDescriptor;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class AppsResolver {
    private static final String TAG = "AppsResolver";
    private final SparseArray<AppDescriptor> mApps;
    private final PackageManager mPm;
    private final Context mContext;
    private Method getPackageInfoAsUser;
    private boolean mFallbackToGlobalResolution;
    private Drawable mVirtualAppIcon;

    public AppsResolver(Context context) {
        mApps = new SparseArray<>();
        mContext = context;
        mPm = context.getPackageManager();

        initVirtualApps();
    }

    private void initVirtualApps() {
        // Use loaders to only load the bitmap in memory if requested via AppDescriptor.getIcon()
        final DrawableLoader virtualIconLoader = () -> {
            // cache this to avoid copies
            if(mVirtualAppIcon == null)
                mVirtualAppIcon = ContextCompat.getDrawable(mContext, android.R.drawable.sym_def_app_icon);
            return mVirtualAppIcon;
        };
        final DrawableLoader unknownIconLoader = () -> ContextCompat.getDrawable(mContext, android.R.drawable.ic_menu_help);

        // https://android.googlesource.com/platform/system/core/+/master/libcutils/include/private/android_filesystem_config.h
        // NOTE: these virtual apps cannot be used as a permanent filter (via addAllowedApplication)
        // as they miss a valid package name
        mApps.put(Utils.UID_UNKNOWN, new AppDescriptor(mContext.getString(R.string.unknown_app),
             unknownIconLoader, "unknown", Utils.UID_UNKNOWN, true)
            .setDescription(mContext.getString(R.string.unknown_app_info)));
        mApps.put(0, new AppDescriptor("Root",
                virtualIconLoader,"root", 0, true)
                .setDescription(mContext.getString(R.string.root_app_info)));
        mApps.put(1000, new AppDescriptor("Android",
                virtualIconLoader,"android", 1000, true)
                .setDescription(mContext.getString(R.string.android_app_info)));
        mApps.put(1001, new AppDescriptor(mContext.getString(R.string.phone_app),
                virtualIconLoader,"phone", 1001, true)
                .setDescription(mContext.getString(R.string.phone_app_info)));
        mApps.put(1013, new AppDescriptor("MediaServer",
                virtualIconLoader,"mediaserver", 1013, true));
        mApps.put(1020, new AppDescriptor("MulticastDNSResponder",
                virtualIconLoader,"multicastdnsresponder", 1020, true));
        mApps.put(1021, new AppDescriptor("GPS",
                virtualIconLoader,"gps", 1021, true));
        mApps.put(1051, new AppDescriptor("netd",
                virtualIconLoader,"netd", 1051, true)
                 .setDescription(mContext.getString(R.string.netd_app_info)));
        mApps.put(9999, new AppDescriptor("Nobody",
                virtualIconLoader,"nobody", 9999, true));
    }

    // Get the AppDescriptor corresponding to the given package name
    // No caching occurs. Virtual apps cannot be used.
    // This is public to provide a fast resolution alternative to getAppByPackage
    public static AppDescriptor resolveInstalledApp(PackageManager pm, String packageName, int pm_flags, boolean warn_not_found) {
        PackageInfo pinfo;

        try {
            pinfo = Utils.getPackageInfo(pm, packageName, pm_flags);
        } catch (PackageManager.NameNotFoundException e) {
            if(warn_not_found)
                Log.w(TAG, "could not retrieve package: " + packageName);
            return null;
        }

        return new AppDescriptor(pm, pinfo);
    }

    public static AppDescriptor resolveInstalledApp(PackageManager pm, String packageName, int pm_flags) {
        return resolveInstalledApp(pm, packageName, pm_flags, true);
    }

    @SuppressLint("DiscouragedPrivateApi")
    public @Nullable AppDescriptor getAppByUid(int uid, int pm_flags) {
        AppDescriptor app = mApps.get(uid);
        if(app != null)
            return app;

        // Map the uid to the package name(s)
        String[] packages = null;

        try {
            packages = mPm.getPackagesForUid(uid);
        } catch (SecurityException e) {
            // A SecurityException is normally raised when trying to query a package of another user/profile
            // without holding the INTERACT_ACROSS_USERS/INTERACT_ACROSS_PROFILES permissions
            e.printStackTrace();
        }

        if((packages == null) || (packages.length < 1)) {
            Log.w(TAG, "could not retrieve package: uid=" + uid);
            return null;
        }

        // Impose order to guarantee that a uid is always mapped to the same package name.
        // The mapping may change if a package sharing this UID is installed/removed.
        // For simplicity we ignore this change at runtime, and only address it in persistent data
        // (e.g. in the MatchList to ensure that a user can always remove rules see #257)
        String packageName = packages[0];
        for(String pkg: packages) {
            if(pkg.compareTo(packageName) < 0)
                packageName = pkg;
        }

        // In case of root capture, we may be capturing traffic of different users/work profiles.
        // To get the correct label and icon, try to resolve the app as the specific user of the connection.
        if(!mFallbackToGlobalResolution && CaptureService.isCapturingAsRoot()) {
            try {
                if(getPackageInfoAsUser == null)
                    getPackageInfoAsUser = PackageManager.class.getDeclaredMethod("getPackageInfoAsUser", String.class, int.class, int.class);

                PackageInfo pinfo = (PackageInfo) getPackageInfoAsUser.invoke(mPm, packageName, pm_flags, Utils.getUserId(uid));
                if(pinfo != null)
                    app = new AppDescriptor(mPm, pinfo);
            } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                Log.w(TAG, "getPackageInfoAsUser call fails, falling back to standard resolution");
                e.printStackTrace();
                mFallbackToGlobalResolution = true;
            }
        }

        if(app == null)
            app = resolveInstalledApp(mPm, packageName, pm_flags);

        if(app != null)
            mApps.put(uid, app);

        return app;
    }

    public @Nullable AppDescriptor getAppByPackage(String package_name, int pm_flags) {
        int uid = getUid(package_name);
        if(uid == Utils.UID_NO_FILTER)
            return null;

        return getAppByUid(uid, pm_flags);
    }

    /* Lookup a UID by package name (including virtual apps).
     * UID_NO_FILTER is returned if no match is found. */
    public int getUid(String package_name) {
        if(!package_name.contains(".")) {
            // This is a virtual app
            for(int i=0; i<mApps.size(); i++) {
                AppDescriptor app = mApps.valueAt(i);

                if(app.getPackageName().equals(package_name))
                    return app.getUid();
            }
        } else {
            try {
                return Utils.getPackageUid(mPm, package_name, 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "Could not retrieve package " + package_name);
                //e.printStackTrace();
            }
        }

        // Not found
        return Utils.UID_NO_FILTER;
    }

    public void clear() {
        mApps.clear();
        initVirtualApps();
    }
}
