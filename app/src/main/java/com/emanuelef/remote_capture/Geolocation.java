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
 * Copyright 2020-26 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.content.Context;

import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.model.Geomodel;
import com.maxmind.db.Reader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

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

    public boolean isAvailable() {
        return (mCountryReader != null) && (mAsnReader != null);
    }

    public static boolean isAvailable(Context ctx) {
        Date builtDate = Geolocation.getDbDate(ctx);
        return (builtDate != null);
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

    public static boolean downloadDb(Context ctx) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);

        // The DB of the current month may not be published yet during its first day
        for(int i = 0; i < 2; i++) {
            Utils.DownloadResult res = downloadDb(ctx, fmt.format(cal.getTime()));
            if(res != Utils.DownloadResult.NOT_FOUND)
                return (res == Utils.DownloadResult.OK);

            cal.add(Calendar.MONTH, -1);
        }

        return false;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static Utils.DownloadResult downloadDb(Context ctx, String dateid) {
        String country_url = "https://download.db-ip.com/free/dbip-country-lite-" + dateid + ".mmdb.gz";
        String asn_url = "https://download.db-ip.com/free/dbip-asn-lite-" + dateid + ".mmdb.gz";
        File country_file = getCountryFile(ctx);
        File asn_file = getAsnFile(ctx);
        File country_tmp = new File(country_file + ".tmp");
        File asn_tmp = new File(asn_file + ".tmp");

        // Only replace the current DBs if both the new ones are valid, to avoid mixing different versions
        try {
            Utils.DownloadResult res = downloadAndUnzip(ctx, "country", country_url, country_tmp);
            if(res == Utils.DownloadResult.OK)
                res = downloadAndUnzip(ctx, "asn", asn_url, asn_tmp);
            if(res != Utils.DownloadResult.OK)
                return res;

            if(!country_tmp.renameTo(country_file) || !asn_tmp.renameTo(asn_file)) {
                Log.w(TAG, "Could not replace the db files");
                return Utils.DownloadResult.FAILED;
            }

            return Utils.DownloadResult.OK;
        } catch (IOException e) {
            e.printStackTrace();
            return Utils.DownloadResult.FAILED;
        } finally {
            country_tmp.delete();
            asn_tmp.delete();
        }
    }

    private static Utils.DownloadResult downloadAndUnzip(Context ctx, String label, String url, File dst) throws IOException {
        File tmp_file = new File(ctx.getCacheDir() + "/geoip_db.zip");

        Utils.DownloadResult rv = Utils.downloadFileWithResult(url, tmp_file.getAbsolutePath());
        if(rv != Utils.DownloadResult.OK) {
            Log.w(TAG, "Could not download " + label + " db from " +  url);
            return rv;
        }

        try(FileInputStream is = new FileInputStream(tmp_file.getAbsolutePath())) {
            if(!Utils.ungzip(is, dst.getAbsolutePath())) {
                Log.w(TAG, "ungzip of " + tmp_file + " failed");
                return Utils.DownloadResult.FAILED;
            }

            // Verify - throws IOException on error
            getDbDate(dst);

            return Utils.DownloadResult.OK;
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
