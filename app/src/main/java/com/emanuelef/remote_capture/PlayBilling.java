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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

public class PlayBilling implements BillingClientStateListener, PurchasesUpdatedListener, SkuDetailsResponseListener {
    public static final String TAG = "PlayBilling";
    public static final String NO_ADS_SKU = "no_ads";

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mHandler;
    private BillingClient mBillingClient;
    private PurchaseReadyListener mListener;
    private boolean mWaitingStart = false;
    private List<SkuDetails> mAvailableSkus = new ArrayList<>();

    /** initPurchases() -> PurchaseReadyListener.onPurchasesReady()
     *   -> the client can now call purchase
     *   -> PurchaseReadyListener.onSKUStateUpdate() may be called in the future
     */
    public interface PurchaseReadyListener {
        void onPurchasesReady(List<SkuDetails> availableSkus);
        void onPurchasesError(BillingResult res);
        void onSKUStateUpdate(String sku, int state);
    }

    public PlayBilling(Context ctx) {
        mContext = ctx;
        mHandler = new Handler(Looper.getMainLooper());
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
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

            for(Purchase purchase : purchases) {
                boolean newPurchase = false;

                for(String sku: purchase.getSkus()) {
                    if(!sku.equals(NO_ADS_SKU)) {
                        continue;
                    }

                    Log.d(TAG, "\tPurchase: " + sku + " -> " + purchstate2Str(purchase.getPurchaseState()));

                    switch (purchase.getPurchaseState()) {
                        case PurchaseState.PENDING:
                            if((mListener != null) && !mWaitingStart)
                                mListener.onSKUStateUpdate(sku, PurchaseState.PENDING);
                            break;
                        case PurchaseState.PURCHASED:
                            if(!isPurchased(sku) && setPurchased(sku, true)) {
                                newPurchase = true;

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

                    mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, billingResult1 -> {
                        Log.d(TAG, "acknowledgePurchase: " + billingResult1.getResponseCode() + " " + billingResult1.getDebugMessage());
                    });
                }
            }

            // Users can void a purchase, e.g. by asking a refund
            String sku = NO_ADS_SKU;

            if(!purchased.contains(sku) && isPurchased(sku)) {
                Log.w(TAG, "Previously purchased SKU " + sku + " was voided");

                if(setPurchased(sku, false) && (mListener != null) && !mWaitingStart)
                    mListener.onSKUStateUpdate(sku, PurchaseState.UNSPECIFIED_STATE);
            }
        }

        if(mWaitingStart) {
            if(billingResult.getResponseCode() == BillingResponseCode.OK)
                mListener.onPurchasesReady(mAvailableSkus);
            else
                mListener.onPurchasesError(billingResult);

            mWaitingStart = false;
        }
    }

    @Override
    public void onSkuDetailsResponse(@NonNull BillingResult billingResult, @Nullable List<SkuDetails> list) {
        Log.d(TAG, "onSkuDetailsResponse: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage());

        if((billingResult.getResponseCode() == BillingResponseCode.OK) && (list != null)) {
            mAvailableSkus = list;

            mBillingClient.queryPurchasesAsync(BillingClient.SkuType.INAPP, (billingResult1, purchases) -> {
                Log.d(TAG, "queryPurchasesAsync: " + billingResult1.getResponseCode() + " " + billingResult1.getDebugMessage());
                processPurchases(billingResult1, purchases);
            });
        } else
            mListener.onPurchasesError(billingResult);
    }

    @Override
    public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
        Log.d(TAG, "onBillingSetupFinished: " + billingResult.getResponseCode() + " " + billingResult.getDebugMessage());

        if(billingResult.getResponseCode() ==  BillingResponseCode.OK) {
            SkuDetailsParams.Builder builder = SkuDetailsParams.newBuilder()
                    .setType(BillingClient.SkuType.INAPP)
                    .setSkusList(Arrays.asList(NO_ADS_SKU));

            mBillingClient.querySkuDetailsAsync(builder.build(), this);
        } else
            mListener.onPurchasesError(billingResult);
    }

    @Override
    public void onBillingServiceDisconnected() {
        Log.w(TAG, "onBillingServiceDisconnected");

        mHandler.postDelayed(() -> {
            mBillingClient.startConnection(PlayBilling.this);
        }, 5000);
    }

    /*
     * startPurchases -> onBillingSetupFinished -> querySkuDetailsAsync -> queryPurchasesAsync -> processPurchases
     *
     * IMPORTANT: the client must call endPurchases at the end
     * */
    public void startPurchases(@NonNull PurchaseReadyListener listener) {
        mListener = listener;
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

    public void endPurchases() {
        // Use post to avoid unsetting one of the variables below while a client is working on them
        mHandler.post(() -> {
            if(mBillingClient != null) {
                mBillingClient.endConnection();
                mBillingClient = null;
                mAvailableSkus.clear();
            }

            mListener = null;
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

    public boolean isPurchased(String sku) {
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

    public boolean purchase(Activity activity, String sku) {
        SkuDetails details = null;

        for(SkuDetails skudet : mAvailableSkus) {
            if(skudet.getSku().equals(sku)) {
                details = skudet;
                break;
            }
        }

        if(details == null) {
            Log.w(TAG, "Unknown SKU " + sku);
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
