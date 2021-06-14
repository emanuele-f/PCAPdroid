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

package com.emanuelef.remote_capture.adapters;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.Whitelist;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;

public class ConnectionsAdapter extends RecyclerView.Adapter<ConnectionsAdapter.ViewHolder>
        implements ConnectionsListener {
    private static final String TAG = "ConnectionsAdapter";
    private final LayoutInflater mLayoutInflater;
    private final Drawable mUnknownIcon;
    private int mUnfilteredItemsCount;
    private View.OnClickListener mListener;
    private final AppsResolver mApps;
    private final Context mContext;
    private int mClickedPosition;
    private int mUidFilter;
    private int mNumRemovedItems;
    public boolean mWhitelistEnabled;
    private final HashMap<Integer, Integer> mIdToFilteredPos;
    private ArrayList<ConnectionDescriptor> mFilteredConn;
    public final Whitelist mWhitelist;

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView statusInd;
        TextView remote;
        TextView l7proto;
        TextView traffic;
        TextView appName;
        TextView lastSeen;
        final String mProtoAndPort;

        ViewHolder(View itemView) {
            super(itemView);

            icon = itemView.findViewById(R.id.icon);
            remote = itemView.findViewById(R.id.remote);
            l7proto = itemView.findViewById(R.id.l7proto);
            traffic = itemView.findViewById(R.id.traffic);
            statusInd = itemView.findViewById(R.id.status_ind);
            appName = itemView.findViewById(R.id.app_name);
            lastSeen = itemView.findViewById(R.id.last_seen);

            Context context = itemView.getContext();
            mProtoAndPort = context.getString(R.string.proto_and_port);
        }

        public void bindConn(Context context, ConnectionDescriptor conn, AppsResolver apps, Drawable unknownIcon) {
            AppDescriptor app = apps.get(conn.uid);
            Drawable appIcon;
            String l7Text;

            appIcon = ((app != null) && (app.getIcon() != null)) ? Objects.requireNonNull(app.getIcon().getConstantState()).newDrawable() : unknownIcon;
            icon.setImageDrawable(appIcon);

            if(conn.info.length() > 0)
                remote.setText(conn.info);
            else
                remote.setText(conn.dst_ip);

            if(conn.dst_port != 0)
                l7Text = String.format(mProtoAndPort, conn.l7proto, conn.dst_port);
            else
                l7Text = conn.l7proto;

            if(conn.ipver == 6)
                l7Text = l7Text + ", IPv6";

            l7proto.setText(l7Text);

            String info_txt = (app != null) ? app.getName() : Integer.toString(conn.uid);
            appName.setText(info_txt);
            traffic.setText(Utils.formatBytes(conn.sent_bytes + conn.rcvd_bytes));
            lastSeen.setText(Utils.formatEpochShort(context, conn.last_seen));
            statusInd.setText(conn.getStatusLabel(context));

            if(conn.status < ConnectionDescriptor.CONN_STATUS_CLOSED)
                statusInd.setTextColor(0xFF28BC36); // Open
            else if((conn.status == ConnectionDescriptor.CONN_STATUS_CLOSED)
                    || (conn.status == ConnectionDescriptor.CONN_STATUS_RESET))
                statusInd.setTextColor(0xFFAAAAAA);
            else
                statusInd.setTextColor(0xFFF20015); // Error
        }
    }

    public ConnectionsAdapter(Context context, AppsResolver resolver) {
        mContext = context;
        mApps = resolver;
        mLayoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mUnknownIcon = ContextCompat.getDrawable(context, R.drawable.ic_image);
        mListener = null;
        mFilteredConn = null;
        mUnfilteredItemsCount = 0;
        mNumRemovedItems = 0;
        mIdToFilteredPos = new HashMap<>();
        mUidFilter = Utils.UID_NO_FILTER;
        mWhitelistEnabled = true;
        mWhitelist = new Whitelist(context);
        setHasStableIds(true);

        mWhitelist.reload();
    }

    @Override
    public int getItemCount() {
        return((mFilteredConn != null) ? mFilteredConn.size() : mUnfilteredItemsCount);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.connection_item, parent, false);

        // Enable the ability to show the context menu
        view.setLongClickable(true);

        if(mListener != null)
            view.setOnClickListener(mListener);

        ViewHolder holder = new ViewHolder(view);

        view.setOnLongClickListener(v -> {
            mClickedPosition = holder.getAbsoluteAdapterPosition();
            return false;
        });

        return holder;
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

        return ((conn != null) ? conn.incr_id : Utils.UID_UNKNOWN);
    }

    private boolean matches(ConnectionDescriptor conn) {
        return((conn != null)
                && ((mUidFilter == Utils.UID_NO_FILTER) || (conn.uid == mUidFilter))
                && (!mWhitelistEnabled || !mWhitelist.matches(conn)));
    }

    private int getFilteredItemPos(int incrId) {
        Integer pos = mIdToFilteredPos.get(incrId);

        if(pos == null)
            return -1;

        return(pos - mNumRemovedItems);
    }

    @Override
    public void connectionsChanges(int num_connetions) {
        mUnfilteredItemsCount = num_connetions;
        refreshFilteredConnections();
    }

    @Override
    public void connectionsAdded(int start, ConnectionDescriptor []conns) {
        mUnfilteredItemsCount += conns.length;

        if(mFilteredConn == null) {
            notifyItemRangeInserted(start, conns.length);
            return;
        }

        int numNew = 0;
        int vpos = mNumRemovedItems + mFilteredConn.size();

        // Assume that connections are only added at the end of the dataset
        for(ConnectionDescriptor conn : conns) {
            if(matches(conn)) {
                mIdToFilteredPos.put(conn.incr_id, vpos++);
                mFilteredConn.add(conn);
                numNew++;
            }
        }

        if(numNew > 0)
            notifyItemRangeInserted(mFilteredConn.size() - numNew, numNew);
    }

    @Override
    public void connectionsRemoved(int start, ConnectionDescriptor []conns) {
        mUnfilteredItemsCount -= conns.length;

        if(mFilteredConn == null) {
            notifyItemRangeRemoved(start, conns.length);
            return;
        }

        for(ConnectionDescriptor conn: conns) {
            if(conn == null)
                continue;

            int vpos = getFilteredItemPos(conn.incr_id);

            if(vpos != -1) {
                // Assume that connections are only remove from the start of the dataset
                mFilteredConn.remove(0);
                mIdToFilteredPos.remove(conn.incr_id);

                mNumRemovedItems++;
                notifyItemRemoved(vpos);
            }
        }
    }

    @Override
    public void connectionsUpdated(int[] positions) {
        if(mFilteredConn == null) {
            for(int pos : positions)
                notifyItemChanged(pos);
            return;
        }

        ConnectionsRegister reg = CaptureService.requireConnsRegister();

        for(int pos : positions) {
            ConnectionDescriptor conn = reg.getConn(pos);

            if(conn != null) {
                int vpos = getFilteredItemPos(conn.incr_id);

                if(vpos != -1) {
                    Log.d(TAG, "Changed item " + vpos + ", dataset size: " + getItemCount());
                    notifyItemChanged(pos);
                }
            }
        }
    }

    public void refreshFilteredConnections() {
        final ConnectionsRegister reg = CaptureService.getConnsRegister();

        if(reg == null)
            return;

        Log.d(TAG, "refreshFilteredConn (" + mUnfilteredItemsCount + ") unfiltered");
        mIdToFilteredPos.clear();
        mNumRemovedItems = 0;

        if((mUidFilter != Utils.UID_NO_FILTER) || hasWhitelistFilter()) {
            int vpos = 0;
            mFilteredConn = new ArrayList<>();

            for(int i=0; i<mUnfilteredItemsCount; i++) {
                ConnectionDescriptor conn = reg.getConn(i);

                if(matches(conn)) {
                    mFilteredConn.add(conn);
                    mIdToFilteredPos.put(conn.incr_id, vpos++);
                }
            }

            Log.d(TAG, "refreshFilteredConn: " + mFilteredConn.size() + " connections matched");
        } else
            mFilteredConn = null;

        notifyDataSetChanged();
    }

    public ConnectionDescriptor getItem(int pos) {
        if(mFilteredConn != null)
            return mFilteredConn.get(pos);

        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if((pos < 0) || (pos >= mUnfilteredItemsCount) || (reg == null))
            return null;

        return reg.getConn(pos);
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

    public ConnectionDescriptor getClickedItem() {
        return getItem(mClickedPosition);
    }

    public boolean hasWhitelistFilter() {
        return (mWhitelistEnabled && !mWhitelist.isEmpty());
    }

    public synchronized String dumpConnectionsCsv() {
        StringBuilder builder = new StringBuilder();
        AppsResolver resolver = new AppsResolver(mContext);

        // Header
        builder.append(mContext.getString(R.string.connections_csv_fields_v1));
        builder.append("\n");

        // Contents
        for(int i=0; i<getItemCount(); i++) {
            ConnectionDescriptor conn = getItem(i);

            if(conn != null) {
                AppDescriptor app = resolver.get(conn.uid);

                builder.append(conn.ipproto);                               builder.append(",");
                builder.append(conn.src_ip);                                builder.append(",");
                builder.append(conn.src_port);                              builder.append(",");
                builder.append(conn.dst_ip);                                builder.append(",");
                builder.append(conn.dst_port);                              builder.append(",");
                builder.append(conn.uid);                                   builder.append(",");
                builder.append((app != null) ? app.getName() : "");         builder.append(",");
                builder.append(conn.l7proto);                               builder.append(",");
                builder.append(conn.getStatusLabel(mContext));              builder.append(",");
                builder.append((conn.info != null) ? conn.info : "");       builder.append(",");
                builder.append(conn.sent_bytes);                            builder.append(",");
                builder.append(conn.rcvd_bytes);                            builder.append(",");
                builder.append(conn.sent_pkts);                             builder.append(",");
                builder.append(conn.rcvd_pkts);                             builder.append(",");
                builder.append(conn.first_seen);                            builder.append(",");
                builder.append(conn.last_seen);                             builder.append("\n");
            }
        }

        return builder.toString();
    }
}
