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

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.emanuelef.remote_capture.AppsLoader;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.model.AppDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AppsActivity extends AppCompatActivity implements AppsLoadListener {
    private static final String TAG = "AppsActivity";
    private Map<Integer, AppDescriptor> mInstalledApps;
    private List<AppsLoadListener> mAppsListeners;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAppsListeners = new ArrayList<>();

        setContentView(R.layout.apps_activity);

        new AppsLoader(this)
                .setAppsLoadListener(this)
                .loadAllApps();
    }

    @Override
    public void onAppsInfoLoaded(Map<Integer, AppDescriptor> apps) {
        mInstalledApps = apps;

        for(AppsLoadListener listener: mAppsListeners)
            listener.onAppsInfoLoaded(apps);
    }

    @Override
    public void onAppsIconsLoaded(Map<Integer, AppDescriptor> apps) {
        mInstalledApps = apps;

        for(AppsLoadListener listener: mAppsListeners)
            listener.onAppsIconsLoaded(apps);
    }

    public void addAppLoadListener(AppsLoadListener l) {
        mAppsListeners.add(l);
    }

    public void removeAppLoadListener(AppsLoadListener l) {
        mAppsListeners.remove(l);
    }

    public Map<Integer, AppDescriptor> getApps() {
        return mInstalledApps;
    }
}
