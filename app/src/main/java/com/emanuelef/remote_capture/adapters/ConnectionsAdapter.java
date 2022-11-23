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

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.FilterDescriptor;
import com.emanuelef.remote_capture.model.MatchList;
import com.emanuelef.remote_capture.model.Prefs;

import java.util.ArrayList;
import java.util.Arrays;

public class ConnectionsAdapter extends RecyclerView.Adapter<ConnectionsAdapter.ViewHolder>
        implements ConnectionsListener {
    private static final String TAG = "ConnectionsAdapter";
    private final LayoutInflater mLayoutInflater;
    private final Drawable mUnknownIcon;
    private int mUnfilteredItemsCount;
    private View.OnClickListener mListener;
    private final AppsResolver mAppsResolver;
    private final Context mContext;
    private ConnectionDescriptor mSelectedItem;
    private int mNumRemovedItems;

    // maps a connection ID to a position in mFilteredConn. Positions are shifted by mNumRemovedItems
    // to provide an always increasing position even when items are removed. The correct unshifted
    // position is returned by getFilteredItemPos. SparseIntArray is less efficient than an HashMap
    // on large collections, but takes much less memory.
    private final SparseIntArray mIdToFilteredPos;

    private ArrayList<ConnectionDescriptor> mFilteredConn;
    private String mSearch;
    public final MatchList mMask;
    public FilterDescriptor mFilter = new FilterDescriptor(); // must call refreshFilteredConnections to apply changes

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        ImageView blacklistedInd;
        ImageView blockedInd;
        ImageView decryptionInd;
        TextView statusInd;
        TextView remote;
        TextView l7proto;
        TextView traffic;
        TextView appName;
        TextView lastSeen;
        //FlagImageView countryFlag;
        final String mProtoAndPort;

        ViewHolder(View itemView) {
            super(itemView);

            icon = itemView.findViewById(R.id.icon);
            remote = itemView.findViewById(R.id.remote);
            l7proto = itemView.findViewById(R.id.l7proto);
            traffic = itemView.findViewById(R.id.traffic);
            statusInd = itemView.findViewById(R.id.status_ind);
            decryptionInd = itemView.findViewById(R.id.decryption_status);
            appName = itemView.findViewById(R.id.app_name);
            lastSeen = itemView.findViewById(R.id.last_seen);
            blacklistedInd = itemView.findViewById(R.id.blacklisted);
            blockedInd = itemView.findViewById(R.id.blocked);
            //countryFlag = itemView.findViewById(R.id.country_flag);

            Context context = itemView.getContext();
            mProtoAndPort = context.getString(R.string.proto_and_port);
        }

        @SuppressWarnings("deprecation")
        public void bindConn(Context context, ConnectionDescriptor conn, AppsResolver apps, Drawable unknownIcon) {
            AppDescriptor app = apps.getAppByUid(conn.uid, 0);
            Drawable appIcon;
            String l7Text;

            appIcon = ((app != null) && (app.getIcon() != null)) ? app.getIcon() : unknownIcon;
            icon.setImageDrawable(appIcon);

            if((conn.info != null) && (conn.info.length() > 0))
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
            lastSeen.setText(Utils.formatEpochShort(context, conn.last_seen / 1000));
            statusInd.setText(conn.getStatusLabel(context));

            int color;
            if(conn.status < ConnectionDescriptor.CONN_STATUS_CLOSED)
                color = R.color.statusOpen;
            else if((conn.status == ConnectionDescriptor.CONN_STATUS_CLOSED)
                    || (conn.status == ConnectionDescriptor.CONN_STATUS_RESET))
                color = R.color.statusClosed;
            else
                color = R.color.statusError;

            statusInd.setTextColor(ContextCompat.getColor(context, color));

            /*if(conn.country.isEmpty())
                countryFlag.setVisibility(View.GONE);
            else {
                countryFlag.setVisibility(View.VISIBLE);
                countryFlag.setCountryCode(conn.country);
            }*/

            blacklistedInd.setVisibility(conn.isBlacklisted() ? View.VISIBLE : View.GONE);
            blockedInd.setVisibility(conn.is_blocked ? View.VISIBLE : View.GONE);

            if(CaptureService.isDecryptingTLS()) {
                decryptionInd.setVisibility(View.VISIBLE);
                Utils.setDecryptionIcon(decryptionInd, conn);
            } else
                decryptionInd.setVisibility(View.GONE);
        }
    }

    public ConnectionsAdapter(Context context, AppsResolver resolver) {
        mContext = context;
        mAppsResolver = resolver;
        mLayoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mUnknownIcon = ContextCompat.getDrawable(context, R.drawable.ic_image);
        mListener = null;
        mFilteredConn = null;
        mUnfilteredItemsCount = 0;
        mNumRemovedItems = 0;
        mIdToFilteredPos = new SparseIntArray();
        mMask = PCAPdroid.getInstance().getVisualizationMask();
        mSearch = null;
        setHasStableIds(true);
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
            // see registerForContextMenu
            mSelectedItem = getItem(holder.getAbsoluteAdapterPosition());
            return false;
        });

        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ConnectionDescriptor conn = getItem(position);
        if(conn == null) {
            Log.w(TAG, "bad position: " + position);
            return;
        }

        holder.bindConn(mContext, conn, mAppsResolver, mUnknownIcon);
    }

    @Override
    public long getItemId(int pos) {
        ConnectionDescriptor conn = getItem(pos);

        return ((conn != null) ? conn.incr_id : Utils.UID_UNKNOWN);
    }

    private boolean matches(ConnectionDescriptor conn) {
        return((conn != null)
                && mFilter.matches(conn)
                && ((mSearch == null) || conn.matches(mAppsResolver, mSearch)));
    }

    // Given an incrId, return the position of the connection into the mFilteredConn array
    private int getFilteredItemPos(int incrId) {
        int pos = mIdToFilteredPos.get(incrId, -1);
        if(pos == -1)
            return -1;

        return(pos - mNumRemovedItems);
    }

    private void removeFilteredItemAt(int pos) {
        // get the previous item which was now removed
        ConnectionDescriptor item = getItem(pos);
        if(item == null)
            return;

        mFilteredConn.remove(pos);
        mIdToFilteredPos.delete(item.incr_id);
        notifyItemRemoved(pos);
    }

    /* Fixes the mappings in mIdToFilteredPos, starting from the provided position */
    private void fixFilteredPositions(int startPos) {
        for(int i=startPos; i<mFilteredConn.size(); i++)
            mIdToFilteredPos.put(mFilteredConn.get(i).incr_id, i + mNumRemovedItems);
    }

    @Override
    public void connectionsChanges(int num_connetions) {
        //Log.d(TAG, "connectionsChanges: " + num_connetions + " connections");
        mUnfilteredItemsCount = num_connetions;
        refreshFilteredConnections();
    }

    @Override
    public void connectionsAdded(int start, ConnectionDescriptor []conns) {
        //Log.d(TAG, "connectionsAdded: at " + start + ", " + conns.length + " connections");
        mUnfilteredItemsCount += conns.length;

        if(mFilteredConn == null) {
            notifyItemRangeInserted(start, conns.length);
            return;
        }

        // Here dealing with filtered connections
        int numNew = 0;
        int pos = mNumRemovedItems + mFilteredConn.size();

        // Assume that connections are only added at the end of the dataset
        for(ConnectionDescriptor conn : conns) {
            if(matches(conn)) {
                mIdToFilteredPos.put(conn.incr_id, pos++);
                mFilteredConn.add(conn);
                numNew++;
            }
        }

        if(numNew > 0)
            notifyItemRangeInserted(mFilteredConn.size() - numNew, numNew);
    }

    @Override
    public void connectionsRemoved(int start, ConnectionDescriptor []conns) {
        //Log.d(TAG, "connectionsRemoved: at " + start + ", " + conns.length + " connections");
        mUnfilteredItemsCount -= conns.length;

        if(mFilteredConn == null) {
            notifyItemRangeRemoved(start, conns.length);
            return;
        }

        // Here dealing with filtered connections
        for(ConnectionDescriptor conn: conns) {
            if(conn == null)
                continue;

            int pos = getFilteredItemPos(conn.incr_id);
            if(pos != -1) {
                // Assume that connections are only removed from the start of the dataset
                removeFilteredItemAt(0);

                // by incrementing mNumRemovedItems we can shift the position of subsequent items
                mNumRemovedItems++;
            }
        }
    }

    @Override
    public void connectionsUpdated(int[] positions) {
        //Log.d(TAG, "connectionsUpdated: " + positions.length + " connections");

        if(mFilteredConn == null) {
            for(int pos : positions)
                notifyItemChanged(pos);
            return;
        }

        // Here dealing with filtered connections
        ConnectionsRegister reg = CaptureService.requireConnsRegister();
        int first_removed_pos = -1;
        int num_just_removed = 0;

        // Sort order necessary to properly use num_just_removed
        Arrays.sort(positions);

        for(int reg_pos : positions) {
            ConnectionDescriptor conn = reg.getConn(reg_pos);
            if(conn != null) {
                // reg_pos is the position in the ConnectionsRegister, whereas pos is the position
                // in mFilteredConn
                int pos = getFilteredItemPos(conn.incr_id);
                if(pos != -1) {
                    // Need to shift by num_just_removed due to the removeFilteredItemAt below until
                    // fixFilteredPositions is called
                    pos -= num_just_removed;

                    if(matches(conn)) {
                        Log.d(TAG, "Changed item " + pos + ", dataset size: " + getItemCount());
                        notifyItemChanged(pos);
                    } else {
                        Log.d(TAG, "Unmatch item " + pos + ": " + conn.toString());

                        // A previously matching connection may not match anymore. This happens, for
                        // example, when its info or protocol is updated. In this case, the connection
                        // must be removed.
                        removeFilteredItemAt(pos);
                        num_just_removed++;

                        if(first_removed_pos == -1)
                            first_removed_pos = pos;
                    }
                }
            }
        }

        // Need to recalculate the mappings starting at the first removed item
        if(first_removed_pos != -1)
            fixFilteredPositions(first_removed_pos);
    }

    @SuppressLint("NotifyDataSetChanged")
    public void refreshFilteredConnections() {
        final ConnectionsRegister reg = CaptureService.getConnsRegister();
        if(reg == null)
            return;

        Log.d(TAG, "refreshFilteredConn (" + mUnfilteredItemsCount + ") unfiltered");
        mIdToFilteredPos.clear();
        mNumRemovedItems = 0;

        if(hasFilter()) {
            int pos = 0;
            mFilteredConn = new ArrayList<>();

            // Synchronize to improve performance of getConn
            synchronized(reg) {
                for(int i = 0; i < mUnfilteredItemsCount; i++) {
                    ConnectionDescriptor conn = reg.getConn(i);

                    if(matches(conn)) {
                        mFilteredConn.add(conn);
                        mIdToFilteredPos.put(conn.incr_id, pos++);
                    }
                }
            }

            Log.d(TAG, "refreshFilteredConn: " + mFilteredConn.size() + " connections matched");
        } else
            mFilteredConn = null;

        notifyDataSetChanged();
    }

    public ConnectionDescriptor getItem(int pos) {
        if(mFilteredConn != null) {
            if((pos < 0) || (pos >= mFilteredConn.size())) {
                Log.w(TAG, "getItem(filtered): bad position: " + pos);
                return null;
            }
            return mFilteredConn.get(pos);
        }

        ConnectionsRegister reg = CaptureService.getConnsRegister();
        if((pos < 0) || (pos >= mUnfilteredItemsCount) || (reg == null)) {
            Log.w(TAG, "getItem: bad position: " + pos);
            return null;
        }

        return reg.getConn(pos);
    }

    public void setSearch(String text) {
        mSearch = text;
        refreshFilteredConnections();
    }

    public void setClickListener(View.OnClickListener listener) {
        mListener = listener;
    }

    public ConnectionDescriptor getSelectedItem() {
        return mSelectedItem;
    }

    public boolean hasFilter() {
        return (mSearch != null) || mFilter.isSet();
    }

    public String dumpConnectionsCsv() {
        StringBuilder builder = new StringBuilder();
        AppsResolver resolver = new AppsResolver(mContext);
        boolean malwareDetection = Prefs.isMalwareDetectionEnabled(mContext, PreferenceManager.getDefaultSharedPreferences(mContext));

        String header = mContext.getString(R.string.connections_csv_fields);
        builder.append(header);
        if(malwareDetection)
            builder.append(",Malicious");
        builder.append("\n");

        // Contents
        for(int i=0; i<getItemCount(); i++) {
            ConnectionDescriptor conn = getItem(i);

            if(conn != null) {
                AppDescriptor app = resolver.getAppByUid(conn.uid, 0);

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
                builder.append(conn.last_seen);

                if(malwareDetection) {
                    builder.append(",");

                    if(conn.isBlacklisted())
                        builder.append("yes");
                }

                builder.append("\n");
            }
        }

        return builder.toString();
    }
}
