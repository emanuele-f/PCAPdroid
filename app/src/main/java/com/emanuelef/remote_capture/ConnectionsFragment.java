package com.emanuelef.remote_capture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class ConnectionsFragment extends Fragment {
    private MainActivity activity;
    private ConnectionsAdapter mAdapter;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        activity = (MainActivity) context;
        activity.setConnectionsFragment(this);
    }

    @Override
    public void onDetach() {
        activity.setConnectionsFragment(null);
        super.onDetach();
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.connections, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ListView connList = view.findViewById(R.id.connections_view);
        TextView emptyText = view.findViewById(R.id.no_connections);
        connList.setEmptyView(emptyText);

        mAdapter = new ConnectionsAdapter(activity);
        connList.setAdapter(mAdapter);

        LocalBroadcastManager bcast_man = LocalBroadcastManager.getInstance(activity);

        /* Register for connections update */
        bcast_man.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                processConnectionsDump(intent);
            }
        }, new IntentFilter(CaptureService.ACTION_CONNECTIONS_DUMP));
    }

    private void processConnectionsDump(Intent intent) {
        Bundle bundle = intent.getExtras();

        if(bundle != null) {
            ConnDescriptor connections[] = (ConnDescriptor[]) bundle.getSerializable("value");

            Log.d("ConnectionsDump", "Got " + connections.length + " connections");
            mAdapter.updateView(connections);
        }
    }
}
