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
import android.os.Handler;
import android.telecom.ConnectionRequest;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;

public class ConnectionsAdapter extends BaseAdapter {
    private static final String TAG = "ConnectionsAdapter";
    private final MainActivity mActivity;
    private final Drawable mUnknownIcon;

    ConnectionsAdapter(MainActivity context) {
        mActivity = context;
        mUnknownIcon = ContextCompat.getDrawable(mActivity, android.R.drawable.ic_menu_help);
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
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        return (reg != null) ? reg.getConnCount() : 0;
    }

    @Override
    public long getItemId(int pos) {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if((pos < 0) || (pos >= getCount()) || (reg == null))
            return -1;

        ConnDescriptor conn = reg.getConn(pos);

        return ((conn != null) ? conn.incr_id : -1);
    }

    @Override
    public ConnDescriptor getItem(int pos) {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        /* Prevent indexOutOfBounds exception in updateView() */
        if((pos < 0) || (pos >= getCount()) || (reg == null))
            return null;

        return reg.getConn(pos);
    }
}
