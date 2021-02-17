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

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppStats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class AppsStatsAdapter extends RecyclerView.Adapter<AppsStatsAdapter.ViewHolder> {
    private static final String TAG = "ConnectionsAdapter";
    private final MainActivity mActivity;
    private final LayoutInflater mLayoutInflater;
    private final Drawable mUnknownIcon;
    private View.OnClickListener mListener;
    private List<AppStats> mStats;

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView info;
        TextView traffic;

        ViewHolder(View itemView) {
            super(itemView);

            icon = itemView.findViewById(R.id.icon);
            info = itemView.findViewById(R.id.app_info);
            traffic = itemView.findViewById(R.id.traffic);
        }

        public void bindAppStats(MainActivity activity, AppStats stats, Drawable unknownIcon) {
            Drawable appIcon;

            // NOTE: can be null
            AppDescriptor app = activity.findAppByUid(stats.getUid());

            appIcon = (app != null) ? Objects.requireNonNull(app.getIcon().getConstantState()).newDrawable() : unknownIcon;
            icon.setImageDrawable(appIcon);

            String info_txt = (app != null) ? app.getName() : Integer.toString(stats.getUid());

            if(stats.num_connections > 1)
                info_txt += " (" + Utils.formatNumber(activity, stats.num_connections) + ")";

            info.setText(info_txt);

            traffic.setText(Utils.formatBytes(stats.bytes));
        }
    }

    public AppsStatsAdapter(MainActivity context) {
        mActivity = context;
        mLayoutInflater = (LayoutInflater) mActivity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mUnknownIcon = ContextCompat.getDrawable(mActivity, android.R.drawable.ic_menu_help);
        mListener = null;
        mStats = new ArrayList<>();
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

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppStats stats = getItem(position);

        if(stats == null)
            return;

        holder.bindAppStats(mActivity, stats, mUnknownIcon);
    }

    @Override
    public long getItemId(int pos) {
        AppStats stats = getItem(pos);

        return((stats != null) ? stats.getUid() : -1);
    }

    public AppStats getItem(int pos) {
        return mStats.get(pos);
    }

    public void setClickListener(View.OnClickListener listener) {
        mListener = listener;
    }

    public void setStats(List<AppStats> stats) {
        if(mActivity.appsLoaded()) {
            Collections.sort(stats, (o1, o2) -> {
                AppDescriptor a1 = mActivity.findAppByUid(o1.getUid());
                AppDescriptor a2 = mActivity.findAppByUid(o2.getUid());

                if((a1 == null) && (a2 == null))
                    return 0;

                if(a1 == null)
                    return -1;

                if(a2 == null)
                    return 1;

                return a1.compareTo(a2);
            });
        }

        mStats = stats;
        notifyDataSetChanged();
    }
}
