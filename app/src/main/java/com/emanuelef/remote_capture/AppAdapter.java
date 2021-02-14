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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.AppViewHolder> {
    private final LayoutInflater mLayoutInflater;
    private View.OnClickListener mListener;
    private List<AppDescriptor> listStorage;

    AppAdapter(Context context, List<AppDescriptor> customizedListView) {
        mLayoutInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        listStorage = customizedListView;
        mListener = null;
    }

    @NonNull
    @Override
    public AppAdapter.AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.installed_app_list, parent, false);
        AppViewHolder recyclerViewHolder = new AppViewHolder(view);

        if(mListener != null)
            view.setOnClickListener(mListener);

        return(recyclerViewHolder);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppDescriptor app = getItem(position);

        holder.textInListView.setText(app.getName());
        holder.imageInListView.setImageDrawable(app.getIcon());
        holder.packageInListView.setText(app.getPackageName());
    }

    @Override
    public int getItemCount() {
        return listStorage.size();
    }

    public AppDescriptor getItem(int pos) {
        if((pos < 0) || (pos > listStorage.size()))
            return null;

        return listStorage.get(pos);
    }

    public static class AppViewHolder extends RecyclerView.ViewHolder {
        TextView textInListView;
        ImageView imageInListView;
        TextView packageInListView;

        public AppViewHolder(View view) {
            super(view);

            textInListView = view.findViewById(R.id.list_app_name);
            imageInListView = view.findViewById(R.id.app_icon);
            packageInListView= view.findViewById(R.id.app_package);
        }
    }

    public void setApps(List<AppDescriptor> apps) {
        listStorage = apps;
        notifyDataSetChanged();
    }

    public void setOnClickListener(final View.OnClickListener listener) {
        mListener = listener;
    }
}
