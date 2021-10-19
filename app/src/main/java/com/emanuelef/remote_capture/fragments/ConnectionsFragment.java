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
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.AppDetailsActivity;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppState;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.activities.ConnectionDetailsActivity;
import com.emanuelef.remote_capture.adapters.ConnectionsAdapter;
import com.emanuelef.remote_capture.model.FilterDescriptor;
import com.emanuelef.remote_capture.model.MatchList;
import com.emanuelef.remote_capture.model.MatchList.RuleType;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;
import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.activities.EditFilterActivity;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

public class ConnectionsFragment extends Fragment implements ConnectionsListener, SearchView.OnQueryTextListener {
    private static final String TAG = "ConnectionsFragment";
    public static final String FILTER_EXTRA = "filter";
    private Handler mHandler;
    private ConnectionsAdapter mAdapter;
    private FloatingActionButton mFabDown;
    private EmptyRecyclerView mRecyclerView;
    private TextView mEmptyText;
    private TextView mOldConnectionsText;
    private boolean autoScroll;
    private boolean listenerSet;
    private MenuItem mMenuFilter;
    private MenuItem mMenuItemSearch;
    private MenuItem mSave;
    private BroadcastReceiver mReceiver;
    private Uri mCsvFname;
    private boolean hasUntrackedConnections;
    private AppsResolver mApps;
    private SearchView mSearchView;
    private String mQueryToApply;

    private final ActivityResultLauncher<Intent> csvFileLauncher =
            registerForActivityResult(new StartActivityForResult(), this::csvFileResult);
    private final ActivityResultLauncher<Intent> filterLauncher =
            registerForActivityResult(new StartActivityForResult(), this::filterResult);

    @Override
    public void onResume() {
        super.onResume();

        registerConnsListener();
        refreshMenuIcons();
    }

