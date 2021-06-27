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

package com.emanuelef.remote_capture.activities;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TextView;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.VPNStats;

public class StatsActivity extends BaseActivity {
    private BroadcastReceiver mReceiver;
    private TextView mBytesSent;
    private TextView mBytesRcvd;
    private TextView mPacketsSent;
    private TextView mPacketsRcvd;
    private TextView mActiveConns;
    private TextView mDroppedConns;
    private TextView mDroppedPkts;
    private TextView mTotConns;
    private TextView mMaxFd;
    private TextView mOpenSocks;
    private TextView mDnsServer;
    private TextView mDnsQueries;
    private TableLayout mTable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.stats);
        setContentView(R.layout.activity_stats);

        mTable = findViewById(R.id.table);
        mBytesSent = findViewById(R.id.bytes_sent);
        mBytesRcvd = findViewById(R.id.bytes_rcvd);
        mPacketsSent = findViewById(R.id.packets_sent);
        mPacketsRcvd = findViewById(R.id.packets_rcvd);
        mActiveConns = findViewById(R.id.active_connections);
        mDroppedConns = findViewById(R.id.dropped_connections);
        mDroppedPkts = findViewById(R.id.pkts_dropped);
        mTotConns = findViewById(R.id.tot_connections);
        mMaxFd = findViewById(R.id.max_fd);
        mOpenSocks = findViewById(R.id.open_sockets);
        mDnsQueries = findViewById(R.id.dns_queries);
        mDnsServer = findViewById(R.id.dns_server);

        if(CaptureService.isCapturingAsRoot()) {
            findViewById(R.id.dns_server_row).setVisibility(View.GONE);
            findViewById(R.id.dns_queries_row).setVisibility(View.GONE);
            findViewById(R.id.open_sockets_row).setVisibility(View.GONE);
            findViewById(R.id.max_fd_row).setVisibility(View.GONE);
            findViewById(R.id.row_dropped_connections).setVisibility(View.GONE);
        } else
            findViewById(R.id.row_pkts_dropped).setVisibility(View.GONE);

        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateVPNStats(intent);
            }
        };

        /* Register for updates */
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(mReceiver, new IntentFilter(CaptureService.ACTION_STATS_DUMP));

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        CaptureService.askStatsDump();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if(mReceiver != null)
            LocalBroadcastManager.getInstance(this)
                    .unregisterReceiver(mReceiver);
    }

    private void updateVPNStats(Intent intent) {
        VPNStats stats = (VPNStats) intent.getSerializableExtra("value");

        mBytesSent.setText(Utils.formatBytes(stats.bytes_sent));
        mBytesRcvd.setText(Utils.formatBytes(stats.bytes_rcvd));
        mPacketsSent.setText(Utils.formatPkts(stats.pkts_sent));
        mPacketsRcvd.setText(Utils.formatPkts(stats.pkts_rcvd));
        mActiveConns.setText(Utils.formatNumber(this, stats.active_conns));
        mDroppedConns.setText(Utils.formatNumber(this, stats.num_dropped_conns));
        mDroppedPkts.setText(Utils.formatNumber(this, stats.pkts_dropped));
        mTotConns.setText(Utils.formatNumber(this, stats.tot_conns));
        mMaxFd.setText(Utils.formatNumber(this, stats.max_fd));
        mOpenSocks.setText(Utils.formatNumber(this, stats.num_open_sockets));
        mDnsQueries.setText(Utils.formatNumber(this, stats.num_dns_queries));
        mDnsServer.setText(CaptureService.getDNSServer());

        if(stats.num_dropped_conns > 0)
            mDroppedConns.setTextColor(Color.RED);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.copy_share_menu, menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.copy_to_clipboard) {
            String contents = Utils.table2Text(mTable);
            Utils.copyToClipboard(this, contents);
            return true;
        } else if(id == R.id.share) {
            String contents = Utils.table2Text(mTable);

            Intent intent = new Intent(android.content.Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.stats));
            intent.putExtra(android.content.Intent.EXTRA_TEXT, contents);

            startActivity(Intent.createChooser(intent, getResources().getString(R.string.share)));

            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}