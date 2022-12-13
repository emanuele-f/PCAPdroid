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
 * Copyright 2020-22 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.adapters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.PortMapping;
import com.emanuelef.remote_capture.model.PortMapping.PortMap;

import java.util.Iterator;

public class PortMappingAdapter extends ArrayAdapter<PortMap> {
    private final LayoutInflater mLayoutInflater;
    private final PortMapping mMappings;

    public PortMappingAdapter(Context context, PortMapping mappings) {
        super(context, R.layout.port_mapping_item);
        mLayoutInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mMappings = mappings;
        reload();
    }

    @SuppressLint("SetTextI18n")
    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if(convertView == null)
            convertView = mLayoutInflater.inflate(R.layout.port_mapping_item, parent, false);

        PortMap mapping = getItem(position);
        String redirect_to = getContext().getString(R.string.ip_and_port, mapping.redirect_ip, mapping.redirect_port);

        ((TextView)convertView.findViewById(R.id.orig_port)).setText(Integer.toString(mapping.orig_port));
        ((TextView)convertView.findViewById(R.id.proto)).setText(Utils.proto2str(mapping.ipproto));
        ((TextView)convertView.findViewById(R.id.redirect_to)).setText(redirect_to);

        return convertView;
    }

    public void reload() {
        clear();

        Iterator<PortMap> iterator = mMappings.iter();

        while(iterator.hasNext()) {
            PortMap item = iterator.next();
            add(item);
        }
    }
}
