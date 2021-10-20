package com.emanuelef.remote_capture;

import android.content.Context;

/* Billing stub */
public class Billing {
    // SKUs
    public static final String NO_ADS_SKU = "no_ads";
    public static final String MALWARE_DETECTION_SKU = "malware_detection";

    protected final Context mContext;

    public Billing(Context ctx) {
        mContext = ctx;
    }

    public boolean isAvailable(String sku) {
        return false;
    }

    public boolean isPurchased(String sku) {
        return false;
    }
}
