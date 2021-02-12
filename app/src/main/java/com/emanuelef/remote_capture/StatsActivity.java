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

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;

public class StatsActivity extends AppCompatActivity {
    TextView mBytesSent;
    TextView mBytesRcvd;
    TextView mPacketsSent;
    TextView mPacketsRcvd;
    TextView mActiveConns;
    TextView mDroppedConns;
    TextView mTotConns;
    TextView mMaxFd;
    TextView mOpenSocks;
    TextView mDnsQueries;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);

        setTitle(R.string.stats);

        mBytesSent = findViewById(R.id.bytes_sent);
        mBytesRcvd = findViewById(R.id.bytes_rcvd);
        mPacketsSent = findViewById(R.id.packets_sent);
        mPacketsRcvd = findViewById(R.id.packets_rcvd);
        mActiveConns = findViewById(R.id.active_connections);
        mDroppedConns = findViewById(R.id.dropped_connections);
        mTotConns = findViewById(R.id.tot_connections);
        mMaxFd = findViewById(R.id.max_fd);
        mOpenSocks = findViewById(R.id.open_sockets);
        mDnsQueries = findViewById(R.id.dns_queries);

        LocalBroadcastManager bcast_man = LocalBroadcastManager.getInstance(this);

        /* Register for updates */
        bcast_man.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateVPNStats(intent);
            }
        }, new IntentFilter(CaptureService.ACTION_STATS_DUMP));
        bcast_man.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateTrafficStats(intent);
            }
        }, new IntentFilter(CaptureService.ACTION_TRAFFIC_STATS_UPDATE));

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        CaptureService.askStatsDump();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case android.R.id.home:
                /* Make the back button in the action bar behave like the back button */
                onBackPressed();
                return true;
            default:
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateVPNStats(Intent intent) {
        VPNStats stats = (VPNStats) intent.getSerializableExtra("value");

        mActiveConns.setText(Utils.formatNumber(this, stats.active_conns));
        mDroppedConns.setText(Utils.formatNumber(this, stats.num_dropped_conns));
        mTotConns.setText(Utils.formatNumber(this, stats.tot_conns));
        mMaxFd.setText(Utils.formatNumber(this, stats.max_fd));
        mOpenSocks.setText(Utils.formatNumber(this, stats.num_open_sockets));
        mDnsQueries.setText(Utils.formatNumber(this, stats.num_dns_queries));

        if(stats.num_dropped_conns > 0)
            mDroppedConns.setTextColor(Color.RED);
    }

    private void updateTrafficStats(Intent intent) {
        long bytes_sent = intent.getLongExtra(CaptureService.TRAFFIC_STATS_UPDATE_SENT_BYTES, 0);
        long bytes_rcvd = intent.getLongExtra(CaptureService.TRAFFIC_STATS_UPDATE_RCVD_BYTES, 0);
        int pkts_sent = intent.getIntExtra(CaptureService.TRAFFIC_STATS_UPDATE_SENT_PKTS, 0);
        int pkts_rcvd = intent.getIntExtra(CaptureService.TRAFFIC_STATS_UPDATE_RCVD_PKTS, 0);

        mBytesSent.setText(Utils.formatBytes(bytes_sent));
        mBytesRcvd.setText(Utils.formatBytes(bytes_rcvd));
        mPacketsSent.setText(Utils.formatPkts(pkts_sent));
        mPacketsRcvd.setText(Utils.formatPkts(pkts_rcvd));
    }
}