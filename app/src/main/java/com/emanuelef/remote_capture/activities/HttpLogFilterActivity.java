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
package com.emanuelef.remote_capture.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.collection.ArraySet;
import androidx.core.view.MenuProvider;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.HttpLog;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.HttpLogFilterDescriptor;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class HttpLogFilterActivity extends BaseActivity implements MenuProvider {
    public static final String FILTER_DESCRIPTOR = "http_log_filter";
    private static final String TAG = "HttpLogFilterActivity";
    private HttpLogFilterDescriptor mFilter;
    private ChipGroup mMethodGroup;
    private ChipGroup mContentTypeGroup;
    private ChipGroup mHttpStatusGroup;
    private Slider mPayloadSizeSlider;
    private Chip mDecryptionErrorChip;
    private Chip mDecryptionNoErrorChip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.http_log_filter_activity);
        setTitle(R.string.edit_filter);
        addMenuProvider(this);

        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeAsUpIndicator(R.drawable.ic_close);
        }

        Intent intent = getIntent();
        if(intent != null) {
            HttpLogFilterDescriptor desc = Utils.getSerializableExtra(intent, FILTER_DESCRIPTOR, HttpLogFilterDescriptor.class);
            if(desc != null)
                mFilter = desc;
        }
        if(mFilter == null)
            mFilter = new HttpLogFilterDescriptor();

        mMethodGroup = findViewById(R.id.method_group);
        mContentTypeGroup = findViewById(R.id.content_type_group);
        mHttpStatusGroup = findViewById(R.id.http_status_group);
        mPayloadSizeSlider = findViewById(R.id.payload_size_slider);
        mDecryptionErrorChip = findViewById(R.id.decryption_error_chip);
        mDecryptionNoErrorChip = findViewById(R.id.decryption_no_error_chip);

        // Populate filters from captured data
        HttpLog httpLog = CaptureService.getHttpLog();
        if(httpLog != null) {
            ArraySet<String> methods = new ArraySet<>();
            Set<String> contentTypes = new HashSet<>();
            Set<Integer> httpStatuses = new HashSet<>();
            long maxPayloadSize = 0;
            boolean hasDecryptionErrors = false;

            synchronized (httpLog) {
                for (int i = 0; i < httpLog.size(); i++) {
                    HttpLog.HttpRequest req = httpLog.getRequest(i);
                    if (req != null) {
                        if (req.method != null && !req.method.isEmpty())
                            methods.add(req.method.toUpperCase());

                        if (req.reply != null) {
                            if (req.reply.contentType != null && !req.reply.contentType.isEmpty())
                                contentTypes.add(req.reply.contentType);
                            if (req.reply.responseCode > 0)
                                httpStatuses.add(req.reply.responseCode);

                            int totalSize = req.bodyLength + req.reply.bodyLength;
                            if (totalSize > maxPayloadSize)
                                maxPayloadSize = totalSize;
                        }

                        if (!req.decryptionError.isEmpty())
                            hasDecryptionErrors = true;
                    }
                }
            }

            // Create method chips
            if (!methods.isEmpty()) {
                LayoutInflater inflater = getLayoutInflater();
                ArrayList<String> sortedMethods = new ArrayList<>(methods);
                sortedMethods.sort(String::compareTo);

                for (String method : sortedMethods) {
                    Chip chip = (Chip) inflater.inflate(R.layout.choice_chip, mMethodGroup, false);
                    chip.setText(method);
                    mMethodGroup.addView(chip);
                }
                mMethodGroup.setVisibility(View.VISIBLE);
                findViewById(R.id.method_label).setVisibility(View.VISIBLE);
            }

            // Create content-type chips
            if (!contentTypes.isEmpty()) {
                LayoutInflater inflater = getLayoutInflater();
                for (String contentType : contentTypes) {
                    Chip chip = (Chip) inflater.inflate(R.layout.choice_chip, mContentTypeGroup, false);
                    chip.setText(contentType);
                    mContentTypeGroup.addView(chip);
                }
                mContentTypeGroup.setVisibility(View.VISIBLE);
                findViewById(R.id.content_type_label).setVisibility(View.VISIBLE);
            }

            // Create HTTP status chips
            if (!httpStatuses.isEmpty()) {
                LayoutInflater inflater = getLayoutInflater();
                ArrayList<Integer> sortedStatuses = new ArrayList<>(httpStatuses);
                sortedStatuses.sort(Integer::compareTo);

                for (Integer status : sortedStatuses) {
                    Chip chip = (Chip) inflater.inflate(R.layout.choice_chip, mHttpStatusGroup, false);
                    chip.setText(String.valueOf(status));
                    mHttpStatusGroup.addView(chip);
                }
                mHttpStatusGroup.setVisibility(View.VISIBLE);
                findViewById(R.id.http_status_label).setVisibility(View.VISIBLE);
            }

            // Setup payload size slider
            long minSizeKB = mFilter.minPayloadSize / 1024;
            long maxSizeKB = maxPayloadSize / 1024;
            maxSizeKB = Math.max(maxSizeKB, minSizeKB);

            if (maxSizeKB >= 2) {
                mPayloadSizeSlider.setValueTo(maxSizeKB);
                mPayloadSizeSlider.setLabelFormatter(value -> Utils.formatBytes(((long) value) * 1024));
                mPayloadSizeSlider.setVisibility(View.VISIBLE);
                findViewById(R.id.payload_size_label).setVisibility(View.VISIBLE);
            }
        }

        model2view();
    }

    private void model2view() {
        long minSizeKB = mFilter.minPayloadSize / 1024;
        if (minSizeKB > 0)
            mPayloadSizeSlider.setValue(minSizeKB);

        // Set decryption error filter
        if (mFilter.decryptionError != null) {
            if (mFilter.decryptionError) {
                mDecryptionErrorChip.setChecked(true);
            } else {
                mDecryptionNoErrorChip.setChecked(true);
            }
        }

        // Set method
        if(mFilter.method != null) {
            int num_chips = mMethodGroup.getChildCount();
            for(int i=0; i<num_chips; i++) {
                Chip chip = (Chip) mMethodGroup.getChildAt(i);
                if(chip.getText().toString().equalsIgnoreCase(mFilter.method)) {
                    chip.setChecked(true);
                    break;
                }
            }
        }

        // Set content type
        if(mFilter.contentType != null) {
            int num_chips = mContentTypeGroup.getChildCount();
            for(int i=0; i<num_chips; i++) {
                Chip chip = (Chip) mContentTypeGroup.getChildAt(i);
                if(chip.getText().equals(mFilter.contentType)) {
                    chip.setChecked(true);
                    break;
                }
            }
        }

        // Set HTTP status
        if(mFilter.httpStatus != null) {
            int num_chips = mHttpStatusGroup.getChildCount();
            for(int i=0; i<num_chips; i++) {
                Chip chip = (Chip) mHttpStatusGroup.getChildAt(i);
                if(chip.getText().equals(String.valueOf(mFilter.httpStatus))) {
                    chip.setChecked(true);
                    break;
                }
            }
        }
    }

    private void view2model() {
        mFilter.minPayloadSize = ((long) mPayloadSizeSlider.getValue()) * 1024;

        // Get decryption error filter
        if (mDecryptionErrorChip.isChecked()) {
            mFilter.decryptionError = true;
        } else if (mDecryptionNoErrorChip.isChecked()) {
            mFilter.decryptionError = false;
        } else {
            mFilter.decryptionError = null;
        }

        // Get method
        int num_chips = mMethodGroup.getChildCount();
        mFilter.method = null;
        for(int i=0; i<num_chips; i++) {
            Chip chip = (Chip) mMethodGroup.getChildAt(i);
            if(chip.isChecked()) {
                mFilter.method = chip.getText().toString();
                break;
            }
        }

        // Get content type
        num_chips = mContentTypeGroup.getChildCount();
        mFilter.contentType = null;
        for(int i=0; i<num_chips; i++) {
            Chip chip = (Chip) mContentTypeGroup.getChildAt(i);
            if(chip.isChecked()) {
                mFilter.contentType = chip.getText().toString();
                break;
            }
        }

        // Get HTTP status
        num_chips = mHttpStatusGroup.getChildCount();
        mFilter.httpStatus = null;
        for(int i=0; i<num_chips; i++) {
            Chip chip = (Chip) mHttpStatusGroup.getChildAt(i);
            if(chip.isChecked()) {
                try {
                    mFilter.httpStatus = Integer.parseInt(chip.getText().toString());
                } catch (NumberFormatException e) {
                    // ignore
                }
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
