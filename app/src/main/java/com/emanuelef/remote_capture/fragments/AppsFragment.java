package com.emanuelef.remote_capture.fragments;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.adapters.AppsStatsAdapter;
import com.emanuelef.remote_capture.interfaces.AppStateListener;
import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppState;
import com.emanuelef.remote_capture.model.AppStats;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;

public class AppsFragment extends Fragment implements AppStateListener, ConnectionsListener {
    private EmptyRecyclerView mRecyclerView;
    private AppsStatsAdapter mAdapter;
    private static final String TAG = "AppsFragment";
    private MainActivity mActivity;
    private Handler mHandler;
    private boolean mRefreshApps;
    private boolean listenerSet;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        mActivity = (MainActivity) context;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mActivity.removeAppStateListener(this);
        unregisterListener();

        mActivity = null;
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.apps_stats, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mRecyclerView = view.findViewById(R.id.apps_stats_view);
        LinearLayoutManager layoutMan = new LinearLayoutManager(mActivity);
        mRecyclerView.setLayoutManager(layoutMan);

        TextView emptyText = view.findViewById(R.id.no_apps);
        mRecyclerView.setEmptyView(emptyText);

        mAdapter = new AppsStatsAdapter(mActivity);
        mRecyclerView.setAdapter(mAdapter);

        mHandler = new Handler(Looper.getMainLooper());
        mRefreshApps = false;

        mAdapter.setClickListener(v -> {
            if(!mActivity.canApplyTmpFilter())
                return;

            int pos = mRecyclerView.getChildLayoutPosition(v);
            AppStats item = mAdapter.getItem(pos);

            if(item != null) {
                AppDescriptor app = mActivity.findAppByUid(item.getUid());

                if(app != null) {
                    mActivity.setSelectedApp(app);
                    mActivity.setActivePage(MainActivity.POS_CONNECTIONS);
                }
            }
        });

        registerListener();
        mActivity.addAppStateListener(this);
    }

    private void registerListener() {
        if (!listenerSet) {
            ConnectionsRegister reg = CaptureService.getConnsRegister();

            if (reg != null) {
                reg.addListener(this);
                listenerSet = true;
            }
        }
    }

    private void unregisterListener() {
        if(listenerSet) {
            ConnectionsRegister reg = CaptureService.getConnsRegister();
            if (reg != null)
                reg.removeListener(this);

            listenerSet = false;
        }
    }

    @Override
    public void appStateChanged(AppState state) {
        if(state == AppState.running) {
            unregisterListener();
            registerListener();
        }
    }

    @Override
    public void appsLoaded() {
        // refresh the icons
        mAdapter.notifyDataSetChanged();
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
    public void connectionsAdded(int start, int count) {
        refreshAppsAsync();
    }

    @Override
    public void connectionsRemoved(int start, int count) {
        refreshAppsAsync();
    }

    @Override
    public void connectionsUpdated(int[] positions) {
        refreshAppsAsync();
    }
}
