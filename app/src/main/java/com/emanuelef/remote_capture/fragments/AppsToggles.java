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
 * Copyright 2020-22 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;

import com.emanuelef.remote_capture.AppsLoader;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.adapters.AppsTogglesAdapter;
import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;

import java.util.List;
import java.util.Set;

import kotlin.NotImplementedError;

public abstract class AppsToggles extends Fragment implements AppsLoadListener,
        AppsTogglesAdapter.AppToggleListener, SearchView.OnQueryTextListener {
    private AppsTogglesAdapter mAdapter;
    private SearchView mSearchView;
    private TextView mEmptyText;

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        setHasOptionsMenu(true);
        return inflater.inflate(R.layout.apps_stats, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        EmptyRecyclerView recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new EmptyRecyclerView.MyLinearLayoutManager(getContext()));

        mAdapter = new AppsTogglesAdapter(requireContext(), getCheckedApps());
        recyclerView.setAdapter(mAdapter);
        mAdapter.setAppToggleListener(this);

        mEmptyText = view.findViewById(R.id.no_apps);
        mEmptyText.setText(R.string.loading_apps);
        recyclerView.setEmptyView(mEmptyText);

        (new AppsLoader((AppCompatActivity) requireActivity()))
                .setAppsLoadListener(this)
                .loadAllApps();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.search_menu, menu);
        mSearchView = (SearchView) menu.findItem(R.id.search).getActionView();
        mSearchView.setOnQueryTextListener(this);
    }

    @Override
    public boolean onQueryTextSubmit(String query) { return true; }

    @Override
    public boolean onQueryTextChange(String newText) {
        mAdapter.setFilter(newText);
        return true;
    }

    @Override
    public void onAppsInfoLoaded(List<AppDescriptor> apps) {
        mAdapter.setApps(apps);
        mEmptyText.setText(R.string.no_matches_found);
    }

    // Must be implemented in sub-classes
    protected Set<String> getCheckedApps() {
        throw new NotImplementedError();
    }
}
