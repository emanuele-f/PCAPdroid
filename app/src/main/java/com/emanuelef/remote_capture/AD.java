package com.emanuelef.remote_capture;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.RequestConfiguration;

import java.util.Collections;

public class AD {
    public static final String TAG = "Advertisements";
    private final Activity ctx;
    private final AdSize adSize;
    private final FrameLayout adContainer;
    private final String unitId;
    private boolean mShown;
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
                mShown = true;
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                Log.w(TAG, "Load ad failed: " + loadAdError);
                hide();
            }
        });

        AdRequest adRequest = new AdRequest.Builder().build();
        adView.loadAd(adRequest);
        Log.d(TAG, "Finished ad loading");
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
            adContainer.removeAllViews();
            adContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
            adView.destroy();
            mShown = false;
        }
    }

    public boolean isShown() {
        return mShown;
    }
}