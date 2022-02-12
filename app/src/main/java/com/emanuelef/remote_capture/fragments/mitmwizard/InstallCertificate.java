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

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.MitmAddon;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.cert.X509Certificate;

public class InstallCertificate extends StepFragment {
    private String mCaPem;
    private X509Certificate mCaCert;

    private final ActivityResultLauncher<Intent> mitmCtrlLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::mitmCtrlResult);
    private final ActivityResultLauncher<Intent> certFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::certFileResult);

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mStepLabel.setText(R.string.checking_the_certificate);
        mStepButton.setText(R.string.export_action);
        mStepButton.setEnabled(false);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClassName(MitmAddon.PACKAGE_NAME, MitmAddon.CONTROL_ACTIVITY);
        intent.putExtra(MitmAddon.ACTION_EXTRA, MitmAddon.ACTION_GET_CA_CERTIFICATE);

        try {
            mitmCtrlLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            mStepLabel.setText(R.string.no_intent_handler_found);
        }
    }

    @Override
    public void onResume() {
        if(Utils.isCAInstalled(mCaCert))
            certOk();

        super.onResume();
    }

    private void mitmCtrlResult(final ActivityResult result) {
        if((result.getResultCode() == Activity.RESULT_OK) && (result.getData() != null)) {
            Intent res = result.getData();
            mCaPem = res.getStringExtra(MitmAddon.CERTIFICATE_RESULT);

            if(mCaPem != null) {
                //Log.d(TAG, "certificate: " + cert_str);
                mCaCert = Utils.x509FromPem(mCaPem);

                if(mCaCert != null) {
                    if(Utils.isCAInstalled(mCaCert))
                        certOk();
                    else {
                        MitmAddon.setDecryptionSetupDone(requireContext(), false);
                        installCaCertificate();
                    }
                } else
                    certFail();
            }
        }
    }

    private void certOk() {
        MitmAddon.setDecryptionSetupDone(requireContext(), true);
        mStepLabel.setText(R.string.cert_installed_correctly);
        nextStep(0);
    }

    private void certFail() {
        mStepLabel.setText(R.string.ca_installation_failed);
        mStepIcon.setColorFilter(mDangerColor);
        MitmAddon.setDecryptionSetupDone(requireContext(), false);
    }

    private void installCaCertificate() {
        String fname = "PCAPdroid_CA.crt";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-x509-ca-cert");
        intent.putExtra(Intent.EXTRA_TITLE, fname);

        if(!Utils.supportsFileDialog(requireContext(), intent)) {
            certFail();
            return;
        }

        // Certificate not installed
        mStepIcon.setColorFilter(mWarnColor);
        mStepLabel.setText(R.string.install_ca_certificate);

        // TODO auto-install on older android versions
        mStepButton.setEnabled(true);
        mStepButton.setOnClickListener(v -> {
            try {
                certFileLauncher.launch(intent);
            } catch (ActivityNotFoundException e) {
                certFail();
            }
        });
    }

    private void certFileResult(final ActivityResult result) {
        if((result.getResultCode() == Activity.RESULT_OK) && (result.getData() != null)) {
            Context ctx = requireContext();
            Uri cert_uri = result.getData().getData();
            boolean written = false;

            try(PrintWriter writer = new PrintWriter(ctx.getContentResolver().openOutputStream(cert_uri))) {
                writer.print(mCaPem);
                written = true;
            } catch (IOException e) {
                e.printStackTrace();
            }

            if(written)
                Utils.showToastLong(ctx, R.string.cert_exported_now_installed);
        }
    }
}