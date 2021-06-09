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
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.adapters.ExclusionsEditAdapter;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.model.AppState;
import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.activities.ConnectionDetailsActivity;
import com.emanuelef.remote_capture.adapters.ConnectionsAdapter;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.model.ConnectionsMatcher;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;

public class ConnectionsFragment extends Fragment implements ConnectionsListener {
    private static final String TAG = "ConnectionsFragment";
    private Handler mHandler;
    private ConnectionsAdapter mAdapter;
    private FloatingActionButton mFabDown;
    private EmptyRecyclerView mRecyclerView;
    private TextView mEmptyText;
    private TextView mOldConnectionsText;
    private boolean autoScroll;
    private boolean listenerSet;
    private MenuItem mMenuItemAppSel;
    private MenuItem mMenuItemExclusions;
    private MenuItem mMenuItemEnableExclusions;
    private MenuItem mMenuItemDisableExclusions;
    private MenuItem mSave;
    private Drawable mFilterIcon;
    private AppDescriptor mNoFilterApp;
    private BroadcastReceiver mReceiver;
    private Uri mCsvFname;
    private boolean hasUntrackedConnections;
    private AppsResolver mApps;

    private final ActivityResultLauncher<Intent> csvFileLauncher =
            registerForActivityResult(new StartActivityForResult(), this::csvFileResult);

    @Override
    public void onResume() {
        super.onResume();

        registerConnsListener();
    }

    @Override
    public void onPause() {
        super.onPause();

        unregisterConnsListener();
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
        LinearLayoutManager layoutMan = new LinearLayoutManager(requireContext());
        mRecyclerView.setLayoutManager(layoutMan);
        mApps = new AppsResolver(requireContext());
        mEmptyText = view.findViewById(R.id.no_connections);

        if(((MainActivity) requireActivity()).getState() == AppState.running)
            mEmptyText.setText(R.string.no_connections);

        mAdapter = new ConnectionsAdapter(requireContext(), mApps);
        mRecyclerView.setAdapter(mAdapter);
        listenerSet = false;
        mRecyclerView.setEmptyView(mEmptyText);
        registerForContextMenu(mRecyclerView);

        Drawable icon = ContextCompat.getDrawable(requireContext(), android.R.color.transparent);
        mNoFilterApp = new AppDescriptor("", icon, this.getResources().getString(R.string.no_filter), Utils.UID_NO_FILTER, false);

        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(mRecyclerView.getContext(),
                layoutMan.getOrientation());
        mRecyclerView.addItemDecoration(dividerItemDecoration);

        mAdapter.setClickListener(v -> {
            int pos = mRecyclerView.getChildLayoutPosition(v);
            ConnectionDescriptor item = mAdapter.getItem(pos);

            if(item != null) {
                Intent intent = new Intent(requireContext(), ConnectionDetailsActivity.class);
                AppDescriptor app = mApps.get(item.uid);
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

        refreshMenuIcons();

        int uidFilter = Utils.UID_NO_FILTER;
        Intent intent = requireActivity().getIntent();

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
                    if(listenerSet) {
                        unregisterConnsListener();
                        registerConnsListener();
                    }

                    autoScroll = true;
                    showFabDown(false);
                    mOldConnectionsText.setVisibility(View.GONE);
                    hasUntrackedConnections = false;
                    mEmptyText.setText(R.string.no_connections);
                    mApps.clear();
                }

                refreshMenuIcons();
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
        }
    }

    @Override
    public void onCreateContextMenu(@NonNull ContextMenu menu, @NonNull View v,
                                    @Nullable ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        MenuInflater inflater = requireActivity().getMenuInflater();
        inflater.inflate(R.menu.connection_context_menu, menu);

        ConnectionDescriptor conn = mAdapter.getClickedItem();

        if(conn == null)
            return;

        AppDescriptor app = mApps.get(conn.uid);
        StyleSpan italic = new StyleSpan(Typeface.ITALIC);
        Context ctx = requireContext();

        if(app != null) {
            MenuItem item = menu.findItem(R.id.exclude_app);
            item.setTitle(Utils.formatTextValue(ctx, null, italic, R.string.app_val, app.getName()));
            item.setVisible(true);
        }

        if((conn.info != null) && (!conn.info.isEmpty())) {
            MenuItem item = menu.findItem(R.id.exclude_host);
            item.setTitle(Utils.formatTextValue(ctx, null, italic, R.string.host_val, conn.info));
            item.setVisible(true);
        }

        menu.findItem(R.id.exclude_ip).setTitle(Utils.formatTextValue(ctx, null, italic, R.string.ip_address_val, conn.dst_ip));
        menu.findItem(R.id.exclude_proto).setTitle(Utils.formatTextValue(ctx, null, italic, R.string.protocol_val, conn.l7proto));
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        ConnectionsRegister reg = CaptureService.getConnsRegister();
        ConnectionDescriptor conn = mAdapter.getClickedItem();

        if((reg == null) || (conn == null))
            return super.onContextItemSelected(item);

        int id = item.getItemId();
        String label = item.getTitle().toString();

        if(id == R.id.exclude_app)
            reg.mExclusions.addApp(conn.uid, label);
        else if(id == R.id.exclude_host)
            reg.mExclusions.addHost(conn.info, label);
        else if(id == R.id.exclude_ip)
            reg.mExclusions.addIp(conn.dst_ip, label);
        else if(id == R.id.exclude_proto)
            reg.mExclusions.addProto(conn.l7proto, label);
        else
            return super.onContextItemSelected(item);

        refreshExclusionsMenu();
        refreshFilteredConnections();
        return true;
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
        // compared to setVisibility, .show/.hide provide animations and also properly clear the AnchorId
        if(visible)
            mFabDown.show();
        else
            mFabDown.hide();
    }

    private void scrollToBottom() {
        int last_pos = mAdapter.getItemCount() - 1;
        mRecyclerView.scrollToPosition(last_pos);

        showFabDown(false);
        mOldConnectionsText.setVisibility(View.GONE);
    }

    private boolean hasConnectionFilter() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();
        return((mAdapter.getUidFilter() != Utils.UID_NO_FILTER) || ((reg != null) && reg.hasExclusionFilter()));
    }

