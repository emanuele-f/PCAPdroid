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

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
    private SharedPreferences mPrefs;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        mActivity = (MainActivity) context;
    }

    @Override
    public void onDestroy() {
        mActivity.setStatusFragment(null);
        mActivity = null;
        super.onDestroy();
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.status, container, false);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mStartButton = view.findViewById(R.id.button_start);
        mCollectorInfo = view.findViewById(R.id.collector_info);
        mCaptureStatus = view.findViewById(R.id.status_view);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mActivity);

        // Make URLs clickable
        mCollectorInfo.setMovementMethod(LinkMovementMethod.getInstance());

        // Add settings icon click
        mCollectorInfo.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                Drawable mCollectorInfoDrawable = mCollectorInfo.getCompoundDrawables()[2 /* Right */];

                if((mCollectorInfoDrawable != null) && (event.getAction() == MotionEvent.ACTION_UP)) {
                    if(event.getRawX() >= (mCollectorInfo.getRight() - mCollectorInfoDrawable.getBounds().width())) {
                        Intent intent = new Intent(mActivity, SettingsActivity.class);
                        startActivity(intent);

                        return true;
                    }
                }
                return false;
            }
        });

        mPrefs.registerOnSharedPreferenceChangeListener(new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
                if((mActivity != null) && (mActivity.getState() == MainActivity.AppState.ready))
                    refreshPcapDumpInfo();
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

        refreshPcapDumpInfo();
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

        refreshPcapDumpInfo();
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

    private void refreshPcapDumpInfo() {
        String info;
        String modeName;

        Prefs.DumpMode mode = CaptureService.isServiceActive() ? CaptureService.getDumpMode() : Prefs.getDumpMode(mPrefs);

        switch (mode) {
        case HTTP_SERVER:
            modeName = getResources().getString(R.string.http_server);
            info = String.format(getResources().getString(R.string.http_server_status),
                    Utils.getLocalIPAddress(mActivity), CaptureService.getHTTPServerPort());
            break;
        case UDP_EXPORTER:
            modeName = getResources().getString(R.string.udp_exporter);
            info = String.format(getResources().getString(R.string.collector_info),
                    CaptureService.getCollectorAddress(), CaptureService.getCollectorPort());
            break;
        default:
            modeName = getResources().getString(R.string.no_dump);
            info = "";
            break;
        }

        if(!CaptureService.isServiceActive()) {
            info = getResources().getString(R.string.dump_mode) + ": " + modeName;

            if(Prefs.getTlsDecryptionEnabled(mPrefs))
                info += " (" + getResources().getString(R.string.with_tls_decryption) + ")";

            mCollectorInfo.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_settings, 0);
        } else
            mCollectorInfo.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0);

        mCollectorInfo.setText(info);
    }
}
