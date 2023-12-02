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
package com.emanuelef.remote_capture.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.core.view.MenuProvider;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.ConnectionDescriptor.Status;
import com.emanuelef.remote_capture.model.ConnectionDescriptor.DecryptionStatus;
import com.emanuelef.remote_capture.model.ConnectionDescriptor.FilteringStatus;
import com.emanuelef.remote_capture.model.FilterDescriptor;
import com.emanuelef.remote_capture.model.ListInfo;
import com.emanuelef.remote_capture.model.Prefs;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.Arrays;
import java.util.ArrayList;

public class EditFilterActivity extends BaseActivity implements MenuProvider {
    public static final String FILTER_DESCRIPTOR = "filter";
    private static final String TAG = "EditFilterActivity";
    private FilterDescriptor mFilter;
    private CheckBox mHideMasked;
    private CheckBox mOnlyBlacklisted;
    private CheckBox mOnlyCleartext;
    private ArrayList<Pair<FilteringStatus, Chip>> mFirewallChips;
    private ArrayList<Pair<Status, Chip>> mStatusChips;
    private ArrayList<Pair<DecryptionStatus, Chip>> mDecChips;
    private ChipGroup mInterfaceGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.edit_filter_activity);
        setTitle(R.string.edit_filter);
        addMenuProvider(this);

        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeAsUpIndicator(R.drawable.ic_close);
        }

        Intent intent = getIntent();
        if(intent != null) {
            FilterDescriptor desc = Utils.getSerializableExtra(intent, FILTER_DESCRIPTOR, FilterDescriptor.class);
            if(desc != null)
                mFilter = desc;
        }
        if(mFilter == null)
            mFilter = new FilterDescriptor();

        mHideMasked = findViewById(R.id.not_hidden);
        mOnlyBlacklisted = findViewById(R.id.only_blacklisted);
        mOnlyCleartext = findViewById(R.id.only_cleartext);
        mInterfaceGroup = findViewById(R.id.interfaces);

        findViewById(R.id.edit_mask).setOnClickListener(v -> {
            Intent editIntent = new Intent(this, EditListActivity.class);
            editIntent.putExtra(EditListActivity.LIST_TYPE_EXTRA, ListInfo.Type.VISUALIZATION_MASK);
            startActivity(editIntent);
        });

        mFirewallChips = new ArrayList<>(Arrays.asList(
                new Pair<>(FilteringStatus.BLOCKED, findViewById(R.id.status_blocked)),
                new Pair<>(FilteringStatus.ALLOWED, findViewById(R.id.status_allowed))
        ));

        mStatusChips = new ArrayList<>(Arrays.asList(
                new Pair<>(Status.STATUS_ACTIVE, findViewById(R.id.status_active)),
                new Pair<>(Status.STATUS_CLOSED, findViewById(R.id.status_closed)),
                new Pair<>(Status.STATUS_UNREACHABLE, findViewById(R.id.status_unreachable)),
                new Pair<>(Status.STATUS_ERROR, findViewById(R.id.status_error))
        ));

        mDecChips = new ArrayList<>(Arrays.asList(
                new Pair<>(DecryptionStatus.DECRYPTED, findViewById(R.id.dec_status_decrypted)),
                new Pair<>(DecryptionStatus.NOT_DECRYPTABLE, findViewById(R.id.dec_status_not_decryptable)),
                new Pair<>(DecryptionStatus.ERROR, findViewById(R.id.dec_status_error))
        ));

        if(CaptureService.isDecryptingTLS()) {
            findViewById(R.id.decryption_status_label).setVisibility(View.VISIBLE);
            findViewById(R.id.decryption_status_group).setVisibility(View.VISIBLE);
            mOnlyCleartext.setVisibility(View.GONE);
        }

        Billing billing = Billing.newInstance(this);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if(!Prefs.isMalwareDetectionEnabled(this, prefs))
            mOnlyBlacklisted.setVisibility(View.GONE);

        if(billing.isFirewallVisible()) {
            findViewById(R.id.firewall_label).setVisibility(View.VISIBLE);
            findViewById(R.id.firewall_group).setVisibility(View.VISIBLE);
        }

        ConnectionsRegister reg = CaptureService.getConnsRegister();
        if((reg != null) && (reg.hasSeenMultipleInterfaces())) {
            LayoutInflater inflater = getLayoutInflater();

            // Create the chips
            for(String ifname: reg.getSeenInterfaces()) {
                Chip chip = (Chip) inflater.inflate(R.layout.choice_chip, mInterfaceGroup, false);
                chip.setText(ifname);
                mInterfaceGroup.addView(chip);
            }

            mInterfaceGroup.setVisibility(View.VISIBLE);
            findViewById(R.id.interfaces_label).setVisibility(View.VISIBLE);
        }

        model2view();
    }

    private <T> void setCheckedChip(ArrayList<Pair<T, Chip>> chipMap, T curValue) {
        for(Pair<T, Chip> mapping: chipMap) {
            Chip chip = mapping.second;
            chip.setChecked(mapping.first.equals(curValue));
        }
    }

    private <T> T getCheckedChip(ArrayList<Pair<T, Chip>> chipMap, T defaultValue) {
        for(Pair<T, Chip> mapping: chipMap) {
            Chip chip = mapping.second;

            if(chip.isChecked())
                return mapping.first;
        }

        return defaultValue;
    }

    private void model2view() {
        mHideMasked.setChecked(!mFilter.showMasked);
        mOnlyBlacklisted.setChecked(mFilter.onlyBlacklisted);
        mOnlyCleartext.setChecked(mFilter.onlyCleartext);

        setCheckedChip(mStatusChips, mFilter.status);
        setCheckedChip(mDecChips, mFilter.decStatus);
        setCheckedChip(mFirewallChips, mFilter.filteringStatus);

        if(mFilter.iface != null) {
            int num_chips = mInterfaceGroup.getChildCount();
            for(int i=0; i<num_chips; i++) {
                Chip chip = (Chip) mInterfaceGroup.getChildAt(i);
                if(chip.getText().equals(mFilter.iface)) {
                    chip.setChecked(true);
                    break;
                }
            }
        }
    }

    private void view2model() {
        mFilter.showMasked = !mHideMasked.isChecked();
        mFilter.onlyBlacklisted = mOnlyBlacklisted.isChecked();
        mFilter.onlyCleartext = mOnlyCleartext.isChecked();

        mFilter.status = getCheckedChip(mStatusChips, Status.STATUS_INVALID);
        mFilter.decStatus = getCheckedChip(mDecChips, DecryptionStatus.INVALID);
        mFilter.filteringStatus = getCheckedChip(mFirewallChips, FilteringStatus.INVALID);

        int num_chips = mInterfaceGroup.getChildCount();
        for(int i=0; i<num_chips; i++) {
            Chip chip = (Chip) mInterfaceGroup.getChildAt(i);
            if(chip.isChecked()) {
                mFilter.iface = chip.getText().toString();
                break;
            }
        }
    }

    private void finishOk() {
        view2model();
        Intent intent = new Intent();
        intent.putExtra(FILTER_DESCRIPTOR, mFilter);
        setResult(RESULT_OK, intent);
        finish();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finishOk();
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        finishOk();
        super.onBackPressed();
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.edit_filter_menu, menu);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        if(item.getItemId() == R.id.reset_changes) {
            mFilter.clear();
            model2view();
            return true;
        }

        return false;
    }
}
