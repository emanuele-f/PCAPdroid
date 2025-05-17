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
 * Copyright 2020-22 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.collection.ArraySet;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.Purchase.PurchaseState;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.emanuelef.remote_capture.model.SkusAvailability;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PlayBilling extends Billing implements BillingClientStateListener, PurchasesUpdatedListener, SkuDetailsResponseListener {
    public static final String TAG = "PlayBilling";
    private static final String PREF_LAST_UNLOCK_TOKEN = "unlock_token";
    private static final String LICENSE_GEN_URL = "https://pcapdroid.org/getlicense";
    private final Handler mHandler;
    private final ArrayMap<String, SkuDetails> mDetails;
    private final ArrayMap<String, String> mSkuToPurchToken;
    private BillingClient mBillingClient;
    private PurchaseReadyListener mListener;
    private boolean mWaitingStart;
    private Thread mRequestTokenThread = null;
    private static boolean mPendingNoticeShown = false; // static to make it work across the app
    private final SkusAvailability mAvailability;
    private static Utils.BuildType mVerifiedBuildType = null;
    private ExecutorService mQrActivationExecutor;
    private QrActivationRequest mPendingQrRequest;

    /** setPurchaseListener() -> connectBilling() -> PurchaseReadyListener.onPurchasesReady()
     *   -> the client can now call purchase
     *   -> PurchaseReadyListener.onSKUStateUpdate() may be called in the future
     *
     *  Clear billing cache: adb shell pm clear com.android.vending
     */
    public interface PurchaseReadyListener {
        default void onPurchasesReady() {}
        default void onPurchasesError() {}
        default void onSKUStateUpdate(String sku, int state) {}
    }

    public static class QrActivationRequest {
        public String installation_id;
        public String qr_request_id;
        public String device_name;

        boolean isValid() {
            return (installation_id != null) && (qr_request_id != null) && (device_name != null);
        }
    }

    public PlayBilling(Context ctx) {
        super(ctx);
        mHandler = new Handler(Looper.getMainLooper());
        mDetails = new ArrayMap<>();
        mSkuToPurchToken = new ArrayMap<>();
        mAvailability = SkusAvailability.load(mPrefs);
        mWaitingStart = false;
    }

    public static String purchstate2Str(int state) {
        switch (state) {
            case PurchaseState.PENDING: return "PENDING";
            case PurchaseState.PURCHASED: return "PURCHASED";
            case PurchaseState.UNSPECIFIED_STATE: return "UNSPECIFIED";
        }
        return "UNKNOWN";
    }

    private void processPurchases(BillingResult billingResult, @Nullable List<Purchase> purchases) {
        if((billingResult.getResponseCode() == BillingResponseCode.OK) && (purchases != null)) {
            ArraySet<String> purchased = new ArraySet<>();
            boolean show_toast = true;
            mSkuToPurchToken.clear();

            for(Purchase purchase : purchases) {
                boolean newPurchase = false;

                for(String sku: purchase.getSkus()) {
                    Log.d(TAG, "\tPurchase: " + sku + " -> " + purchstate2Str(purchase.getPurchaseState()));

                    switch (purchase.getPurchaseState()) {
                        case PurchaseState.PENDING:
                            if(!mPendingNoticeShown) {
                                mHandler.post(() -> Utils.showToastLong(mContext, R.string.pending_transaction));
                                mPendingNoticeShown = true;
                            }

                            if(!mWaitingStart)
                                // NOTE: using mHandler.post because otherwise any exceptions are caught (and hidden) by the billing library!
                                mHandler.post(() -> {
                                    if(mListener != null)
                                        mListener.onSKUStateUpdate(sku, PurchaseState.PENDING);
                                });
                            break;
                        case PurchaseState.PURCHASED:
                            if(sku.equals(Billing.UNLOCK_TOKEN_SKU)) {
                                Log.d(TAG, "Purchased unlock token: " + purchase.getPurchaseToken() + " - " + purchase.getOrderId());

                                // It will be consumed as soon as we get an unlock token
                                // purchase sku -> get unlock token -> consume sku -> use the token
                                if(mRequestTokenThread == null) {
                                    mRequestTokenThread = new Thread(() -> requestUnlockToken(purchase.getPurchaseToken()), "RequestUnlockToken");
                                    mRequestTokenThread.start();
                                    mHandler.post(() -> Utils.showToast(mContext, R.string.requesting_unlock_token));
                                }
                            } else if(!isPurchased(sku) && setPurchased(sku, true)) {
                                newPurchase = true;
                                Log.d(TAG, "New purchase: " + sku);

                                if(show_toast) {
                                    mHandler.post(() -> Utils.showToastLong(mContext, R.string.can_use_purchased_feature));
                                    show_toast = false;
                                }

                                if(!mWaitingStart) {
                                    mHandler.post(() -> {
                                        if (mListener != null)
                                            mListener.onSKUStateUpdate(sku, PurchaseState.PURCHASED);
                                    });
                                }
                            }

                            mSkuToPurchToken.put(sku, purchase.getPurchaseToken());
                            purchased.add(sku);
                            break;
                        case PurchaseState.UNSPECIFIED_STATE:
                            break;
                    }
                }

                if (newPurchase && !purchase.isAcknowledged()) {
                    Log.d(TAG, "Calling acknowledgePurchase on order " + purchase.getOrderId());

                    AcknowledgePurchaseParams acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.getPurchaseToken())
                            .build();

                    if (mBillingClient != null)
                        mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, billingResult1 ->
                                Log.d(TAG, "acknowledgePurchase: " + billingResult1.getResponseCode() + " " + billingResult1.getDebugMessage()));
                }
            }

            // Check for voided purchases (e.g. due to a refund)
            for(String sku: ALL_SKUS) {
                if(!purchased.contains(sku) && isPurchased(sku)) {
                    Log.w(TAG, "Previously purchased SKU " + sku + " was voided");

                    if(setPurchased(sku, false) && !mWaitingStart)
                        mHandler.post(() -> {
                            if(mListener != null)
                                mListener.onSKUStateUpdate(sku, PurchaseState.UNSPECIFIED_STATE);
                        });
                }
            }
        }

        if(mWaitingStart) {
            if(billingResult.getResponseCode() == BillingResponseCode.OK)
                mHandler.post(() -> {
                    if(mListener != null)
                        mListener.onPurchasesReady();
                });
            else
                onPurchasesError(billingResult);

            mWaitingStart = false;
        }
    }

    private void onPurchasesError(@NonNull BillingResult billingResult) {
        Log.e(TAG, "Billing returned error " + billingResult + ", disconnecting");

        mHandler.post(() -> {
            if(mListener != null)
                mListener.onPurchasesError();
        });

        disconnectBilling();
    }

    @Override
    public void onSkuDetailsResponse(@NonNull BillingResult billingResult, @Nullable List<SkuDetails> list) {
        Log.d(TAG, "onSkuDetailsResponse: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage());

        if((billingResult.getResponseCode() == BillingResponseCode.OK) && (list != null)) {
            mAvailability.update(list, mPrefs);
            Log.d(TAG, "Num available SKUs: " + list.size());

            mDetails.clear();
            for(SkuDetails sku: list) {
                //Log.d(TAG, "Available: " + sku);
                mDetails.put(sku.getSku(), sku);
            }

            mBillingClient.queryPurchasesAsync(BillingClient.SkuType.INAPP, (billingResult1, purchases) -> {
                Log.d(TAG, "queryPurchasesAsync: " + billingResult1.getResponseCode() + " " + billingResult1.getDebugMessage());
                processPurchases(billingResult1, purchases);
            });
        } else
            onPurchasesError(billingResult);
    }

    @Override
    public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
        Log.d(TAG, "onBillingSetupFinished: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage());

        if(billingResult.getResponseCode() ==  BillingResponseCode.OK) {
            SkuDetailsParams.Builder builder = SkuDetailsParams.newBuilder()
                    .setType(BillingClient.SkuType.INAPP)
                    .setSkusList(ALL_SKUS);

            mBillingClient.querySkuDetailsAsync(builder.build(), this);
        } else
            onPurchasesError(billingResult);
    }

    @Override
    public void onBillingServiceDisconnected() {
        Log.w(TAG, "onBillingServiceDisconnected");

        // Reconnect
        mHandler.postDelayed(() -> {
            if(mBillingClient != null)
                mBillingClient.startConnection(PlayBilling.this);
        }, 5000);
    }

    public void setPurchaseReadyListener(PurchaseReadyListener listener) {
        mListener = listener;
    }

    /*
     * connectBilling -> onBillingSetupFinished -> querySkuDetailsAsync -> queryPurchasesAsync -> processPurchases
     * Starts the connection to Google Play.
     * IMPORTANT: the client must call disconnectBilling to prevent leaks
     * */
    @Override
    public void connectBilling() {
        if(!verifiedBuild())
            return;

        mWaitingStart = true;

        if(mBillingClient != null)
            return;

        mBillingClient = BillingClient.newBuilder(mContext)
                .setListener(this)
                .enablePendingPurchases()
                .build();

        // Will call onBillingSetupFinished when ready
        mBillingClient.startConnection(this);
    }

    @Override
    public void disconnectBilling() {
        // Use post to avoid unsetting one of the variables below while a client is working on them
        mHandler.post(() -> {
            if(mBillingClient != null) {
                mBillingClient.endConnection();
                mBillingClient = null;
            }

            if(mQrActivationExecutor != null) {
                mQrActivationExecutor.shutdownNow();
                mQrActivationExecutor = null;
            }
        });
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, @Nullable List<Purchase> purchases) {
        Log.d(TAG, "onPurchasesUpdated: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage());
        processPurchases(billingResult, purchases);
    }

    /* For testing purposes */
    public void consumePurchase(String sku) {
        String token = mSkuToPurchToken.get(sku);
        if(token == null) {
            mHandler.post(() ->Toast.makeText(mContext, "Purchase token not found", Toast.LENGTH_SHORT).show());
            return;
        }

        mBillingClient.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(token).build(), (billingResult, s) ->
                Log.d(TAG, "consumeAsync response: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage()));
    }

    private String sku2pref(String sku) {
        return "SKU:" + sku;
    }

    private boolean verifiedBuild() {
        if(mVerifiedBuildType == null)
            mVerifiedBuildType = Utils.getVerifiedBuild(mContext);

        // detect apk modding tools
        return mVerifiedBuildType.equals(Utils.BuildType.PLAYSTORE);
    }

    /* NOTE: this is also used to determine if billing is available */
    @Override
    public boolean isAvailable(String sku) {
        if(!verifiedBuild())
            return false;

        // mAvailability acts as a persistent cache that can be used before the billing connection
        // is established
        return mAvailability.isAvailable(sku);
    }

    @Override
    public boolean isPlayStore() {
        return true;
    }

    @Override
    public boolean setLicense(String license) { return false; }

    public boolean isPurchased(String sku) {
        // one-use items
        if(sku.equals(UNLOCK_TOKEN_SKU))
            return false;

        if(!(sku.equals(SUPPORTER_SKU)) && isPurchased(SUPPORTER_SKU))
            return true;

        long purchaseTime = mPrefs.getLong(sku2pref(sku), 0);
        return(purchaseTime != 0);
    }

    public boolean setPurchased(String sku, boolean purchased) {
        SharedPreferences.Editor editor = mPrefs.edit();
        String key = sku2pref(sku);

        if(purchased)
            editor.putLong(key, System.currentTimeMillis());
        else
            editor.remove(key);

        editor.apply();
        return true;
    }

    @Nullable
    public SkuDetails getSkuDetails(String sku) {
        return mDetails.get(sku);
    }

    public boolean purchase(Activity activity, String sku) {
        if((mBillingClient == null) || (!mBillingClient.isReady())) {
            mHandler.post(() -> Utils.showToast(mContext, R.string.billing_connecting));
            return false;
        }

        SkuDetails details = mDetails.get(sku);
        if(details == null) {
            mHandler.post(() -> Utils.showToast(mContext, R.string.feature_not_available));
            return false;
        }

        Log.d(TAG, "Starting purchasing SKU " + sku);

        BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
                .setSkuDetails(details)
                .build();

        // will call onPurchasesUpdated when done
        mPendingNoticeShown = false;
        BillingResult res = mBillingClient.launchBillingFlow(activity, billingFlowParams);
        Log.d(TAG, "BillingFlow result: " + res.getResponseCode() + " " + res.getDebugMessage());

        return(res.getResponseCode() == BillingResponseCode.OK);
    }

    private void requestUnlockToken(String purchaseToken) {
        if (mBillingClient == null) {
            Log.e(TAG, "Billing disconnected, requesting an unlock token cannot proceed");
            return;
        }

        Log.i(TAG, "Requesting an unlock token...");

        try {
            URL url = new URL(LICENSE_GEN_URL + "/token?purchase_token=" +
                    URLEncoder.encode(purchaseToken, StandardCharsets.US_ASCII.toString()));

            HttpURLConnection con = (HttpURLConnection) url.openConnection();

            try {
                // Necessary otherwise the connection will stay open
                con.setRequestProperty("Connection", "Close");
                con.setRequestProperty("User-Agent", Utils.getAppVersionString());
                con.setRequestMethod("POST");
                int code = con.getResponseCode();
                Log.d(TAG, "requestUnlockToken: " + code);

                StringBuilder builder = new StringBuilder();
                try(BufferedReader br = new BufferedReader(
                        new InputStreamReader((code >= 400) ? con.getErrorStream() : con.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null)
                        builder.append(line);
                }

                String token = builder.toString();

                if((code != 200) || token.isEmpty()) {
                    Log.i(TAG, "requestUnlockToken error [" + code + "]: " + token);

                    mHandler.post(() -> {
                        String msg = mContext.getString(R.string.unlock_token_error, code, token) + "\n\nPurchase Token:" + purchaseToken;
                        AlertDialog.Builder abuilder = new AlertDialog.Builder(mContext);
                        abuilder.setTitle(R.string.error);
                        abuilder.setMessage(msg);
                        abuilder.setCancelable(true);
                        abuilder.setNeutralButton(R.string.copy_to_clipboard, (dialog, which) -> Utils.copyToClipboard(mContext, msg));
                        abuilder.setPositiveButton(R.string.ok, (dialog, id1) -> {});

                        AlertDialog alert = abuilder.create();
                        alert.setCanceledOnTouchOutside(false);
                        alert.show();
                    });
                    return;
                }

                Log.i(TAG, "requestUnlockToken: " + token);

                // Success, consume the purchase
                mBillingClient.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build(), (billingResult, s) -> {
                        Log.d(TAG, "consumeAsync[unlockToken] response: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage());
                        if(billingResult.getResponseCode() == BillingResponseCode.OK) {
                            mHandler.post(() -> {
                                if (mListener != null)
                                    // to purchase it again
                                    mListener.onSKUStateUpdate(Billing.UNLOCK_TOKEN_SKU, PurchaseState.UNSPECIFIED_STATE);

                                mPrefs.edit().putString(PREF_LAST_UNLOCK_TOKEN, token).apply();

                                if(mPendingQrRequest != null)
                                    startQrActivation(mPendingQrRequest, token);
                                else
                                    showUnlockToken();
                            });
                        }
                });
            } finally {
                con.disconnect();
            }
        } catch (IOException e) {
            e.printStackTrace();
            mHandler.post(() -> Utils.showToastLong(mContext, R.string.license_service_unavailable));
        } finally {
            mRequestTokenThread = null;
        }
    }

    private boolean requestQrLicenseCode(String unlockToken, String installationId, String qrRequestId) {
        try {
            URL url = new URL(LICENSE_GEN_URL + "/");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();

            try {
                // Necessary otherwise the connection will stay open
                con.setRequestProperty("Connection", "Close");
                con.setRequestProperty("User-Agent", Utils.getAppVersionString());
                con.setRequestMethod("POST");
                con.setAllowUserInteraction(false);
                con.setDoInput(true);
                con.setDoOutput(true);
                con.setConnectTimeout(3000);
                con.setReadTimeout(5000);

                // Send POST request
                try (BufferedOutputStream os = new BufferedOutputStream(con.getOutputStream())) {
                    String req = "unlock_token=" + unlockToken + "&installation_id=" + installationId + "&qr_request_id=" + qrRequestId;
                    os.write(req.getBytes());
                }

                int code = con.getResponseCode();
                Log.d(TAG, "requestQrLicenseCode: " + code);

                if((code == 302) || (code == 200))
                    return true;

                StringBuilder builder = new StringBuilder();
                try(BufferedReader br = new BufferedReader(
                        new InputStreamReader(con.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null)
                        builder.append(line);
                }

                String err = builder.toString();
                Log.i(TAG, "requestQrLicenseCode failure: " + err);

                if(code == 402)
                    err = mContext.getString(R.string.license_limit_reached);
                else if (code == 410)
                    err = mContext.getString(R.string.qr_code_expired);
                else
                    err = mContext.getString(R.string.license_error, code, err);

                final String msg = err;
                mHandler.post(() -> {
                    AlertDialog.Builder abuilder = new AlertDialog.Builder(mContext);
                    abuilder.setTitle(R.string.error);
                    abuilder.setMessage(msg);
                    abuilder.setCancelable(true);
                    abuilder.setNeutralButton(R.string.copy_to_clipboard, (dialog, which) -> Utils.copyToClipboard(mContext, msg));
                    abuilder.setPositiveButton(R.string.ok, (dialog, id1) -> {});

                    AlertDialog alert = abuilder.create();
                    alert.setCanceledOnTouchOutside(false);
                    alert.show();
                });
            } finally {
                con.disconnect();
            }
        } catch (IOException e) {
            e.printStackTrace();
            mHandler.post(() -> Utils.showToastLong(mContext, R.string.license_service_unavailable));
        }
        return false;
    }

    public String getLastUnlockToken() {
        return mPrefs.getString(PREF_LAST_UNLOCK_TOKEN, "");
    }

    private void startQrActivation(QrActivationRequest qrActivation, String unlock_token) {
        Utils.showToast(mContext, R.string.requesting_license);

        if(mQrActivationExecutor != null)
            mQrActivationExecutor.shutdownNow();

        mQrActivationExecutor = Executors.newSingleThreadExecutor();

        Log.d(TAG, "QR code activation: installation_id=" + qrActivation.installation_id +
                ", req_id=" + qrActivation.qr_request_id + ", device=" + qrActivation.device_name);

        mQrActivationExecutor.execute(() -> {
            if(requestQrLicenseCode(unlock_token, qrActivation.installation_id, qrActivation.qr_request_id))
                mHandler.post(() -> {
                    Utils.showToast(mContext, R.string.license_activation_ok);

                    // token just purchased, also show it
                    if(mPendingQrRequest != null)
                        showUnlockToken();
                });
        });
    }

    public void performQrActivation(Activity activity, QrActivationRequest qrRequest) {
        if(!qrRequest.isValid()) {
            Log.e(TAG, "Invalid QR activation request");
            return;
        }

        String unlock_token = getLastUnlockToken();

        if(!unlock_token.isEmpty()) {
            new AlertDialog.Builder(mContext)
                    .setTitle(R.string.license_code)
                    .setMessage(mContext.getString(R.string.qr_license_confirm, qrRequest.device_name) + "\n\n" + unlock_token)
                    .setPositiveButton(R.string.yes, (dialogInterface, i) -> startQrActivation(qrRequest, unlock_token))
                    .setNegativeButton(R.string.no, (dialogInterface, i) -> {})
                    .show();
        } else {
            new AlertDialog.Builder(mContext)
                    .setTitle(R.string.unlock_token)
                    .setMessage(mContext.getString(R.string.qr_purchase_required))
                    .setPositiveButton(R.string.ok, (dialogInterface, i) -> {
                        mPendingQrRequest = qrRequest;
                        purchase(activity, Billing.UNLOCK_TOKEN_SKU);
                    })
                    .setNegativeButton(R.string.cancel_action, (dialogInterface, i) -> {})
                    .show();
        }
    }

    public void showUnlockToken() {
        String token = getLastUnlockToken();
        if(token.isEmpty())
            return;

        LayoutInflater inflater = LayoutInflater.from(mContext);
        View content = inflater.inflate(R.layout.unlock_token_dialog, null);
        ((TextView)content.findViewById(R.id.unlock_token)).setText(token);
        Utils.setTextUrls(content.findViewById(R.id.unlock_token_msg), R.string.unlock_token_msg1, LICENSE_GEN_URL + "/?unlock_token=" + token);

        new AlertDialog.Builder(mContext)
                .setTitle(R.string.unlock_token)
                .setView(content)
                .setPositiveButton(R.string.ok, (dialogInterface, i) -> {})
                .setNeutralButton(R.string.copy_to_clipboard, (dialogInterface, i) -> Utils.copyToClipboard(mContext, token))
                .show();
    }
}
