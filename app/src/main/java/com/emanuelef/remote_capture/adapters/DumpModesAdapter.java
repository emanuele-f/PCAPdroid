package com.emanuelef.remote_capture.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.emanuelef.remote_capture.R;

public class DumpModesAdapter extends BaseAdapter {
    private final Context mContext;
    private final LayoutInflater mInflater;
    private final DumpModeInfo[] mModes;

    public class DumpModeInfo {
        public final String key;
        public final String label;
        public final String description;

        public DumpModeInfo(String _key, String _label, String _descr) {
            key = _key;
            label = _label;
            description = _descr;
        }
    }

    public DumpModesAdapter(Context context) {
        mContext = context;
        mInflater = LayoutInflater.from(mContext);

        String[] keys = mContext.getResources().getStringArray(R.array.pcap_dump_modes);
        String[] labels = mContext.getResources().getStringArray(R.array.pcap_dump_modes_labels);
        String[] descriptions = mContext.getResources().getStringArray(R.array.pcap_dump_modes_descriptions);

        assert ((keys.length == labels.length) && (keys.length == descriptions.length));
        mModes = new DumpModeInfo[keys.length];

        for(int i=0; i<keys.length; i++) {
            mModes[i] = new DumpModeInfo(keys[i], labels[i], descriptions[i]);
        }
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

        DumpModeInfo mode = (DumpModeInfo) getItem(position);

        TextView title =convertView.findViewById(R.id.title);
        TextView description =convertView.findViewById(R.id.description);

        title.setText(mode.label);
        description.setText(mode.description);

        return convertView;
    }
}
