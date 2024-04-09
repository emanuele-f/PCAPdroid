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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.model.AppDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppsTogglesAdapter extends RecyclerView.Adapter<AppsTogglesAdapter.AppViewHolder> {
    private static final String TAG = "AppToggleAdapter";
    private final LayoutInflater mLayoutInflater;
    private final Set<String> mCheckedItems;
    private AppToggleListener mListener;
    private String mFilter = "";
    private List<AppDescriptor> mApps = new ArrayList<>();
    private final List<AppDescriptor> mFilteredApps = new ArrayList<>();
    private @Nullable RecyclerView mRecyclerView;

    public AppsTogglesAdapter(Context context, Set<String> checkedItems) {
        mLayoutInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mCheckedItems = new HashSet<>(checkedItems);
        mListener = null;
    }

    public interface AppToggleListener {
        void onAppToggled(AppDescriptor app, boolean checked);
    }

    public static class AppViewHolder extends RecyclerView.ViewHolder {
        TextView appName;
        TextView packageName;
        ImageView icon;
        SwitchCompat toggle;

        public AppViewHolder(View view) {
            super(view);

            appName = view.findViewById(R.id.app_name);
            icon = view.findViewById(R.id.icon);
            packageName = view.findViewById(R.id.app_package);
            toggle = view.findViewById(R.id.toggle_btn);
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        mRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        mRecyclerView = null;
    }

    @NonNull
    @Override
    public AppsTogglesAdapter.AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.app_selection_item, parent, false);
        AppViewHolder recyclerViewHolder = new AppViewHolder(view);

        view.setOnClickListener((v) -> {
            if(mRecyclerView != null) {
                int pos = recyclerViewHolder.getAbsoluteAdapterPosition();
                AppDescriptor app = getItem(pos);

                if (app != null) {
                    boolean checked = mCheckedItems.contains(app.getPackageName());
                    handleToggle(pos, !checked);
                }
            }
        });

        recyclerViewHolder.toggle.setOnClickListener((v) -> {
            if(mRecyclerView != null) {
                int pos = recyclerViewHolder.getAbsoluteAdapterPosition();
                boolean checked = ((SwitchCompat)v).isChecked();
                handleToggle(pos, checked);
            }
        });

        return(recyclerViewHolder);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppDescriptor app = getItem(position);

        holder.appName.setText(app.getName());
        holder.packageName.setText(app.getPackageName());
        holder.toggle.setChecked(mCheckedItems.contains(app.getPackageName()));

        if(app.getIcon() != null)
            holder.icon.setImageDrawable(app.getIcon());
    }

    private List<AppDescriptor> getApps() {
        if(mFilter.isEmpty())
            return mApps;
        else
            return mFilteredApps;
    }

    @Override
    public int getItemCount() {
        return getApps().size();
    }

    public AppDescriptor getItem(int pos) {
        if((pos < 0) || (pos > getItemCount()))
            return null;

        return getApps().get(pos);
    }

    private void handleToggle(int old_pos, boolean checked) {
        AppDescriptor app = getItem(old_pos);
        String packageName = app.getPackageName();

        if(checked == mCheckedItems.contains(packageName))
            return; // nothing changed

        if(checked)
            mCheckedItems.add(packageName);
        else
            mCheckedItems.remove(packageName);

        if(mListener != null)
            mListener.onAppToggled(app, checked);

        List<AppDescriptor> apps = getApps();

        // determine the new item position
        int new_pos = old_pos;
        for(int i=0; i<apps.size(); i++) {
            AppDescriptor other = apps.get(i);

            if((i != old_pos) && compareCheckedFirst(app, other) <= 0) {
                new_pos = i;
                break;
            }
        }

        if(new_pos > old_pos)
            new_pos--;

        Log.d(TAG, "Item @" + old_pos + ": " + (checked ? "checked" : "unchecked") + " -> " + new_pos);
        notifyItemChanged(old_pos);

        if(new_pos != old_pos) {
            apps.remove(old_pos);
            apps.add(new_pos, app);
            notifyItemMoved(old_pos, new_pos);

            if(mRecyclerView != null) {
                if(checked)
                    mRecyclerView.scrollToPosition(new_pos);
                else
                    mRecyclerView.scrollToPosition(old_pos);
            }
        }
    }

    // sort apps so that checked items always appear first
    private int compareCheckedFirst(AppDescriptor a, AppDescriptor b) {
        boolean aChecked = mCheckedItems.contains(a.getPackageName());
        boolean bChecked = mCheckedItems.contains(b.getPackageName());

        if(aChecked && !bChecked)
            return -1;
        else if(!aChecked && bChecked)
            return 1;
        return a.compareTo(b);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void refreshedFiteredApps() {
        mFilteredApps.clear();

        if(!mFilter.isEmpty()) {
            for(AppDescriptor app: mApps) {
                if(app.matches(mFilter, false))
                    mFilteredApps.add(app);
            }
        }

        Collections.sort(getApps(), this::compareCheckedFirst);
        notifyDataSetChanged();
    }

    public void setApps(List<AppDescriptor> apps) {
        mApps = apps;
        refreshedFiteredApps();
    }

    public void setFilter(String text) {
        mFilter = text;
        refreshedFiteredApps();
    }

    public void setAppToggleListener(final AppToggleListener listener) {
        mListener = listener;
    }
}
