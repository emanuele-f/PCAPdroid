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

package com.emanuelef.remote_capture.activities;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;

public class ConnectionDetailsActivity extends AppCompatActivity {
    public static final String CONN_EXTRA_KEY = "conn_descriptor";
    public static final String APP_NAME_EXTRA_KEY = "app_name";
    private TextView mBytesView;
    private TextView mPacketsView;
    private TextView mDurationView;
    private ConnectionDescriptor conn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connection_details);

        conn = (ConnectionDescriptor) getIntent().getSerializableExtra(CONN_EXTRA_KEY);
        String app_name = getIntent().getStringExtra(APP_NAME_EXTRA_KEY);

        TextView app = findViewById(R.id.detail_app);
        TextView proto = findViewById(R.id.detail_protocol);
        TextView info_label = findViewById(R.id.detail_info_label);
        TextView info = findViewById(R.id.detail_info);
        TextView url = findViewById(R.id.detail_url);
        View url_row = findViewById(R.id.detail_url_row);
        View info_row = findViewById(R.id.detail_info_row);
        TextView source = findViewById(R.id.detail_source);
        TextView destination = findViewById(R.id.detail_destination);
        mBytesView = findViewById(R.id.detail_bytes);
        mPacketsView = findViewById(R.id.detail_packets);
        mDurationView = findViewById(R.id.detail_duration);

        if(conn != null) {
            proto.setText(String.format(getResources().getString(R.string.app_and_proto), conn.l7proto, Utils.proto2str(conn.ipproto)));
            source.setText(String.format(getResources().getString(R.string.ip_and_port), conn.src_ip, conn.src_port));
            destination.setText(String.format(getResources().getString(R.string.ip_and_port), conn.dst_ip, conn.dst_port));

            if(conn.info != null) {
                if(conn.l7proto.equals("DNS"))
                    info_label.setText(R.string.query);
                else if(conn.l7proto.equals("HTTP"))
                    info_label.setText(R.string.host);
                info.setText(conn.info);
            } else
                info_row.setVisibility(View.GONE);

            updateStats();
        }

        if(app_name != null)
            app.setText(String.format(getResources().getString(R.string.app_and_proto), app_name, Integer.toString(conn.uid)));
        else
            app.setText( Integer.toString(conn.uid));

        if(!conn.url.isEmpty())
            url.setText(conn.url);
        else
            url_row.setVisibility(View.GONE);

        // TODO update the details if the connection is updated
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if(item.getItemId() == android.R.id.home) {
            /* Make the back button in the action bar behave like the back button */
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateStats() {
        if(conn != null) {
            mBytesView.setText(String.format(getResources().getString(R.string.up_and_down), Utils.formatBytes(conn.rcvd_bytes), Utils.formatBytes(conn.sent_bytes)));
            mPacketsView.setText(String.format(getResources().getString(R.string.up_and_down), Utils.formatPkts(conn.rcvd_pkts), Utils.formatPkts(conn.sent_pkts)));
            mDurationView.setText(Utils.formatDuration(conn.last_seen - conn.first_seen));
        }
    }
}
