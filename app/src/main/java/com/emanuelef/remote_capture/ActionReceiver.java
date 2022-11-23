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
 * Copyright 2022 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationManagerCompat;

import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.Blocklist;

public class ActionReceiver extends BroadcastReceiver {
    public static final String EXTRA_UNBLOCK_APP = "unblock_app";
    private static final String TAG = "TAG";

    @Override
    public void onReceive(Context context, Intent intent) {
        String unblock_app = intent.getStringExtra(EXTRA_UNBLOCK_APP);

        if((unblock_app != null) && !unblock_app.isEmpty()) {
            Log.d(TAG, "unblock_app: " + unblock_app);
            Blocklist blocklist = PCAPdroid.getInstance().getBlocklist();
            blocklist.removeApp(unblock_app);
            blocklist.saveAndReload();

            // remove notification
            NotificationManagerCompat man = NotificationManagerCompat.from(context);
            man.cancel(CaptureService.NOTIFY_ID_APP_BLOCKED);

            AppDescriptor app = AppsResolver.resolveInstalledApp(context.getPackageManager(), unblock_app, 0);
            String label = (app != null) ? app.getName() : unblock_app;
            Utils.showToastLong(context, R.string.app_unblocked, label);
        }
    }
}
