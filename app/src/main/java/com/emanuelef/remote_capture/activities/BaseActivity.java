package com.emanuelef.remote_capture.activities;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.Prefs;

class BaseActivity extends AppCompatActivity {
    protected SharedPreferences mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        if(!Prefs.useSystemLanguage(mPrefs))
            Utils.setAppLanguage(this, "en");
    }
}
