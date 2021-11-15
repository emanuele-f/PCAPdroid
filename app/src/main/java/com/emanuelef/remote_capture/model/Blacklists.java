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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

/* Represents the malware blacklists.
 * The blacklists are hard-coded via the Blacklists.addList calls. Blacklists update is performed
 * as follows:
 *
 * 1. If Blacklists.needsUpdate return true, Blacklists.update downloads the blacklists files
 * 2. The reloadBlacklists native method is called to inform the capture thread
 * 3. The capture thread loads the blacklists in memory
 * 4. When the loading is complete, the Blacklists.onNativeLoaded method is called.
 */
public class Blacklists {
    public static final String PREF_BLACKLISTS_STATUS = "blacklists_status";
    public static final int BLACKLISTS_UPDATE_SECONDS = 86400; // 1d
    private static final String TAG = "Blacklists";
    private final ArrayList<BlacklistDescriptor> mLists = new ArrayList<>();
    private final HashMap<String, BlacklistDescriptor> mListByFname = new HashMap<>();
    private final SharedPreferences mPrefs;
    private final Context mContext;
    int num_up_to_date;
    long last_update;
    int num_domain_rules;
    int num_ip_rules;
    int num_updated;

    public Blacklists(Context ctx) {
        num_up_to_date = 0;
        last_update = 0;
        num_domain_rules = 0;
        num_ip_rules = 0;
        mContext = ctx;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        // Domains
        addList("Maltrail", BlacklistDescriptor.Type.DOMAIN_BLACKLIST,"maltrail-malware-domains.txt",
                "https://raw.githubusercontent.com/stamparm/aux/master/maltrail-malware-domains.txt");

        // IPs
        addList("Emerging Threats", BlacklistDescriptor.Type.IP_BLACKLIST, "emerging-Block-IPs.txt",
                "https://rules.emergingthreats.net/fwrules/emerging-Block-IPs.txt");
        addList("SSLBL Botnet C2", BlacklistDescriptor.Type.IP_BLACKLIST, "abuse_sslipblacklist.txt",
                "https://sslbl.abuse.ch/blacklist/sslipblacklist.txt");
        addList("Feodo Tracker Botnet C2", BlacklistDescriptor.Type.IP_BLACKLIST, "feodotracker_ipblocklist.txt",
                "https://feodotracker.abuse.ch/downloads/ipblocklist.txt"); // NOTE: some IPs are in emergingthreats, but not all
        addList("DigitalSide Threat-Intel", BlacklistDescriptor.Type.IP_BLACKLIST,  "digitalsideit_ips.txt",
                "https://raw.githubusercontent.com/davidonzo/Threat-Intel/master/lists/latestips.txt");

        // To review
        //https://github.com/StevenBlack/hosts
        //https://phishing.army/download/phishing_army_blocklist.txt
        //https://snort.org/downloads/ip-block-list
        num_updated = mLists.size();

        deserialize();
        checkFiles();
    }

    private void addList(String label, BlacklistDescriptor.Type tp, String fname, String url) {
        BlacklistDescriptor item = new BlacklistDescriptor(label, tp, fname, url);
        mLists.add(item);
        mListByFname.put(fname, item);
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

    private String getListPath(BlacklistDescriptor bl) {
        return mContext.getFilesDir().getPath() + "/malware_bl/" + bl.fname;
    }

    private void checkFiles() {
        HashSet<File> validLists = new HashSet<>();

        // Ensure that all the lists files exist, otherwise force update
        for(BlacklistDescriptor bl: mLists) {
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

        for(BlacklistDescriptor bl: mLists) {
            Log.d(TAG, "\tupdating " + bl.fname + "...");

            if(Utils.downloadFile(bl.url, getListPath(bl))) {
                bl.setUpdated(System.currentTimeMillis());
                num_ok++;
            } else
                bl.setOutdated();
        }

        synchronized (this) {
            num_updated = num_ok;
            last_update = System.currentTimeMillis();
        }
    }

    public static class NativeBlacklistStatus {
        public final String fname;
        public final int num_domain_rules;
        public final int num_ip_rules;

        public NativeBlacklistStatus(String fname, int num_domain_rules, int num_ip_rules) {
            this.fname = fname;
            this.num_domain_rules = num_domain_rules;
            this.num_ip_rules = num_ip_rules;
        }
    }

    // Called when the blacklists are loaded in memory by the native code
    public void onNativeLoaded(NativeBlacklistStatus[] loaded_blacklists) {
        int num_loaded = 0;
        int num_domains = 0;
        int num_ips = 0;

        for(NativeBlacklistStatus bl_status: loaded_blacklists) {
            if(bl_status == null)
                break;

            BlacklistDescriptor bl = mListByFname.get(bl_status.fname);
            if(bl != null) {
                // Update the number of rules
                bl.num_domain_rules = bl_status.num_domain_rules;
                bl.num_ip_rules = bl_status.num_ip_rules;
                bl.loaded = true;
            } else
                Log.w(TAG, "Loaded unknown blacklist " + bl_status.fname);

            num_loaded++;
            num_domains += bl_status.num_domain_rules;
            num_ips += bl_status.num_ip_rules;
        }

        // TODO , int num_loaded, int num_domains, int num_ips
        Log.d(TAG, "Blacklists loaded: " + num_loaded + " lists, " + num_domains + " domains, " + num_ips + " IPs");

        synchronized (this) {
            num_up_to_date = Math.min(num_updated, num_loaded);
            num_domain_rules = num_domains;
            num_ip_rules = num_ips;
        }
    }

    public Iterator<BlacklistDescriptor> iter() {
        return mLists.iterator();
    }

    public int getNumLoadedDomainRules() {
        return num_domain_rules;
    }

    public int getNumLoadedIPRules() {
        return num_ip_rules;
    }

    public long getLastUpdate() {
        return last_update;
    }

    public int getNumBlacklists() {
        return mLists.size();
    }

    public int getNumUpdatedBlacklists() {
        return num_up_to_date;
    }
}

