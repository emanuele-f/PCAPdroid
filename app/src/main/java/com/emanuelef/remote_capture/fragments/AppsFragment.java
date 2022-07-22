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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.activities.AppDetailsActivity;
import com.emanuelef.remote_capture.adapters.AppsStatsAdapter;
import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.AppStats;
import com.emanuelef.remote_capture.model.Blocklist;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;

public class AppsFragment extends Fragment implements ConnectionsListener {
    private EmptyRecyclerView mRecyclerView;
    private AppsStatsAdapter mAdapter;
    private static final String TAG = "AppsFragment";
    private Handler mHandler;
    private boolean mRefreshApps;
    private boolean listenerSet;
    private BroadcastReceiver mReceiver;

    @Override
    public void onPause() {
        super.onPause();

        unregisterConnsListener();
    }

    @Override
    public void onResume() {
        super.onResume();

        registerConnsListener();
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.apps_stats, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mRecyclerView = view.findViewById(R.id.recycler_view);
        mRecyclerView.setLayoutManager(new EmptyRecyclerView.MyLinearLayoutManager(getContext()));
        registerForContextMenu(mRecyclerView);

        mAdapter = new AppsStatsAdapter(getContext());
        doRefreshApps();
        mRecyclerView.setAdapter(mAdapter);

        TextView emptyText = view.findViewById(R.id.no_apps);
        mRecyclerView.setEmptyView(emptyText);

        mHandler = new Handler(Looper.getMainLooper());
        mRefreshApps = false;

        mAdapter.setClickListener(v -> {
            int pos = mRecyclerView.getChildLayoutPosition(v);
            int uid = (int) mAdapter.getItemId(pos);

            Intent intent = new Intent(getActivity(), AppDetailsActivity.class);
            intent.putExtra(AppDetailsActivity.APP_UID_EXTRA, uid);
            startActivity(intent);
        });

        /* Register for service status */
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String status = intent.getStringExtra(CaptureService.SERVICE_STATUS_KEY);

                if(CaptureService.SERVICE_STATUS_STARTED.equals(status)) {
                    if(listenerSet) {
                        // register the new connection register
                        unregisterConnsListener();
                        registerConnsListener();
                    }
                }
            }
        };

        LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(mReceiver, new IntentFilter(CaptureService.ACTION_SERVICE_STATUS));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if(mReceiver != null) {
            LocalBroadcastManager.getInstance(requireContext())
                    .unregisterReceiver(mReceiver);
            mReceiver = null;
        }
    }

    @Override
    public void onCreateContextMenu(@NonNull ContextMenu menu, @NonNull View v,
                                    @Nullable ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        Log.d(TAG, "onCreateContextMenu");

        MenuInflater inflater = requireActivity().getMenuInflater();
        inflater.inflate(R.menu.app_context_menu, menu);

        AppStats stats = mAdapter.getSelectedItem();
        if(stats == null)
            return;

        boolean isBlocked = PCAPdroid.getInstance().getBlocklist().matchesApp(stats.getUid());
        menu.findItem(R.id.block_app).setVisible(!isBlocked);

        menu.findItem(R.id.unblock_app_permanently).setVisible(isBlocked);
        menu.findItem(R.id.unblock_app_10m).setVisible(isBlocked)
                .setTitle(getString(R.string.unblock_for_n_minutes, 10));
        menu.findItem(R.id.unblock_app_1h).setVisible(isBlocked)
                .setTitle(getString(R.string.unblock_for_n_hours, 1));
        menu.findItem(R.id.unblock_app_8h).setVisible(isBlocked)
                .setTitle(getString(R.string.unblock_for_n_hours, 8));
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        Blocklist blocklist = PCAPdroid.getInstance().getBlocklist();
        AppStats app = mAdapter.getSelectedItem();

        if(id == R.id.block_app)
            blocklist.addApp(app.getUid());
        else if(id == R.id.unblock_app_permanently)
            blocklist.removeApp(app.getUid());
        else if(id == R.id.unblock_app_10m)
            blocklist.unblockAppForMinutes(app.getUid(), 10);
        else if(id == R.id.unblock_app_1h)
            blocklist.unblockAppForMinutes(app.getUid(), 60);
        else if(id == R.id.unblock_app_8h)
            blocklist.unblockAppForMinutes(app.getUid(), 480);
        else
            return super.onContextItemSelected(item);

        blocklist.save();
        if(CaptureService.isServiceActive())
            CaptureService.requireInstance().reloadBlocklist();

        // refresh the item
        mAdapter.notifyItemChanged(app);

        return true;
    }

    private void registerConnsListener() {
        if (!listenerSet) {
            ConnectionsRegister reg = CaptureService.getConnsRegister();

            if (reg != null) {
                reg.addListener(this);
                listenerSet = true;
            }
        }
    }

    private void unregisterConnsListener() {
        if(listenerSet) {
            ConnectionsRegister reg = CaptureService.getConnsRegister();
            if (reg != null)
                reg.removeListener(this);

            listenerSet = false;
        }
    }

    // NOTE: do not use synchronized as it could cause a deadlock with the ConnectionsRegister lock
    private void doRefreshApps() {
        mRefreshApps = false;

        ConnectionsRegister reg = CaptureService.getConnsRegister();
        if (reg == null)
            return;

        mAdapter.setStats(reg.getAppsStats());
    }

    private void refreshAppsAsync() {
        if(!mRefreshApps) {
            mRefreshApps = true;

            // schedule a delayed refresh to possibly catch multiple refreshes
            mHandler.postDelayed(this::doRefreshApps, 100);
        }
    }

    @Override
    public void connectionsChanges(int num_connections) {
        refreshAppsAsync();
    }

    @Override
    public void connectionsAdded(int start, ConnectionDescriptor []conns) {
        refreshAppsAsync();
    }

    @Override
    public void connectionsRemoved(int start, ConnectionDescriptor []conns) {
        refreshAppsAsync();
    }

    @Override
    public void connectionsUpdated(int[] positions) {
        refreshAppsAsync();
    }
}
