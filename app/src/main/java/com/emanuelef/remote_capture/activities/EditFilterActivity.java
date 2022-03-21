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
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CheckBox;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.model.ConnectionDescriptor.Status;
import com.emanuelef.remote_capture.model.FilterDescriptor;
import com.emanuelef.remote_capture.model.ListInfo;
import com.emanuelef.remote_capture.model.MatchList;
import com.emanuelef.remote_capture.model.Prefs;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

public class EditFilterActivity extends BaseActivity {
    public static final String FILTER_DESCRIPTOR = "filter";
    private static final String TAG = "FilterEditActivity";
    private FilterDescriptor mFilter;
    private CheckBox mHideMasked;
    private CheckBox mOnlyBlocked;
    private CheckBox mOnlyBlacklisted;
    private CheckBox mOnlyCleartext;
    private Chip mStatusOpen;
    private Chip mStatusClosed;
    private Chip mStatusUnreachable;
    private Chip mStatusError;
    private ChipGroup mInterfaceGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.edit_filter_activity);
        setTitle(R.string.edit_filter);

        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeAsUpIndicator(R.drawable.ic_close);
        }

        Intent intent = getIntent();
        if(intent != null) {
            FilterDescriptor desc = (FilterDescriptor)intent.getSerializableExtra(FILTER_DESCRIPTOR);
            if(desc != null)
                mFilter = desc;
        }
        if(mFilter == null)
            mFilter = new FilterDescriptor();

        mHideMasked = findViewById(R.id.not_hidden);
        mOnlyBlocked = findViewById(R.id.only_blocked);
        mOnlyBlacklisted = findViewById(R.id.only_blacklisted);
        mOnlyCleartext = findViewById(R.id.only_cleartext);
        mStatusOpen = findViewById(R.id.status_open);
        mStatusClosed = findViewById(R.id.status_closed);
        mStatusUnreachable = findViewById(R.id.status_unreachable);
        mStatusError = findViewById(R.id.status_error);
        mInterfaceGroup = findViewById(R.id.interfaces);

        findViewById(R.id.edit_mask).setOnClickListener(v -> {
            Intent editIntent = new Intent(this, EditListActivity.class);
            editIntent.putExtra(EditListActivity.LIST_TYPE_EXTRA, ListInfo.Type.VISUALIZATION_MASK);
            startActivity(editIntent);
        });

        Billing billing = Billing.newInstance(this);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if(!Prefs.isMalwareDetectionEnabled(this, prefs))
            mOnlyBlacklisted.setVisibility(View.GONE);

        if(!billing.isRedeemed(Billing.FIREWALL_SKU) || Prefs.isRootCaptureEnabled(prefs))
            mOnlyBlocked.setVisibility(View.GONE);

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

    @Override
    protected void onResume() {
        super.onResume();

        MatchList mask = PCAPdroid.getInstance().getVisualizationMask();
        findViewById(R.id.connections_mask).setVisibility(mask.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void model2view() {
        mHideMasked.setChecked(!mFilter.showMasked);
        mOnlyBlocked.setChecked(mFilter.onlyBLocked);
        mOnlyBlacklisted.setChecked(mFilter.onlyBlacklisted);
        mOnlyCleartext.setChecked(mFilter.onlyCleartext);

        mStatusOpen.setChecked(false);
        mStatusClosed.setChecked(false);
        mStatusUnreachable.setChecked(false);
        mStatusError.setChecked(false);

        Chip selected_status = null;
        switch(mFilter.status) {
            case STATUS_OPEN: selected_status = mStatusOpen; break;
            case STATUS_CLOSED: selected_status = mStatusClosed; break;
            case STATUS_UNREACHABLE: selected_status = mStatusUnreachable; break;
            case STATUS_ERROR: selected_status = mStatusError; break;
        }
        if(selected_status != null)
            selected_status.setChecked(true);

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
        mFilter.onlyBLocked = mOnlyBlocked.isChecked();
        mFilter.onlyBlacklisted = mOnlyBlacklisted.isChecked();
        mFilter.onlyCleartext = mOnlyCleartext.isChecked();

        if(mStatusOpen.isChecked())
            mFilter.status = Status.STATUS_OPEN;
        else if(mStatusClosed.isChecked())
            mFilter.status = Status.STATUS_CLOSED;
        else if(mStatusUnreachable.isChecked())
            mFilter.status = Status.STATUS_UNREACHABLE;
        else if(mStatusError.isChecked())
            mFilter.status = Status.STATUS_ERROR;
        else
            mFilter.status = Status.STATUS_INVALID;

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
    public void onBackPressed() {
        finishOk();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.edit_filter_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if(item.getItemId() == R.id.reset_changes) {
            mFilter = new FilterDescriptor();
            model2view();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
