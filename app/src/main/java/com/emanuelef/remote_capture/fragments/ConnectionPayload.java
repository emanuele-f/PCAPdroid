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

package com.emanuelef.remote_capture.fragments;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.activities.ConnectionDetailsActivity;
import com.emanuelef.remote_capture.adapters.PayloadAdapter;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.PayloadChunk;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;

public class ConnectionPayload extends Fragment implements ConnectionDetailsActivity.ConnUpdateListener {
    private ConnectionDetailsActivity mActivity;
    private ConnectionDescriptor mConn;
    private PayloadAdapter mAdapter;
    private TextView mTruncatedWarning;
    private int mCurChunks;

    public static ConnectionPayload newInstance(PayloadChunk.ChunkType mode) {
        ConnectionPayload fragment = new ConnectionPayload();
        Bundle args = new Bundle();
        args.putSerializable("mode", mode);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mActivity = (ConnectionDetailsActivity) context;
        mConn = mActivity.getConn();
        mActivity.addConnUpdateListener(this);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mActivity.removeConnUpdateListener(this);
        mActivity = null;
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.connection_payload, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        PayloadChunk.ChunkType mode;
        if((args != null) && args.containsKey("mode"))
            mode = (PayloadChunk.ChunkType) args.getSerializable("mode");
        else
            mode = PayloadChunk.ChunkType.RAW;

        EmptyRecyclerView recyclerView = view.findViewById(R.id.payload);
        EmptyRecyclerView.MyLinearLayoutManager layoutMan = new EmptyRecyclerView.MyLinearLayoutManager(requireContext());
        recyclerView.setLayoutManager(layoutMan);

        mTruncatedWarning = view.findViewById(R.id.truncated_warning);
        mTruncatedWarning.setText(String.format(getString(R.string.payload_truncated), getString(R.string.full_payload)));
        if(mConn.isPayloadTruncated())
            mTruncatedWarning.setVisibility(View.VISIBLE);

        mAdapter = new PayloadAdapter(requireContext(), layoutMan, mConn, mode);
        mCurChunks = mConn.getNumPayloadChunks();
        recyclerView.setAdapter(mAdapter);
    }

    @Override
    public void connectionUpdated() {
        if(mConn.getNumPayloadChunks() > mCurChunks) {
            mAdapter.handleChunksAdded(mConn.getNumPayloadChunks());
            mCurChunks = mConn.getNumPayloadChunks();
        }

        if(mConn.isPayloadTruncated() && (mTruncatedWarning != null))
            mTruncatedWarning.setVisibility(View.VISIBLE);
    }
}
