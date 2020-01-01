package com.emanuelef.remote_capture;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

public class ConnectionDetails extends AppCompatActivity {
    static final String CONN_EXTRA_KEY = "conn_descriptor";
    static final String APP_NAME_EXTRA_KEY = "app_name";
    TextView mBytesView;
    TextView mPacketsView;
    TextView mDurationView;
    ConnDescriptor conn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connection_details);

        conn = (ConnDescriptor) getIntent().getSerializableExtra(CONN_EXTRA_KEY);
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
            app.setText(Integer.toString(conn.uid));

        if(!conn.url.isEmpty())
            url.setText(conn.url);
        else
            url_row.setVisibility(View.GONE);

        LocalBroadcastManager bcast_man = LocalBroadcastManager.getInstance(this);

        /* Register for connections update */
        bcast_man.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateConnectionStats(intent);
            }
        }, new IntentFilter(CaptureService.ACTION_CONNECTIONS_DUMP));
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

    private void updateConnectionStats(Intent intent) {
        ConnDescriptor connections[] = (ConnDescriptor[]) intent.getSerializableExtra("value");

        for (ConnDescriptor eval_conn : connections) {
            if(eval_conn.incr_id == conn.incr_id) {
                /* Connection found, update stats */
                conn = eval_conn;
                updateStats();
            }
        }
    }

    private void updateStats() {
        if(conn != null) {
            mBytesView.setText(String.format(getResources().getString(R.string.up_and_down), Utils.formatBytes(conn.rcvd_bytes), Utils.formatBytes(conn.sent_bytes)));
            mPacketsView.setText(String.format(getResources().getString(R.string.up_and_down), Utils.formatPkts(conn.rcvd_pkts), Utils.formatPkts(conn.sent_pkts)));
            mDurationView.setText(Utils.formatDuration(conn.last_seen - conn.first_seen));
        }
    }
}
