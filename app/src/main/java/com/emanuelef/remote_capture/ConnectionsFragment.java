package com.emanuelef.remote_capture;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class ConnectionsFragment extends Fragment {
    private MainActivity activity;

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
    }
}
