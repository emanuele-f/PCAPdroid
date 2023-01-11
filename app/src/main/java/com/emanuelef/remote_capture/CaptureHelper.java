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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.VpnService;

import com.emanuelef.remote_capture.interfaces.CaptureStartListener;
import com.emanuelef.remote_capture.model.CaptureSettings;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
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
        else if(mListener != null) {
            Utils.showToastLong(mActivity, R.string.vpn_setup_failed);
            mListener.onCaptureStartResult(false);
        }
    }

    private void startCaptureOk() {
        final Intent intent = new Intent(mActivity, CaptureService.class);
        intent.putExtra("settings", mSettings);

        ContextCompat.startForegroundService(mActivity, intent);
        if(mListener != null)
            mListener.onCaptureStartResult(true);
    }

    public void startCapture(CaptureSettings settings) {
        if(CaptureService.isServiceActive())
            CaptureService.stopService();

        mSettings = settings;

        if(settings.root_capture) {
            startCaptureOk();
            return;
        }

        Intent vpnPrepareIntent = VpnService.prepare(mActivity);
        if(vpnPrepareIntent != null) {
            new AlertDialog.Builder(mActivity)
                    .setMessage(R.string.vpn_setup_msg)
                    .setPositiveButton(R.string.ok, (dialog, whichButton) -> {
                        try {
                            mLauncher.launch(vpnPrepareIntent);
                        } catch (ActivityNotFoundException e) {
                            Utils.showToastLong(mActivity, R.string.no_intent_handler_found);
                            mListener.onCaptureStartResult(false);
                        }
                    })
                    .setOnCancelListener(dialog -> {
                        Utils.showToastLong(mActivity, R.string.vpn_setup_failed);
                        mListener.onCaptureStartResult(false);
                    })
                    .show();
        } else
            startCaptureOk();
    }

    public void setListener(CaptureStartListener listener) {
        mListener = listener;
    }
}
