package com.emanuelef.remote_capture.fragments;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.emanuelef.remote_capture.AppsLoader;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.adapters.AppsStatsAdapter;
import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppStats;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;

import java.util.Map;

public class AppsFragment extends Fragment implements ConnectionsListener, AppsLoadListener {
    private EmptyRecyclerView mRecyclerView;
    private AppsStatsAdapter mAdapter;
    private static final String TAG = "AppsFragment";
    private Handler mHandler;
    private boolean mRefreshApps;
    private boolean listenerSet;
    private Map<Integer, AppDescriptor> mApps;

    @Override
    public void onDestroy() {
        super.onDestroy();

        unregisterConnsListener();
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.apps_stats, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mRecyclerView = view.findViewById(R.id.apps_stats_view);
        LinearLayoutManager layoutMan = new LinearLayoutManager(getContext());
        mRecyclerView.setLayoutManager(layoutMan);

        TextView emptyText = view.findViewById(R.id.no_apps);
        mRecyclerView.setEmptyView(emptyText);

        mAdapter = new AppsStatsAdapter(getContext());
        mRecyclerView.setAdapter(mAdapter);

        mHandler = new Handler(Looper.getMainLooper());
        mRefreshApps = false;

        mAdapter.setClickListener(v -> {
            int pos = mRecyclerView.getChildLayoutPosition(v);
            AppStats item = mAdapter.getItem(pos);

            if(item != null) {
                AppDescriptor app = mApps.get(item.getUid());

                if(app != null) {
                    // TODO
                    //mActivity.setSelectedApp(app);
                    //mActivity.setActivePage(MainActivity.POS_CONNECTIONS);
                }
            }
        });

        registerConnsListener();

        (new AppsLoader((AppCompatActivity) getActivity()))
                .setAppsLoadListener(this)
                .loadAllApps();

        LocalBroadcastManager bcast_man = LocalBroadcastManager.getInstance(getContext());

        /* Register for service status */
        bcast_man.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String status = intent.getStringExtra(CaptureService.SERVICE_STATUS_KEY);

                if(CaptureService.SERVICE_STATUS_STARTED.equals(status)) {
                    // register the new connection register
                    unregisterConnsListener();
                    registerConnsListener();
                }
            }
        }, new IntentFilter(CaptureService.ACTION_SERVICE_STATUS));
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

    @Override
    public void onAppsInfoLoaded(Map<Integer, AppDescriptor> apps) {
        // refresh the names
        mApps = apps;
        mAdapter.setApps(apps);
    }

    @Override
    public void onAppsIconsLoaded(Map<Integer, AppDescriptor> apps) {
        // refresh the icons
        mApps = apps;
        mAdapter.setApps(apps);
    }
}
