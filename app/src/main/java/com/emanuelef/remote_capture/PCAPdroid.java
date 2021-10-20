package com.emanuelef.remote_capture;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;

import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.model.MatchList;
import com.emanuelef.remote_capture.model.Prefs;

import java.lang.ref.WeakReference;

public class PCAPdroid extends Application {
    private MatchList mVisMask;
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

    public MatchList getVisualizationMask() {
        if(mVisMask == null)
            mVisMask = new MatchList(this, Prefs.PREF_VISUALIZATION_MASK);

        return mVisMask;
    }

    public Billing getBilling(Context ctx) {
        return new Billing(ctx);
    }
}