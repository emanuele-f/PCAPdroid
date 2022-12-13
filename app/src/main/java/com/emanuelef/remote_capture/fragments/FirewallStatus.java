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

package com.emanuelef.remote_capture.fragments;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.ConnectionsActivity;
import com.emanuelef.remote_capture.activities.FirewallActivity;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.model.Blocklist;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.FilterDescriptor;
import com.emanuelef.remote_capture.model.MatchList;
import com.emanuelef.remote_capture.model.Prefs;

public class FirewallStatus extends Fragment implements MenuProvider {
    private static final String TAG = "FirewallStatus";
    private static boolean whitelistWarningAck = false;
    private Handler mHandler;
    private SharedPreferences mPrefs;
    private Menu mMenu;
    private SwitchCompat mToggle;
    private ImageView mStatusIcon;
    private TextView mStatus;
    private TextView mNumBlocked;
    private TextView mNumChecked;
    private TextView mNumRules;
    private TextView mLastBlock;
    private Blocklist mBlocklist;
    private MatchList mWhitelist;
    private int mOkColor, mWarnColor, mGrayColor;

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        requireActivity().addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        return inflater.inflate(R.layout.firewall_status, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Context ctx = view.getContext();

        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        mBlocklist = PCAPdroid.getInstance().getBlocklist();
        mWhitelist = PCAPdroid.getInstance().getFirewallWhitelist();
        mStatus = view.findViewById(R.id.status);
        mHandler = new Handler(Looper.getMainLooper());
        mStatusIcon = view.findViewById(R.id.status_icon);
        mNumBlocked = view.findViewById(R.id.num_blocked);
        mNumChecked = view.findViewById(R.id.num_checked);
        mNumRules = view.findViewById(R.id.num_rules);
        mLastBlock = view.findViewById(R.id.last_block);

        mOkColor = ContextCompat.getColor(ctx, R.color.ok);
        mWarnColor = ContextCompat.getColor(ctx, R.color.warning);
        mGrayColor = ContextCompat.getColor(ctx, R.color.lightGray);

        view.findViewById(R.id.show_connections).setOnClickListener(v -> {
            FilterDescriptor filter = new FilterDescriptor();
            filter.filteringStatus = ConnectionDescriptor.FilteringStatus.BLOCKED;

            Intent intent = new Intent(requireContext(), ConnectionsActivity.class)
                    .putExtra(ConnectionsFragment.FILTER_EXTRA, filter);
            startActivity(intent);
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.firewall_menu, menu);
        mMenu = menu;

        mToggle = (SwitchCompat) menu.findItem(R.id.toggle_btn).getActionView();
        mToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if(isChecked == Prefs.isFirewallEnabled(requireContext(), mPrefs))
                return; // not changed

            Log.d(TAG, "Firwall is now " + (isChecked ? "enabled" : "disabled"));

            CaptureService.setFirewallEnabled(isChecked);
            mPrefs.edit().putBoolean(Prefs.PREF_FIREWALL, isChecked).apply();

            updateStatus();
        });

        menu.findItem(R.id.whitelist_mode).setChecked(Prefs.isFirewallWhitelistMode(mPrefs));
        menu.findItem(R.id.block_new_apps).setChecked(Prefs.blockNewApps(mPrefs));
        reloadMode(false);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.user_guide) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.FIREWALL_DOCS_URL));
            Utils.startActivity(requireContext(), browserIntent);
            return true;
        } else if(id == R.id.block_new_apps) {
            boolean checked = !item.isChecked();
            item.setChecked(checked);
            mPrefs.edit().putBoolean(Prefs.PREF_BLOCK_NEW_APPS, checked).apply();
            return true;
        } else if(id == R.id.whitelist_mode) {
            boolean checked = !item.isChecked();
            if(checked && !whitelistWarningAck) {
                AlertDialog dialog = new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.whitelist_mode)
                        .setMessage(R.string.firewall_whitelist_notice)
                        .setPositiveButton(R.string.ok, (d, whichButton) -> {
                            whitelistWarningAck = true;
                            item.setChecked(true);
                            mPrefs.edit().putBoolean(Prefs.PREF_FIREWALL_WHITELIST_MODE, true).apply();
                            reloadMode(true);
                        })
                        .setNegativeButton(R.string.cancel_action, (d, whichButton) -> {})
                        .show();

                dialog.setCanceledOnTouchOutside(false);
            } else {
                item.setChecked(checked);
                mPrefs.edit().putBoolean(Prefs.PREF_FIREWALL_WHITELIST_MODE, checked).apply();
                reloadMode(true);
            }
            return true;
        }

        return false;
    }

    private void reloadMode(boolean changed) {
        if(changed && CaptureService.isServiceActive())
            CaptureService.requireInstance().reloadFirewallWhitelist();

        ((FirewallActivity)requireActivity()).recheckTabs();
        updateStatus();
    }

    private void updateStatus() {
        Context ctx = requireContext();
        ConnectionsRegister reg = CaptureService.getConnsRegister();
        boolean is_running = CaptureService.isServiceActive();
        boolean is_enabled = Prefs.isFirewallEnabled(ctx, mPrefs);
        boolean whitelist_mode = Prefs.isFirewallWhitelistMode(mPrefs);

        if(!is_running) {
            mStatusIcon.setImageResource(R.drawable.ic_shield);
            mStatusIcon.setColorFilter(mGrayColor);
            mStatus.setText(R.string.capture_not_running_status);
        } else if(CaptureService.isDNSEncrypted()) {
            mStatusIcon.setImageResource(R.drawable.ic_exclamation_triangle_solid);
            mStatusIcon.setColorFilter(mWarnColor);
            mStatus.setText(R.string.private_dns_hinders_detection);
        } else {
            mStatusIcon.setImageResource(R.drawable.ic_shield);

            if(is_enabled) {
                mStatusIcon.setColorFilter(mOkColor);
                mStatus.setText(R.string.firewall_is_enabled);
            } else {
                mStatusIcon.setColorFilter(mWarnColor);
                mStatus.setText(R.string.firewall_is_disabled);
            }
        }

        if(mToggle != null)
            mToggle.setChecked(is_enabled);

        mNumBlocked.setText(Utils.formatIntShort(((reg != null) ? reg.getNumBlockedConnections() : 0)));
        mNumChecked.setText(Utils.formatIntShort(CaptureService.getNumCheckedFirewallConnections()));
        mLastBlock.setText(Utils.formatEpochMin(ctx, ((reg != null) ? reg.getLastFirewallBlock() / 1000 : 0)));
        mNumRules.setText(Utils.formatIntShort(mBlocklist.getSize() + (whitelist_mode ? mWhitelist.getSize() : 0)));

        // Periodic update
        mHandler.postDelayed(this::updateStatus, 1000);
    }
}
