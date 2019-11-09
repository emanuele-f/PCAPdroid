package com.emanuelef.remote_capture;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

public class ConnectionsAdapter extends ArrayAdapter<ConnDescriptor> {
    private MainActivity mActivity;

    public ConnectionsAdapter(MainActivity context) {
        super(context, -1);

        mActivity = context;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ConnDescriptor conn = getItem(position);
        LayoutInflater inflater = (LayoutInflater) mActivity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View connView = inflater.inflate(R.layout.connection_item, parent, false);

        ImageView icon = connView.findViewById(R.id.icon);
        TextView remote = connView.findViewById(R.id.remote);
        TextView traffic = connView.findViewById(R.id.traffic);
        AppDescriptor app = mActivity.findAppByUid(conn.uid);

        if(app != null)
            icon.setImageDrawable(app.getIcon().getConstantState().newDrawable());

        remote.setText(conn.dst_ip + ":" + Integer.toString(conn.dst_port));
        traffic.setText(Utils.formatBytes(conn.sent_bytes + conn.rcvd_bytes));

        return(connView);
    }

    void updateView(ConnDescriptor[] connections) {
        clear();
        addAll(connections);
        notifyDataSetChanged();
    }
}
