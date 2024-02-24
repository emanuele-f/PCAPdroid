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
 * Copyright 2020-24 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.activities;

import android.content.Intent;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.adapters.PayloadAdapter;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;

public class PayloadExportActivity extends BaseActivity implements PayloadAdapter.ExportPayloadHandler {
    private String mStringPayloadToExport;
    private byte[] mRawPayloadToExport;

    private final ActivityResultLauncher<Intent> payloadExportLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::payloadExportResult);

    @Override
    public void exportPayload(String payload) {
        mStringPayloadToExport = payload;
        mRawPayloadToExport = null;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, Utils.getUniqueFileName(this, "txt"));

        Utils.launchFileDialog(this, intent, payloadExportLauncher);
    }

    @Override
    public void exportPayload(byte[] payload, String contentType, String fname) {
        mStringPayloadToExport = null;
        mRawPayloadToExport = payload;

        if (fname.isEmpty()) {
            String ext;

            switch (contentType) {
                case "text/html":
                    ext = "html";
                    break;
                case "application/octet-stream":
                    ext = "bin";
                    break;
                case "application/json":
                    ext = "json";
                    break;
                default:
                    ext = "txt";
            }

            fname = Utils.getUniqueFileName(this, ext);
        }

        /* This is an unmapped mime type, which allows the user to specify the file,
         * extension instead of Android forcing it, see
         * https://android.googlesource.com/platform/external/mime-support/+/fa3f892f28db393b1411f046877ee48179f6a4cf/mime.types */
        final String generic_mime = "application/http";

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(generic_mime);
        intent.putExtra(Intent.EXTRA_TITLE, fname);

        Utils.launchFileDialog(this, intent, payloadExportLauncher);
    }

    private void payloadExportResult(final ActivityResult result) {
        if ((mRawPayloadToExport == null) && (mStringPayloadToExport == null))
            return;

        if((result.getResultCode() == RESULT_OK) && (result.getData() != null) && (result.getData().getData() != null)) {
            try(OutputStream out = getContentResolver().openOutputStream(result.getData().getData(), "rwt")) {
                if (out != null) {
                    if (mStringPayloadToExport != null) {
                        try (OutputStreamWriter writer = new OutputStreamWriter(out)) {
                            writer.write(mStringPayloadToExport);
                        }
                    } else
                        out.write(mRawPayloadToExport);
                    Utils.showToast(this, R.string.save_ok);
                } else
                    Utils.showToastLong(this, R.string.export_failed);
            } catch (IOException e) {
                e.printStackTrace();
                Utils.showToastLong(this, R.string.export_failed);
            }
        }

        mRawPayloadToExport = null;
        mStringPayloadToExport = null;
    }
}
