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

package com.emanuelef.remote_capture.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.preference.PreferenceManager;

public class MitmAddon {
    public static final String PACKAGE_NAME = "com.pcapdroid.mitm";
    public static final String MITM_PERMISSION = "com.pcapdroid.permission.MITM";

    public static final String MITM_SERVICE = PACKAGE_NAME + ".MitmService";
    public static final int MSG_START_MITM = 1;

    public static final String CONTROL_ACTIVITY = PACKAGE_NAME + ".MitmCtrl";
    public static final String ACTION_EXTRA = "action";
    public static final String ACTION_GET_CA_CERTIFICATE = "getCAcert";
    public static final String CERTIFICATE_RESULT = "certificate";

    public static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PACKAGE_NAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public static boolean hasMitmPermission(Context ctx) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            return ctx.checkSelfPermission(MitmAddon.MITM_PERMISSION) == PackageManager.PERMISSION_GRANTED;

        return true;
    }

    public static void setDecryptionSetupDone(Context ctx, boolean done) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        prefs.edit()
                .putBoolean(Prefs.PREF_TLS_DECRYPTION_SETUP_DONE, done)
                .apply();
    }

    public static boolean needsSetup(Context ctx) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        if(!Prefs.isTLSDecryptionSetupDone(prefs))
            return true;

        // Perform some other quick checks just in case the env has changed
        if(!isInstalled(ctx) || !hasMitmPermission(ctx)) {
            setDecryptionSetupDone(ctx, false);
            return true;
        }

        return false;
    }
}
