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
 * Copyright 2022 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.adapters;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.SkuDetails;
import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.PlayBilling;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.activities.MainActivity;

public class SKUsAdapter extends ArrayAdapter<SKUsAdapter.SKUItem> {
    private static final String TAG = "SKUsAdapter";
    private final Context mCtx;
    private final LayoutInflater mLayoutInflater;
    private final PlayBilling mIab;
    private final SKUClickListener mListener;

    public static class SKUItem {
        public final String sku;
        public final String label;
        public final String description;
        public final String price;
        public final String docs_url;
        public final boolean purchased;

        SKUItem(String _sku, String _label, String _descr, String _price, boolean _purchased, String _docs_url) {
            sku = _sku;
            label = _label;
            description = _descr;
            price = _price;
            purchased = _purchased;
            docs_url = _docs_url;
        }
    }

    public interface SKUClickListener {
        void onPurchaseClick(SKUItem item);
        void onLearnMoreClick(SKUItem item);
    }

    public SKUsAdapter(@NonNull Context context, PlayBilling iab, SKUClickListener listener) {
        super(context, 0);
        mCtx = context;
        mLayoutInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mIab = iab;
        mListener = listener;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View view, @NonNull ViewGroup parent) {
        if(view == null)
            view = mLayoutInflater.inflate(R.layout.sku_item, parent, false);

        SKUItem sku = getItem(position);
        ((TextView)view.findViewById(R.id.label)).setText(sku.label);
        ((TextView)view.findViewById(R.id.description)).setText(sku.description);
        ((TextView)view.findViewById(R.id.price)).setText(sku.purchased ? mCtx.getString(R.string.purchased) : sku.price);

        View purchaseBtn = view.findViewById(R.id.purchase);
        purchaseBtn.setEnabled(!sku.purchased);
        purchaseBtn.setOnClickListener(v -> mListener.onPurchaseClick(sku));

        View moreBtn = view.findViewById(R.id.learn_more);
        moreBtn.setVisibility((sku.docs_url == null) ? View.INVISIBLE : View.VISIBLE);
        moreBtn.setOnClickListener(v -> mListener.onLearnMoreClick(sku));

        return view;
    }

    private void addIfAvailable(String sku, int title, int descr, String docs_url) {
        if(!mIab.isAvailable(sku))
            return;

        SkuDetails sd = mIab.getSkuDetails(sku);
        if(sd == null)
            return;

        Log.d(TAG, "SKU [" + sd.getSku() + "]: " + sd.getTitle() + " -> " + sd.getPrice() + " " + sd.getPriceCurrencyCode());

        add(new SKUItem(sku,
                mCtx.getString(title),
                (descr > 0) ? mCtx.getString(descr) : "",
                sd.getPrice(),
                mIab.isPurchased(sku),
                docs_url));
    }

    public void loadSKUs() {
        Log.d(TAG, "Populating SKUs...");
        clear();

        addIfAvailable(Billing.MALWARE_DETECTION_SKU, R.string.malware_detection,
                R.string.malware_detection_summary, MainActivity.DOCS_URL + "/paid_features#51-malware-detection");

        addIfAvailable(Billing.NO_ADS_SKU, R.string.remove_ads, R.string.remove_ads_description, null);
    }
}
