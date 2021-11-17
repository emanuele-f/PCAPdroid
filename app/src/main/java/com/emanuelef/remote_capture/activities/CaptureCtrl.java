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

package com.emanuelef.remote_capture.activities;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.CaptureHelper;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.model.CtrlPermissions;

public class CaptureCtrl extends AppCompatActivity {
    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    private static final String TAG = "CaptureCtrl";
    private static AppDescriptor mStarterApp = null; // the app which started the capture, may be unknown
    private CaptureHelper mCapHelper;
    private CtrlPermissions mPermissions;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final WindowInsetsController insetsController = getWindow().getInsetsController();
            if (insetsController != null) {
                insetsController.hide(WindowInsets.Type.statusBars());
            }
        } else {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
        }
        getWindow().addFlags(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        super.onCreate(savedInstanceState);

        mCapHelper = new CaptureHelper(this);
        mCapHelper.setListener(success -> {
            setResult(success ? RESULT_OK : RESULT_CANCELED, null);
            finish();
        });

        Intent intent = getIntent();
        String action = intent.getStringExtra("action");

        if(action == null) {
            Log.e(TAG, "no action provided");
            abort();
            return;
        }

        // Check if a control permission rule was set
        mPermissions = PCAPdroid.getInstance().getCtrlPermissions();
        AppDescriptor app = getCallingApp();
        if(app != null) {
            CtrlPermissions.ConsentType consent = mPermissions.getConsent(app.getPackageName());

            if(consent == CtrlPermissions.ConsentType.ALLOW) {
                processRequest(intent, action);
                return;
            } else if(consent == CtrlPermissions.ConsentType.DENY) {
                abort();
                return;
            }
        }

        if(isControlApp(action)) {
            processRequest(intent, action);
            return;
        }

        // Show authorization window
        setContentView(R.layout.ctrl_consent);
        findViewById(R.id.allow_btn).setOnClickListener(v -> {
            controlAction(intent, action, true);
        });
        findViewById(R.id.deny_btn).setOnClickListener(v -> {
            controlAction(intent, action, false);
        });

        if(app != null) {
            ((TextView)findViewById(R.id.app_name)).setText(app.getName());
            ((TextView)findViewById(R.id.app_package)).setText(app.getPackageName());
            ((ImageView)findViewById(R.id.app_icon)).setImageDrawable(app.getIcon());
        } else
            findViewById(R.id.caller_app).setVisibility(View.GONE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Button btn = findViewById(R.id.allow_btn);
                    btn.setTextColor(0xFF0099CC);
                    btn.setEnabled(true);
                }, 1500);
    }

    private AppDescriptor getCallingApp() {
        String callp = getCallingPackage();
        return (callp != null) ? AppsResolver.resolve(getPackageManager(), callp, 0) : null;
    }

    private void controlAction(Intent intent, String action, boolean allow) {
        AppDescriptor app = getCallingApp();
        if(app != null) {
            boolean is_forever = ((RadioButton)findViewById(R.id.choice_forever)).isChecked();
            if(is_forever) {
                Log.d(TAG, (allow ? "Grant" : "Deny") + " forever to " + app.getPackageName());
                mPermissions.add(app.getPackageName(), allow ? CtrlPermissions.ConsentType.ALLOW : CtrlPermissions.ConsentType.DENY);
            }
        }

        if(!allow)
            abort();
        else
            processRequest(intent, action);
    }

    @Override
    protected void onDestroy() {
        mCapHelper = null;
        super.onDestroy();
    }

    private boolean isControlApp(@NonNull String action) {
        // Automatically authorize an app to stop the capture it started
        return !action.equals(ACTION_START) && (mStarterApp != null)
                && (mStarterApp.getPackageName().equals(getCallingPackage()));
    }

    @Override
    public void onStart() {
        super.onStart();
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    @Override
    public void onBackPressed() {
        abort();
    }

    private void abort() {
        Utils.showToast(this, R.string.ctrl_consent_denied);
        setResult(RESULT_CANCELED, null);
        finish();
    }

    private void processRequest(Intent req_intent, @NonNull String action) {
        Utils.showToast(this, R.string.ctrl_consent_allowed);

        if(action.equals(ACTION_START)) {
            mStarterApp = getCallingApp();
            Log.d(TAG, "Starting capture, caller=" + mStarterApp);

            // will call the mCapHelper listener
            mCapHelper.startCapture(new CaptureSettings(req_intent));
            return;
        } else if(action.equals(ACTION_STOP)) {
            Log.d(TAG, "Stopping capture");

            CaptureService.stopService();
            mStarterApp = null;
        } else {
            Log.e(TAG, "unknown action: " + action);
            abort();
            return;
        }

        setResult(RESULT_OK, null);
        finish();
    }
}
