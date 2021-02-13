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
 * Copyright 2020 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

public class ConnectionsFragment extends Fragment implements AppStateListener, ConnectionsListener {
    private static final String TAG = "ConnectionsFragment";
    private MainActivity mActivity;
    private Handler mHandler;
    private ConnectionsAdapter mAdapter;
    private View mFabDown;
    private EmptyRecyclerView mRecyclerView;
    private boolean autoScroll;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        mActivity = (MainActivity) context;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mActivity.removeAppStateListener(this);

        ConnectionsRegister reg = CaptureService.getConnsRegister();
        if(reg != null)
            reg.setListener(null);

        mActivity = null;
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.connections, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mRecyclerView = view.findViewById(R.id.connections_view);
        LinearLayoutManager layoutMan = new LinearLayoutManager(mActivity);
        mRecyclerView.setLayoutManager(layoutMan);

        TextView emptyText = view.findViewById(R.id.no_connections);
        mRecyclerView.setEmptyView(emptyText);

        mAdapter = new ConnectionsAdapter(mActivity);
        mRecyclerView.setAdapter(mAdapter);

        /*DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(mRecyclerView.getContext(),
                layoutMan.getOrientation());
        mRecyclerView.addItemDecoration(dividerItemDecoration);*/

        mAdapter.setClickListener(v -> {
            int pos = mRecyclerView.getChildLayoutPosition(v);
            ConnDescriptor item = mAdapter.getItem(pos);

            if(item != null) {
                Intent intent = new Intent(getContext(), ConnectionDetails.class);
                AppDescriptor app = mActivity.findAppByUid(item.uid);
                String app_name = null;//;1051

                if(app != null)
                    app_name = app.getName();
                else if(item.uid == 1000)
                    app_name = "system";
                else if(item.uid == 1051)
                    app_name = "netd";

                intent.putExtra(ConnectionDetails.CONN_EXTRA_KEY, item);

                if(app_name != null)
                    intent.putExtra(ConnectionDetails.APP_NAME_EXTRA_KEY, app_name);

                startActivity(intent);
            }
        });

        mFabDown = view.findViewById(R.id.fabDown);
        autoScroll = true;
        showFabDown(false);

        mFabDown.setOnClickListener(v -> scrollToBottom());

        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
            //public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int state) {
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
                }
            }
        });

        mHandler = new Handler();
        ConnectionsRegister reg = CaptureService.getConnsRegister();
        if(reg != null)
            reg.setListener(this);

        mActivity.addAppStateListener(this);
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
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void appStateChanged(AppState state) {
        if(state == AppState.running) {
            ConnectionsRegister reg = CaptureService.getConnsRegister();

            if(reg != null)
                reg.setListener(this);

            autoScroll = true;
            showFabDown(false);
        }
    }

    @Override
    public void appsLoaded() {
        // Refresh the adapter to load the apps icons
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void connectionsChanges() {
        mHandler.post(() -> {
            mAdapter.notifyDataSetChanged();

            if(autoScroll)
                scrollToBottom();
        });
    }

    @Override
    public void connectionsAdded(int start, int count) {
        mHandler.post(() -> {
            mAdapter.notifyItemRangeInserted(start, count);

            if(autoScroll)
                scrollToBottom();
        });
    }

    @Override
    public void connectionsRemoved(int start, int count) {
        mHandler.post(() -> mAdapter.notifyItemRangeRemoved(start, count));
    }

    @Override
    public void connectionsUpdated(int[] positions) {
        mHandler.post(() -> {
            for(int pos : positions) {
                mAdapter.notifyItemChanged(pos);
            }
        });
    }
}
