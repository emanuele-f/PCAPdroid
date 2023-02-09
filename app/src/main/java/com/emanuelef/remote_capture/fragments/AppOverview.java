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
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppStats;

public class AppOverview extends Fragment implements MenuProvider {
    private static final String UID_ARG = "UID";
    private int mUid;
    private Handler mHandler;
    private View mBlockedConnsRow;
    private TextView mBytes;
    private TextView mConnections;
    private TextView mBlockedConnections;
    private TableLayout mTable;
    private TextView mPermissions;
    private PackageInfo mPinfo;
    private boolean mCreateError;

    public static AppOverview newInstance(int uid) {
        AppOverview fragment = new AppOverview();
        Bundle args = new Bundle();
        args.putInt(UID_ARG, uid);

        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        requireActivity().addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        return inflater.inflate(R.layout.app_overview, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        assert getArguments() != null;
        mUid = getArguments().getInt(UID_ARG);
        Context ctx = requireContext();

        AppsResolver res = new AppsResolver(ctx);
        AppDescriptor dsc = res.getAppByUid(mUid, PackageManager.GET_PERMISSIONS);
        if(dsc == null) {
            mCreateError = true;
            Utils.showToast(ctx, R.string.app_not_found, mUid);
            requireActivity().finish();
            return;
        }

        mHandler = new Handler(Looper.getMainLooper());
        mBytes = view.findViewById(R.id.detail_bytes);
        mConnections = view.findViewById(R.id.connections);
        mBlockedConnections = view.findViewById(R.id.conns_blocked);
        mBlockedConnsRow = view.findViewById(R.id.conns_blocked_row);
        mPermissions = view.findViewById(R.id.permissions);

        if(Utils.isTv(ctx)) {
            // necessary to make scroll work on TV
            // but disables ability to select and copy permissions textview
            ViewGroup layout = view.findViewById(R.id.layout);
            layout.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }

        ((TextView)view.findViewById(R.id.uid)).setText(Utils.formatInteger(ctx, dsc.getUid()));
        ((TextView)view.findViewById(R.id.name)).setText(dsc.getName());
        ((ImageView)view.findViewById(R.id.app_icon)).setImageDrawable(dsc.getIcon());

        mPinfo = dsc.getPackageInfo();

        if(mPinfo != null) {
            ((TextView)view.findViewById(R.id.package_name)).setText(dsc.getPackageName());
            ((TextView)view.findViewById(R.id.version)).setText(mPinfo.versionName);
            ((TextView)view.findViewById(R.id.target_sdk)).setText(Utils.formatInteger(ctx, mPinfo.applicationInfo.targetSdkVersion));
            ((TextView)view.findViewById(R.id.install_date)).setText(Utils.formatEpochFull(ctx, mPinfo.firstInstallTime / 1000));
            ((TextView)view.findViewById(R.id.last_update)).setText(Utils.formatEpochFull(ctx, mPinfo.lastUpdateTime / 1000));

            if((mPinfo.requestedPermissions != null) && (mPinfo.requestedPermissions.length != 0)) {
                StringBuilder builder = new StringBuilder();
                boolean first = true;

                for(String perm: mPinfo.requestedPermissions) {
                    if(first)
                        first = false;
                    else
                        builder.append("\n");

                    builder.append(perm);
                }

                mPermissions.setText(builder.toString());

                if(Utils.isTv(ctx)) {
                    mPermissions.setOnClickListener(v -> Utils.shareText(ctx, getString(R.string.permissions), mPermissions.getText().toString()));
                }
            } else {
                view.findViewById(R.id.permissions_label).setVisibility(View.GONE);
                view.findViewById(R.id.permissions).setVisibility(View.GONE);
            }
        } else {
            // This is a virtual App
            if(!dsc.getDescription().isEmpty()) {
                ((TextView) view.findViewById(R.id.vapp_info)).setText(dsc.getDescription());
                view.findViewById(R.id.vapp_info).setVisibility(View.VISIBLE);
            }

            view.findViewById(R.id.package_name_row).setVisibility(View.GONE);
            view.findViewById(R.id.version_row).setVisibility(View.GONE);
            view.findViewById(R.id.target_sdk_row).setVisibility(View.GONE);
            view.findViewById(R.id.install_date_row).setVisibility(View.GONE);
            view.findViewById(R.id.last_update_row).setVisibility(View.GONE);
            view.findViewById(R.id.permissions_label).setVisibility(View.GONE);
            view.findViewById(R.id.permissions).setVisibility(View.GONE);
        }

        mTable = view.findViewById(R.id.table);


    }

    @Override
    public void onResume() {
        super.onResume();
        if(mCreateError)
            return;

        updateStatus();
    }

    @Override
    public void onPause() {
        super.onPause();
        if(mCreateError)
            return;

        mHandler.removeCallbacksAndMessages(null);
    }

    private String asString() {
        if(mPermissions.getVisibility() == View.GONE)
            return Utils.table2Text(mTable);

        return Utils.table2Text(mTable) +
                "\n" +
                getString(R.string.permissions) +
                ":\n" +
                mPermissions.getText();
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.app_overview_menu, menu);

        if(mPinfo == null)
            menu.findItem(R.id.app_info).setVisible(false);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.app_info) {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", mPinfo.packageName, null));
            Utils.startActivity(requireContext(), intent);
            return true;
        } else if(id == R.id.copy_to_clipboard) {
            Utils.copyToClipboard(requireContext(), asString());
            return true;
        } else if(id == R.id.share) {
            Utils.shareText(requireContext(), getString(R.string.app_details), asString());
            return true;
        }

        return false;
    }

    private void updateStatus() {
        Context ctx = requireContext();
        ConnectionsRegister reg = CaptureService.getConnsRegister();
        if(reg == null)
            return;

        AppStats stats = reg.getAppStats(mUid);
        if(stats == null)
            stats = new AppStats(mUid);

        mBytes.setText(getString(R.string.rcvd_and_sent, Utils.formatBytes(stats.rcvdBytes), Utils.formatBytes(stats.sentBytes)));
        mConnections.setText(Utils.formatInteger(ctx, stats.numConnections));

        mBlockedConnsRow.setVisibility(stats.numBlockedConnections > 0 ? View.VISIBLE : View.GONE);
        mBlockedConnections.setText(Utils.formatInteger(ctx, stats.numBlockedConnections));
    }
}
