package com.emanuelef.remote_capture.activities;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.emanuelef.remote_capture.CaptureHelper;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.CaptureSettings;

public class CaptureCtrl extends AppCompatActivity {
    private static final String TAG = "CaptureCtrl";
    private static String calling_package = null;
    private CaptureHelper mCapHelper;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
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

        if(isAlreadyAuthorized(action)) {
            processRequest(intent, action);
            return;
        }

        // Show authorization window
        setContentView(R.layout.ctrl_consent);
        findViewById(R.id.allow_btn).setOnClickListener(v -> {
            Utils.showToast(this, R.string.ctrl_consent_allowed);
            processRequest(intent, action);
        });
        findViewById(R.id.deny_btn).setOnClickListener(v -> abort());

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Button btn = findViewById(R.id.allow_btn);
                    btn.setTextColor(0xFF0099CC);
                    btn.setEnabled(true);
                }, 1500);
    }

    @Override
    protected void onDestroy() {
        mCapHelper = null;
        super.onDestroy();
    }

    private boolean isAlreadyAuthorized(@NonNull String action) {
        // Automatically authorize an app to stop the capture it started
        return !action.equals("start") && (calling_package != null)
                && (calling_package.equals(getCallingPackage()));
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
        if(action.equals("start")) {
            calling_package = getCallingPackage();
            Log.d(TAG, "Starting capture, caller=" + calling_package);

            // will call the mCapHelper listener
            mCapHelper.startCapture(new CaptureSettings(req_intent));
            return;
        } else if(action.equals("stop")) {
            Log.d(TAG, "Stopping capture");

            CaptureService.stopService();
            calling_package = null;
        } else {
            Log.e(TAG, "unknown action: " + action);
            abort();
            return;
        }

        setResult(RESULT_OK, null);
        finish();
    }
}
