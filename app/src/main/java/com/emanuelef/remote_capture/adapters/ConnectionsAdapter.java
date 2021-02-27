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

package com.emanuelef.remote_capture.adapters;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;

import java.util.Map;
import java.util.Objects;

public class ConnectionsAdapter extends RecyclerView.Adapter<ConnectionsAdapter.ViewHolder> {
    private static final String TAG = "ConnectionsAdapter";
    private final LayoutInflater mLayoutInflater;
    private final Drawable mUnknownIcon;
    private int mItemCount;
    private View.OnClickListener mListener;
    private Map<Integer, AppDescriptor> mApps;
    private final Context mContext;
    int mUidFilter;

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        ImageView statusInd;
        TextView remote;
        TextView l7proto;
        TextView traffic;

        ViewHolder(View itemView) {
            super(itemView);

            icon = itemView.findViewById(R.id.icon);
            remote = itemView.findViewById(R.id.remote);
            l7proto = itemView.findViewById(R.id.l7proto);
            traffic = itemView.findViewById(R.id.traffic);
            statusInd = itemView.findViewById(R.id.status_ind);
        }

        public void bindConn(Context context, ConnectionDescriptor conn, Map<Integer, AppDescriptor> apps, Drawable unknownIcon) {
            AppDescriptor app = (apps != null) ? apps.get(conn.uid) : null;
            Drawable appIcon;

            appIcon = ((app != null) && (app.getIcon() != null)) ? Objects.requireNonNull(app.getIcon().getConstantState()).newDrawable() : unknownIcon;
            icon.setImageDrawable(appIcon);

            if(conn.info.length() > 0)
                remote.setText(conn.info);
            else
                remote.setText(String.format(context.getResources().getString(R.string.ip_and_port),
                        conn.dst_ip, conn.dst_port));

            l7proto.setText(conn.l7proto);
            traffic.setText(Utils.formatBytes(conn.sent_bytes + conn.rcvd_bytes));

            if(conn.closed)
                statusInd.setVisibility(View.INVISIBLE);
            else
                statusInd.setVisibility(View.VISIBLE);
        }
    }

    public ConnectionsAdapter(Context context) {
        mContext = context;
        mLayoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mUnknownIcon = ContextCompat.getDrawable(context, android.R.drawable.ic_menu_help);
        mListener = null;
        mItemCount = 0;
        mUidFilter = -1;
    }

    public void setItemCount(int count) {
        mItemCount = count;
    }

    @Override
    public int getItemCount() {
        return mItemCount;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.connection_item, parent, false);

        if(mListener != null)
            view.setOnClickListener(mListener);

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ConnectionDescriptor conn = getItem(position);

        if(conn == null)
            return;

        holder.bindConn(mContext, conn, mApps, mUnknownIcon);
    }

    @Override
    public long getItemId(int pos) {
        ConnectionDescriptor conn = getItem(pos);

        return ((conn != null) ? conn.incr_id : -1);
    }

    public ConnectionDescriptor getItem(int pos) {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if((pos < 0) || (pos >= getItemCount()) || (reg == null))
            return null;

        if(mUidFilter == -1)
            return reg.getConn(pos);
        else
            return reg.getUidConn(mUidFilter, pos);
    }

    public void setClickListener(View.OnClickListener listener) {
        mListener = listener;
    }

    public void setUidFilter(int uid) {
        mUidFilter = uid;
    }

    public int getUidFilter() {
        return mUidFilter;
    }

    public void setApps(Map<Integer, AppDescriptor> apps) {
        mApps = apps;
    }
}
