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

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.model.Geomodel;
import com.maxmind.db.Reader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;

/* A class to query geolocation info from IP addresses. */
public class Geolocation {
    private static final String TAG = "Geolocation";
    private final Context mContext;
    private Reader mCountryReader;
    private Reader mAsnReader;

    public Geolocation(Context ctx) {
        mContext = ctx;
        openDb();
    }

    @Override
    public void finalize() {
        Utils.safeClose(mCountryReader);
        Utils.safeClose(mAsnReader);
        mCountryReader = null;
        mAsnReader = null;
    }

    private void openDb() {
        try {
            mCountryReader = new Reader(getCountryFile(mContext));
            Log.d(TAG, "Country DB loaded: " + mCountryReader.getMetadata());

            mAsnReader = new Reader(getAsnFile(mContext));
            Log.d(TAG, "ASN DB loaded: " + mAsnReader.getMetadata());
        } catch (IOException e) {
            Log.i(TAG, "Geolocation is not available");
        }
    }

    private static File getCountryFile(Context ctx) {
        return new File(ctx.getFilesDir() + "/dbip_country_lite.mmdb");
    }

    private static File getAsnFile(Context ctx) {
        return new File(ctx.getFilesDir() + "/dbip_asn_lite.mmdb");
    }

    public static Date getDbDate(File file) throws IOException {
        try(Reader reader = new Reader(file)) {
            return reader.getMetadata().getBuildDate();
        }
    }

    public static @Nullable Date getDbDate(Context ctx) {
        try {
            return getDbDate(getCountryFile(ctx));
        } catch (IOException ignored) {
            return null;
        }
    }

    public static long getDbSize(Context ctx) {
        return getCountryFile(ctx).length() + getAsnFile(ctx).length();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void deleteDb(Context ctx) {
        getCountryFile(ctx).delete();
        getAsnFile(ctx).delete();
    }

    @SuppressLint("SimpleDateFormat")
    public static boolean downloadDb(Context ctx) {
        String dateid = new SimpleDateFormat("yyyy-MM").format(new Date());
        String country_url = "https://download.db-ip.com/free/dbip-country-lite-" + dateid + ".mmdb.gz";
        String asn_url = "https://download.db-ip.com/free/dbip-asn-lite-" + dateid + ".mmdb.gz";

        try {
            return downloadAndUnzip(ctx, "country", country_url, getCountryFile(ctx)) &&
                    downloadAndUnzip(ctx, "asn", asn_url, getAsnFile(ctx));
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static boolean downloadAndUnzip(Context ctx, String label, String url, File dst) throws IOException {
        File tmp_file = new File(ctx.getCacheDir() + "/geoip_db.zip");

        boolean rv = Utils.downloadFile(url, tmp_file.getAbsolutePath());
        if(!rv) {
            Log.w(TAG, "Could not download " + label + " db from " +  url);
            return false;
        }

        try(FileInputStream is = new FileInputStream(tmp_file.getAbsolutePath())) {
            if(!Utils.ungzip(is, dst.getAbsolutePath())) {
                Log.w(TAG, "ungzip of " + tmp_file + " failed");
                return false;
            }

            // Verify - throws IOException on error
            getDbDate(dst);

            return true;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp_file.delete();
        }
    }

    public String getCountryCode(InetAddress addr) {
        if(mCountryReader != null) {
            try {
                Geomodel.CountryResult res = mCountryReader.get(addr, Geomodel.CountryResult.class);
                if ((res != null) && (res.country != null))
                    return res.country.isoCode;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // fallback
        return "";
    }

    public Geomodel.ASN getASN(InetAddress addr) {
        if(mAsnReader != null) {
            try {
                Geomodel.ASN res = mAsnReader.get(addr, Geomodel.ASN.class);
                if (res != null)
                    return res;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        // fallback
        return new Geomodel.ASN();
    }
}
