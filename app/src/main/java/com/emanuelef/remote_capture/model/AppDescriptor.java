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
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import java.io.Serializable;

public class AppDescriptor implements Comparable<AppDescriptor>, Serializable {
    private final String mName;
    private final String mPackageName;
    private final int mUid;
    private final boolean mIsSystem;
    private Drawable mIcon;

    // NULL for virtual apps
    PackageManager mPm;
    ApplicationInfo mAppInfo;

    public AppDescriptor(String name, Drawable icon, String package_name, int uid, boolean is_system) {
        this.mName = name;
        this.mIcon = icon;
        this.mPackageName = package_name;
        this.mUid = uid;
        this.mIsSystem = is_system;
    }

    public AppDescriptor(PackageManager pm, ApplicationInfo appInfo) {
        this(appInfo.loadLabel(pm).toString(), null, appInfo.packageName, appInfo.uid,
                (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);

        mPm = pm;
        mAppInfo = appInfo;
    }

    public String getName() {
        return mName;
    }

    public @Nullable Drawable getIcon() {
        if(mIcon != null)
            return mIcon;

        if((mAppInfo == null) || (mPm == null))
            return null;

        // NOTE: this call is expensive
        mIcon = mAppInfo.loadIcon(mPm);

        return mIcon;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public int getUid() {
        return mUid;
    }

    public boolean isSystem() { return mIsSystem; }

    // the app does not have a package name (e.g. uid 0 is android system)
    public boolean isVirtual() { return (mAppInfo == null); }

    @Override
    public int compareTo(AppDescriptor o) {
        int rv = getName().toLowerCase().compareTo(o.getName().toLowerCase());

        if(rv == 0)
            rv = getPackageName().compareTo(o.getPackageName());

        return rv;
    }
}
