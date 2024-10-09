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
import android.content.SharedPreferences;
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
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.ConnectionDetailsActivity;
import com.emanuelef.remote_capture.adapters.PayloadAdapter;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.PayloadChunk;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;

public class ConnectionPayload extends Fragment implements ConnectionDetailsActivity.ConnUpdateListener, MenuProvider {
    private static final String TAG = "ConnectionPayload";
    private ConnectionDetailsActivity mActivity;
    private ConnectionDescriptor mConn;
    private PayloadAdapter mAdapter;
    private TextView mTruncatedWarning;
    private EmptyRecyclerView mRecyclerView;
    private int mCurChunks;
    private Menu mMenu;
    private boolean mJustCreated;
    private boolean mShowAsPrintable;

    public static ConnectionPayload newInstance(PayloadChunk.ChunkType mode, int conn_id) {
        ConnectionPayload fragment = new ConnectionPayload();
        Bundle args = new Bundle();
        args.putSerializable("mode", mode);
        args.putInt("conn_id", conn_id);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mActivity = (ConnectionDetailsActivity) context;
        mActivity.addConnUpdateListener(this);

        if (mAdapter != null)
            mAdapter.setExportPayloadHandler(mActivity);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mActivity.removeConnUpdateListener(this);
        mActivity = null;

        if (mAdapter != null)
            mAdapter.setExportPayloadHandler(null);
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        requireActivity().addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        return inflater.inflate(R.layout.connection_payload, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        PayloadChunk.ChunkType mode;
        assert args != null;
        ConnectionsRegister reg = CaptureService.requireConnsRegister();
        mode = Utils.getSerializable(args, "mode", PayloadChunk.ChunkType.class);
        assert(mode != null);

        mConn = reg.getConnById(args.getInt("conn_id"));
        if(mConn == null) {
            Utils.showToast(requireContext(), R.string.connection_not_found);
            mActivity.finish();
            return;
        }

        mRecyclerView = view.findViewById(R.id.payload);
        EmptyRecyclerView.MyLinearLayoutManager layoutMan = new EmptyRecyclerView.MyLinearLayoutManager(requireContext());
        mRecyclerView.setLayoutManager(layoutMan);

        mTruncatedWarning = view.findViewById(R.id.truncated_warning);
        mTruncatedWarning.setText(String.format(getString(R.string.payload_truncated), getString(R.string.full_payload)));
        if(mConn.isPayloadTruncated())
            mTruncatedWarning.setVisibility(View.VISIBLE);

        mCurChunks = mConn.getNumPayloadChunks();
        if(mCurChunks > 0)
            mShowAsPrintable = guessDisplayAsPrintable();
        else
            mShowAsPrintable = false;
        mAdapter = new PayloadAdapter(requireContext(), mConn, mode, mShowAsPrintable);
        mAdapter.setExportPayloadHandler(mActivity);
        mJustCreated = true;

        // only set adapter after acknowledged (see setMenuVisibility below)
        if(payloadNoticeAcknowledged(PreferenceManager.getDefaultSharedPreferences(requireContext())))
            mRecyclerView.setAdapter(mAdapter);
    }

    @Override
    public void setMenuVisibility(boolean menuVisible) {
        super.setMenuVisibility(menuVisible);

        Context context = getContext();
        if(context == null)
            return;

        Log.d(TAG, "setMenuVisibility : " + menuVisible);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if(menuVisible && !payloadNoticeAcknowledged(prefs)) {
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(R.string.warning)
                    .setMessage(R.string.payload_scams_notice)
                    .setOnCancelListener((d) -> requireActivity().finish())
                    .setNegativeButton(R.string.cancel_action, (d, b) -> requireActivity().finish())
                    .setPositiveButton(R.string.show_data_action, (d, whichButton) -> {
                        // show the data
                        mRecyclerView.setAdapter(mAdapter);

                        prefs.edit().putBoolean(Prefs.PREF_PAYLOAD_NOTICE_ACK, true).apply();
                    }).show();

            dialog.setCanceledOnTouchOutside(false);
        }
    }

    private boolean payloadNoticeAcknowledged(SharedPreferences prefs) {
        return prefs.getBoolean(Prefs.PREF_PAYLOAD_NOTICE_ACK, false);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater menuInflater) {
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
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
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

        return false;
    }

    private boolean guessDisplayAsPrintable() {
        // try to determine the best mode based on the current payload
        if(mConn.getNumPayloadChunks() == 0)
            return mConn.l7proto.equals("HTTPS");

        PayloadChunk firstChunk = mConn.getPayloadChunk(0);
        if((firstChunk == null) || (firstChunk.type == PayloadChunk.ChunkType.HTTP))
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
