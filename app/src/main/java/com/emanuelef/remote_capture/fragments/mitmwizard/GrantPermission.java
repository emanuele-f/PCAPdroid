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
 * Copyright 2022 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.fragments.mitmwizard;

import android.content.ActivityNotFoundException;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.MitmAddon;

public class GrantPermission extends StepFragment {
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), this::onPermissionGrant);

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mStepLabel.setText(R.string.grant_mitm_permission);

        if(!MitmAddon.hasMitmPermission(requireContext()))
            requestPermission();
        else
            permOk();
    }

    private void permOk() {
        nextStep(R.id.navto_install_cert);
    }

    private void onPermissionGrant(boolean isGranted) {
        if(isGranted)
            permOk();
    }

    private void requestPermission() {
        mStepButton.setText(R.string.configure_action);
        mStepButton.setOnClickListener(v -> {
            try {
                requestPermissionLauncher.launch(MitmAddon.MITM_PERMISSION);
            } catch (ActivityNotFoundException e) {
                Utils.showToastLong(requireContext(), R.string.no_intent_handler_found);
            }
        });
    }
}