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
import androidx.core.view.MenuProvider;

import android.app.ActivityManager;
import android.content.Context;
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
import com.emanuelef.remote_capture.model.CaptureStats;

import java.util.Locale;

public class StatsActivity extends BaseActivity implements MenuProvider {
    private TextView mBytesSent;
    private TextView mBytesRcvd;
    private TextView mIPv6BytesSent;
    private TextView mIPv6BytesRcvd;
    private TextView mIPv6BytesPercentage;
    private TextView mPacketsSent;
    private TextView mPacketsRcvd;
    private TextView mActiveConns;
    private TextView mDroppedConns;
    private TextView mDroppedPkts;
    private TextView mTotConns;
    private TextView mHeapUsage;
    private TextView mMemUsage;
    private TextView mLowMem;
    private TextView mOpenSocks;
    private TextView mDnsServer;
    private TextView mDnsQueries;
    private TableLayout mTable;
    private TextView mAllocStats;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.stats);
        displayBackAction();
        setContentView(R.layout.activity_stats);
        addMenuProvider(this);

        mTable = findViewById(R.id.table);
        mBytesSent = findViewById(R.id.bytes_sent);
        mBytesRcvd = findViewById(R.id.bytes_rcvd);
        mIPv6BytesSent = findViewById(R.id.ipv6_bytes_sent);
        mIPv6BytesRcvd = findViewById(R.id.ipv6_bytes_rcvd);
        mIPv6BytesPercentage = findViewById(R.id.ipv6_bytes_percentage);
        mPacketsSent = findViewById(R.id.packets_sent);
        mPacketsRcvd = findViewById(R.id.packets_rcvd);
        mActiveConns = findViewById(R.id.active_connections);
        mDroppedConns = findViewById(R.id.dropped_connections);
        mDroppedPkts = findViewById(R.id.pkts_dropped);
        mTotConns = findViewById(R.id.tot_connections);
        mHeapUsage = findViewById(R.id.heap_usage);
        mMemUsage = findViewById(R.id.mem_usage);
        mLowMem = findViewById(R.id.low_mem_detected);
        mOpenSocks = findViewById(R.id.open_sockets);
        mDnsQueries = findViewById(R.id.dns_queries);
        mDnsServer = findViewById(R.id.dns_server);
        mAllocStats = findViewById(R.id.alloc_stats);

        if(CaptureService.isCapturingAsRoot()) {
            findViewById(R.id.open_sockets_row).setVisibility(View.GONE);
            findViewById(R.id.row_dropped_connections).setVisibility(View.GONE);
        } else {
            if (!CaptureService.isReadingFromPcapFile() && !CaptureService.isIPv6Enabled()) {
                // vpn mode without IPv6
                findViewById(R.id.ipv6_bytes_sent_row).setVisibility(View.GONE);
                findViewById(R.id.ipv6_bytes_rcvd_row).setVisibility(View.GONE);
                findViewById(R.id.ipv6_bytes_percentage_row).setVisibility(View.GONE);
            }

            findViewById(R.id.row_pkts_dropped).setVisibility(View.GONE);
        }

        // Listen for stats updates
        CaptureService.observeStats(this, this::updateStats);

        CaptureService.askStatsDump();
    }

    private void updateStats(CaptureStats stats) {
        mBytesSent.setText(Utils.formatBytes(stats.bytes_sent));
        mBytesRcvd.setText(Utils.formatBytes(stats.bytes_rcvd));

        mIPv6BytesSent.setText(Utils.formatBytes(stats.ipv6_bytes_sent));
        mIPv6BytesRcvd.setText(Utils.formatBytes(stats.ipv6_bytes_rcvd));

        long tot_bytes = stats.bytes_sent + stats.bytes_rcvd;
        long percentage = (tot_bytes > 0) ?
                ((stats.ipv6_bytes_sent + stats.ipv6_bytes_rcvd) * 100 / tot_bytes) : 0;
        mIPv6BytesPercentage.setText(
                String.format(Utils.getPrimaryLocale(this),"%d%%", percentage));

        mPacketsSent.setText(Utils.formatIntShort(stats.pkts_sent));
        mPacketsRcvd.setText(Utils.formatIntShort(stats.pkts_rcvd));
        mActiveConns.setText(Utils.formatNumber(this, stats.active_conns));
        mDroppedConns.setText(Utils.formatNumber(this, stats.num_dropped_conns));
        mDroppedPkts.setText(Utils.formatNumber(this, stats.pkts_dropped));
        mTotConns.setText(Utils.formatNumber(this, stats.tot_conns));
        mOpenSocks.setText(Utils.formatNumber(this, stats.num_open_sockets));
        mDnsQueries.setText(Utils.formatNumber(this, stats.num_dns_queries));

        updateMemoryStats();

        if(!CaptureService.isDNSEncrypted()) {
            findViewById(R.id.dns_server_row).setVisibility(View.VISIBLE);
            findViewById(R.id.dns_queries_row).setVisibility(View.VISIBLE);
            mDnsServer.setText(CaptureService.getDNSServer());
        } else {
            findViewById(R.id.dns_server_row).setVisibility(View.GONE);
            findViewById(R.id.dns_queries_row).setVisibility(View.GONE);
        }

        if(stats.num_dropped_conns > 0)
            mDroppedConns.setTextColor(Color.RED);

        if(stats.alloc_summary != null) {
            mAllocStats.setVisibility(View.VISIBLE);
            mAllocStats.setText(stats.alloc_summary);
        }
    }

    private void updateMemoryStats() {
        Locale locale = Utils.getPrimaryLocale(this);
        long heapAvailable = Utils.getAvailableHeap();
        long heapMax = Runtime.getRuntime().maxMemory();

        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        mHeapUsage.setText(String.format(locale, "%s / %s (%d%%)", Utils.formatBytes(heapMax - heapAvailable),
                Utils.formatBytes(heapMax),
                (heapMax - heapAvailable) * 100 / heapMax));

        mMemUsage.setText(String.format(locale, "%s / %s (%d%%)", Utils.formatBytes(memoryInfo.totalMem - memoryInfo.availMem),
                Utils.formatBytes(memoryInfo.totalMem),
                (memoryInfo.totalMem - memoryInfo.availMem) * 100 / memoryInfo.totalMem));

        mLowMem.setText(getString(CaptureService.isLowMemory() ? R.string.yes : R.string.no));
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.copy_share_menu, menu);
    }

    private String getContents() {
        return Utils.table2Text(mTable);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.copy_to_clipboard) {
            Utils.copyToClipboard(this, getContents());
            return true;
        } else if(id == R.id.share) {
            Utils.shareText(this, getString(R.string.stats), getContents());
            return true;
        }

        return false;
    }
}