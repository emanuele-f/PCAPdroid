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

package com.emanuelef.remote_capture.fragments;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.adapters.BlacklistsAdapter;
import com.emanuelef.remote_capture.interfaces.BlacklistsStateListener;
import com.emanuelef.remote_capture.Blacklists;
import com.emanuelef.remote_capture.model.BlacklistDescriptor;

public class BlacklistsFragment extends Fragment implements BlacklistsStateListener, MenuProvider {
    private static final String TAG = "BlacklistsFragment";
    private BlacklistsAdapter mAdapter;
    private Blacklists mBlacklists;
    private MenuItem mUpdateItem;
    private Handler mHandler;

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        requireActivity().addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        return inflater.inflate(R.layout.malware_detection_blacklists, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mBlacklists = PCAPdroid.getInstance().getBlacklists();
        mAdapter = new BlacklistsAdapter(view.getContext(), PCAPdroid.getInstance().getBlacklists().iter());
        ListView listView = view.findViewById(R.id.listview);
        listView.setAdapter(mAdapter);

        listView.setOnItemClickListener((parent, view1, position, id) -> {
            BlacklistDescriptor bl = mAdapter.getItem(position);
            if (bl != null)
                openUrl(view1.getContext(), bl.url);
        });

        mHandler = new Handler(Looper.getMainLooper());

        CaptureService.observeStatus(this, serviceStatus -> refreshStatus());
    }

    private void openUrl(Context ctx, String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        Utils.startActivity(ctx, intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        mBlacklists.addOnChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        mBlacklists.removeOnChangeListener(this);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.blacklists_menu, menu);
        mUpdateItem = menu.findItem(R.id.update);
        refreshStatus();
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.update) {
            CaptureService.requestBlacklistsUpdate();
            return true;
        }

        return false;
    }

    private void refreshStatus() {
        if(mAdapter != null)
            mAdapter.notifyDataSetChanged();

        if(mUpdateItem != null) {
            mUpdateItem.setVisible(CaptureService.isServiceActive());
            mUpdateItem.setEnabled(!mBlacklists.isUpdateInProgress());
        }
    }

    @Override
    public void onBlacklistsStateChanged() {
        Log.d(TAG, "onBlacklistsStateChanged");
        mHandler.post(this::refreshStatus);
    }
}
