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

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.text.HtmlCompat;
import androidx.core.view.MenuProvider;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.MitmAddon;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.Prefs;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

import androidmads.library.qrgenearator.QRGContents;
import androidmads.library.qrgenearator.QRGEncoder;

public class AboutActivity extends BaseActivity implements MenuProvider {
    private static final String TAG = "AboutActivity";
    private ExecutorService mQrReqExecutor;
    private HttpsURLConnection mQrCon;
    private boolean mDialogClosing = false;
    private long mQrStartTime = 0;
    private long mQrDeadline = 0;
    private Handler mHandler;
    private AlertDialog mLicenseDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.about);
        setContentView(R.layout.about_activity);
        addMenuProvider(this);

        mHandler = new Handler(Looper.getMainLooper());
        TextView appVersion = findViewById(R.id.app_version);
        appVersion.setText("PCAPdroid " + Utils.getAppVersion(this));

        ((TextView)findViewById(R.id.app_license)).setMovementMethod(LinkMovementMethod.getInstance());
        ((TextView)findViewById(R.id.opensource_licenses)).setMovementMethod(LinkMovementMethod.getInstance());

        TextView sourceLink = findViewById(R.id.app_source_link);
        String localized = sourceLink.getText().toString();
        sourceLink.setText(HtmlCompat.fromHtml("<a href='" + MainActivity.GITHUB_PROJECT_URL + "'>" + localized + "</a>", HtmlCompat.FROM_HTML_MODE_LEGACY));
        sourceLink.setMovementMethod(LinkMovementMethod.getInstance());
    }

    @Override
    protected void onStop() {
        stopQrExecutor();
        super.onStop();
    }

    private void stopQrExecutor() {
        // necessary to interrupt the executor thread
        if(mQrCon != null)
            mQrCon.disconnect();
        mQrCon = null;

        if(mQrReqExecutor != null)
            mQrReqExecutor.shutdownNow();
        mQrReqExecutor = null;

        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.about_menu, menu);

        Billing billing = Billing.newInstance(this);
        if(billing.isPlayStore())
            menu.findItem(R.id.paid_features).setVisible(false);
    }

    @Override
    public boolean onMenuItemSelected(MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.paid_features) {
            showLicenseDialog();
            return true;
        } else if(id == R.id.on_boarding) {
            Intent intent = new Intent(this, OnBoardingActivity.class);
            intent.putExtra(OnBoardingActivity.ENABLE_BACK_BUTTON, true);
            startActivity(intent);
            return true;
        } else if(id == R.id.build_info) {
            String deviceInfo = Utils.getBuildInfo(this) + "\n\n" +
                    Prefs.asString(this);

            // Private DNS
            Utils.PrivateDnsMode dns_mode = CaptureService.getPrivateDnsMode();
            if(dns_mode == null) {
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ConnectivityManager cm = (ConnectivityManager) getSystemService(Service.CONNECTIVITY_SERVICE);
                    Network net = cm.getActiveNetwork();

                    if(net != null) {
                        LinkProperties lp = cm.getLinkProperties(net);
                        if (lp != null)
                            dns_mode = Utils.getPrivateDnsMode(lp);
                    }
                }
            }

            if(dns_mode != null)
                deviceInfo += "\n" + "PrivateDnsMode: " + dns_mode;

            // Mitm doze
            deviceInfo += "\n" + "MitmBatteryOptimized: " + ((MitmAddon.isInstalled(this) && MitmAddon.isDozeEnabled(this)) ? "true" : "false");

            LayoutInflater inflater = LayoutInflater.from(this);
            View view = inflater.inflate(R.layout.scrollable_dialog, null);
            ((TextView)view.findViewById(R.id.text)).setText(deviceInfo);

            final String deviceInfoStr = deviceInfo;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.build_info)
                    .setView(view)
                    .setPositiveButton(R.string.ok, (dialogInterface, i) -> {})
                    .setNeutralButton(R.string.copy_to_clipboard, (dialogInterface, i) ->
                            Utils.copyToClipboard(this, deviceInfoStr)).show();
            return true;
        }

        return false;
    }

    private void showLicenseDialog() {
        Billing billing = Billing.newInstance(this);
        LayoutInflater inflater = getLayoutInflater();
        final View content = inflater.inflate(R.layout.license_dialog, null);

        String instId = billing.getInstallationId();
        TextView instIdText = content.findViewById(R.id.installation_id);
        instIdText.setText(instId);

        mDialogClosing = false;
        final View showQr = content.findViewById(R.id.show_qr_code);
        showQr.setOnClickListener(v -> showQrCode(content, instId));

        if(Utils.isTv(this) && !billing.isPurchased(Billing.SUPPORTER_SKU)) {
            instIdText.setOnClickListener(v -> Utils.shareText(this, getString(R.string.installation_id), instId));
            showQrCode(content, instId);
        }

        TextView validationRc = content.findViewById(R.id.validation_rc);
        EditText licenseCode = content.findViewById(R.id.license_code);
        licenseCode.setText(billing.getLicense());
        Utils.setTextUrls((content.findViewById(R.id.paid_features_msg)), R.string.access_paid_features_msg, MainActivity.PAID_FEATURES_URL);

        content.findViewById(R.id.copy_id).setOnClickListener(v -> Utils.copyToClipboard(this, instId));

        mLicenseDialog = new AlertDialog.Builder(this)
                .setView(content)
                .setPositiveButton(R.string.ok, (dialog, whichButton) -> {
                    boolean was_valid = billing.isPurchased(Billing.SUPPORTER_SKU);
                    billing.setLicense(licenseCode.getText().toString());

                    if(!was_valid && billing.isPurchased(Billing.SUPPORTER_SKU))
                        Utils.showToastLong(this, R.string.paid_features_unlocked);
                })
                .setOnDismissListener(dialog -> {
                    mDialogClosing = true;
                    mLicenseDialog = null;
                    stopQrExecutor();
                })
                .setNeutralButton(R.string.validate, (dialog, which) -> {}) // see below
                .create();

        mLicenseDialog.show();
        mLicenseDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            boolean valid = billing.isValidLicense(licenseCode.getText().toString());
            validationRc.setText(valid ? R.string.valid : R.string.invalid);
            validationRc.setTextColor(ContextCompat.getColor(this, valid ? R.color.ok : R.color.danger));
        });
        mLicenseDialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void showQrCode(View dialog, String instId) {
        View qrBox = dialog.findViewById(R.id.qr_box);
        View qrLoading = dialog.findViewById(R.id.qr_code_loading);
        View showQr = dialog.findViewById(R.id.show_qr_code);
        View qrInfo = dialog.findViewById(R.id.qr_info_text);

        showQr.setVisibility(View.GONE);
        qrLoading.setVisibility(View.VISIBLE);
        qrBox.setVisibility(View.GONE);
        qrInfo.setVisibility(View.GONE);

        mQrReqExecutor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());

        // start activation
        mQrReqExecutor.execute(() -> {
            try {
                URL url = new URL(Utils.PCAPDROID_WEBSITE + "/getlicense/qr_activation");
                HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
                mQrCon = con;

                try {
                    con.setRequestProperty("User-Agent", Utils.getAppVersionString());
                    con.setRequestMethod("POST");
                    con.setUseCaches(false);
                    con.setAllowUserInteraction(false);
                    con.setDoInput(true);
                    con.setDoOutput(true);
                    con.setConnectTimeout(5000);

                    // Send POST request
                    try (BufferedOutputStream os = new BufferedOutputStream(con.getOutputStream())) {
                        os.write(("installation_id=" + instId).getBytes());
                    }

                    int rc = con.getResponseCode();
                    Log.d(TAG, "QR HTTP response: " + rc);
                    if (rc != 200) {
                        handler.post(() ->
                                hideQrCode(dialog, "QR request failed with code " + rc));
                        return;
                    }

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                        // Step 1: get QR request ID
                        String timeout_s = parseSseLine(reader.readLine());
                        String qr_req_id = parseSseLine(reader.readLine());
                        if ((qr_req_id == null) || (timeout_s == null)) {
                            handler.post(() ->
                                    hideQrCode(dialog, "Invalid QR request ID"));
                            return;
                        }
                        int timeout_ms = Integer.parseInt(timeout_s) * 1000;
                        long deadline = SystemClock.elapsedRealtime() + timeout_ms;
                        Log.d(TAG, "QR request_id=" + qr_req_id + ", timeout=" + timeout_ms + " ms");

                        // Step 2: generate QR code
                        Bitmap qrBitmap = genQrCode(instId, qr_req_id);
                        handler.post(() -> onQrRequestReady(dialog, qrBitmap, deadline));

                        // Step 3: wait license
                        String license = parseSseLine(reader.readLine());
                        if(license == null) {
                            handler.post(() ->
                                hideQrCode(dialog, getString(R.string.qr_code_expired)));
                            return;
                        }
                        handler.post(() -> onQrLicenseReceived(dialog, license));
                    }
                } finally {
                    con.disconnect();
                }
            } catch (IOException | NumberFormatException e) {
                e.printStackTrace();

                handler.post(() -> {
                    if(e instanceof EOFException)
                        hideQrCode(dialog, getString(R.string.qr_code_expired));
                    else
                        hideQrCode(dialog, getString(R.string.connection_error, e.getMessage()));
                });
            }
        });
    }

    private String parseSseLine(String line) {
        if(line == null)
            return null;

        if(line.startsWith("data: "))
            line = line.substring(6);
        return line;
    }

    private Bitmap genQrCode(String instId, String qrReqId) {
        float maxDp = 180f;
        int maxPx = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                maxDp,
                getResources().getDisplayMetrics()
        );
        int smallerDimension = Math.min(Utils.getSmallerDisplayDimension(this) / 2, maxPx);

        String device_name = Utils.getDeviceName(this);
        if(device_name == null)
            device_name = Utils.getDeviceModel();

        String qrData = "pcapdroid://get_license?installation_id="+ instId +"&qr_request_id=" + qrReqId + "&device=" + Uri.encode(device_name);
        Log.d(TAG, "QR activation URI: " + qrData);

        QRGEncoder qrgEncoder = new QRGEncoder(qrData, null, QRGContents.Type.TEXT, smallerDimension);
        return qrgEncoder.getBitmap(0);
    }

    private void onQrRequestReady(View dialog, Bitmap qrcode, long deadline) {
        View qrBox = dialog.findViewById(R.id.qr_box);
        ImageView qrImage = dialog.findViewById(R.id.qr_code);
        View qrLoading = dialog.findViewById(R.id.qr_code_loading);
        View qrInfo = dialog.findViewById(R.id.qr_info_text);

        mQrStartTime = SystemClock.elapsedRealtime();
        mQrDeadline = deadline;
        updateQrProgress(dialog);

        qrImage.setImageBitmap(qrcode);
        qrBox.setVisibility(View.VISIBLE);
        qrInfo.setVisibility(View.VISIBLE);
        qrLoading.setVisibility(View.GONE);
    }

    private void updateQrProgress(View dialog) {
        ProgressBar qrProgress = dialog.findViewById(R.id.qr_remaining_time);
        if(qrProgress == null)
            return;

        long interval = mQrDeadline - mQrStartTime;
        int progress = Math.min((int)((SystemClock.elapsedRealtime() - mQrStartTime) * 100 / interval), 100);
        qrProgress.setProgress(100 - progress);

        mHandler.postDelayed(() -> updateQrProgress(dialog), 1000);
    }

    private void onQrLicenseReceived(View dialog, String license) {
        EditText licenseCode = dialog.findViewById(R.id.license_code);
        Billing billing = Billing.newInstance(this);
        boolean was_valid = billing.isPurchased(Billing.SUPPORTER_SKU);

        if(billing.setLicense(license)) {
            licenseCode.setText(license);

            Utils.showToast(this, R.string.license_activation_ok);
            if(!was_valid)
                Utils.showToastLong(this, R.string.paid_features_unlocked);

            hideQrCode(dialog, null);
            if(mLicenseDialog != null)
                mLicenseDialog.dismiss();
        } else
            hideQrCode(dialog, getString(R.string.invalid_license));
    }

    private void hideQrCode(View dialog, @Nullable String error_msg) {
        View showQr = dialog.findViewById(R.id.show_qr_code);
        View qrLoading = dialog.findViewById(R.id.qr_code_loading);
        View qrBox = dialog.findViewById(R.id.qr_box);
        View qrInfo = dialog.findViewById(R.id.qr_info_text);

        qrBox.setVisibility(View.GONE);
        qrInfo.setVisibility(View.GONE);
        qrLoading.setVisibility(View.GONE);
        showQr.setVisibility(View.VISIBLE);

        if((error_msg != null) && !mDialogClosing)
            Toast.makeText(this, error_msg, Toast.LENGTH_LONG).show();

        stopQrExecutor();
    }
}
