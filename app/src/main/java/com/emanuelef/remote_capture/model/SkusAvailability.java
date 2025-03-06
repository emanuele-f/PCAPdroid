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

package com.emanuelef.remote_capture.model;

import android.content.SharedPreferences;

import androidx.collection.ArraySet;

import com.android.billingclient.api.SkuDetails;
import com.emanuelef.remote_capture.Log;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

public class SkusAvailability implements Serializable {
    private final static String TAG = "SkusAvailability";
    private final static String PREF_KEY = "available_skus";
    private final HashSet<String> mSkus;

    private SkusAvailability() {
        mSkus = new HashSet<>();
    }

    public static SkusAvailability load(SharedPreferences prefs) {
        String serialized = prefs.getString(PREF_KEY, "");
        Gson gson = new Gson();
        SkusAvailability obj = null;

        try {
            obj = gson.fromJson(serialized, SkusAvailability.class);
        } catch (JsonSyntaxException | IllegalArgumentException e) {
            Log.e(TAG, "SkusAvailability JSON load error: " + e);
        }

        if(obj == null)
            obj = new SkusAvailability();
        return obj;
    }

    private void save(SharedPreferences pref) {
        Gson gson = new Gson();
        String json = gson.toJson(this);

        SharedPreferences.Editor prefsEditor = pref.edit();
        prefsEditor.putString(PREF_KEY, json);
        prefsEditor.apply();
    }

    public boolean update(List<SkuDetails> details, SharedPreferences prefs) {
        boolean changed = false;
        HashSet<String> available = new HashSet<>();

        // Check new skus
        for(SkuDetails detail: details) {
            String sku = detail.getSku();
            available.add(sku);

            if(!mSkus.contains(sku)) {
                changed = true;
                mSkus.add(sku);
            }
        }

        // Check removed skus
        Iterator<String> it = mSkus.iterator();
        while(it.hasNext()) {
            String sku = it.next();
            if(!available.contains(sku)) {
                it.remove();
                changed = true;
            }
        }

        if(changed)
            save(prefs);
        return changed;
    }

    public boolean isAvailable(String sku) {
        return mSkus.contains(sku);
    }
}
