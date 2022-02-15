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
import java.security.cert.X509Certificate;

public class InstallCertificate extends StepFragment implements MitmListener {
    private static final String TAG = "InstallCertificate";
    private MitmAddon mAddon;
    private String mCaPem;
    private X509Certificate mCaCert;

    private final ActivityResultLauncher<Intent> certFileLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::certFileResult);

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mStepLabel.setText(R.string.checking_the_certificate);
        mStepButton.setText(R.string.export_action);
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

    @Override
    public void onMitmGetCaCertificateResult(@Nullable String ca_pem) {
        mAddon.disconnect();
        mCaPem = ca_pem;

        if(mCaPem != null) {
            Log.d(TAG, "Got certificate");
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