    @Override
    public void onPause() {
        super.onPause();

        unregisterConnsListener();

        if(mSearchView != null)
            mQueryToApply = mSearchView.getQuery().toString();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if(mSearchView != null)
            outState.putString("search", mSearchView.getQuery().toString());
        if(mAdapter != null)
            outState.putSerializable("filter_desc", mAdapter.mFilter);
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

        if((requireActivity() instanceof MainActivity) &&
                (((MainActivity) requireActivity()).getState() == AppState.running))
            mEmptyText.setText(R.string.no_connections);

        mAdapter = new ConnectionsAdapter(requireContext(), mApps);
        mRecyclerView.setAdapter(mAdapter);
        listenerSet = false;
        mRecyclerView.setEmptyView(mEmptyText);
        registerForContextMenu(mRecyclerView);

        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(mRecyclerView.getContext(),
                layoutMan.getOrientation());
        mRecyclerView.addItemDecoration(dividerItemDecoration);

        mAdapter.setClickListener(v -> {
            int pos = mRecyclerView.getChildLayoutPosition(v);
            ConnectionDescriptor item = mAdapter.getItem(pos);

            if(item != null) {
                Intent intent = new Intent(requireContext(), ConnectionDetailsActivity.class);
                AppDescriptor app = mApps.get(item.uid, 0);
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

        String search = "";
        boolean fromIntent = false;
        Intent intent = requireActivity().getIntent();

        if(intent != null) {
            search = intent.getStringExtra(FILTER_EXTRA);

            if((search != null) && !search.isEmpty()) {
                // Avoid hiding the interesting items
                mAdapter.mFilter.showMasked = true;
                fromIntent = true;
            }
        }

        if(savedInstanceState != null) {
            if((search == null) || search.isEmpty())
                search = savedInstanceState.getString("search");

            if(!fromIntent && savedInstanceState.containsKey("filter_desc"))
                mAdapter.mFilter = (FilterDescriptor) savedInstanceState.getSerializable("filter_desc");
        }

        if((search != null) && !search.isEmpty())
            mQueryToApply = search;

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

        AppDescriptor app = mApps.get(conn.uid, 0);
        Context ctx = requireContext();

        if(app != null) {
            MenuItem item = menu.findItem(R.id.hide_app);
            String label = MatchList.getLabel(ctx, RuleType.APP, app.getName());
            item.setTitle(label);
            item.setVisible(true);

            item = menu.findItem(R.id.search_app);
            item.setTitle(label);
            item.setVisible(true);
        }

        if((conn.info != null) && (!conn.info.isEmpty())) {
            MenuItem item = menu.findItem(R.id.hide_host);
            String label = MatchList.getLabel(ctx, RuleType.HOST, conn.info);
            item.setTitle(label);
            item.setVisible(true);

            item = menu.findItem(R.id.search_host);
            item.setTitle(label);
            item.setVisible(true);

            String rootDomain = Utils.getRootDomain(conn.info);

            if(!rootDomain.equals(conn.info)) {
                item = menu.findItem(R.id.hide_root_domain);
                item.setTitle(MatchList.getLabel(ctx, RuleType.ROOT_DOMAIN, rootDomain));
                item.setVisible(true);
            }
        }

        String label = MatchList.getLabel(ctx, RuleType.IP, conn.dst_ip);
        menu.findItem(R.id.hide_ip).setTitle(label);
        menu.findItem(R.id.search_ip).setTitle(label);

        label = MatchList.getLabel(ctx, RuleType.PROTOCOL, conn.l7proto);
        menu.findItem(R.id.hide_proto).setTitle(label);
        menu.findItem(R.id.search_proto).setTitle(label);
    }

    private void setQuery(String query) {
        mSearchView.setIconified(false);
        mMenuItemSearch.expandActionView();

        mSearchView.setIconified(false);
        mMenuItemSearch.expandActionView();

        // Delay otherwise the query won't be set when the activity is just started.
        mSearchView.post(() -> {
            mSearchView.setQuery(query, true);
        });
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        ConnectionDescriptor conn = mAdapter.getClickedItem();

        if(conn == null)
            return super.onContextItemSelected(item);

        int id = item.getItemId();
        String label = item.getTitle().toString();

        if(id == R.id.hide_app)
            mAdapter.mMask.addApp(conn.uid, label);
        else if(id == R.id.hide_host)
            mAdapter.mMask.addHost(conn.info, label);
        else if(id == R.id.hide_ip)
            mAdapter.mMask.addIp(conn.dst_ip, label);
        else if(id == R.id.hide_proto)
            mAdapter.mMask.addProto(conn.l7proto, label);
        else if(id == R.id.hide_root_domain)
            mAdapter.mMask.addRootDomain(Utils.getRootDomain(conn.info), label);
        else if(id == R.id.search_app) {
            setQuery(Objects.requireNonNull(
                    mApps.get(conn.uid, 0)).getPackageName());
            return true;
        } else if(id == R.id.search_host) {
            setQuery(conn.info);
            return true;
        } else if(id == R.id.search_ip) {
            setQuery(conn.dst_ip);
            return true;
        } else if(id == R.id.search_proto) {
            setQuery(conn.l7proto);
            return true;
        } else if(id == R.id.open_app_details) {
            Intent intent = new Intent(requireContext(), AppDetailsActivity.class);
            intent.putExtra(AppDetailsActivity.APP_UID_EXTRA, conn.uid);
            startActivity(intent);
            return true;
        } else
            return super.onContextItemSelected(item);

        mAdapter.mMask.save();
        mAdapter.mFilter.showMasked = false;
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

    // This performs an unoptimized adapter refresh
    private void refreshFilteredConnections() {
        mAdapter.refreshFilteredConnections();
        refreshMenuIcons();
        recheckScroll();
    }

    @Override
    public void connectionsChanges(int num_connections) {
        // Important: must use the provided num_connections rather than accessing the register
        // in order to avoid desyncs

        mHandler.post(() -> {
            Log.d(TAG, "New connections size: " + num_connections);

            mAdapter.connectionsChanges(num_connections);
            recheckScroll();

            if(autoScroll)
                scrollToBottom();
        });
    }

    @Override
    public void connectionsAdded(int start, ConnectionDescriptor []conns) {
        mHandler.post(() -> {
            Log.d(TAG, "Add " + conns.length + " connections at " + start);

            mAdapter.connectionsAdded(start, conns);

            if(autoScroll)
                scrollToBottom();

            ConnectionsRegister reg = CaptureService.requireConnsRegister();

            if(reg.getUntrackedConnCount() > 0) {
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
    public void connectionsRemoved(int start,ConnectionDescriptor []conns) {
        mHandler.post(() -> {
            Log.d(TAG, "Remove " + conns.length + " connections at " + start);
            mAdapter.connectionsRemoved(start, conns);
        });
    }

    @Override
    public void connectionsUpdated(int[] positions) {
        mHandler.post(() -> mAdapter.connectionsUpdated(positions));
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.connections_menu, menu);

        mSave = menu.findItem(R.id.save);
        mMenuFilter = menu.findItem(R.id.edit_filter);
        mMenuItemSearch = menu.findItem(R.id.search);

        mSearchView = (SearchView) mMenuItemSearch.getActionView();
        mSearchView.setOnQueryTextListener(this);

        if((mQueryToApply != null) && (!mQueryToApply.isEmpty())) {
            String query = mQueryToApply;
            mQueryToApply = null;
            setQuery(query);
        }

        refreshMenuIcons();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.save) {
            openFileSelector();
            return true;
        } else if(id == R.id.edit_filter) {
            Intent intent = new Intent(requireContext(), EditFilterActivity.class);
            intent.putExtra(EditFilterActivity.FILTER_DESCRIPTOR, mAdapter.mFilter);
            filterLauncher.launch(intent);
            return true;
        }

        return false;
    }

    private void refreshMenuIcons() {
        if(mSave == null)
            return;

        boolean is_enabled = (CaptureService.getConnsRegister() != null);

        mMenuItemSearch.setVisible(is_enabled); // NOTE: setEnabled does not work for this
        //mMenuFilter.setEnabled(is_enabled);
        mSave.setEnabled(is_enabled);
    }

    private void dumpCsv() {
        String dump = mAdapter.dumpConnectionsCsv();

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

    private void csvFileResult(final ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            mCsvFname = result.getData().getData();
            dumpCsv();
        } else {
            mCsvFname = null;
        }
    }

    private void filterResult(final ActivityResult result) {
        if(result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            FilterDescriptor descriptor = (FilterDescriptor)result.getData().getSerializableExtra(EditFilterActivity.FILTER_DESCRIPTOR);
            if(descriptor != null) {
                mAdapter.mFilter = descriptor;
                mAdapter.refreshFilteredConnections();
            }
        }
    }

    @Override
    public boolean onQueryTextSubmit(String query) { return true; }

    @Override
    public boolean onQueryTextChange(String newText) {
        mAdapter.setSearch(newText);
        recheckScroll();
        return true;
    }

    public boolean onBackPressed() {
        if((mSearchView != null) && !mSearchView.isIconified()) {
            // Required to close the SearchView when the search submit button was not pressed
            mSearchView.setIconified(true);
            return true;
        }

        return false;
    }
}
