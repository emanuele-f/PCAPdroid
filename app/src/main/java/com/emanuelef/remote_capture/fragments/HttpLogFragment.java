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
 * Copyright 2020-24 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.HttpLog;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.HttpDetailsActivity;
import com.emanuelef.remote_capture.adapters.HttpLogAdapter;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class HttpLogFragment extends Fragment implements HttpLog.Listener, MenuProvider, SearchView.OnQueryTextListener {
    private static final String TAG = "HttpLogFragment";
    private TextView mEmptyText;
    private HttpLogAdapter mAdapter;
    private EmptyRecyclerView mRecyclerView;
    private FloatingActionButton mFabDown;
    private MenuItem mMenuItemSearch;
    private SearchView mSearchView;
    private Handler mHandler;

    private String mQueryToApply;
    private AppsResolver mApps;
    private boolean autoScroll;

    @Override
    public void onResume() {
        super.onResume();

        refreshEmptyText();

        registerHttpListener();
        mRecyclerView.setEmptyView(mEmptyText); // after registerConnsListener, when the adapter is populated
    }

    @Override
    public void onPause() {
        super.onPause();

        unregisterHttpListener();
        mRecyclerView.setEmptyView(null);

        if(mSearchView != null)
            mQueryToApply = mSearchView.getQuery().toString();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if(mSearchView != null)
            outState.putString("search", mSearchView.getQuery().toString());
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        requireActivity().addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        return inflater.inflate(R.layout.connections, container, false);
    }

    private void registerHttpListener() {
        HttpLog httpLog = CaptureService.getHttpLog();

        if (httpLog != null)
            httpLog.setListener(this);

        if (mAdapter != null) {
            mAdapter.onHttpRequestsClear();
            recheckScroll();
            scrollToBottom();
        }
    }

    private void unregisterHttpListener() {
        HttpLog httpLog = CaptureService.getHttpLog();

        if (httpLog != null)
            httpLog.setListener(null);

        if (mAdapter != null) {
            mAdapter.onHttpRequestsClear();
            recheckScroll();
            scrollToBottom();
        }
    }

    private void refreshEmptyText() {
        if((CaptureService.getHttpLog() != null) || CaptureService.isServiceActive())
            mEmptyText.setText(mAdapter.hasFilter() ? R.string.no_matches_found : R.string.no_requests);
        else
            mEmptyText.setText(R.string.capture_not_running_status);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mHandler = new Handler(Looper.getMainLooper());
        mFabDown = view.findViewById(R.id.fabDown);
        mRecyclerView = view.findViewById(R.id.connections_view);
        EmptyRecyclerView.MyLinearLayoutManager layoutMan = new EmptyRecyclerView.MyLinearLayoutManager(requireContext());
        mRecyclerView.setLayoutManager(layoutMan);
        mApps = new AppsResolver(requireContext());

        mEmptyText = view.findViewById(R.id.no_connections);
        mAdapter = new HttpLogAdapter(requireContext(), mApps);
        mRecyclerView.setAdapter(mAdapter);

        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(mRecyclerView.getContext(),
                layoutMan.getOrientation());
        mRecyclerView.addItemDecoration(dividerItemDecoration);

        mAdapter.setClickListener(v -> {
            int pos = mRecyclerView.getChildLayoutPosition(v);
            HttpLog.HttpRequest item = mAdapter.getItem(pos);

            if(item != null) {
                Intent intent = new Intent(requireContext(), HttpDetailsActivity.class);
                intent.putExtra(HttpDetailsActivity.HTTP_REQ_POS_KEY, item.getPosition());
                startActivity(intent);
            }
        });

        autoScroll = true;
        showFabDown(false);
        mFabDown.setOnClickListener(v -> scrollToBottom());

        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                recheckScroll();
            }
        });

        if(savedInstanceState != null) {
            String search = savedInstanceState.getString("search");

            if((search != null) && !search.isEmpty())
                mQueryToApply = search;
        }

        CaptureService.observeStatus(this, serviceStatus -> {
            if(serviceStatus == CaptureService.ServiceStatus.STARTED) {
                unregisterHttpListener();
                registerHttpListener();

                autoScroll = true;
                showFabDown(false);
                mEmptyText.setText(R.string.no_requests);
                mApps.clear();
            }
        });
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.http_log_menu, menu);

        mMenuItemSearch = menu.findItem(R.id.search);

        mSearchView = (SearchView) mMenuItemSearch.getActionView();
        mSearchView.setOnQueryTextListener(this);

        if((mQueryToApply != null) && (!mQueryToApply.isEmpty())) {
            String query = mQueryToApply;
            mQueryToApply = null;
            setQuery(query);
        }
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        return false;
    }

    private void setQuery(String query) {
        Utils.setSearchQuery(mSearchView, mMenuItemSearch, query);
    }

    @Override
    public boolean onQueryTextSubmit(String query) { return true; }

    @Override
    public boolean onQueryTextChange(String newText) {
        mAdapter.setSearch(newText);
        recheckScroll();
        refreshEmptyText();
        return true;
    }

    // NOTE: dispatched from activity, returns true if handled
    public boolean onBackPressed() {
        return Utils.backHandleSearchview(mSearchView);
    }

    @Override
    public void onHttpRequestAdded(int pos) {
        Utils.runOnUi(() -> {
            if (mAdapter != null) {
                mAdapter.onHttpRequestAdded(pos);

                recheckScroll();
                if (autoScroll)
                    scrollToBottom();
            }
        }, mHandler);
    }

    @Override
    public void onHttpRequestUpdated(int pos) {
        Utils.runOnUi(() -> {
            if (mAdapter != null)
                mAdapter.onHttpRequestUpdated(pos);
        }, mHandler);
    }

    @Override
    public void onHttpRequestsClear() {
        Utils.runOnUi(() -> {
            if (mAdapter != null)
                mAdapter.onHttpRequestsClear();
        }, mHandler);
    }

    private void recheckScroll() {
        final EmptyRecyclerView.MyLinearLayoutManager layoutMan = (EmptyRecyclerView.MyLinearLayoutManager) mRecyclerView.getLayoutManager();
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
    }
}
