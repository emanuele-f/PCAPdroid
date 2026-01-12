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
 * Copyright 2026 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.fragments;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.Lifecycle;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.FilterDescriptor;
import com.google.android.material.tabs.TabLayout;

public class DataViewContainerFragment extends Fragment implements MenuProvider {
    private static final String TAG = "DataViewContainer";
    private static final String STATE_CURRENT_VIEW = "current_view";

    private static final int VIEW_CONNECTIONS = 0;
    private static final int VIEW_HTTP_LOG = 1;

    private static final int TAB_POSITION = 1;

    private int mCurrentView = VIEW_CONNECTIONS;
    private Fragment mConnectionsFragment;
    private Fragment mHttpLogFragment;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mCurrentView = savedInstanceState.getInt(STATE_CURRENT_VIEW, VIEW_CONNECTIONS);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        requireActivity().addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        return inflater.inflate(R.layout.data_view_container, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        FragmentManager childFragmentManager = getChildFragmentManager();

        mConnectionsFragment = childFragmentManager.findFragmentByTag("connections");
        mHttpLogFragment = childFragmentManager.findFragmentByTag("http_log");

        Intent intent = requireActivity().getIntent();
        Bundle connectionArgs = null;
        if (intent != null) {
            FilterDescriptor filter = Utils.getSerializableExtra(intent,
                ConnectionsFragment.FILTER_EXTRA, FilterDescriptor.class);
            String query = intent.getStringExtra(ConnectionsFragment.QUERY_EXTRA);

            if (filter != null || (query != null && !query.isEmpty())) {
                mCurrentView = VIEW_CONNECTIONS;

                connectionArgs = new Bundle();
                if (filter != null) {
                    connectionArgs.putSerializable(ConnectionsFragment.FILTER_EXTRA, filter);
                }
                if (query != null && !query.isEmpty()) {
                    connectionArgs.putString(ConnectionsFragment.QUERY_EXTRA, query);
                }

                intent.removeExtra(ConnectionsFragment.FILTER_EXTRA);
                intent.removeExtra(ConnectionsFragment.QUERY_EXTRA);
            }
        }

        FragmentTransaction transaction = childFragmentManager.beginTransaction();

        if (mConnectionsFragment == null) {
            mConnectionsFragment = new ConnectionsFragment();
            if (connectionArgs != null) {
                mConnectionsFragment.setArguments(connectionArgs);
            }
            transaction.add(R.id.child_fragment_container, mConnectionsFragment, "connections");
        }

        if (mHttpLogFragment == null) {
            mHttpLogFragment = new HttpLogFragment();
            transaction.add(R.id.child_fragment_container, mHttpLogFragment, "http_log");
        }

        if (mCurrentView == VIEW_CONNECTIONS) {
            transaction.show(mConnectionsFragment);
            transaction.hide(mHttpLogFragment);
        } else {
            transaction.show(mHttpLogFragment);
            transaction.hide(mConnectionsFragment);
        }

        transaction.commit();

        updateTabTitle();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_CURRENT_VIEW, mCurrentView);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
        if ((mCurrentView == VIEW_CONNECTIONS) && (mConnectionsFragment != null)) {
            if (mConnectionsFragment instanceof ConnectionsFragment) {
                ((ConnectionsFragment) mConnectionsFragment).onCreateMenu(menu, menuInflater);
            }

            if (CaptureService.getHttpLog() != null)
                menu.add(Menu.NONE, R.id.switch_to_http_log, 25, R.string.switch_to_http)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        } else if ((mCurrentView == VIEW_HTTP_LOG) && (mHttpLogFragment != null)) {
            if (mHttpLogFragment instanceof HttpLogFragment) {
                ((HttpLogFragment) mHttpLogFragment).onCreateMenu(menu, menuInflater);
            }
            menu.add(Menu.NONE, R.id.switch_to_connections, 25, R.string.switch_to_connections)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.switch_to_http_log) {
            switchToView(VIEW_HTTP_LOG);
            return true;
        } else if (id == R.id.switch_to_connections) {
            switchToView(VIEW_CONNECTIONS);
            return true;
        }

        if (mCurrentView == VIEW_CONNECTIONS && mConnectionsFragment != null) {
            if (mConnectionsFragment instanceof ConnectionsFragment) {
                if (((ConnectionsFragment) mConnectionsFragment).onMenuItemSelected(item)) {
                    return true;
                }
            }
        } else if (mCurrentView == VIEW_HTTP_LOG && mHttpLogFragment != null) {
            if (mHttpLogFragment instanceof HttpLogFragment) {
                if (((HttpLogFragment) mHttpLogFragment).onMenuItemSelected(item)) {
                    return true;
                }
            }
        }

        return false;
    }

    private void switchToView(int targetView) {
        if (mCurrentView == targetView) {
            return;
        }

        mCurrentView = targetView;

        FragmentTransaction transaction = getChildFragmentManager().beginTransaction();

        if (mCurrentView == VIEW_CONNECTIONS) {
            transaction.hide(mHttpLogFragment);
            transaction.show(mConnectionsFragment);
        } else {
            transaction.hide(mConnectionsFragment);
            transaction.show(mHttpLogFragment);
        }

        transaction.commit();

        updateTabTitle();

        requireActivity().invalidateMenu();
    }

    private void updateTabTitle() {
        FragmentActivity activity = requireActivity();
        TabLayout tabLayout = activity.findViewById(R.id.tablayout);

        if (tabLayout != null) {
            TabLayout.Tab tab = tabLayout.getTabAt(TAB_POSITION);
            if (tab != null) {
                int titleRes = (mCurrentView == VIEW_CONNECTIONS) ?
                    R.string.connections_view : R.string.http_requests;
                tab.setText(getString(titleRes));
            }
        }
    }

    public boolean onBackPressed() {
        if (mCurrentView == VIEW_CONNECTIONS && mConnectionsFragment != null) {
            if (mConnectionsFragment instanceof ConnectionsFragment) {
                return ((ConnectionsFragment) mConnectionsFragment).onBackPressed();
            }
        } else if (mCurrentView == VIEW_HTTP_LOG && mHttpLogFragment != null) {
            if (mHttpLogFragment instanceof HttpLogFragment) {
                return ((HttpLogFragment) mHttpLogFragment).onBackPressed();
            }
        }
        return false;
    }
}
