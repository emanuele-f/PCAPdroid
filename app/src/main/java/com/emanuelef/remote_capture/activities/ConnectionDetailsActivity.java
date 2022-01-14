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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TextView;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.haipq.android.flagkit.FlagImageView;

public class ConnectionDetailsActivity extends BaseActivity implements ConnectionsListener {
    private static final String TAG = "ConnectionDetails";
    public static final String CONN_EXTRA_KEY = "conn_descriptor";
    public static final String APP_NAME_EXTRA_KEY = "app_name";
    private TableLayout mTable;
    private TextView mBytesView;
    private TextView mPacketsView;
    private TextView mDurationView;
    private TextView mRequestData;
    private TextView mBlockedPkts;
    private View mBlockedPktsRow;
    private ConnectionDescriptor mConn;
    private TextView mStatus;
    private TextView mFirstSeen;
    private TextView mLastSeen;
    private TextView mTcpFlags;
    private ImageView mBlacklistedIp;
    private ImageView mBlacklistedHost;
    private Handler mHandler;
    private int mConnPos;
    private boolean mListenerSet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.connection_details);
        displayBackAction();
        setContentView(R.layout.activity_connection_details);

        mConn = (ConnectionDescriptor) getIntent().getSerializableExtra(CONN_EXTRA_KEY);
        mHandler = new Handler(Looper.getMainLooper());
        mConnPos = -1;
        String app_name = getIntent().getStringExtra(APP_NAME_EXTRA_KEY);

        TextView app = findViewById(R.id.detail_app);
        TextView proto = findViewById(R.id.detail_protocol);
        TextView info_label = findViewById(R.id.detail_info_label);
        TextView info = findViewById(R.id.detail_info);
        TextView url = findViewById(R.id.detail_url);
        View url_row = findViewById(R.id.detail_url_row);
        View info_row = findViewById(R.id.detail_info_row);
        TextView source = findViewById(R.id.detail_source);
        mRequestData = findViewById(R.id.request_data);
        TextView request_data_lbl = findViewById(R.id.request_data_label);
        TextView destination = findViewById(R.id.detail_destination);
        TextView country = findViewById(R.id.country_name);
        FlagImageView country_flag = findViewById(R.id.country_flag);
        TextView asn = findViewById(R.id.asn);
        mTable = findViewById(R.id.table);
        mBytesView = findViewById(R.id.detail_bytes);
        mPacketsView = findViewById(R.id.detail_packets);
        mBlockedPkts = findViewById(R.id.blocked_pkts);
        mBlockedPktsRow = findViewById(R.id.blocked_row);
        mDurationView = findViewById(R.id.detail_duration);
        mStatus = findViewById(R.id.detail_status);
        mFirstSeen = findViewById(R.id.first_seen);
        mLastSeen = findViewById(R.id.last_seen);
        mTcpFlags = findViewById(R.id.tcp_flags);
        mBlacklistedIp = findViewById(R.id.blacklisted_ip);
        mBlacklistedHost = findViewById(R.id.blacklisted_host);

        findViewById(R.id.whois_ip).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://search.arin.net/rdap/?query=" + mConn.dst_ip));
            Utils.startActivity(this, intent);
        });

        String l4proto = Utils.proto2str(mConn.ipproto);

        //if(l4proto.equals("TCP"))
        //    findViewById(R.id.tcp_flags_row).setVisibility(View.VISIBLE);

        if(mConn != null) {
            if(!mConn.l7proto.equals(l4proto))
                proto.setText(String.format(getResources().getString(R.string.app_and_proto), mConn.l7proto, l4proto));
            else
                proto.setText(mConn.l7proto);

            if(l4proto.equals("ICMP")) {
                source.setText(mConn.src_ip);
                destination.setText(mConn.dst_ip);
            } else {
                source.setText(String.format(getResources().getString(R.string.ip_and_port), mConn.src_ip, mConn.src_port));
                destination.setText(String.format(getResources().getString(R.string.ip_and_port), mConn.dst_ip, mConn.dst_port));
            }

            if((mConn.info != null) && (!mConn.info.isEmpty())) {
                if(mConn.l7proto.equals("DNS"))
                    info_label.setText(R.string.query);
                else if(mConn.l7proto.equals("HTTP"))
                    info_label.setText(R.string.host);
                info.setText(mConn.info);
            } else
                info_row.setVisibility(View.GONE);

            if(app_name != null)
                app.setText(String.format(getResources().getString(R.string.app_and_proto), app_name, Integer.toString(mConn.uid)));
            else
                app.setText(Integer.toString(mConn.uid));

            if(!mConn.url.isEmpty())
                url.setText(mConn.url);
            else
                url_row.setVisibility(View.GONE);

            if(!mConn.request_plaintext.isEmpty())
                mRequestData.setText(mConn.request_plaintext);
            else {
                mRequestData.setVisibility(View.GONE);
                request_data_lbl.setVisibility(View.GONE);
            }

            if(!mConn.country.isEmpty()) {
                country.setText(Utils.getCountryName(this, mConn.country));
                country_flag.setCountryCode(mConn.country);
            } else
                findViewById(R.id.country_row).setVisibility(View.GONE);

            if(mConn.asn.isKnown())
                asn.setText(mConn.asn.toString());
            else
                findViewById(R.id.asn_row).setVisibility(View.GONE);

            if(mConn.ifidx > 0) {
                String ifname = CaptureService.getInterfaceName(mConn.ifidx);

                if(!ifname.isEmpty()) {
                    findViewById(R.id.interface_row).setVisibility(View.VISIBLE);
                    ((TextView) findViewById(R.id.capture_interface)).setText(ifname);
                }
            }

            updateStats(mConn);
        }

        if(Utils.isTv(this)) {
            mRequestData.setOnClickListener(v -> Utils.shareText(this, getString(R.string.request_plaintext), mRequestData.getText().toString()));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mConnPos = -1;

        // Closed connections won't be updated
        if(mConn.status < ConnectionDescriptor.CONN_STATUS_CLOSED)
            registerConnsListener();
    }

    @Override
    public void onPause() {
        super.onPause();

        unregisterConnsListener();
    }

    private void registerConnsListener() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if((reg != null) && !mListenerSet) {
            mConnPos = reg.getConnPositionById(mConn.incr_id);

            if(mConnPos != -1) {
                ConnectionDescriptor conn = reg.getConn(mConnPos);

                if(conn != null) {
                    if(conn.status < ConnectionDescriptor.CONN_STATUS_CLOSED) {
                        Log.d(TAG, "Adding connections listener");
                        reg.addListener(this);
                        mListenerSet = true;
                    }

                    updateStats(conn);
                }
            }
        }
    }

    private void unregisterConnsListener() {
        if(mListenerSet) {
            ConnectionsRegister reg = CaptureService.getConnsRegister();

            if(reg != null) {
                Log.d(TAG, "Removing connections listener");
                reg.removeListener(this);
            }

            mListenerSet = false;
        }

        mConnPos = -1;
    }

    private void updateStats(ConnectionDescriptor conn) {
        if(conn != null) {
            mBytesView.setText(String.format(getResources().getString(R.string.rcvd_and_sent), Utils.formatBytes(conn.rcvd_bytes), Utils.formatBytes(conn.sent_bytes)));
            mPacketsView.setText(String.format(getResources().getString(R.string.rcvd_and_sent), Utils.formatIntShort(conn.rcvd_pkts), Utils.formatIntShort(conn.sent_pkts)));
            if(conn.blocked_pkts > 0) {
                mBlockedPkts.setText(String.format(getResources().getString(R.string.n_pkts), Utils.formatIntShort(conn.blocked_pkts)));
                mBlockedPktsRow.setVisibility(View.VISIBLE);
            }
            mDurationView.setText(Utils.formatDuration((conn.last_seen - conn.first_seen) / 1000));
            mFirstSeen.setText(Utils.formatEpochMillis(this, conn.first_seen));
            mLastSeen.setText(Utils.formatEpochMillis(this, conn.last_seen));
            mStatus.setText(conn.getStatusLabel(this));
            mTcpFlags.setText(Utils.tcpFlagsToStr(conn.getRcvdTcpFlags()) + " <- " + Utils.tcpFlagsToStr(conn.getSentTcpFlags()));
            mBlacklistedIp.setVisibility(conn.isBlacklistedIp() ? View.VISIBLE : View.GONE);
            mBlacklistedHost.setVisibility(conn.isBlacklistedHost() ? View.VISIBLE : View.GONE);

            if(conn.status >= ConnectionDescriptor.CONN_STATUS_CLOSED)
                unregisterConnsListener();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.copy_share_menu, menu);

        return super.onCreateOptionsMenu(menu);
    }

    private String getContents() {
        if(mTable == null)
            return "";

        String contents = Utils.table2Text(mTable);

        if(mRequestData.getText().length() > 0)
            contents += "\n" + getString(R.string.request_plaintext) + ":\n" + mRequestData.getText();

        return contents;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.copy_to_clipboard) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.connection_details), getContents());
            clipboard.setPrimaryClip(clip);

            Utils.showToast(this, R.string.copied);
            return true;
        } else if(id == R.id.share) {
            Utils.shareText(this, getString(R.string.connection_details), getContents());
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void connectionsChanges(int num_connetions) {}

    @Override
    public void connectionsAdded(int start, ConnectionDescriptor []conns) {}

    @Override
    public void connectionsRemoved(int start, ConnectionDescriptor []conns) {}

    @Override
    public void connectionsUpdated(int[] positions) {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if((reg == null) || (mConnPos < 0))
            return;

        for(int pos : positions) {
            if(pos == mConnPos) {
                ConnectionDescriptor conn = reg.getConn(pos);

                // Double check the incr_id
                if((conn != null) && (conn.incr_id == mConn.incr_id))
                    mHandler.post(() -> updateStats(conn));
                else
                    unregisterConnsListener();

                break;
            }
        }
    }
}
