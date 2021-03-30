package com.emanuelef.remote_capture;

import android.app.Application;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.model.Prefs;

public class MyApplication extends Application {
    public void onCreate() {
        super.onCreate();

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
}
