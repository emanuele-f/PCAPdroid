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

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

public class Whitelist extends ConnectionsMatcher {
    private final SharedPreferences mPrefs;

    public Whitelist(Context ctx) {
        super(ctx);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public void reload() {
        // Try to restore the whitelist
        String serialized = mPrefs.getString(Prefs.PREF_WHITELIST, "");

        if(!serialized.isEmpty())
            fromJson(serialized);
    }

    public void save() {
        mPrefs.edit()
            .putString(Prefs.PREF_WHITELIST, toJson())
            .apply();
    }
}
