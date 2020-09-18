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
 * Copyright 2020 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

// TODO add searchbar
// https://stackoverflow.com/questions/31085086/how-to-implement-floating-searchwidget-android

class AppsView extends RecyclerView {
    List<AppDescriptor> mInstalledApps;

    public interface OnSelectedAppListener {
        void onSelectedApp(AppDescriptor app);
    }

    public AppsView(Context context, List<AppDescriptor> installedApps) {
        super(context);

        mInstalledApps = installedApps;
        setLayoutManager(new LinearLayoutManager(context));
        setHasFixedSize(true);
    }

    public void setSelectedAppListener(final OnSelectedAppListener listener) {
        AppAdapter installedAppAdapter = new AppAdapter(getContext(), mInstalledApps, new OnClickListener() {
            @Override
            public void onClick(View view) {
                int itemPosition = getChildLayoutPosition(view);
                AppDescriptor app = mInstalledApps.get(itemPosition);
                listener.onSelectedApp(app);
            }
        });

        setAdapter(installedAppAdapter);
    }
}
