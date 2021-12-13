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
import android.util.Log;

import com.emanuelef.remote_capture.model.Geomodel;
import com.maxmind.db.Reader;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.zip.GZIPInputStream;

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
        try {
            mCountryReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            mAsnReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void openDb() {
        try {
            File countryFile = new File(mContext.getCacheDir() + "/dbip_country_lite.mmdb");
            ungzip(R.raw.dbip_country_lite_2021_11_mmdb_gz, countryFile);
            mCountryReader = new Reader(countryFile);
            Log.d(TAG, "Country DB loaded: " + mCountryReader.getMetadata());

            File asnFile = new File(mContext.getCacheDir() + "/dbip_asn_lite.mmdb");
            ungzip(R.raw.dbip_asn_lite_2021_11_mmdb_gz, asnFile);
            mAsnReader = new Reader(asnFile);
            Log.d(TAG, "ASN DB loaded: " + mAsnReader.getMetadata());
        } catch (IOException e) {
            e.printStackTrace();
            throw new IllegalStateException();
        }
    }

    private void ungzip(int resid, File dst) throws IOException {
        try(InputStream is = new GZIPInputStream(mContext.getResources().openRawResource(resid))) {
            try(BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst))) {
                byte[] bytesIn = new byte[4096];
                int read = 0;

                while ((read = is.read(bytesIn)) != -1)
                    bos.write(bytesIn, 0, read);
            }
        }
    }

    public String getCountryCode(InetAddress addr) {
        try {
            Geomodel.CountryResult res = mCountryReader.get(addr, Geomodel.CountryResult.class);
            if((res != null) && (res.country != null))
                return res.country.isoCode;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    public Geomodel.ASN getASN(InetAddress addr) {
        try {
            Geomodel.ASN res = mAsnReader.get(addr, Geomodel.ASN.class);
            if(res != null)
                return res;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return new Geomodel.ASN();
    }
}
