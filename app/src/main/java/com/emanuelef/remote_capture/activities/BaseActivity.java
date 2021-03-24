package com.emanuelef.remote_capture.activities;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import com.emanuelef.remote_capture.Utils;

class BaseActivity extends AppCompatActivity {
    @Override
    protected void attachBaseContext(Context base) {
        applyOverrideConfiguration(Utils.getLocalizedConfig(base));
        super.attachBaseContext(base);
    }
}
