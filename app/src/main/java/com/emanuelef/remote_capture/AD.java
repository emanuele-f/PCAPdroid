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

public class AD {
    public static final String TAG = "Advertisements";
    private Activity ctx;
    private final AdSize adSize;
    private final FrameLayout adContainer;
    private final String unitId;
    private AdView adView;

    public AD(Activity activity, String unitid) {
        ctx = activity;
        unitId = BuildConfig.DEBUG ? "ca-app-pub-3940256099942544/6300978111" : unitid;
        adSize = getAdSize(ctx);
        adContainer = ctx.findViewById(R.id.adContainer);

        Log.d(TAG, "ad size: " + adSize.toString());
        MobileAds.initialize(ctx, initializationStatus -> Log.d(TAG, "initialized: " + initializationStatus));
    }

    public void show() {
        adView = new AdView(ctx);
        adView.setAdUnitId(unitId);
        adContainer.addView(adView);

        adContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, adSize.getHeightInPixels(ctx)));
        adView.setAdSize(adSize);

        adView.setAdListener(new AdListener() {
            @Override
            public void onAdLoaded() {
                Log.w(TAG, "AD successfully loaded");
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                Log.w(TAG, "Load ad failed: " + loadAdError);
            }
        });

        AdRequest adRequest = new AdRequest.Builder().build();
        adView.loadAd(adRequest);
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
        }
    }
}