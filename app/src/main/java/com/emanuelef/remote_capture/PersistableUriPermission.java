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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.PreferenceManager;

// If an app asks to dump to a PCAP file, ensure that PCAPdroid has such a permission.
// When dumping to the external storage, the first time it's necessary to get a persistable
// permission, which requires the user to manually select the output file from the UI.
public class PersistableUriPermission {
    private static final String TAG = "PersistableUriPermission";
    private static final String PREF_KEY = "persistable_uri";

    /* FLAG_GRANT_READ_URI_PERMISSION required for showPcapActionDialog (e.g. when auto-started at boot) */
    private static int PERSIST_MODE = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    public String key = "";
    public Uri persistableUri;
    private String mNewKey;
    private final Context mCtx;
    private final SharedPreferences mPrefs;
    private PupListener mListener;
    private final ActivityResultLauncher<Intent> mPcapLauncher;

    public interface PupListener {
        void onUriChecked(Uri grantedUri);
    }

    public PersistableUriPermission(ComponentActivity activity) {
        mCtx = activity;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mCtx);
        mPcapLauncher = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::pcapFileResult);
        reload();
    }

    public void reload() {
        String k = mPrefs.getString(PREF_KEY, "");
        int sep = k.indexOf("|");
        if(sep < 0)
            return;

        key = k.substring(0, sep);
        persistableUri = Uri.parse(k.substring(sep + 1));
    }

    public void save() {
        String serialized = key + "|" + persistableUri;
        mPrefs.edit().putString(PREF_KEY, serialized).apply();
    }

    public void checkPermission(String perm_key, boolean pcapng_format, PupListener listener) {
        boolean hasPermission = false;
        boolean keyChanged = !perm_key.equals(key);
        mNewKey = perm_key;
        mListener = listener;

        // Revoke the previous permissions and check
        for(UriPermission permission : mCtx.getContentResolver().getPersistedUriPermissions()) {
            if(keyChanged || !permission.getUri().equals(persistableUri)) {
                Log.d(TAG, "Releasing URI permission: " + permission.getUri().toString());
                mCtx.getContentResolver().releasePersistableUriPermission(permission.getUri(), PERSIST_MODE);
            } else
                hasPermission = true;
        }

        if(!hasPermission)
            openFileSelector(pcapng_format);
        else
            mListener.onUriChecked(persistableUri);
    }

    private void openFileSelector(boolean pcapng_format) {
        boolean noFileDialog = false;
        String fname = Utils.getUniquePcapFileName(mCtx, pcapng_format);
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, fname);

        if(Utils.supportsFileDialog(mCtx, intent)) {
            try {
                mPcapLauncher.launch(intent);
            } catch (ActivityNotFoundException e) {
                noFileDialog = true;
            }
        } else
            noFileDialog = true;

        if(noFileDialog) {
            Log.w(TAG, "No app found to handle file selection");
            Utils.showToastLong(mCtx, R.string.no_activity_file_selection);
            mListener.onUriChecked(null);
        }
    }

    private void pcapFileResult(final ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Uri uri = result.getData().getData();
            boolean persistable = (result.getData().getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0;

            /* Request a persistent permission to write this URI without invoking the system picker.
             * This is needed to write to the URI when invoking PCAPdroid from other apps via Intents
             * or when starting the capture at boot. */
            if(persistable) {
                try {
                    mCtx.getContentResolver().takePersistableUriPermission(uri, PERSIST_MODE);

                    // save the persistable uri to use it for the next capture
                    persistableUri = uri;
                    key = mNewKey;
                    save();
                } catch (SecurityException e) {
                    // This should never occur
                    Log.e(TAG, "Could not get PersistableUriPermission");
                    e.printStackTrace();
                }
            }

            mListener.onUriChecked(uri);
        } else
            mListener.onUriChecked(null);
    }
}
