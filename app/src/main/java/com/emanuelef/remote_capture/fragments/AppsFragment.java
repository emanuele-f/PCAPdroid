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

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.activities.AppDetailsActivity;
import com.emanuelef.remote_capture.adapters.AppsStatsAdapter;
import com.emanuelef.remote_capture.adapters.AppsStatsAdapter.SortField;
import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.AppStats;
import com.emanuelef.remote_capture.model.Blocklist;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.MatchList;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;

public class AppsFragment extends Fragment implements ConnectionsListener, MenuProvider {
    private EmptyRecyclerView mRecyclerView;
    private AppsStatsAdapter mAdapter;
    private static final String TAG = "AppsFragment";
    private Handler mHandler;
    private boolean mRefreshApps;
    private boolean listenerSet;
    private Menu mMenu;

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
        requireActivity().addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
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
        CaptureService.observeStatus(this, serviceStatus -> {
            if(serviceStatus == CaptureService.ServiceStatus.STARTED) {
                if(listenerSet) {
                    // register the new connection register
                    unregisterConnsListener();
                    registerConnsListener();
                }
            }
        });
    }

    private void refreshSortField() {
        if((mMenu == null) || (mAdapter == null))
            return;

        SortField sortField = mAdapter.getSortField();
        Log.d(TAG, "Sort field:" + sortField);

        MenuItem byName = mMenu.findItem(R.id.sort_by_name);
        MenuItem byTotalBytes = mMenu.findItem(R.id.sort_by_total_bytes);
        MenuItem byBytesSent = mMenu.findItem(R.id.sort_by_bytes_sent);
        MenuItem byBytesRcvd = mMenu.findItem(R.id.sort_by_bytes_rcvd);

        // important: the checked item must first be unchecked
        byName.setChecked(false);
        byTotalBytes.setChecked(false);
        byBytesSent.setChecked(false);
        byBytesRcvd.setChecked(false);

        if(sortField.equals(SortField.NAME))
            byName.setChecked(true);
        else if(sortField.equals(SortField.TOTAL_BYTES))
            byTotalBytes.setChecked(true);
        else if(sortField.equals(SortField.BYTES_SENT))
            byBytesSent.setChecked(true);
        else if(sortField.equals(SortField.BYTES_RCVD))
            byBytesRcvd.setChecked(true);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.apps_menu, menu);
        mMenu = menu;
        refreshSortField();
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        int id = menuItem.getItemId();

        if(id == R.id.reset) {
            new AlertDialog.Builder(requireContext())
                .setMessage(R.string.reset_stats_confirm)
                .setPositiveButton(R.string.yes, (dialog, whichButton) -> {
                    ConnectionsRegister reg = CaptureService.getConnsRegister();
                    if(reg != null) {
                        reg.resetAppsStats();
                        doRefreshApps();
                    }
                })
                .setNegativeButton(R.string.no, (dialog, whichButton) -> {})
                .show();

            return true;
        } else if(id == R.id.sort_by_name) {
            mAdapter.setSortField(SortField.NAME);
            refreshSortField();
            return true;
        } else if(id == R.id.sort_by_total_bytes) {
            mAdapter.setSortField(SortField.TOTAL_BYTES);
            refreshSortField();
            return true;
        } else if(id == R.id.sort_by_bytes_sent) {
            mAdapter.setSortField(SortField.BYTES_SENT);
            refreshSortField();
            return true;
        } else if(id == R.id.sort_by_bytes_rcvd) {
            mAdapter.setSortField(SortField.BYTES_RCVD);
            refreshSortField();
            return true;
        }

        return false;
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

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        boolean isBlocked = PCAPdroid.getInstance().getBlocklist().matchesApp(stats.getUid());
        menu.findItem(R.id.block_app).setVisible(!isBlocked);

        if(Prefs.isFirewallWhitelistMode(prefs)) {
            boolean isWhitelisted = PCAPdroid.getInstance().getFirewallWhitelist().matchesApp(stats.getUid());
            menu.findItem(R.id.add_to_fw_whitelist).setVisible(!isWhitelisted);
            menu.findItem(R.id.remove_from_fw_whitelist).setVisible(isWhitelisted);
        }

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
        MatchList whitelist = PCAPdroid.getInstance().getFirewallWhitelist();
        boolean whitelistChanged = false;
        AppStats app = mAdapter.getSelectedItem();

        if(app == null)
            return super.onContextItemSelected(item);

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
        else if(id == R.id.add_to_fw_whitelist) {
            whitelist.addApp(app.getUid());
            whitelistChanged = true;
        } else if(id == R.id.remove_from_fw_whitelist) {
            whitelist.removeApp(app.getUid());
            whitelistChanged = true;
        } else
            return super.onContextItemSelected(item);

        if(whitelistChanged) {
            whitelist.save();
            if (CaptureService.isServiceActive())
                CaptureService.requireInstance().reloadFirewallWhitelist();
        } else
            blocklist.saveAndReload();

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
