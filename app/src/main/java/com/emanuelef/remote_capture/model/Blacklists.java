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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.io.File;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;

/* Represents the status of the blacklists loading */
public class Blacklists {
    public static final String PREF_BLACKLISTS_STATUS = "blacklists_status";
    public static final int BLACKLISTS_UPDATE_SECONDS = 86400; // 1d
    private static final String TAG = "BlacklistsStatus";
    private final ArrayList<BlacklistInfo> mLists = new ArrayList<>();
    private final SharedPreferences mPrefs;
    private final Context mContext;
    int num_up_to_date;
    long last_update;
    int num_domain_rules;
    int num_ip_rules;
    int num_updated;

    private static class BlacklistInfo {
        String mFname;
        String mUrl;

        BlacklistInfo(String fname, String url) {
            mFname = fname;
            mUrl = url;
        }
    }

    public Blacklists(Context ctx) {
        num_up_to_date = 0;
        last_update = 0;
        num_domain_rules = 0;
        num_ip_rules = 0;
        mContext = ctx;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        // Domains
        addList("maltrail-malware-domains.txt", "https://raw.githubusercontent.com/stamparm/aux/master/maltrail-malware-domains.txt");
        // IPs
        addList("emerging-Block-IPs.txt", "https://rules.emergingthreats.net/fwrules/emerging-Block-IPs.txt");
        addList("abuse_sslipblacklist.txt", "https://sslbl.abuse.ch/blacklist/sslipblacklist.txt");
        addList("feodotracker_ipblocklist.txt", "https://feodotracker.abuse.ch/downloads/ipblocklist.txt"); // NOTE: some IPs are in emergingthreats, but not all
        addList("digitalsideit_ips.txt", "https://raw.githubusercontent.com/davidonzo/Threat-Intel/master/lists/latestips.txt");
        // To review
        //https://github.com/StevenBlack/hosts
        //https://phishing.army/download/phishing_army_blocklist.txt
        //https://snort.org/downloads/ip-block-list
        num_updated = mLists.size();

        deserialize();
        checkFiles();
    }

    private void addList(String fname, String url) {
        mLists.add(new BlacklistInfo(fname, url));
    }

    public void deserialize() {
        String serialized = mPrefs.getString(PREF_BLACKLISTS_STATUS, "");
        if(!serialized.isEmpty()) {
            JsonObject obj = JsonParser.parseString(serialized).getAsJsonObject();

            num_up_to_date = obj.getAsJsonPrimitive("num_up_to_date").getAsInt();
            last_update = obj.getAsJsonPrimitive("last_update").getAsLong();
            num_domain_rules = obj.getAsJsonPrimitive("num_domain_rules").getAsInt();
            num_ip_rules = obj.getAsJsonPrimitive("num_ip_rules").getAsInt();
        }
    }

    private static class Serializer implements JsonSerializer<Blacklists> {
        @Override
        public JsonElement serialize(Blacklists src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject rv = new JsonObject();

            rv.add("num_up_to_date", new JsonPrimitive(src.num_up_to_date));
            rv.add("last_update", new JsonPrimitive(src.last_update));
            rv.add("num_domain_rules", new JsonPrimitive(src.num_domain_rules));
            rv.add("num_ip_rules", new JsonPrimitive(src.num_ip_rules));

            return rv;
        }
    }

    public String toJson() {
        Gson gson = new GsonBuilder().registerTypeAdapter(getClass(), new Serializer())
                .create();
        return gson.toJson(this);
    }

    public synchronized void save() {
        mPrefs.edit()
                .putString(PREF_BLACKLISTS_STATUS, toJson())
                .apply();
    }

    private String getListPath(BlacklistInfo bl) {
        return mContext.getFilesDir().getPath() + "/malware_bl/" + bl.mFname;
    }

    private void checkFiles() {
        HashSet<File> validLists = new HashSet<>();

        // Ensure that all the lists files exist, otherwise force update
        for(BlacklistInfo bl: mLists) {
            File f = new File(getListPath(bl));
            validLists.add(f);

            if(!f.exists()) {
                // must update
                last_update = 0;
            }
        }

        // Ensure that the only the specified lists exist
        File bldir = new File(mContext.getFilesDir().getPath() + "/malware_bl");
        bldir.mkdir();
        File[] files = bldir.listFiles();
        if(files != null) {
            for(File f: files) {
                if(!validLists.contains(f)) {
                    Log.d(TAG, "Removing unknown list: " + f.getPath());
                    f.delete();
                }
            }
        }
    }

    @Override
    public @NonNull String toString() {
        return String.format(mContext.getString(R.string.blacklists_status_summary),
                num_up_to_date, mLists.size(),
                Utils.formatInteger(mContext, num_domain_rules),
                Utils.formatInteger(mContext, num_ip_rules),
                Utils.formatEpochShort(mContext, last_update / 1000));
    }

    public boolean needsUpdate() {
        long now = System.currentTimeMillis();
        return((now - last_update) >= BLACKLISTS_UPDATE_SECONDS * 1000);
    }

    public void update() {
        // NOTE: invoked in a separate thread (CaptureService.mBlacklistsUpdateThread)
        if(!needsUpdate())
            return;

        Log.d(TAG, "Updating " + mLists.size() + " blacklists...");
        int num_ok = 0;

        for(BlacklistInfo bl: mLists) {
            Log.d(TAG, "\tupdating " + bl.mFname + "...");

            if(Utils.downloadFile(bl.mUrl, getListPath(bl)))
                num_ok++;
        }

        synchronized (this) {
            num_updated = num_ok;
            last_update = System.currentTimeMillis();
        }
    }

    // Called when the blacklists are loaded in memory
    public void onNativeLoaded(int num_loaded, int num_domains, int num_ips) {
        Log.d(TAG, "Blacklists loaded: " + num_loaded + " lists, " + num_domains + " domains, " + num_ips + " IPs");

        synchronized (this) {
            num_up_to_date = Math.min(num_updated, num_loaded);
            num_domain_rules = num_domains;
            num_ip_rules = num_ips;
        }
    }
}

