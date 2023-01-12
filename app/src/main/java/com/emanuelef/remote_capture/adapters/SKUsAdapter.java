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
import androidx.appcompat.content.res.AppCompatResources;

import com.android.billingclient.api.SkuDetails;
import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.PlayBilling;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.google.android.material.button.MaterialButton;

public class SKUsAdapter extends ArrayAdapter<SKUsAdapter.SKUItem> {
    private static final String TAG = "SKUsAdapter";
    private static final boolean TEST_MODE = false;
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

        SKUItem(String _sku, String _label, String _descr, String _price, String _docs_url) {
            sku = _sku;
            label = _label;
            description = _descr;
            price = _price;
            docs_url = _docs_url;
        }
    }

    public interface SKUClickListener {
        void onPurchaseClick(SKUItem item);
        void onShowClick(SKUItem item);
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

        SKUItem item = getItem(position);
        boolean purchased = mIab.isPurchased(item.sku);

        ((TextView)view.findViewById(R.id.label)).setText(item.label);
        ((TextView)view.findViewById(R.id.description)).setText(item.description);

        String text;
        if(purchased)
            text = mCtx.getString(R.string.purchased);
        else
            text = item.price;
        ((TextView)view.findViewById(R.id.price)).setText(text);

        MaterialButton showBtn = view.findViewById(R.id.show);
        showBtn.setVisibility((item.sku.equals(Billing.UNLOCK_TOKEN_SKU) && !mIab.getLatestUnlockToken().isEmpty()) ? View.VISIBLE : View.GONE);
        showBtn.setOnClickListener(v -> mListener.onShowClick(item));

        MaterialButton purchaseBtn = view.findViewById(R.id.purchase);
        purchaseBtn.setEnabled(!purchased);
        purchaseBtn.setOnClickListener(v -> mListener.onPurchaseClick(item));

        View moreBtn = view.findViewById(R.id.learn_more);
        moreBtn.setVisibility((item.docs_url == null) ? View.INVISIBLE : View.VISIBLE);
        moreBtn.setOnClickListener(v -> mListener.onLearnMoreClick(item));

        // Only for testing
        if(TEST_MODE && purchased) {
            purchaseBtn.setIcon(AppCompatResources.getDrawable(mCtx, R.drawable.ic_block));
            purchaseBtn.setText("Consume");
            purchaseBtn.setEnabled(true);
            purchaseBtn.setOnClickListener(v -> {
                mIab.consumePurchase(item.sku);
                purchaseBtn.setEnabled(false);
            });
        }

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
                docs_url));
    }

    public void loadSKUs() {
        Log.d(TAG, "Populating SKUs...");
        clear();

        addIfAvailable(Billing.FIREWALL_SKU, R.string.no_root_firewall,
                R.string.firewall_summary, MainActivity.FIREWALL_DOCS_URL);

        addIfAvailable(Billing.MALWARE_DETECTION_SKU, R.string.malware_detection,
                R.string.malware_detection_summary, MainActivity.MALWARE_DETECTION_DOCS_URL);

        addIfAvailable(Billing.PCAPNG_SKU, R.string.pcapng_format,
                R.string.pcapng_format_summary, MainActivity.PCAPNG_DOCS_URL);

        addIfAvailable(Billing.UNLOCK_TOKEN_SKU, R.string.unlock_token,
                R.string.unlock_token_summary, MainActivity.PAID_FEATURES_URL);

        notifyDataSetChanged();
    }
}
