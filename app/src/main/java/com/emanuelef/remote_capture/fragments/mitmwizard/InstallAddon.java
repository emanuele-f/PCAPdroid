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

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.view.View;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.MitmAddon;

public class InstallAddon extends StepFragment {
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mStepLabel.setText(R.string.install_mitm_addon);

        if(MitmAddon.isInstalled(requireContext()))
            addonOk();
        else
            installAddon();
    }

    private void addonOk() {
        nextStep(R.id.navto_grant_permission);
    }

    private void installAddon() {
        mStepLabel.setText(R.string.install_mitm_addon);
        mStepButton.setText(R.string.install_action);
        mStepButton.setOnClickListener(v -> {
            // TODO install
            if(MitmAddon.isInstalled(requireContext()))
                addonOk();
        });
    }
}