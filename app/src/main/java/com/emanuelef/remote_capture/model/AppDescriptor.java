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
 * Copyright 2020 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.model;

import android.graphics.drawable.Drawable;

import java.io.Serializable;

public class AppDescriptor implements Comparable<AppDescriptor>, Serializable {
    private final String name;
    private final Drawable icon;
    private final String package_name;
    private final int uid;
    private final boolean is_system;

    public AppDescriptor(String name, Drawable icon, String package_name, int uid, boolean is_system) {
        this.name = name;
        this.icon = icon;
        this.package_name = package_name;
        this.uid = uid;
        this.is_system = is_system;
    }

    public String getName() {
        return name;
    }

    public Drawable getIcon() {
        return icon;
    }

    public String getPackageName() {
        return package_name;
    }

    public int getUid() {
        return uid;
    }

    public boolean isSystem() { return is_system; }

    @Override
    public int compareTo(AppDescriptor o) {
        int rv = getName().toLowerCase().compareTo(o.getName().toLowerCase());

        if(rv == 0)
            rv = getPackageName().compareTo(o.getPackageName());

        return rv;
    }
}
