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

package com.emanuelef.remote_capture;

import android.graphics.drawable.Drawable;

class AppDescriptor {
    private String name;
    private Drawable icon;
    private String packages;
    private int uid;

    AppDescriptor(String name, Drawable icon, String packages, int uid) {
        this.name = name;
        this.icon = icon;
        this.packages = packages;
        this.uid = uid;
    }

    String getName() {
        return name;
    }

    Drawable getIcon() {
        return icon;
    }

    String getPackages() {
        return packages;
    }

    int getUid() {
        return uid;
    }
}
