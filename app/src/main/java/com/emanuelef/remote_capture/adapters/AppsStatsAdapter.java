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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppStats;
import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.model.Blocklist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppsStatsAdapter extends RecyclerView.Adapter<AppsStatsAdapter.ViewHolder> {
    private static final String TAG = "AppsStatsAdapter";
    private final Context mContext;
    private final LayoutInflater mLayoutInflater;
    private final Drawable mUnknownIcon;
    private final Blocklist mBlocklist;
    private final boolean mFirewallAvailable;
    private View.OnClickListener mListener;
    private List<AppStats> mStats;
    private final AppsResolver mApps;
    private AppStats mSelectedItem;

    public class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        ImageView blockedFlag;
        ImageView tempUnblocked;
        TextView info;
        TextView traffic;

        ViewHolder(View itemView) {
            super(itemView);

            icon = itemView.findViewById(R.id.icon);
            blockedFlag = itemView.findViewById(R.id.blocked);
            tempUnblocked = itemView.findViewById(R.id.temp_unblocked);
            info = itemView.findViewById(R.id.app_info);
            traffic = itemView.findViewById(R.id.traffic);
        }

        public void bindAppStats(AppStats stats) {
            Drawable appIcon;

            // NOTE: can be null
            AppDescriptor app = (mApps != null) ? mApps.get(stats.getUid(), 0) : null;

            appIcon = ((app != null) && (app.getIcon() != null)) ? app.getIcon() : mUnknownIcon;
            icon.setImageDrawable(appIcon);

            String info_txt = (app != null) ? app.getName() : Integer.toString(stats.getUid());

            if(stats.numConnections > 1)
                info_txt += " (" + Utils.formatNumber(mContext, stats.numConnections) + ")";

            info.setText(info_txt);

            boolean isGracedApp = mBlocklist.isExemptedApp(stats.getUid());
            boolean isBlockedApp = mBlocklist.matchesApp(stats.getUid());

            traffic.setText(Utils.formatBytes(stats.sentBytes + stats.rcvdBytes));
            blockedFlag.setVisibility(isBlockedApp ? View.VISIBLE : View.GONE);
            tempUnblocked.setVisibility(isGracedApp ? View.VISIBLE : View.GONE);
        }
    }

    public AppsStatsAdapter(Context context) {
        mContext = context;
        mApps = new AppsResolver(context);
        mLayoutInflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mUnknownIcon = ContextCompat.getDrawable(mContext, android.R.drawable.ic_menu_help);
        mBlocklist = PCAPdroid.getInstance().getBlocklist();
        mListener = null;
        mStats = new ArrayList<>();
        mFirewallAvailable = Billing.newInstance(context).isFirewallVisible();
        setHasStableIds(true);
    }

    @Override
    public int getItemCount() {
        return mStats.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.app_item, parent, false);

        if(mListener != null)
            view.setOnClickListener(mListener);

        ViewHolder holder = new ViewHolder(view);

        if(mFirewallAvailable) {
            // Enable the ability to show the context menu
            view.setLongClickable(true);

            view.setOnLongClickListener(v -> {
                // see registerForContextMenu
                mSelectedItem = getItem(holder.getAbsoluteAdapterPosition());
                return false;
            });
        }

        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppStats stats = getItem(position);

        if(stats == null)
            return;

        holder.bindAppStats(stats);
    }

    @Override
    public long getItemId(int pos) {
        AppStats stats = getItem(pos);

        return((stats != null) ? stats.getUid() : Utils.UID_UNKNOWN);
    }

    public AppStats getItem(int pos) {
        return mStats.get(pos);
    }

    public AppStats getSelectedItem() {
        return mSelectedItem;
    }

    public void notifyItemChanged(AppStats app) {
        int pos = mStats.indexOf(app);
        if(pos >= 0)
            notifyItemChanged(pos);
    }

    public String getItemPackage(int pos) {
        AppStats stats = getItem(pos);

        if(stats == null)
            return null;

        AppDescriptor descr = mApps.get(stats.getUid(), 0);

        return((descr != null) ? descr.getPackageName() : null);
    }

    public void setClickListener(View.OnClickListener listener) {
        mListener = listener;
    }

    public void setStats(List<AppStats> stats) {
        Collections.sort(stats, (o1, o2) -> {
            AppDescriptor a1 = mApps.get(o1.getUid(), 0);
            AppDescriptor a2 = mApps.get(o2.getUid(), 0);

            if((a1 == null) && (a2 == null))
                return 0;

            if(a1 == null)
                return -1;

            if(a2 == null)
                return 1;

            return a1.compareTo(a2);
        });

        mStats = stats;
        notifyDataSetChanged();
    }
}
