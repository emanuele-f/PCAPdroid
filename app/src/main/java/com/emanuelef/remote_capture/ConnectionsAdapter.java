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

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

public class ConnectionsAdapter extends BaseAdapter {
    private static final String TAG = "ConnectionsAdapter";
    private static final int MIN_CONNECTION_DISPLAY_SECONDS = 10;
    private MainActivity mActivity;
    private ArrayList<ConnDescriptor> mItems;
    private Drawable mUnknownIcon;

    ConnectionsAdapter(MainActivity context) {
        mActivity = context;
        mItems = new ArrayList<>();
        mUnknownIcon = mActivity.getResources().getDrawable(android.R.drawable.ic_menu_help);
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        ConnDescriptor conn = getItem(position);

        if(convertView == null) {
            LayoutInflater inflater = (LayoutInflater) mActivity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            assert inflater != null;
            convertView = inflater.inflate(R.layout.connection_item, parent, false);
        }

        assert conn != null;
        ImageView icon = convertView.findViewById(R.id.icon);
        TextView remote = convertView.findViewById(R.id.remote);
        TextView l7proto = convertView.findViewById(R.id.l7proto);
        TextView traffic = convertView.findViewById(R.id.traffic);
        AppDescriptor app = mActivity.findAppByUid(conn.uid);
        Drawable appIcon;

        appIcon = (app != null) ? Objects.requireNonNull(app.getIcon().getConstantState()).newDrawable() : mUnknownIcon;
        icon.setImageDrawable(appIcon);

        if(conn.info.length() > 0)
            remote.setText(conn.info);
        else
            remote.setText(String.format(mActivity.getResources().getString(R.string.ip_and_port),
                    conn.dst_ip, conn.dst_port));

        l7proto.setText(conn.l7proto);
        traffic.setText(Utils.formatBytes(conn.sent_bytes + conn.rcvd_bytes));

        return(convertView);
    }

    @Override
    public int getCount() {
        return mItems.size();
    }

    @Override
    public long getItemId(int i) {
        return i;
    }

    @Override
    public ConnDescriptor getItem(int pos) {
        /* Prevent indexOutOfBounds exception in updateView() */
        if((pos < 0) || (pos >= getCount()))
            return null;

        return mItems.get(pos);
    }

    void updateConnections(ConnDescriptor[] connections) {
        long now = Utils.now();

        /* Sort connections by ascending ID */
        Arrays.sort(connections, new Comparator<ConnDescriptor>() {
            @Override
            public int compare(ConnDescriptor connDescriptor, ConnDescriptor t1) {
                return Integer.compare(connDescriptor.incr_id, t1.incr_id);
            }
        });

        int adapter_pos = 0;

        for (ConnDescriptor eval_conn : connections) {
            ConnDescriptor adapter_conn = getItem(adapter_pos);

            /* Check the closed connections */
            while ((adapter_conn != null) && (eval_conn.incr_id > adapter_conn.incr_id)) {
                if((now - adapter_conn.first_seen) >= MIN_CONNECTION_DISPLAY_SECONDS)
                    mItems.remove(adapter_pos);
                else
                    /* Too early, let the connection displayed for a while */
                    adapter_pos++;
                adapter_conn = getItem(adapter_pos);
            }

            if (adapter_conn == null)
                /* New untracked connection */
                mItems.add(eval_conn);
            else {
                /* Existing connection */
                if (eval_conn.incr_id == adapter_conn.incr_id) {
                    /* Update data */
                    mItems.set(adapter_pos, eval_conn);
                } else {
                    /* This should never happen as it would mean that a connection ID has been
                     * reused. */
                    Log.w(TAG, "Logic error? Missing item #" + eval_conn.incr_id +
                            " (adapter item: #" + adapter_conn.incr_id + ")");

                    /* Try to recover */
                    clear();
                }
            }

            adapter_pos++;
        }

        notifyDataSetChanged();
    }

    void clear() {
        mItems.clear();
    }
}
