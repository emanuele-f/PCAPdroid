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

package com.emanuelef.remote_capture.model;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.UserHandle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.interfaces.DrawableLoader;

import java.io.Serializable;
import java.util.Objects;

public class AppDescriptor implements Comparable<AppDescriptor>, Serializable {
    private final String mName;
    private final String mPackageName;
    private final int mUid;
    private final boolean mIsSystem;
    private final boolean mHasLauncherIntent;
    private Drawable mIcon;
    private final DrawableLoader mIconLoader;
    private String mDescription;
    private static final String TAG = "AppDescriptor";
    private static boolean badgedIconFails = false;

    // NULL for virtual apps
    PackageManager mPm;
    PackageInfo mPackageInfo;

    public AppDescriptor(String name, DrawableLoader icon_loader, String package_name, int uid, boolean is_system) {
        this(name, icon_loader, package_name, uid, is_system, false);
    }

    private AppDescriptor(String name, DrawableLoader icon_loader, String package_name, int uid, boolean is_system, boolean has_launcher) {
        this.mName = name;
        this.mIcon = null;
        this.mIconLoader = icon_loader;
        this.mPackageName = package_name;
        this.mUid = uid;
        this.mIsSystem = is_system;
        this.mHasLauncherIntent = has_launcher;
        this.mDescription = "";
    }

    public AppDescriptor(PackageManager pm, @NonNull PackageInfo pkgInfo) {
        this(pm, pkgInfo, Objects.requireNonNull(pkgInfo.applicationInfo));
    }

    private AppDescriptor(PackageManager pm, PackageInfo pkgInfo, ApplicationInfo appInfo) {
        this(String.valueOf(appInfo.loadLabel(pm)),
                makeIconLoader(pm, appInfo),
                appInfo.packageName, appInfo.uid,
                (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0,
                pm.getLaunchIntentForPackage(appInfo.packageName) != null);

        mPm = pm;
        mPackageInfo = pkgInfo;
    }

    public AppDescriptor setDescription(String dsc) {
        mDescription = dsc;
        return this;
    }

    public String getDescription() {
        return mDescription;
    }

    public String getName() {
        return mName;
    }

    public @Nullable Drawable getCachedIcon() {
        return mIcon;
    }

    // NOTE: must be called from the main thread. For background loading use loadIcon() + setLoadedIcon()
    public @Nullable Drawable getIcon() {
        if((mIcon == null) && (mIconLoader != null))
            mIcon = mIconLoader.getDrawable();
        return mIcon;
    }

    // Calls the icon loader without caching. Safe to call from any thread.
    public @Nullable Drawable loadIcon() {
        if(mIconLoader != null)
            return mIconLoader.getDrawable();
        return null;
    }

    public void setLoadedIcon(@Nullable Drawable icon) {
        mIcon = icon;
    }

    private static DrawableLoader makeIconLoader(PackageManager pm, ApplicationInfo appInfo) {
        int uid = appInfo.uid;

        return () -> {
            // NOTE: this call is expensive
            Drawable icon;

            if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) && CaptureService.isCapturingAsRoot()) {
                // Contrary to "loadIcon", this returns the correct icon for main-profile apps
                // when PCAPdroid is running into a work profile with root. For work-profile apps,
                // the badge is added below via getUserHandleForUid
                icon = appInfo.loadUnbadgedIcon(pm);

                if (!badgedIconFails) {
                    try {
                        UserHandle handle = UserHandle.getUserHandleForUid(uid);

                        // On some systems may throw "java.lang.SecurityException: You need MANAGE_USERS permission to:
                        // check if specified user a managed profile outside your profile group"
                        icon = pm.getUserBadgedIcon(icon, handle);
                    } catch (SecurityException e) {
                        Log.w(TAG, "getUserBadgedIcon failed, using icons without badges: " + e.getMessage());
                        badgedIconFails = true;
                    }
                }
            } else
                icon = appInfo.loadIcon(pm);

            return icon;
        };
    }

    public String getPackageName() {
        return mPackageName;
    }

    public int getUid() {
        return mUid;
    }

    public boolean isSystem() { return mIsSystem; }

    // A system app with no launcher intent (e.g. NFC service, SystemUI).
    // Pre-installed apps like Chrome/YouTube have a launcher intent and return false.
    public boolean isBackgroundSystemApp() { return mIsSystem && !mHasLauncherIntent; }

    // the app does not have a package name (e.g. uid 0 is android system)
    public boolean isVirtual() { return (mPackageInfo == null); }

    public @Nullable PackageInfo getPackageInfo() { return mPackageInfo; }

    @Override
    public int compareTo(AppDescriptor o) {
        int rv = getName().toLowerCase().compareTo(o.getName().toLowerCase());

        if(rv == 0)
            rv = getPackageName().compareTo(o.getPackageName());

        return rv;
    }

    public boolean matches(String filter, boolean exactPackage) {
        String package_name = getPackageName().toLowerCase();

        return getName().toLowerCase().contains(filter) ||
                (exactPackage && package_name.equals(filter)) ||
                (!exactPackage && package_name.contains(filter));
    }
}
