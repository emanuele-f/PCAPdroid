package com.emanuelef.remote_capture;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.ArrayMap;

import androidx.collection.ArraySet;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.interfaces.BlacklistsStateListener;
import com.emanuelef.remote_capture.model.BlacklistDescriptor;
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
    public static final long BLACKLISTS_UPDATE_MILLIS = 86400 * 1000; // 1d
    private static final String TAG = "Blacklists";
    private final ArrayList<BlacklistDescriptor> mLists = new ArrayList<>();
    private final ArrayMap<String, BlacklistDescriptor> mListByFname = new ArrayMap<>();
    private final ArrayList<BlacklistsStateListener> mListeners = new ArrayList<>();
    private final SharedPreferences mPrefs;
    private final Context mContext;
    private boolean mUpdateInProgress;
    private boolean mStopRequest;
    private long mLastUpdate;
    private long mLastUpdateMonotonic;
    private int mNumDomainRules;
    private int mNumIPRules;

    public Blacklists(Context ctx) {
        mLastUpdate = 0;
        mLastUpdateMonotonic = -BLACKLISTS_UPDATE_MILLIS;
        mNumDomainRules = 0;
        mNumIPRules = 0;
        mContext = ctx;
        mUpdateInProgress = false;
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
        addList("CINS Army", BlacklistDescriptor.Type.IP_BLACKLIST, "ci_badguys.txt",
                "https://cinsscore.com/list/ci-badguys.txt");
        
        // Experimental blacklists
        addList("Phishing Army", BlacklistDescriptor.Type.IP_BLACKLIST,  "phishing_army_blocklist.txt",
                "https://phishing.army/download/phishing_army_blocklist.txt");
        addList("SNORT", BlacklistDescriptor.Type.IP_BLACKLIST,  "ip-block-list",
                "https://snort.org/downloads/ip-block-list");
        
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
            mLastUpdate = obj.getAsJsonPrimitive("last_update").getAsLong();
            mNumDomainRules = obj.getAsJsonPrimitive("num_domain_rules").getAsInt();
            mNumIPRules = obj.getAsJsonPrimitive("num_ip_rules").getAsInt();

            // set the monotonic time based on the last update wall clock time
            long millis_since_last_update = System.currentTimeMillis() - mLastUpdate;
            if (millis_since_last_update > 0)
                mLastUpdateMonotonic = SystemClock.elapsedRealtime() - millis_since_last_update;

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
            rv.add("last_update", new JsonPrimitive(src.mLastUpdate));
            rv.add("num_domain_rules", new JsonPrimitive(src.mNumDomainRules));
            rv.add("num_ip_rules", new JsonPrimitive(src.mNumIPRules));
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
        ArraySet<File> validLists = new ArraySet<>();

        // Ensure that all the lists files exist, otherwise force update
        for(BlacklistDescriptor bl: mLists) {
            File f = new File(getListPath(bl));
            validLists.add(f);

            if(!f.exists()) {
                // must update
                mLastUpdateMonotonic = -BLACKLISTS_UPDATE_MILLIS;
            }
        }

        // Ensure that only the specified lists exist
        File bldir = new File(mContext.getFilesDir().getPath() + "/malware_bl");
        bldir.mkdir();
        File[] files = bldir.listFiles();
        if(files != null) {
            for(File f: files) {
                if(!validLists.contains(f)) {
                    Log.i(TAG, "Removing unknown list: " + f.getPath());

                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
    }

    public boolean needsUpdate(boolean firstUpdate) {
        long now = SystemClock.elapsedRealtime();
        return (((now - mLastUpdateMonotonic) >= BLACKLISTS_UPDATE_MILLIS)
                || (firstUpdate && (getNumUpdatedBlacklists() < getNumBlacklists())));
    }

    // NOTE: invoked in a separate thread (CaptureService.mBlacklistsUpdateThread)
    public void update() {
        mUpdateInProgress = true;
        mStopRequest = false;
        for(BlacklistDescriptor bl: mLists)
            bl.setUpdating();
        notifyListeners();

        Log.i(TAG, "Updating " + mLists.size() + " blacklists...");

        for(BlacklistDescriptor bl: mLists) {
            if(mStopRequest) {
                Log.i(TAG, "Stop request received, abort");
                break;
            }

            Log.i(TAG, "\tupdating " + bl.fname + "...");

            if(Utils.downloadFile(bl.url, getListPath(bl)))
                bl.setUpdated(System.currentTimeMillis());
            else
                bl.setOutdated();

            notifyListeners();
        }

        mLastUpdate = System.currentTimeMillis();
        mLastUpdateMonotonic = SystemClock.elapsedRealtime();
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
        ArraySet<String> loaded = new ArraySet<>();

        for(NativeBlacklistStatus bl_status: loaded_blacklists) {
            if(bl_status == null)
                break;

            BlacklistDescriptor bl = mListByFname.get(bl_status.fname);
            if(bl != null) {
                // Update the number of rules
                bl.num_rules = bl_status.num_rules;
                bl.loaded = true;
                loaded.add(bl.fname);

                if(bl.type == BlacklistDescriptor.Type.DOMAIN_BLACKLIST)
                    num_domains += bl_status.num_rules;
                else
                    num_ips += bl_status.num_rules;

                num_loaded++;
            } else
                Log.w(TAG, "Loaded unknown blacklist " + bl_status.fname);
        }

        for(BlacklistDescriptor bl: mLists) {
            if(!loaded.contains(bl.fname)) {
                Log.w(TAG, "Blacklist not loaded: " + bl.fname);
                bl.loaded = false;
            }
        }

        Log.i(TAG, "Blacklists loaded: " + num_loaded + " lists, " + num_domains + " domains, " + num_ips + " IPs");
        mNumDomainRules = num_domains;
        mNumIPRules = num_ips;
        mUpdateInProgress = false;
        notifyListeners();
    }

    public Iterator<BlacklistDescriptor> iter() {
        return mLists.iterator();
    }

    public int getNumLoadedDomainRules() {
        return mNumDomainRules;
    }

    public int getNumLoadedIPRules() {
        return mNumIPRules;
    }

    public long getLastUpdate() {
        return mLastUpdate;
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

    public boolean isUpdateInProgress() {
        return mUpdateInProgress;
    }

    public void abortUpdate() {
        mStopRequest = true;
    }
}