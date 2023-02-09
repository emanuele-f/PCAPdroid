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
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.BlacklistDescriptor;

import java.util.Iterator;

public class BlacklistsAdapter extends ArrayAdapter<BlacklistDescriptor> {
    private final LayoutInflater mLayoutInflater;

    public BlacklistsAdapter(@NonNull Context context, Iterator<BlacklistDescriptor> iterator) {
        super(context, R.layout.blacklist_item);
        mLayoutInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        while(iterator.hasNext()) {
            BlacklistDescriptor item = iterator.next();
            add(item);
        }
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if(convertView == null)
            convertView = mLayoutInflater.inflate(R.layout.blacklist_item, parent, false);

        Context ctx = parent.getContext();
        BlacklistDescriptor bl = getItem(position);

        TextView label = convertView.findViewById(R.id.label);
        label.setText(bl.label);

        TextView status = convertView.findViewById(R.id.status);
        status.setText(bl.getStatusLabel(ctx));
        status.setTextColor(bl.getStatusColor(ctx));
        status.setVisibility(CaptureService.isServiceActive() ? View.VISIBLE : View.INVISIBLE);

        ((TextView)convertView.findViewById(R.id.type)).setText(String.format(ctx.getString(R.string.blacklist_type), bl.getTypeLabel(ctx)));
        ((TextView)convertView.findViewById(R.id.rules)).setText(String.format(ctx.getString(R.string.n_rules), Utils.formatIntShort(bl.num_rules)));
        ((TextView)convertView.findViewById(R.id.last_update)).setText(String.format(ctx.getString(R.string.last_update_val), Utils.formatEpochMin(ctx, bl.getLastUpdate() / 1000)));

        return convertView;
    }
}
