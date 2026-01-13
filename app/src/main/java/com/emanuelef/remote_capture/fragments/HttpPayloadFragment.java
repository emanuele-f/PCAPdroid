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
 * Copyright 2020-26 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.HttpLog;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.ConnectionDetailsActivity;
import com.emanuelef.remote_capture.activities.HttpDetailsActivity;
import com.emanuelef.remote_capture.adapters.PayloadAdapter;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;

public class HttpPayloadFragment extends Fragment {
    private static final String TAG = "ConnectionPayload";
    private HttpDetailsActivity mActivity;
    private HttpLog.HttpRequest mHttpReq;
    private PayloadAdapter mAdapter;
    private EmptyRecyclerView mRecyclerView;
    private boolean mShowAsPrintable;

    public static HttpPayloadFragment newInstance(int req_pos, boolean show_reply) {
        HttpPayloadFragment fragment = new HttpPayloadFragment();
        Bundle args = new Bundle();
        args.putSerializable("show_reply", show_reply);
        args.putInt("req_pos", req_pos);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        mActivity = (HttpDetailsActivity) context;

        if (mAdapter != null)
            mAdapter.setExportPayloadHandler(mActivity);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mActivity = null;

        if (mAdapter != null)
            mAdapter.setExportPayloadHandler(null);
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.connection_payload, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Bundle args = getArguments();
        assert args != null;

        HttpLog httpLog = CaptureService.getHttpLog();
        if (httpLog == null) {
            mActivity.finish();
            return;
        }

        mHttpReq = httpLog.getRequest(args.getInt("req_pos"));
        if(mHttpReq == null) {
            Utils.showToast(requireContext(), R.string.item_not_found);
            mActivity.finish();
            return;
        }

        mRecyclerView = view.findViewById(R.id.payload);
        EmptyRecyclerView.MyLinearLayoutManager layoutMan = new EmptyRecyclerView.MyLinearLayoutManager(requireContext());
        mRecyclerView.setLayoutManager(layoutMan);

        // Add window insets handling to prevent unwanted padding from propagated insets
        ViewCompat.setOnApplyWindowInsetsListener(mRecyclerView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                    WindowInsetsCompat.Type.displayCutout());
            v.setPadding(0, 0, 0, insets.bottom);

            return WindowInsetsCompat.CONSUMED;
        });
        mRecyclerView.setClipToPadding(false);

        boolean show_reply = args.getBoolean("show_reply");
        mShowAsPrintable = true;
        mAdapter = new PayloadAdapter(requireContext(), mHttpReq, show_reply);
        mAdapter.setDisplayAsPrintableText(true);
        mAdapter.setExportPayloadHandler(mActivity);

        if (mHttpReq.decryptionError.isEmpty()) {
            // only set adapter after acknowledged (see setMenuVisibility below)
            if (payloadNoticeAcknowledged(PreferenceManager.getDefaultSharedPreferences(requireContext())))
                mRecyclerView.setAdapter(mAdapter);
        } else {
            LinearLayout errorContainer = view.findViewById(R.id.decryption_error_container);
            TextView urlView = view.findViewById(R.id.request_url);
            TextView errorView = view.findViewById(R.id.decryption_error);
            Button showConnectionBtn = view.findViewById(R.id.show_connection_btn);

            urlView.setText(mHttpReq.getProtoAndHost());
            errorView.setText(mHttpReq.decryptionError);
            mRecyclerView.setEmptyView(errorContainer);

            showConnectionBtn.setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), ConnectionDetailsActivity.class);
                intent.putExtra(ConnectionDetailsActivity.CONN_ID_KEY, mHttpReq.conn.incr_id);
                startActivity(intent);
            });
        }
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
                        mRecyclerView.setAdapter(mAdapter);
                        prefs.edit().putBoolean(Prefs.PREF_PAYLOAD_NOTICE_ACK, true).apply();
                    }).show();

            dialog.setCanceledOnTouchOutside(false);
        }

        if(menuVisible && mActivity != null)
            mActivity.updateMenuVisibility();
    }

    private boolean payloadNoticeAcknowledged(SharedPreferences prefs) {
        return prefs.getBoolean(Prefs.PREF_PAYLOAD_NOTICE_ACK, false);
    }

    public void setDisplayMode(boolean showAsPrintable) {
        mShowAsPrintable = showAsPrintable;
        mAdapter.setDisplayAsPrintableText(showAsPrintable);
    }
}
