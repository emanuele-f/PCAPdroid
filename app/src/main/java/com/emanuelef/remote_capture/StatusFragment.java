package com.emanuelef.remote_capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

public class StatusFragment extends Fragment implements AppStateListener {
    private Button mStartButton;
    private TextView mCollectorInfo;
    private TextView mCaptureStatus;
    private MainActivity mActivity;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        mActivity = (MainActivity) context;
    }

    @Override
    public void onDestroy() {
        mActivity.setStatusFragment(null);
        super.onDestroy();
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.status, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mStartButton = view.findViewById(R.id.button_start);
        mCollectorInfo = view.findViewById(R.id.collector_info);
        mCaptureStatus = view.findViewById(R.id.status_view);

        SharedPreferences mPrefs = PreferenceManager.getDefaultSharedPreferences(mActivity);

        mPrefs.registerOnSharedPreferenceChangeListener(new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
                if(mActivity.getState() == MainActivity.AppState.ready)
                    setCollectorInfo(mActivity.getCollectorIPPref(), mActivity.getCollectorPortPref());
            }
        });

        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d("Main", "Clicked");
                mActivity.toggleService();
            }
        });

        LocalBroadcastManager bcast_man = LocalBroadcastManager.getInstance(mActivity);

        /* Register for stats update */
        bcast_man.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                processStatsUpdateIntent(intent);
            }
        }, new IntentFilter(CaptureService.ACTION_TRAFFIC_STATS_UPDATE));

        /* Important: call this after all the fields have been initialized */
        mActivity.setStatusFragment(this);
    }

    @Override
    public void appStateReady() {
        mStartButton.setText(R.string.start_button);
        mStartButton.setEnabled(true);
        mCaptureStatus.setText(R.string.ready);

        setCollectorInfo(mActivity.getCollectorIPPref(), mActivity.getCollectorPortPref());
    }

    @Override
    public void appStateStarting() {
        mStartButton.setEnabled(false);
    }

    @Override
    public void appStateRunning() {
        mStartButton.setText(R.string.stop_button);
        mStartButton.setEnabled(true);
        mCaptureStatus.setText(Utils.formatBytes(CaptureService.getBytes()));

        setCollectorInfo(CaptureService.getCollectorAddress(),
                Integer.toString(CaptureService.getCollectorPort()));
    }

    @Override
    public void appStateStopping() {
        mStartButton.setEnabled(false);
    }

    private void processStatsUpdateIntent(Intent intent) {
        long bytes_sent = intent.getLongExtra(CaptureService.TRAFFIC_STATS_UPDATE_SENT_BYTES, 0);
        long bytes_rcvd = intent.getLongExtra(CaptureService.TRAFFIC_STATS_UPDATE_RCVD_BYTES, 0);
        int pkts_sent = intent.getIntExtra(CaptureService.TRAFFIC_STATS_UPDATE_SENT_PKTS, 0);
        int pkts_rcvd = intent.getIntExtra(CaptureService.TRAFFIC_STATS_UPDATE_RCVD_PKTS, 0);

        Log.d("MainReceiver", "Got StatsUpdate: bytes_sent=" + bytes_sent + ", bytes_rcvd=" +
                bytes_rcvd + ", pkts_sent=" + pkts_sent + ", pkts_rcvd=" + pkts_rcvd);

        mCaptureStatus.setText(Utils.formatBytes(bytes_sent + bytes_rcvd));
    }

    private void setCollectorInfo(String collector_ip, String collector_port) {
        mCollectorInfo.setText(String.format(getResources().getString(R.string.collector_info),
                collector_ip, collector_port));
    }
}
