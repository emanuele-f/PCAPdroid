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
import android.widget.Filter;
import android.widget.Filterable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;

import com.emanuelef.remote_capture.adapters.AppsAdapter;
import com.emanuelef.remote_capture.model.AppDescriptor;

import java.util.ArrayList;
import java.util.List;

public class AppsListView extends EmptyRecyclerView implements SearchView.OnQueryTextListener, Filterable {
    private List<AppDescriptor> mAllApps;
    private AppsAdapter mAdapter;
    private String mLastFilter;

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
    public Filter getFilter() {
        return new Filter() {

            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                String charString = constraint.toString().toLowerCase();
                List<AppDescriptor> appsFiltered;

                if(charString.isEmpty())
                    appsFiltered = mAllApps;
                else {
                    appsFiltered = new ArrayList<>();

                    for(AppDescriptor app : mAllApps) {
                        if(app.getPackageName().toLowerCase().contains(charString)
                                || app.getName().toLowerCase().contains(charString)) {
                            appsFiltered.add(app);
                        }
                    }
                }

                FilterResults filterResults = new FilterResults();
                filterResults.values = appsFiltered;
                return filterResults;
            }

            @SuppressWarnings("unchecked")
            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                List<AppDescriptor> appsFiltered = (List<AppDescriptor>) results.values;
                mAdapter.setApps(appsFiltered);
            }
        };
    }

    @Override
    public boolean onQueryTextSubmit(String query) {
        return true;
    }

    @Override
    public boolean onQueryTextChange(String newText) {
        mLastFilter = newText;
        getFilter().filter(newText);
        return true;
    }

    interface OnSelectedAppListener {
        void onSelectedApp(AppDescriptor app);
    }

    public void setApps(List<AppDescriptor> installedApps) {
        mAllApps = installedApps;

        if(mAdapter == null) {
            mAdapter = new AppsAdapter(getContext(), mAllApps);
            setAdapter(mAdapter);
        } else
            mAdapter.setApps(mAllApps);

        if(mLastFilter != null)
            getFilter().filter(mLastFilter);
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
