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

package com.emanuelef.remote_capture.views;

import android.content.Context;
import android.util.AttributeSet;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;

import com.emanuelef.remote_capture.adapters.AppsAdapter;
import com.emanuelef.remote_capture.model.AppDescriptor;

import java.util.ArrayList;
import java.util.List;

public class AppsListView extends EmptyRecyclerView implements SearchView.OnQueryTextListener {
    private List<AppDescriptor> mAllApps;
    private AppsAdapter mAdapter;
    private String mLastFilter;
    private boolean mShowSystemApps;

    public AppsListView(@NonNull Context context) {
        super(context);
        initialize(context);
    }

    public AppsListView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initialize(context);
    }

    public AppsListView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize(context);
    }

    private void initialize(Context context) {
        mAllApps = null;
        setLayoutManager(new MyLinearLayoutManager(context));
        setHasFixedSize(true);
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        mLastFilter = newText;
        if(mAllApps != null)
            mAdapter.setApps(getFilteredApps());
        return true;
    }

    interface OnSelectedAppListener {
        void onSelectedApp(AppDescriptor app);
    }

    private List<AppDescriptor> getFilteredApps() {
        String filter = (mLastFilter != null) ? mLastFilter.toLowerCase() : "";

        if(filter.isEmpty() && mShowSystemApps)
            return mAllApps;

        List<AppDescriptor> filtered = new ArrayList<>();
        for(AppDescriptor app : mAllApps) {
            if(!mShowSystemApps && app.isSystem())
                continue;
            if(!filter.isEmpty()
                    && !app.getPackageName().toLowerCase().contains(filter)
                    && !app.getName().toLowerCase().contains(filter))
                continue;
            filtered.add(app);
        }
        return filtered;
    }

    public void setApps(List<AppDescriptor> installedApps) {
        mAllApps = installedApps;
        List<AppDescriptor> apps = getFilteredApps();

        if(mAdapter == null) {
            mAdapter = new AppsAdapter(getContext(), apps);
            setAdapter(mAdapter);
        } else
            mAdapter.setApps(apps);
    }

    public void setShowSystemApps(boolean show) {
        mShowSystemApps = show;
        if(mAllApps != null)
            mAdapter.setApps(getFilteredApps());
    }

    public void setSelectedAppListener(final OnSelectedAppListener listener) {
        mAdapter.setOnClickListener(view -> {
            int itemPosition = getChildLayoutPosition(view);

            AppDescriptor app = mAdapter.getItem(itemPosition);

            if(app != null)
                listener.onSelectedApp(app);
        });
    }
}
