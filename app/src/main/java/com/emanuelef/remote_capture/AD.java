package com.emanuelef.remote_capture;

import android.app.Activity;
import android.content.Intent;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.emanuelef.remote_capture.activities.SettingsActivity;
import com.emanuelef.remote_capture.model.Prefs;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.RequestConfiguration;

import java.util.Collections;
import java.util.Random;

public class AD {
    public static final String TAG = "Advertisements";
    private final Activity ctx;
    private final AdSize adSize;
    private final FrameLayout adContainer;
    private final String unitId;
    private boolean mShownAdmob;
    private AdView adView;

    public AD(Activity activity, String unitid) {
        ctx = activity;
        unitId = BuildConfig.DEBUG ? "ca-app-pub-3940256099942544/6300978111" : unitid;
        adSize = getAdSize(ctx);
        adContainer = ctx.findViewById(R.id.adContainer);

        if(BuildConfig.DEBUG) {
            RequestConfiguration configuration = new RequestConfiguration.Builder().setTestDeviceIds(
                    Collections.singletonList("f4e7e555-760e-4a4e-a2e9-98fcdef94a09")).build();
            MobileAds.setRequestConfiguration(configuration);
        }

        Log.d(TAG, "ad size: " + adSize.toString());

        // Calling initialize is not needed
        //MobileAds.initialize(ctx, initializationStatus -> Log.d(TAG, "initialized: " + initializationStatus));
    }

    public void show() {
        if(adView != null)
            return;

        Random rand = new Random();
        if((rand.nextInt() % 5) == 0) {
            // show self promotion 1/5th of the times (or if admob loading fails)
            if(showSelfPromotion())
                return;
        }

        Log.d(TAG, "Start ad loading");
        adView = new AdView(ctx);
        adView.setAdUnitId(unitId);
        adContainer.addView(adView);

        adContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, adSize.getHeightInPixels(ctx)));
        adView.setAdSize(adSize);

        adView.setAdListener(new AdListener() {
            @Override
            public void onAdLoaded() {
                Log.w(TAG, "AD successfully loaded");
                mShownAdmob = true;
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                Log.w(TAG, "Load ad failed: " + loadAdError);
                hide();
                showSelfPromotion();
            }
        });

        AdRequest adRequest = new AdRequest.Builder().build();
        adView.loadAd(adRequest);
        Log.d(TAG, "Finished ad loading");
    }

    private boolean showSelfPromotion() {
        PlayBilling billing = new PlayBilling(ctx);
        if(!billing.isAvailable(Billing.MALWARE_DETECTION_SKU))
            return false;

        LayoutInflater inflater = ctx.getLayoutInflater();
        View adView = inflater.inflate(R.layout.self_promotion_ad, adContainer, false);
        adView.findViewById(R.id.confirm_btn).setOnClickListener(v -> {
            Intent intent = new Intent(ctx, SettingsActivity.class);
            intent.putExtra(SettingsActivity.TARGET_PREF_EXTRA, Prefs.PREF_MALWARE_DETECTION);
            ctx.startActivity(intent);
        });
        adContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        adContainer.addView(adView);
        return true;
    }

    // https://developers.google.com/admob/android/banner/adaptive
    private static AdSize getAdSize(Activity ctx) {
        Display display = ctx.getWindowManager().getDefaultDisplay();
        DisplayMetrics outMetrics = new DisplayMetrics();
        display.getMetrics(outMetrics);

        float widthPixels = outMetrics.widthPixels;
        float density = outMetrics.density;

        int adWidth = (int) (widthPixels / density);
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, adWidth);
    }

    public void hide() {
        if(adView != null) {
            Log.d(TAG, "ad destroy");
            adView.destroy();
            mShownAdmob = false;
        }

        adContainer.removeAllViews();
        adContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
    }

    public boolean isShownAdmob() {
        return mShownAdmob;
    }
}