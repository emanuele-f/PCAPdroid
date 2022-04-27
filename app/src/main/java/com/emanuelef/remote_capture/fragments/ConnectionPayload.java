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
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
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
    private Menu mMenu;
    private boolean mJustCreated;
    private boolean mShowAsPrintable;

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
        setHasOptionsMenu(true);
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

        mAdapter = new PayloadAdapter(requireContext(), mConn, mode);
        mCurChunks = mConn.getNumPayloadChunks();
        recyclerView.setAdapter(mAdapter);
        mJustCreated = true;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.connection_payload, menu);
        mMenu = menu;
        if((mCurChunks > 0) && mJustCreated) {
            mShowAsPrintable = guessDisplayAsPrintable();
            mAdapter.setDisplayAsPrintableText(mShowAsPrintable);
            mJustCreated = false;
        }
        refreshDisplayMode();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.printable_text) {
            mShowAsPrintable = true;
            mAdapter.setDisplayAsPrintableText(true);
            refreshDisplayMode();
            return true;
        } else if(id == R.id.hexdump) {
            mShowAsPrintable = false;
            mAdapter.setDisplayAsPrintableText(false);
            refreshDisplayMode();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private boolean guessDisplayAsPrintable() {
        // try to determine the best mode based on the current payload
        if(mConn.getNumPayloadChunks() == 0)
            return mConn.l7proto.equals("HTTPS");

        PayloadChunk firstChunk = mConn.getPayloadChunk(0);
        if(firstChunk.type == PayloadChunk.ChunkType.HTTP)
            return true;

        // guess based on the actual data
        int maxLen = Math.min(firstChunk.payload.length, 16);
        for(int i=0; i<maxLen; i++) {
            if(!Utils.isPrintable(firstChunk.payload[i]))
                return false;
        }

        return true;
    }

    private void refreshDisplayMode() {
        if(mMenu == null)
            return;

        MenuItem printableText = mMenu.findItem(R.id.printable_text);
        MenuItem hexdump = mMenu.findItem(R.id.hexdump);

        // important: the checked item must first be unchecked
        if(mShowAsPrintable) {
            hexdump.setChecked(false);
            printableText.setChecked(true);
        } else {
            printableText.setChecked(false);
            hexdump.setChecked(true);
        }
    }

    @Override
    public void connectionUpdated() {
        if(mCurChunks == 0) {
            mShowAsPrintable = guessDisplayAsPrintable();
            mAdapter.setDisplayAsPrintableText(mShowAsPrintable);
        }

        if(mConn.getNumPayloadChunks() > mCurChunks) {
            mAdapter.handleChunksAdded(mConn.getNumPayloadChunks());
            mCurChunks = mConn.getNumPayloadChunks();
        }

        if(mConn.isPayloadTruncated() && (mTruncatedWarning != null))
            mTruncatedWarning.setVisibility(View.VISIBLE);
    }
}
