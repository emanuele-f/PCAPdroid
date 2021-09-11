package com.emanuelef.remote_capture;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;

import com.emanuelef.remote_capture.interfaces.CaptureStartListener;
import com.emanuelef.remote_capture.model.CaptureSettings;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

public class CaptureHelper {
    private final ComponentActivity mActivity;
    private final ActivityResultLauncher<Intent> mLauncher;
    private CaptureSettings mSettings;
    private CaptureStartListener mListener;

    public CaptureHelper(ComponentActivity activity) {
        mActivity = activity;
        mLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::captureServiceResult);
    }

    private void captureServiceResult(final ActivityResult result) {
        if(result.getResultCode() == Activity.RESULT_OK)
            startCaptureOk();
        else if(mListener != null)
            mListener.onCaptureStartResult(false);
    }

    private void startCaptureOk() {
        final Intent intent = new Intent(mActivity, CaptureService.class);
        intent.putExtra("settings", mSettings);

        ContextCompat.startForegroundService(mActivity, intent);
        if(mListener != null)
            mListener.onCaptureStartResult(true);
    }

    public void startCapture(CaptureSettings settings) {
        mSettings = settings;

        if(settings.root_capture) {
            startCaptureOk();
            return;
        }

        Intent vpnPrepareIntent = VpnService.prepare(mActivity);
        if(vpnPrepareIntent != null)
            mLauncher.launch(vpnPrepareIntent);
        else
            startCaptureOk();
    }

    public void setListener(CaptureStartListener listener) {
        mListener = listener;
    }
}
