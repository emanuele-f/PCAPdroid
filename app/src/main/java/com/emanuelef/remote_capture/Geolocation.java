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
import java.util.concurrent.atomic.AtomicInteger;

/*
 * A class to query geolocation info from IP addresses.
 * It's meant to be used only by a single thread.
 */
public class Geolocation {
    private static final String TAG = "Geolocation";

    // incremented when the DB files change, to reload them while the capture is running
    private static final AtomicInteger sDbVersion = new AtomicInteger();

    private final Context mContext;
    private Reader mCountryReader;
    private Reader mAsnReader;
    private int mDbVersion;

    public Geolocation(Context ctx) {
        mContext = ctx;
        openDb();
    }

    private void openDb() {
        mDbVersion = sDbVersion.get();

        try {
            mCountryReader = new Reader(getCountryFile(mContext));
            Log.d(TAG, "Country DB loaded: " + mCountryReader.getMetadata());

            mAsnReader = new Reader(getAsnFile(mContext));
            Log.d(TAG, "ASN DB loaded: " + mAsnReader.getMetadata());
        } catch (IOException e) {
            Log.i(TAG, "Geolocation is not available");
        }
    }

    private void closeDb() {
        Utils.safeClose(mCountryReader);
        Utils.safeClose(mAsnReader);
        mCountryReader = null;
        mAsnReader = null;
    }

    private void checkReload() {
        if(mDbVersion == sDbVersion.get())
            return;

        Log.i(TAG, "Reloading the DB");
        closeDb();
        openDb();
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
        sDbVersion.incrementAndGet();
    }

    public static boolean isDbUpToDate(Context ctx) {
        Date builtDate = getDbDate(ctx);
        if(builtDate == null)
            return false;

        Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        Calendar built = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        built.setTime(builtDate);

        int builtMonth = built.get(Calendar.YEAR) * 12 + built.get(Calendar.MONTH);
        int curMonth = now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH);
        return (builtMonth >= curMonth);
    }

    private static String getDateId(Calendar cal) {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(cal.getTime());
    }

    /* Downloads the DB of the current month.
     * Retries with the previous month on 404.
     * Returns true if any DB was downloaded, false otherwise */
    public static synchronized boolean downloadDb(Context ctx) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);

        // The DB of the current month may not be published yet during its first day
        for(int i = 0; i < 2; i++) {
            Utils.DownloadResult res = downloadDbForDate(ctx, getDateId(cal));
            if(res != Utils.DownloadResult.NOT_FOUND)
                return (res == Utils.DownloadResult.OK);

            cal.add(Calendar.MONTH, -1);
        }

        return false;
    }

    /* Downloads the DB of the current month, unless already installed. Returns false if the DB
     * of the current month could not be installed. In such case, if no DB is installed, the DB of
     * the previous month is downloaded */
    public static synchronized boolean downloadDbIfOutdated(Context ctx) {
        if(isDbUpToDate(ctx))
            return true;

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        Utils.DownloadResult res = downloadDbForDate(ctx, getDateId(cal));
        if(res == Utils.DownloadResult.OK)
            return true;

        if((res == Utils.DownloadResult.NOT_FOUND) && !isAvailable(ctx)) {
            // try to download the one from previous month
            cal.add(Calendar.MONTH, -1);
            downloadDbForDate(ctx, getDateId(cal));
        }

        return false;
    }

    private static Utils.DownloadResult downloadDbForDate(Context ctx, String date_id) {
        String country_url = "https://download.db-ip.com/free/dbip-country-lite-" + date_id + ".mmdb.gz";
        String asn_url = "https://download.db-ip.com/free/dbip-asn-lite-" + date_id + ".mmdb.gz";
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

            sDbVersion.incrementAndGet();
            return Utils.DownloadResult.OK;
        } catch (IOException e) {
            e.printStackTrace();
            return Utils.DownloadResult.FAILED;
        } finally {
            country_tmp.delete();
            asn_tmp.delete();
        }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
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
        checkReload();

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
        checkReload();

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
