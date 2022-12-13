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

package com.emanuelef.remote_capture.views;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.AppsLoader;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.Prefs;
import com.pcapdroid.mitm.MitmAPI;

import java.util.ArrayList;
import java.util.List;

public class AppSelectDialog implements AppsLoadListener {
    private static final String TAG = "AppSelectDialog";
    private AppsListView mOpenAppsList;
    private TextView mEmptyAppsView;
    private Dialog mDialog;
    private AppsLoader mLoader;
    private AppCompatActivity mActivity;
    private AppSelectListener mListener;
    private final SharedPreferences mPrefs;
    private final int mTitleRes;

    public interface AppSelectListener {
        void onSelectedApp(AppDescriptor app);
        void onAppSelectionAborted();
    }

    public AppSelectDialog(AppCompatActivity activity, int title_res, AppSelectListener listener) {
        mActivity = activity;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        mListener = listener;
        mTitleRes = title_res;
        show();
    }

    @Override
    public void onAppsInfoLoaded(List<AppDescriptor> installedApps) {
        if(mOpenAppsList == null)
            return;

        mEmptyAppsView.setText(R.string.no_apps);

        if(Prefs.isTLSDecryptionSetupDone(mPrefs)) {
            // Remove the mitm addon from the list
            AppDescriptor mitmAddon = null;

            for(AppDescriptor cur: installedApps) {
                if(cur.getPackageName().equals(MitmAPI.PACKAGE_NAME)) {
                    mitmAddon = cur;
                    break;
                }
            }

            if(mitmAddon != null)
                installedApps.remove(mitmAddon);
        }

        Log.d(TAG, "loading " + installedApps.size() +" apps in dialog, icons=" + installedApps);
        mOpenAppsList.setApps(installedApps);
    }

    private void show() {
        mDialog = getDialog();
        mDialog.setOnCancelListener(dialog1 -> {
            if(mListener != null)
                mListener.onAppSelectionAborted();
        });
        mDialog.setOnDismissListener(dialog1 -> {
            mOpenAppsList = null;
        });

        mDialog.show();

        // NOTE: run this after dialog.show
        mOpenAppsList = mDialog.findViewById(R.id.apps_list);
        mEmptyAppsView = mDialog.findViewById(R.id.no_apps);
        mEmptyAppsView.setText(R.string.loading_apps);

        mLoader = (new AppsLoader(mActivity))
                .setAppsLoadListener(this)
                .loadAllApps();
    }

    private Dialog getDialog() {
        View dialogLayout = mActivity.getLayoutInflater().inflate(R.layout.apps_selector, null);
        SearchView searchView = dialogLayout.findViewById(R.id.apps_search);
        AppsListView apps = dialogLayout.findViewById(R.id.apps_list);
        TextView emptyText = dialogLayout.findViewById(R.id.no_apps);

        apps.setApps(new ArrayList<>());
        apps.setEmptyView(emptyText);
        searchView.setOnQueryTextListener(apps);

        AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(mActivity);
        builder.setTitle(mTitleRes);
        builder.setView(dialogLayout);

        final AlertDialog alert = builder.create();
        alert.setCanceledOnTouchOutside(true);

        apps.setSelectedAppListener(app -> {
            if(mListener != null)
                mListener.onSelectedApp(app);

            // dismiss the dialog
            alert.dismiss();
        });

        return alert;
    }

    // call this to avoid context leaks
    public void abort() {
        mDialog.dismiss();
        mLoader.abort();
        mOpenAppsList = null;
        mActivity = null;
        mListener = null;
    }
}
