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

package com.emanuelef.remote_capture.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.PlayBilling;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.adapters.SKUsAdapter;

public class IABActivity extends BaseActivity implements PlayBilling.PurchaseReadyListener, SKUsAdapter.SKUClickListener {
    private static final String TAG = "IABActivity";
    private PlayBilling mIab;
    private ListView mListView;
    private TextView mListEmpty;
    private SKUsAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.paid_features);
        displayBackAction();
        setContentView(R.layout.simple_list);

        mIab = new PlayBilling(this);
        mListView = findViewById(R.id.listview);
        mListEmpty = findViewById(R.id.list_empty);
        mListEmpty.setText(R.string.loading);
        mAdapter = new SKUsAdapter(this, mIab, this);
        mListView.setAdapter(mAdapter);

        mIab.setPurchaseReadyListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIab.connectBilling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIab.disconnectBilling();
    }

    @Override
    public void onPurchasesReady() {
        Log.d(TAG, "Purchases Ready");
        mListEmpty.setText(R.string.no_items_for_purchase);
        reloadAvailableSkus();

    }

    @Override
    public void onPurchasesError() {
        Log.d(TAG, "Purchases Error");
        mListEmpty.setText(R.string.billing_failure);
        reloadAvailableSkus();
    }

    @Override
    public void onSKUStateUpdate(String sku, int state) {
        Log.d(TAG, "SKU " + sku + " state update: " + PlayBilling.purchstate2Str(state));
        reloadAvailableSkus();
    }

    private void reloadAvailableSkus() {
        if(!mIab.isAvailable(Billing.NO_ADS_SKU)) {
            // IAB not available
            Log.d(TAG, "No SKUs available");
            mListEmpty.setVisibility(View.VISIBLE);
            mListView.removeAllViews();
            return;
        }

        mListEmpty.setVisibility(View.GONE);
        mAdapter.loadSKUs();
    }

    @Override
    public void onPurchaseClick(SKUsAdapter.SKUItem item) {
        mIab.purchase(this, item.sku);
    }

    @Override
    public void onLearnMoreClick(SKUsAdapter.SKUItem item) {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.docs_url));
        Utils.startActivity(this, browserIntent);
    }
}