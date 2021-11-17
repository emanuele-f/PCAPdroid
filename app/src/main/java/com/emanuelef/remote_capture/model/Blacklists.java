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

import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.interfaces.BlacklistsStateListener;
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
import java.util.Map;

/* Represents the malware blacklists.
 * The blacklists are hard-coded via the Blacklists.addList calls. Blacklists update is performed
 * as follows:
 *
 * 1. If Blacklists.needsUpdate return true, Blacklists.update downloads the blacklists files
 * 2. The reloadBlacklists native method is called to inform the capture thread
 * 3. The capture thread loads the blacklists in memory
 * 4. When the loading is complete, the Blacklists.onNativeLoaded method is called.
 *
 * NOTE: use via PCAPdroid.getInstance().getBlacklists()
 */
public class Blacklists {
    public static final String PREF_BLACKLISTS_STATUS = "blacklists_status";
    public static final int BLACKLISTS_UPDATE_SECONDS = 86400; // 1d
    private static final String TAG = "Blacklists";
    private final ArrayList<BlacklistDescriptor> mLists = new ArrayList<>();
    private final HashMap<String, BlacklistDescriptor> mListByFname = new HashMap<>();
    private final ArrayList<BlacklistsStateListener> mListeners = new ArrayList<>();
    private final SharedPreferences mPrefs;
    private final Context mContext;
    long last_update;
    boolean first_update;
    int num_domain_rules;
    int num_ip_rules;

    public Blacklists(Context ctx) {
        last_update = 0;
        num_domain_rules = 0;
        num_ip_rules = 0;
        mContext = ctx;
        first_update = true;
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

            last_update = obj.getAsJsonPrimitive("last_update").getAsLong();
            num_domain_rules = obj.getAsJsonPrimitive("num_domain_rules").getAsInt();
            num_ip_rules = obj.getAsJsonPrimitive("num_ip_rules").getAsInt();

            JsonObject blacklists_obj = obj.getAsJsonObject("blacklists");
            if(blacklists_obj != null) { // support old format
                for(Map.Entry<String, JsonElement> bl_entry: blacklists_obj.entrySet()) {
                    BlacklistDescriptor bl = mListByFname.get(bl_entry.getKey());
                    if(bl != null) {
                        JsonObject bl_obj = bl_entry.getValue().getAsJsonObject();

                        bl.num_rules = bl_obj.getAsJsonPrimitive("num_rules").getAsInt();
                        bl.setUpdated(bl_obj.getAsJsonPrimitive("last_update").getAsLong());
                    }
                }
            }
        }
    }

    private static class Serializer implements JsonSerializer<Blacklists> {
        @Override
        public JsonElement serialize(Blacklists src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject blacklists_obj = new JsonObject();

            for(BlacklistDescriptor bl: src.mLists) {
                JsonObject bl_obj = new JsonObject();

                bl_obj.add("num_rules", new JsonPrimitive(bl.num_rules));
                bl_obj.add("last_update", new JsonPrimitive(bl.getLastUpdate()));
                blacklists_obj.add(bl.fname, bl_obj);
            }

            JsonObject rv = new JsonObject();
            rv.add("last_update", new JsonPrimitive(src.last_update));
            rv.add("num_domain_rules", new JsonPrimitive(src.num_domain_rules));
            rv.add("num_ip_rules", new JsonPrimitive(src.num_ip_rules));
            rv.add("blacklists", blacklists_obj);

            return rv;
        }
    }

    public String toJson() {
        Gson gson = new GsonBuilder().registerTypeAdapter(getClass(), new Serializer())
                .create();
        return gson.toJson(this);
    }

    public void save() {
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

    public boolean needsUpdate() {
        long now = System.currentTimeMillis();
        return((now - last_update) >= BLACKLISTS_UPDATE_SECONDS * 1000)
                || (first_update && (getNumUpdatedBlacklists() < getNumBlacklists()));
    }

    public void update() {
        // NOTE: invoked in a separate thread (CaptureService.mBlacklistsUpdateThread)
        if(!needsUpdate())
            return;

        Log.d(TAG, "Updating " + mLists.size() + " blacklists...");
        first_update = false;

        for(BlacklistDescriptor bl: mLists) {
            Log.d(TAG, "\tupdating " + bl.fname + "...");

            if(Utils.downloadFile(bl.url, getListPath(bl)))
                bl.setUpdated(System.currentTimeMillis());
            else
                bl.setOutdated();
        }

        last_update = System.currentTimeMillis();
        notifyListeners();
    }

    public static class NativeBlacklistStatus {
        public final String fname;
        public final int num_rules;

        public NativeBlacklistStatus(String fname, int num_rules) {
            this.fname = fname;
            this.num_rules = num_rules;
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
                bl.num_rules = bl_status.num_rules;
                bl.loaded = true;

                if(bl.type == BlacklistDescriptor.Type.DOMAIN_BLACKLIST)
                    num_domains += bl_status.num_rules;
                else
                    num_ips += bl_status.num_rules;

                num_loaded++;
            } else
                Log.w(TAG, "Loaded unknown blacklist " + bl_status.fname);
        }

        Log.d(TAG, "Blacklists loaded: " + num_loaded + " lists, " + num_domains + " domains, " + num_ips + " IPs");
        num_domain_rules = num_domains;
        num_ip_rules = num_ips;
        notifyListeners();
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
        int ctr = 0;

        for(BlacklistDescriptor bl: mLists) {
            if(bl.isUpToDate())
                ctr++;
        }

        return ctr;
    }

    private void notifyListeners() {
        for(BlacklistsStateListener listener: mListeners)
            listener.onBlacklistsStateChanged();
    }

    public void addOnChangeListener(BlacklistsStateListener listener) {
        mListeners.add(listener);
    }

    public void removeOnChangeListener(BlacklistsStateListener listener) {
        mListeners.remove(listener);
    }
}

