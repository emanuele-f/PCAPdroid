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

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.model.Blacklists;
import com.emanuelef.remote_capture.model.MatchList;
import com.emanuelef.remote_capture.model.Prefs;

import java.lang.ref.WeakReference;

public class PCAPdroid extends Application {
    private MatchList mVisMask;
    private MatchList mMalwareWhitelist;
    private Blacklists mBlacklists;
    private Context mLocalizedContext;
    private static WeakReference<PCAPdroid> mInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        mInstance = new WeakReference<>(this);
        mLocalizedContext = createConfigurationContext(Utils.getLocalizedConfig(this));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String theme = prefs.getString(Prefs.PREF_APP_THEME, "");

        if("".equals(theme)) {
            if(Utils.isTv(this)) {
                // Use the dark theme by default on Android TV
                theme = "dark";
                prefs.edit().putString(Prefs.PREF_APP_THEME, theme).apply();
            } else
                theme = "system";
        }
        Utils.setAppTheme(theme);
    }

    @Override
    public Resources getResources() {
        if(mLocalizedContext == null)
            return super.getResources();

        // Ensure that the selected locale is used
        return mLocalizedContext.getResources();
    }

    public static PCAPdroid getInstance() {
        return mInstance.get();
    }

    public Billing getBilling(Context ctx) {
        return new PlayBilling(ctx);
    }

    public MatchList getVisualizationMask() {
        if(mVisMask == null)
            mVisMask = new MatchList(this, Prefs.PREF_VISUALIZATION_MASK);

        return mVisMask;
    }

    public Blacklists getBlacklistsStatus() {
        if(mBlacklists == null)
            mBlacklists = new Blacklists(this);

        return mBlacklists;
    }

    public MatchList getMalwareWhitelist() {
        if(mMalwareWhitelist == null)
            mMalwareWhitelist = new MatchList(this, Prefs.PREF_MALWARE_WHITELIST);

        return mMalwareWhitelist;
    }
}