    // This performs an unoptimized adapter refresh
    private void refreshFilteredConnections() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();
        int item_count;
        int uid = mAdapter.getUidFilter();

        if(reg != null)
            item_count = reg.getFilteredConnCount(uid);
        else
            item_count = mAdapter.getItemCount();

        Log.d(TAG, "New dataset size (uid=" +uid + "): " + item_count);

        mAdapter.setItemCount(item_count);
        mAdapter.notifyDataSetChanged();
        recheckScroll();
    }

    @Override
    public void connectionsChanges(int num_connections) {
        // Important: must use the provided num_connections rather than accessing the register
        // in order to avoid desyncs

        mHandler.post(() -> {
            if(hasConnectionFilter()) {
                refreshFilteredConnections();
                return;
            }

            Log.d(TAG, "New dataset size: " + num_connections);

            mAdapter.setItemCount(num_connections);
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

            if(!hasConnectionFilter()) {
                mAdapter.setItemCount(mAdapter.getItemCount() + count);
                mAdapter.notifyItemRangeInserted(start, count);
            } else
                refreshFilteredConnections();

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

            if (!hasConnectionFilter()) {
                mAdapter.setItemCount(mAdapter.getItemCount() - count);
                mAdapter.notifyItemRangeRemoved(start, count);
            } else
                refreshFilteredConnections();
        });
    }

    @Override
    public void connectionsUpdated(int[] positions) {
        mHandler.post(() -> {
            if (hasConnectionFilter()) {
                refreshFilteredConnections();
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
        mMenuItemExclusions = menu.findItem(R.id.exclusions);
        mMenuItemEnableExclusions = menu.findItem(R.id.enable_exclusions);
        mMenuItemDisableExclusions = menu.findItem(R.id.disable_exclusions);
        mFilterIcon = mMenuItemAppSel.getIcon();

        refreshFilterIcon();
        refreshMenuIcons();
        refreshExclusionsMenu();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.action_show_app_filter) {
            if(hasConnectionFilter())
                setUidFilter(Utils.UID_NO_FILTER);
            else
                openAppSelector();

            return true;
        } else if(id == R.id.save) {
            openFileSelector();
            return true;
        } else if(id == R.id.edit_exclusions) {
            showExclusionsEditor();
            return true;
        } else if((id == R.id.enable_exclusions) || (id == R.id.disable_exclusions)) {
            ConnectionsRegister reg = CaptureService.getConnsRegister();
            if(reg == null)
                return false;

            reg.mExclusionsEnabled = !reg.mExclusionsEnabled;

            // Delay the refresh to wait for the menu to be closed
            (new Handler(requireActivity().getMainLooper())).postDelayed(() -> {
                refreshExclusionsMenu();
                refreshFilteredConnections();
            }, 50);

            return true;
        } else if(id == R.id.delete_exclusions) {
            new AlertDialog.Builder(requireContext())
                    .setMessage(R.string.delete_exclusions_confirm)
                    .setPositiveButton(R.string.yes, (dialog, whichButton) -> deleteExclusions())
                    .setNegativeButton(R.string.no, (dialog, whichButton) -> {})
                    .show();

            return true;
        }

        return false;
    }

    private void openAppSelector() {
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

        Utils.getAppSelectionDialog(requireActivity(), appsData, app -> setUidFilter(app.getUid())).show();
    }

    private void setUidFilter(int uid) {
        if(mAdapter.getUidFilter() != uid) {
            // rather than calling refreshAllTheConnections, its better to let the register to the
            // job by properly scheduling the ConnectionsListener callbacks
            boolean hasListener = listenerSet;
            unregisterConnsListener();
            mAdapter.setUidFilter(uid);

            if(hasListener)
                registerConnsListener();
        }

        refreshFilterIcon();
    }

    private void refreshFilterIcon() {
        if(mMenuItemAppSel == null)
            return;

        int uid = mAdapter.getUidFilter();
        AppDescriptor app = (uid != Utils.UID_NO_FILTER) ? mApps.get(uid) : null;

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
    }

    private void refreshMenuIcons() {
        if(mSave == null)
            return;

        boolean is_enabled = (CaptureService.getConnsRegister() != null);

        mMenuItemAppSel.setEnabled(is_enabled);
        mSave.setEnabled(is_enabled);
    }

    private void refreshExclusionsMenu() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if(reg == null)
            return;

        // Update the icon only if something changed
        // NOTE: getApplicationContext required to properly style the tint
        mMenuItemExclusions.setIcon(
                ContextCompat.getDrawable(requireContext().getApplicationContext(),
                        reg.mExclusionsEnabled ? R.drawable.ic_eye_slash : R.drawable.ic_eye));

        mMenuItemExclusions.setVisible(!reg.mExclusions.isEmpty());
        mMenuItemDisableExclusions.setVisible(reg.mExclusionsEnabled);
        mMenuItemEnableExclusions.setVisible(!reg.mExclusionsEnabled);
    }

    private void dumpCsv() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if(reg == null)
            return;

        String dump = reg.dumpConnectionsCsv(requireContext(), mAdapter.getUidFilter());

        if(mCsvFname != null) {
            Log.d(TAG, "Writing CSV file: " + mCsvFname);
            boolean error = true;

            try {
                OutputStream stream = requireActivity().getContentResolver().openOutputStream(mCsvFname);

                if(stream != null) {
                    stream.write(dump.getBytes());
                    stream.close();
                }

                String fname = Utils.getUriFname(requireContext(), mCsvFname);

                if(fname != null) {
                    String msg = String.format(getString(R.string.file_saved_with_name), fname);
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
                } else
                    Utils.showToast(requireContext(), R.string.file_saved);

                error = false;
            } catch (IOException e) {
                e.printStackTrace();
            }

            if(error)
                Utils.showToast(requireContext(), R.string.cannot_write_file);
        }

        mCsvFname = null;
    }

    public void openFileSelector() {
        boolean noFileDialog = false;
        String fname = Utils.getUniqueFileName(requireContext(), "csv");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, fname);

        if(Utils.supportsFileDialog(requireContext(), intent)) {
            try {
                csvFileLauncher.launch(intent);
            } catch (ActivityNotFoundException e) {
                noFileDialog = true;
            }
        } else
            noFileDialog = true;

        if(noFileDialog) {
            Log.w(TAG, "No app found to handle file selection");

            // Pick default path
            Uri uri = Utils.getInternalStorageFile(requireContext(), fname);

            if(uri != null) {
                mCsvFname = uri;
                dumpCsv();
            } else
                Utils.showToastLong(requireContext(), R.string.no_activity_file_selection);
        }
    }

    private void showExclusionsEditor() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if(reg == null)
            return;

        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        ExclusionsEditAdapter adapter = new ExclusionsEditAdapter(requireContext(),
                R.layout.exclusion_item, reg.mExclusions.iterItems());
        View exclListView = requireActivity().getLayoutInflater().inflate(R.layout.exclusion_list, null);

        ListView exclusion = ((ListView)exclListView.findViewById(R.id.list));
        exclusion.setAdapter(adapter);
        exclusion.setOnItemClickListener((parent, view, position, id) -> {
            Log.d(TAG, "TODO:2 on click view " + position);

            if(adapter.getCount() > 1)
                adapter.remove(adapter.getItem(position));
        });

        builder.setTitle(R.string.edit_exclusions);
        builder.setView(exclListView);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
           updateExclusions(adapter);
        });
        builder.setNeutralButton(R.string.cancel, (dialog, which) -> dialog.dismiss());

        final AlertDialog alert = builder.create();
        alert.setCanceledOnTouchOutside(true);

        alert.show();
    }

    private void updateExclusions(ExclusionsEditAdapter adapter) {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if(reg == null)
            return;

        Iterator<ConnectionsMatcher.Item> iter = reg.mExclusions.iterItems();
        boolean changed = false;

        // Remove the exclusions which are not in the adapter dataset
        while(iter.hasNext()) {
            ConnectionsMatcher.Item item = iter.next();

            if(adapter.getPosition(item) < 0) {
                iter.remove();
                changed = true;
            }
        }

        if(changed) {
            refreshExclusionsMenu();
            refreshFilteredConnections();
        }
    }

    private void deleteExclusions() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if(reg == null)
            return;

        reg.mExclusions.clear();
        refreshExclusionsMenu();
        refreshFilteredConnections();
    }

    private void csvFileResult(final ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            mCsvFname = result.getData().getData();
            dumpCsv();
        } else {
            mCsvFname = null;
        }
    }
}
