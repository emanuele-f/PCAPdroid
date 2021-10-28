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

package com.emanuelef.remote_capture;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClient.BillingResponseCode;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.Purchase.PurchaseState;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.android.billingclient.api.SkuDetailsResponseListener;
import com.emanuelef.remote_capture.model.SkusAvailability;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public class PlayBilling extends Billing implements BillingClientStateListener, PurchasesUpdatedListener, SkuDetailsResponseListener {
    public static final String TAG = "PlayBilling";
    private final SharedPreferences mPrefs;
    private final Handler mHandler;
    private final HashMap<String, SkuDetails> mDetails;
    private BillingClient mBillingClient;
    private PurchaseReadyListener mListener;
    private boolean mWaitingStart;
    private final SkusAvailability mAvailability;

    /** setPurchaseListener() -> connectBilling() -> PurchaseReadyListener.onPurchasesReady()
     *   -> the client can now call purchase
     *   -> PurchaseReadyListener.onSKUStateUpdate() may be called in the future
     */
    public interface PurchaseReadyListener {
        void onPurchasesReady();
        void onSKUStateUpdate(String sku, int state);
    }

    public PlayBilling(Context ctx) {
        super(ctx);
        mHandler = new Handler(Looper.getMainLooper());
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        mDetails = new HashMap<>();
        mAvailability = SkusAvailability.load(mPrefs);
        mWaitingStart = false;
    }

    private static String purchstate2Str(int state) {
        switch (state) {
            case PurchaseState.PENDING: return "PENDING";
            case PurchaseState.PURCHASED: return "PURCHASED";
            case PurchaseState.UNSPECIFIED_STATE: return "UNSPECIFIED";
        }
        return "UNKNOWN";
    }

    private void processPurchases(BillingResult billingResult, @Nullable List<Purchase> purchases) {
        if((billingResult.getResponseCode() == BillingResponseCode.OK) && (purchases != null)) {
            HashSet<String> purchased = new HashSet<>();
            boolean show_toast = true;

            for(Purchase purchase : purchases) {
                boolean newPurchase = false;

                for(String sku: purchase.getSkus()) {
                    Log.d(TAG, "\tPurchase: " + sku + " -> " + purchstate2Str(purchase.getPurchaseState()));

                    switch (purchase.getPurchaseState()) {
                        case PurchaseState.PENDING:
                            if(show_toast) {
                                Utils.showToastLong(mContext, R.string.pending_transaction);
                                show_toast = false;
                            }

                            if((mListener != null) && !mWaitingStart)
                                mListener.onSKUStateUpdate(sku, PurchaseState.PENDING);
                            break;
                        case PurchaseState.PURCHASED:
                            if(!isPurchased(sku) && setPurchased(sku, true)) {
                                newPurchase = true;

                                if(show_toast) {
                                    Utils.showToastLong(mContext, R.string.purchased_feature_ok);
                                    show_toast = false;
                                }

                                if((mListener != null) && !mWaitingStart)
                                    mListener.onSKUStateUpdate(sku, PurchaseState.PURCHASED);
                            }

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

                    mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, billingResult1 ->
                            Log.d(TAG, "acknowledgePurchase: " + billingResult1.getResponseCode() + " " + billingResult1.getDebugMessage()));
                }
            }

            // Check for voided purchases (e.g. due to a refund)
            for(String sku: ALL_SKUS) {
                if(!purchased.contains(sku) && isPurchased(sku)) {
                    Log.w(TAG, "Previously purchased SKU " + sku + " was voided");

                    if(setPurchased(sku, false) && (mListener != null) && !mWaitingStart)
                        mListener.onSKUStateUpdate(sku, PurchaseState.UNSPECIFIED_STATE);
                }
            }
        }

        if(mWaitingStart && (mListener != null)) {
            if(billingResult.getResponseCode() == BillingResponseCode.OK)
                mListener.onPurchasesReady();
            else
                onPurchasesError(billingResult);

            mWaitingStart = false;
        }
    }

    private void onPurchasesError(@NonNull BillingResult billingResult) {
        Log.e(TAG, "Billing returned error " + billingResult + ", disconnecting");
        disconnectBilling();
    }

    @Override
    public void onSkuDetailsResponse(@NonNull BillingResult billingResult, @Nullable List<SkuDetails> list) {
        Log.d(TAG, "onSkuDetailsResponse: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage());

        if((billingResult.getResponseCode() == BillingResponseCode.OK) && (list != null)) {
            mAvailability.update(list, mPrefs);

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
    public void connectBilling() {
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

    public void disconnectBilling() {
        // Use post to avoid unsetting one of the variables below while a client is working on them
        mHandler.post(() -> {
            if(mBillingClient != null) {
                mBillingClient.endConnection();
                mBillingClient = null;
            }
        });
    }

    @Override
    public void onPurchasesUpdated(@NonNull BillingResult billingResult, @Nullable List<Purchase> purchases) {
        Log.d(TAG, "onPurchasesUpdated: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage());
        processPurchases(billingResult, purchases);
    }

    private String sku2pref(String sku) {
        return "SKU:" + sku;
    }

    @Override
    public boolean isPurchased(String sku) {
        if(!sku.equals(SUPPORTER_SKU) && isPurchased(SUPPORTER_SKU))
            return true;

        if(sku.equals(NO_ADS_SKU)) {
            // If the user purchases any other feature, then remove ads
            for(String other_sku: ALL_SKUS) {
                if(!other_sku.equals(PlayBilling.NO_ADS_SKU) && isPurchased(other_sku))
                    return true;
            }
        }

        long purchaseTime = mPrefs.getLong(sku2pref(sku), 0);
        return(purchaseTime != 0);
    }

    @Override
    public boolean isAvailable(String sku) {
        // mAvailability acts as a persistent cache that can be used before the billing connection
        // is established
        return mAvailability.isAvailable(sku);
    }

    public boolean canPurchase(String sku) {
        return isAvailable(sku) && !isPurchased(sku);
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

    public boolean purchase(Activity activity, String sku) {
        if((mBillingClient == null) || (!mBillingClient.isReady())) {
            Utils.showToast(mContext, R.string.billing_connecting);
            return false;
        }

        SkuDetails details = mDetails.get(sku);
        if(details == null) {
            Utils.showToast(mContext, R.string.feature_not_available);
            return false;
        }

        Log.d(TAG, "Starting purchasing SKU " + sku);

        BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
                .setSkuDetails(details)
                .build();

        // will call onPurchasesUpdated when done
        BillingResult res = mBillingClient.launchBillingFlow(activity, billingFlowParams);
        Log.d(TAG, "BillingFlow result: " + res.getResponseCode() + " " + res.getDebugMessage());

        return(res.getResponseCode() == BillingResponseCode.OK);
    }
}
