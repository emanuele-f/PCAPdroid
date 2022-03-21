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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.emanuelef.remote_capture.R;

public class PrefSpinnerAdapter extends BaseAdapter {
    private final LayoutInflater mInflater;
    private final ModeInfo[] mModes;

    public static class ModeInfo {
        public final String key;
        public final String label;
        public final String description;

        public ModeInfo(String _key, String _label, String _descr) {
            key = _key;
            label = _label;
            description = _descr;
        }
    }

    public PrefSpinnerAdapter(Context context, int keysRes, int labelsRes, int descrRes) {
        mInflater = LayoutInflater.from(context);

        String[] keys = context.getResources().getStringArray(keysRes);
        String[] labels = context.getResources().getStringArray(labelsRes);
        String[] descriptions = context.getResources().getStringArray(descrRes);

        assert ((keys.length == labels.length) && (keys.length == descriptions.length));
        mModes = new ModeInfo[keys.length];

        for(int i=0; i<keys.length; i++)
            mModes[i] = new ModeInfo(keys[i], labels[i], descriptions[i]);
    }

    public int getModePos(String key) {
        for(int i=0; i<mModes.length; i++) {
            if(key.equals(mModes[i].key))
                return i;
        }

        return 0;
    }

    @Override
    public int getCount() {
        return mModes.length;
    }

    @Override
    public Object getItem(int position) {
        return mModes[position];
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if(convertView == null)
            convertView = mInflater.inflate(R.layout.quick_settings_item, parent, false);

        ModeInfo mode = (ModeInfo) getItem(position);

        TextView title = convertView.findViewById(R.id.title);
        title.setText(mode.label);

        TextView description = convertView.findViewById(R.id.description);
        description.setText(mode.description);

        return convertView;
    }
}
