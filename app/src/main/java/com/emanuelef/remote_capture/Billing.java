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

import android.content.Context;

import java.util.Arrays;
import java.util.List;

/* Billing stub */
public class Billing {
    // SKUs
    public static final String SUPPORTER_SKU = "pcapdroid_supporter";
    public static final String NO_ADS_SKU = "no_ads";
    public static final String MALWARE_DETECTION_SKU = "malware_detection";
    public static final String FIREWALL_SKU = "firewall";
    public static final List<String> ALL_SKUS = Arrays.asList(
            SUPPORTER_SKU, NO_ADS_SKU, MALWARE_DETECTION_SKU, FIREWALL_SKU
    );

    protected final Context mContext;

    protected Billing(Context ctx) {
        mContext = ctx;
    }

    public static Billing newInstance(Context ctx) {
        return new Billing(ctx);
    }

    public boolean isAvailable(String sku) {
        return false;
    }

    public boolean isPurchased(String sku) {
        return false;
    }
}
