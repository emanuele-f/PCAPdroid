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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.model.AppState;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.activities.ConnectionDetailsActivity;
import com.emanuelef.remote_capture.adapters.ConnectionsAdapter;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.interfaces.ConnectionsListener;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ConnectionsFragment extends Fragment implements ConnectionsListener, AppsLoadListener {
    private static final String TAG = "ConnectionsFragment";
    private Handler mHandler;
    private ConnectionsAdapter mAdapter;
    private View mFabDown;
    private EmptyRecyclerView mRecyclerView;
    private TextView mEmptyText;
    private TextView mOldConnectionsText;
    private boolean autoScroll;
    private boolean listenerSet;
    private MenuItem mMenuItemAppSel;
    private MenuItem mSave;
    private Map<Integer, AppDescriptor> mApps;
    private Drawable mFilterIcon;
    private AppDescriptor mNoFilterApp;
    private boolean mDumpWhenDone;
    private boolean mOpenAppsWhenDone;
    private BroadcastReceiver mReceiver;
    private Uri mCsvFname;
    private boolean hasUntrackedConnections;

    @Override
    public void onDestroy() {
        super.onDestroy();

        unregisterConnsListener();
        ((MainActivity) getActivity()).removeAppLoadListener(this);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt("uidFilter", mAdapter.getUidFilter());
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        setHasOptionsMenu(true);
        return inflater.inflate(R.layout.connections, container, false);
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

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mHandler = new Handler(Looper.getMainLooper());
        mFabDown = view.findViewById(R.id.fabDown);
        mRecyclerView = view.findViewById(R.id.connections_view);
        mOldConnectionsText = view.findViewById(R.id.old_connections_notice);
        LinearLayoutManager layoutMan = new LinearLayoutManager(getContext());
        mRecyclerView.setLayoutManager(layoutMan);

        mEmptyText = view.findViewById(R.id.no_connections);
        mRecyclerView.setEmptyView(mEmptyText);

        if(((MainActivity) getActivity()).getState() == AppState.running)
            mEmptyText.setText(R.string.no_connections);

        mAdapter = new ConnectionsAdapter(getContext());
        mRecyclerView.setAdapter(mAdapter);
        listenerSet = false;

        Drawable icon = ContextCompat.getDrawable(getContext(), android.R.color.transparent);
        mNoFilterApp = new AppDescriptor("", icon, this.getResources().getString(R.string.no_filter), Utils.UID_NO_FILTER, false, true);

        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(mRecyclerView.getContext(),
                layoutMan.getOrientation());
        mRecyclerView.addItemDecoration(dividerItemDecoration);

        mAdapter.setClickListener(v -> {
            int pos = mRecyclerView.getChildLayoutPosition(v);
            ConnectionDescriptor item = mAdapter.getItem(pos);

            if(item != null) {
                Intent intent = new Intent(getContext(), ConnectionDetailsActivity.class);
                AppDescriptor app = (mApps != null) ? mApps.get(item.uid) : null;
                String app_name = null;

                if(app != null)
                    app_name = app.getName();

                intent.putExtra(ConnectionDetailsActivity.CONN_EXTRA_KEY, item);

                if(app_name != null)
                    intent.putExtra(ConnectionDetailsActivity.APP_NAME_EXTRA_KEY, app_name);

                startActivity(intent);
            }
        });

        autoScroll = true;
        showFabDown(false);
        mOldConnectionsText.setVisibility(View.GONE);

        mFabDown.setOnClickListener(v -> scrollToBottom());

        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            //public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int state) {
                recheckScroll();
            }
        });

        registerConnsListener();
        refreshMenuIcons();

        MainActivity activity = (MainActivity) getActivity();
        if(activity.getApps() != null)
            onAppsIconsLoaded(activity.getApps());
        activity.addAppLoadListener(this);

        int uidFilter = Utils.UID_NO_FILTER;
        Intent intent = activity.getIntent();

        if(intent != null) {
            uidFilter = intent.getIntExtra(MainActivity.UID_FILTER_EXTRA, Utils.UID_NO_FILTER);

            if(uidFilter != Utils.UID_NO_FILTER) {
                // "consume" it
                intent.removeExtra(MainActivity.UID_FILTER_EXTRA);
            }
        }

        if ((uidFilter == Utils.UID_NO_FILTER) && (savedInstanceState != null)) {
            uidFilter = savedInstanceState.getInt("uidFilter", Utils.UID_NO_FILTER);
        }

        if(uidFilter != Utils.UID_NO_FILTER)
            setUidFilter(uidFilter);

        // Register for service status
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String status = intent.getStringExtra(CaptureService.SERVICE_STATUS_KEY);

                if(CaptureService.SERVICE_STATUS_STARTED.equals(status)) {
                    // register the new connection register
                    unregisterConnsListener();
                    registerConnsListener();

                    autoScroll = true;
                    showFabDown(false);
                    mOldConnectionsText.setVisibility(View.GONE);
                    hasUntrackedConnections = false;
                    mEmptyText.setText(R.string.no_connections);
                }

                refreshMenuIcons();
            }
        };

        LocalBroadcastManager.getInstance(getContext())
                .registerReceiver(mReceiver, new IntentFilter(CaptureService.ACTION_SERVICE_STATUS));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if(mReceiver != null) {
            LocalBroadcastManager.getInstance(getContext())
                    .unregisterReceiver(mReceiver);
        }
    }

    private void recheckScroll() {
        final LinearLayoutManager layoutMan = (LinearLayoutManager) mRecyclerView.getLayoutManager();
        assert layoutMan != null;
        int first_visibile_pos = layoutMan.findFirstCompletelyVisibleItemPosition();
        int last_visible_pos = layoutMan.findLastCompletelyVisibleItemPosition();
        int last_pos = mAdapter.getItemCount() - 1;
        boolean reached_bottom = (last_visible_pos >= last_pos);
        boolean is_scrolling = (first_visibile_pos != 0) || (!reached_bottom);

        if(is_scrolling) {
            if(reached_bottom) {
                autoScroll = true;
                showFabDown(false);
            } else {
                autoScroll = false;
                showFabDown(true);
            }
        } else
            showFabDown(false);

        if((first_visibile_pos == 0) && hasUntrackedConnections)
            mOldConnectionsText.setVisibility(View.VISIBLE);
        else
            mOldConnectionsText.setVisibility(View.GONE);
    }

    private void showFabDown(boolean visible) {
        if(visible)
            mFabDown.setVisibility(View.VISIBLE);
        else
            mFabDown.setVisibility(View.INVISIBLE);
    }

    private void scrollToBottom() {
        int last_pos = mAdapter.getItemCount() - 1;
        mRecyclerView.scrollToPosition(last_pos);

        showFabDown(false);
        mOldConnectionsText.setVisibility(View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    // This performs an unoptimized adapter refresh
    private void refreshUidConnections() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();
        int item_count;
        int uid = mAdapter.getUidFilter();

        if(reg != null)
            item_count = reg.getUidConnCount(uid);
        else
            item_count = mAdapter.getItemCount();

        Log.d(TAG, "New dataset size (uid=" +uid + "): " + item_count);

        mAdapter.setItemCount(item_count);
        mAdapter.notifyDataSetChanged();
        recheckScroll();
    }

    @Override
    public void connectionsChanges(int num_connetions) {
        // Important: must use the provided num_connections rather than accessing the register
        // in order to avoid desyncs

        mHandler.post(() -> {
            if(mAdapter.getUidFilter() != Utils.UID_NO_FILTER) {
                refreshUidConnections();
                return;
            }

            Log.d(TAG, "New dataset size: " + num_connetions);

            mAdapter.setItemCount(num_connetions);
            mAdapter.notifyDataSetChanged();
            recheckScroll();

            if(autoScroll)
                scrollToBottom();
        });
    }

    @Override
    public void connectionsAdded(int start, int count) {
        mHandler.post(() -> {
            Log.d(TAG, "Add " + count + " items at " + start);

            if(mAdapter.getUidFilter() == Utils.UID_NO_FILTER) {
                mAdapter.setItemCount(mAdapter.getItemCount() + count);
                mAdapter.notifyItemRangeInserted(start, count);
            } else
                refreshUidConnections();

            if(autoScroll)
                scrollToBottom();

            ConnectionsRegister reg = CaptureService.getConnsRegister();

            if((reg != null) && (reg.getUntrackedConnCount() > 0)) {
                String info = String.format(getString(R.string.older_connections_notice), reg.getUntrackedConnCount());
                mOldConnectionsText.setText(info);

                if(!hasUntrackedConnections) {
                    hasUntrackedConnections = true;
                    recheckScroll();
                }
            }
        });
    }

    @Override
    public void connectionsRemoved(int start, int count) {
        mHandler.post(() -> {
            Log.d(TAG, "Remove " + count + " items at " + start);

            if (mAdapter.getUidFilter() == Utils.UID_NO_FILTER) {
                mAdapter.setItemCount(mAdapter.getItemCount() - count);
                mAdapter.notifyItemRangeRemoved(start, count);
            } else
                refreshUidConnections();
        });
    }

    @Override
    public void connectionsUpdated(int[] positions) {
        mHandler.post(() -> {
            if (mAdapter.getUidFilter() != Utils.UID_NO_FILTER) {
                refreshUidConnections();
                return;
            }

            int item_count = mAdapter.getItemCount();

            for(int pos : positions) {
                if(pos < item_count) {
                    Log.d(TAG, "Changed item " + pos + ", dataset size: " + mAdapter.getItemCount());
                    mAdapter.notifyItemChanged(pos);
                }
            }
        });
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.connections_menu, menu);

        mSave = menu.findItem(R.id.save);
        mMenuItemAppSel = menu.findItem(R.id.action_show_app_filter);
        mFilterIcon = mMenuItemAppSel.getIcon();

        refreshFilterIcon();
        refreshMenuIcons();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.action_show_app_filter) {
            if(mAdapter.getUidFilter() != Utils.UID_NO_FILTER)
                setUidFilter(Utils.UID_NO_FILTER);
            else
                openAppSelector();

            return true;
        } else if(id == R.id.save) {
            openFileSelector();
            return true;
        }

        return false;
    }

    private void openAppSelector() {
        if(mApps == null) {
            /* The applications loader has not finished yet. */
            mOpenAppsWhenDone = true;
            Utils.showToast(getContext(), R.string.apps_loading_please_wait);
            return;
        }

        mOpenAppsWhenDone = false;

        ConnectionsRegister reg = CaptureService.getConnsRegister();
        if(reg == null)
            return;

        // Only show the seen apps
        Set<Integer> seen_uids = reg.getSeenUids();
        ArrayList<AppDescriptor> appsData = new ArrayList<>();

        for(Integer uid: seen_uids) {
            AppDescriptor app = mApps.get(uid);

            if(app != null)
                appsData.add(app);
        }

        Collections.sort(appsData);

        Utils.getAppSelectionDialog(getActivity(), appsData, app -> setUidFilter(app.getUid())).show();
    }

    private void setUidFilter(int uid) {
        if(mAdapter.getUidFilter() != uid) {
            // rather than calling refreshAllTheConnections, its better to let the register to the
            // job by properly scheduling the ConnectionsListener callbacks
            unregisterConnsListener();
            mAdapter.setUidFilter(uid);
            registerConnsListener();
        }

        refreshFilterIcon();
    }

    private void refreshFilterIcon() {
        if(mMenuItemAppSel == null)
            return;

        int uid = mAdapter.getUidFilter();
        AppDescriptor app = (mApps != null) ? mApps.get(uid) : null;

        if(app == null)
            app = mNoFilterApp;

        if(app.getUid() != Utils.UID_NO_FILTER) {
            Drawable drawable = (app.getIcon() != null) ? Objects.requireNonNull(app.getIcon().getConstantState()).newDrawable() : null;

            if(drawable != null) {
                mMenuItemAppSel.setIcon(drawable);
                mMenuItemAppSel.setTitle(R.string.remove_app_filter);
            }
        } else {
            // no filter
            mMenuItemAppSel.setIcon(mFilterIcon);
            mMenuItemAppSel.setTitle(R.string.set_app_filter);
        }

        if (mOpenAppsWhenDone)
            openAppSelector();
    }

    @Override
    public void onAppsInfoLoaded(Map<Integer, AppDescriptor> apps) {
        mApps = apps;

        if(mDumpWhenDone)
            dumpCsv();
    }

    @Override
    public void onAppsIconsLoaded(Map<Integer, AppDescriptor> apps) {
        // Refresh the adapter to load the apps icons
        // Don't use notifyDataSetChanged as connectionsAdded/connectionsRemoved may be pending
        mApps = apps;
        mAdapter.setApps(apps);
        mAdapter.notifyItemRangeChanged(0, mAdapter.getItemCount());

        refreshFilterIcon();
    }

    private void refreshMenuIcons() {
        if(mSave == null)
            return;

        boolean is_enabled = (CaptureService.getConnsRegister() != null);

        mMenuItemAppSel.setEnabled(is_enabled);
        mSave.setEnabled(is_enabled);
    }

    private void dumpCsv() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if(reg == null)
            return;

        if(mApps == null) {
            mDumpWhenDone = true;
            Utils.showToast(getContext(), R.string.apps_loading_please_wait);
            return;
        }

        String dump = reg.dumpConnectionsCsv(getContext(), mApps, mAdapter.getUidFilter());

        if(mCsvFname != null) {
            Log.d(TAG, "Writing CSV file: " + mCsvFname);

            try {
                OutputStream stream = getActivity().getContentResolver().openOutputStream(mCsvFname);
                stream.write(dump.getBytes());
                stream.close();

                Utils.showToast(getContext(), R.string.file_saved);
            } catch (IOException e) {
                Utils.showToast(getContext(), R.string.cannot_write_file);
                e.printStackTrace();
            }
        }

        mCsvFname = null;
        mDumpWhenDone = false;
    }

    public void openFileSelector() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, Utils.getUniqueFileName(getContext(), "csv"));

        startActivityForResult(intent, MainActivity.REQUEST_CODE_CSV_FILE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if(requestCode == MainActivity.REQUEST_CODE_CSV_FILE) {
            if(resultCode == Activity.RESULT_OK) {
                mCsvFname = data.getData();
                dumpCsv();
            } else
                mCsvFname = null;
        }
    }
}
