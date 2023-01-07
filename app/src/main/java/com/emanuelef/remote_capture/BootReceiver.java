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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.model.Prefs;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String action = intent.getAction();
        Log.d(TAG, "onReceive: " + action);

        if(!action.equals(Intent.ACTION_BOOT_COMPLETED) && !action.equals("android.intent.action.QUICKBOOT_POWERON")) {
            Log.w(TAG, "Unexpected action: " + action);
            return;
        }

        if(!Prefs.startAtBoot(prefs))
            return;

        if(CaptureService.isServiceActive()) {
            // this can happen, for example, if always-on VPN is enabled, which causes PCAPdroid
            // to be started early
            Log.i(TAG, "Service already active, nothing to do");
            return;
        }

        CaptureSettings settings = new CaptureSettings(context, prefs);

        if(!settings.root_capture) {
            Intent vpnPrepareIntent = VpnService.prepare(context);
            if(vpnPrepareIntent != null) {
                // Cannot perform the VPN setup without an Activity
                Utils.showToastLong(context, R.string.vpn_setup_failed);
                return;
            }
        }

        Log.i(TAG, "Starting capture service");
        Intent capIntent = new Intent(context, CaptureService.class);
        capIntent.putExtra("settings", settings);
        ContextCompat.startForegroundService(context, capIntent);
    }
}
