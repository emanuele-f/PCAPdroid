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
    private LayoutInflater layoutInflater;
    private List<AppDescriptor> listStorage;
    private View.OnClickListener mListener;

    AppAdapter(Context context, List<AppDescriptor> customizedListView, final View.OnClickListener listener) {
        layoutInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        listStorage = customizedListView;
        mListener = listener;
    }

    @NonNull
    @Override
    public AppAdapter.AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.installed_app_list, parent, false);
        AppViewHolder recyclerViewHolder = new AppViewHolder(view);
        view.setOnClickListener(mListener);

        return(recyclerViewHolder);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        holder.textInListView.setText(listStorage.get(position).getName());
        holder.imageInListView.setImageDrawable(listStorage.get(position).getIcon());
        holder.packageInListView.setText(listStorage.get(position).getPackageName());
    }

    @Override
    public int getItemCount() {
        return listStorage.size();
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
}
