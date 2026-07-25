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
 * Copyright 2020-26 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.util.Log;

import com.emanuelef.remote_capture.interfaces.CaptureStartListener;
import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.model.Prefs;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

public class CaptureHelper {
    private static final String TAG = "CaptureHelper";
    private final Context mContext;
    private final @Nullable ComponentActivity mActivity;
    private final @Nullable ActivityResultLauncher<Intent> mLauncher;
    private final @Nullable ActivityResultLauncher<String> mPermLauncher;
    private CaptureSettings mSettings;
    private CaptureStartListener mListener;

    public CaptureHelper(ComponentActivity activity) {
        mContext = activity;
        mActivity = activity;
        mLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), this::captureServiceResult);
        mPermLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), this::localNetworkPermissionResult);
    }

    /** Note: This constructor does not handle the first-time VPN prepare */
    public CaptureHelper(Context context) {
        mContext = context;
        mActivity = null;
        mLauncher = null;
        mPermLauncher = null;
    }

    private void captureServiceResult(final ActivityResult result) {
        if(result.getResultCode() == Activity.RESULT_OK)
            startCaptureOk();
        else if(mListener != null) {
            Utils.showToastLong(mContext, R.string.vpn_setup_failed);
            mListener.onCaptureStartResult(false);
        }
    }

    private void startCaptureOk() {
        final Intent intent = new Intent(mContext, CaptureService.class);
        intent.putExtra("settings", mSettings);

        ContextCompat.startForegroundService(mContext, intent);
        if(mListener != null)
            mListener.onCaptureStartResult(true);
    }

    public void startCapture(CaptureSettings settings) {
        mSettings = settings;

        if(!settings.readFromPcap() && !Utils.hasLocalNetworkPermission(mContext)) {
            askLocalNetworkPermission();
            return;
        }

        prepareVpn();
    }

    private void askLocalNetworkPermission() {
        if((mActivity == null) || (mPermLauncher == null)) {
            Log.w(TAG, "Cannot ask the local network permission without an activity");
            localNetworkPermissionMissing();
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);

        // the notice tells why the permission is needed, show it before the first system prompt
        if(!Prefs.localNetworkNoticeShown(prefs) || Utils.shouldShowLocalNetworkPermissionRationale(mActivity))
            showLocalNetworkNoticeDialog(prefs);
        else
            requestLocalNetworkPermission();
    }

    private void showLocalNetworkNoticeDialog(SharedPreferences prefs) {
        AlertDialog dialog = new AlertDialog.Builder(mContext)
                .setTitle(R.string.permission_required)
                .setMessage(R.string.local_network_notice)
                .setPositiveButton(R.string.grant_permission_action, (d, whichButton) -> {
                    Prefs.setLocalNetworkNoticeShown(prefs);
                    requestLocalNetworkPermission();
                })
                .setNegativeButton(R.string.cancel_action, (d, whichButton) -> localNetworkPermissionMissing())
                .setOnCancelListener(d -> localNetworkPermissionMissing())
                .show();

        dialog.setCanceledOnTouchOutside(false);
    }

    private void showLocalNetworkSettingsDialog() {
        AlertDialog dialog = new AlertDialog.Builder(mContext)
                .setTitle(R.string.permission_required)
                .setMessage(R.string.local_network_permission_denied)
                .setPositiveButton(R.string.open_settings, (d, whichButton) -> {
                    Utils.openAppSettings(mContext, mContext.getPackageName());
                    captureStartFailed();
                })
                .setNegativeButton(R.string.cancel_action, (d, whichButton) -> localNetworkPermissionMissing())
                .setOnCancelListener(d -> localNetworkPermissionMissing())
                .show();

        dialog.setCanceledOnTouchOutside(false);
    }

    private void requestLocalNetworkPermission() {
        assert (mActivity != null) && (mPermLauncher != null);

        try {
            if(!Utils.requestLocalNetworkPermission(mPermLauncher))
                localNetworkPermissionMissing();
        } catch (ActivityNotFoundException e) {
            Utils.showToastLong(mContext, R.string.no_intent_handler_found);
            captureStartFailed();
        }
    }

    private void localNetworkPermissionResult(boolean isGranted) {
        Log.d(TAG, "Local network permission " + (isGranted ? "granted" : "denied"));

        if(!isGranted) {
            assert mActivity != null;

            // the rationale is unset when the system is not going to prompt the user again,
            // in which case the permission can only be granted from the Android settings
            if(!Utils.shouldShowLocalNetworkPermissionRationale(mActivity))
                showLocalNetworkSettingsDialog();
            else
                localNetworkPermissionMissing();
            return;
        }

        if(mSettings == null) {
            // the activity was recreated (e.g. rotated) while the permission prompt was shown
            Log.w(TAG, "Capture settings not available, cannot start the capture");
            captureStartFailed();
            return;
        }

        prepareVpn();
    }

    private void localNetworkPermissionMissing() {
        Utils.showToastLong(mContext, R.string.local_network_permission_required);
        captureStartFailed();
    }

    private void captureStartFailed() {
        if(mListener != null)
            mListener.onCaptureStartResult(false);
    }

    private void prepareVpn() {
        if(CaptureService.isServiceActive())
            CaptureService.stopService();

        if(mSettings.root_capture || mSettings.readFromPcap()) {
            startCaptureOk();
            return;
        }

        Intent vpnPrepareIntent = null;
        try {
            vpnPrepareIntent = VpnService.prepare(mContext);
        } catch (RuntimeException e) {
            e.printStackTrace();
        }

        if(vpnPrepareIntent != null) {
            final Intent prepareIntent = vpnPrepareIntent;

            if (mLauncher != null)
                new AlertDialog.Builder(mContext)
                        .setMessage(R.string.vpn_setup_msg)
                        .setPositiveButton(R.string.ok, (dialog, whichButton) -> {
                            try {
                                mLauncher.launch(prepareIntent);
                            } catch (ActivityNotFoundException e) {
                                Utils.showToastLong(mContext, R.string.no_intent_handler_found);
                                mListener.onCaptureStartResult(false);
                            }
                        })
                        .setOnCancelListener(dialog -> {
                            Utils.showToastLong(mContext, R.string.vpn_setup_failed);
                            mListener.onCaptureStartResult(false);
                        })
                        .show();
            else if (mListener != null)
                mListener.onCaptureStartResult(false);
        } else
            startCaptureOk();
    }

    public void setListener(CaptureStartListener listener) {
        mListener = listener;
    }
}
