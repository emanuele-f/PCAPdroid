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
import android.os.Build;
import android.os.Bundle;
import android.security.KeyChain;
import android.util.Log;
import android.view.View;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.interfaces.MitmListener;
import com.emanuelef.remote_capture.MitmAddon;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;

public class InstallCertificate extends StepFragment implements MitmListener {
    private static final String TAG = "InstallCertificate";
    private MitmAddon mAddon;
    private String mCaPem;
    private X509Certificate mCaCert;
    private boolean mFallbackExport;

    private final ActivityResultLauncher<Intent> certExportLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::certExportResult);
    private final ActivityResultLauncher<Intent> certInstallLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::certInstallResult);

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mStepLabel.setText(R.string.checking_the_certificate);
        mStepButton.setText(canInstallCertViaIntent() ? R.string.install_action : R.string.export_action);
        mStepButton.setEnabled(false);

        mAddon = new MitmAddon(requireContext(), this);
        if(!mAddon.connect(0))
            certFail();
    }

    @Override
    public void onDestroyView() {
        mAddon.disconnect();
        super.onDestroyView();
    }

    @Override
    public void onResume() {
        if(Utils.isCAInstalled(mCaCert))
            certOk();

        super.onResume();
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

    private boolean canInstallCertViaIntent() {
        // On Android < 11, an intent can be used for cert installation
        // On Android 11+, users must manually install the certificate from the settings
        return((Build.VERSION.SDK_INT < Build.VERSION_CODES.R) && !mFallbackExport);
    }

    private void fallbackToCertExport() {
        // If there are problems with the cert installation via Intent, fallback to export+install
        mFallbackExport = true;
        onMitmGetCaCertificateResult(mCaPem);
    }

    private void exportCaCertificate() {
        String fname = "PCAPdroid_CA.crt";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-x509-ca-cert");
        intent.putExtra(Intent.EXTRA_TITLE, fname);

        if(!Utils.supportsFileDialog(requireContext(), intent)) {
            Utils.showToastLong(requireContext(), R.string.no_activity_file_selection);
            certFail();
            return;
        }

        try {
            certExportLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Utils.showToastLong(requireContext(), R.string.no_activity_file_selection);
            certFail();
        }
    }

    private void installCaCertificate() {
        Intent intent = KeyChain.createInstallIntent();
        intent.putExtra(KeyChain.EXTRA_NAME, "PCAPdroid CA");
        intent.putExtra(KeyChain.EXTRA_CERTIFICATE, mCaPem.getBytes(StandardCharsets.UTF_8));

        try {
            certInstallLauncher.launch(intent);
        } catch (ActivityNotFoundException e) {
            Utils.showToastLong(requireContext(), R.string.no_intent_handler_found);
            fallbackToCertExport();
        }
    }

    private void certExportResult(final ActivityResult result) {
        if((result.getResultCode() == Activity.RESULT_OK) && (result.getData() != null)) {
            Context ctx = requireContext();
            Uri cert_uri = result.getData().getData();
            boolean written = false;

            try(PrintWriter writer = new PrintWriter(ctx.getContentResolver().openOutputStream(cert_uri, "rwt"))) {
                writer.print(mCaPem);
                written = true;
            } catch (IOException e) {
                e.printStackTrace();
            }

            if(written)
                Utils.showToastLong(ctx, R.string.cert_exported_now_installed);
        }
    }

    private void certInstallResult(final ActivityResult result) {
        if((result.getResultCode() == Activity.RESULT_OK) && Utils.isCAInstalled(mCaCert))
            certOk();
        else
            fallbackToCertExport();
    }

    @Override
    public void onMitmGetCaCertificateResult(@Nullable String ca_pem) {
        mAddon.disconnect();
        mCaPem = ca_pem;

        // NOTE: onMitmGetCaCertificateResult can be called by fallbackToCertExport
        mStepButton.setText(canInstallCertViaIntent() ? R.string.install_action : R.string.export_action);

        if(mCaPem != null) {
            Log.d(TAG, "Got certificate");
            //Log.d(TAG, "certificate: " + cert_str);
            mCaCert = Utils.x509FromPem(mCaPem);

            if(mCaCert != null) {
                if(Utils.isCAInstalled(mCaCert))
                    certOk();
                else {
                    // Cert not installed
                    MitmAddon.setDecryptionSetupDone(requireContext(), false);
                    mStepIcon.setColorFilter(mWarnColor);
                    mStepButton.setEnabled(true);

                    if(canInstallCertViaIntent())
                        mStepLabel.setText(R.string.install_ca_certificate);
                    else
                        mStepLabel.setText(R.string.export_ca_certificate);

                    mStepButton.setOnClickListener((v) -> {
                        if(canInstallCertViaIntent())
                            installCaCertificate();
                        else
                            exportCaCertificate();
                    });
                }
            } else
                certFail();
        }
    }

    @Override
    public void onMitmServiceConnect() {
        if(!mAddon.requestCaCertificate())
            certFail();
    }

    @Override
    public void onMitmServiceDisconnect() {
        if(mCaPem == null)
            certFail();
    }
}