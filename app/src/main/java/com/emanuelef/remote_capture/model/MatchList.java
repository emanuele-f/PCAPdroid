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
import android.graphics.Typeface;
import android.text.style.StyleSpan;
import android.util.ArrayMap;

import androidx.annotation.Nullable;
import androidx.collection.ArraySet;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/* Matches connections against the configured rules. */
public class MatchList {
    private static final String TAG = "MatchList";
    private static final StyleSpan italic = new StyleSpan(Typeface.ITALIC);
    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final String mPrefName;
    private final ArrayList<ListChangeListener> mListeners = new ArrayList<>();
    private final ArrayList<Rule> mRules = new ArrayList<>();
    private final ArrayMap<String, Rule> mMatches = new ArrayMap<>();
    private final ArraySet<Integer> mUids = new ArraySet<>();
    private final ArrayMap<String, Integer> mPackageToUid = new ArrayMap<>();
    private final AppsResolver mResolver;
    private boolean mMigration = false;

    public enum RuleType {
        APP,
        IP,
        HOST,
        PROTOCOL,
        COUNTRY
    }

    public class Rule {
        private final String mLabel;
        private final RuleType mType;
        private final Object mValue;

        private Rule(RuleType tp, Object value) {
            mLabel = MatchList.getRuleLabel(mContext, tp, value.toString());
            mType = tp;
            mValue = value;
        }

        public String getLabel() {
            return mLabel;
        }

        public RuleType getType() {
            return mType;
        }

        public Object getValue() {
            return mValue;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if(!(obj instanceof Rule))
                return super.equals(obj);

            Rule other = (Rule) obj;
            return((mType == other.mType) && (mValue.equals(other.mValue)));
        }
    }

    public interface ListChangeListener {
        void onListChanged();
    }

    public static class ListDescriptor {
        public final List<String> apps = new ArrayList<>();
        public final List<String> hosts = new ArrayList<>();
        public final List<String> ips = new ArrayList<>();
    }

    public MatchList(Context ctx, String pref_name) {
        mContext = ctx;
        mPrefName = pref_name; // The preference to bake the list rules
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        mResolver = new AppsResolver(ctx);
        reload();
    }

    public void reload() {
        String serialized = mPrefs.getString(mPrefName, "");
        //Log.d(TAG, serialized);

        if(!serialized.isEmpty()) {
            fromJson(serialized);

            if(mMigration) {
                Log.i(TAG, "Migration completed");
                save();
                mMigration = false;
            }
        } else
            clear();
    }

    public void save() {
        mPrefs.edit()
                .putString(mPrefName, toJson(false))
                .apply();
    }

    public static String getRuleLabel(Context ctx, RuleType tp, String value) {
        int resid;

        switch(tp) {
            case APP:           resid = R.string.app_val; break;
            case IP:            resid = R.string.ip_address_val; break;
            case HOST:          resid = R.string.host_val; break;
            case PROTOCOL:      resid = R.string.protocol_val; break;
            case COUNTRY:       resid = R.string.country_val; break;
            default:
                return "";
        }

        if(tp == RuleType.APP) {
            // TODO handle cross-users/profiles?
            AppDescriptor app = AppsResolver.resolveInstalledApp(ctx.getPackageManager(), value, 0, false);
            if(app != null)
                value = app.getName();
        } else if(tp == RuleType.HOST)
            value = Utils.cleanDomain(value);
        else if(tp == RuleType.COUNTRY)
            value = Utils.getCountryName(ctx, value);

        return Utils.formatTextValue(ctx, null, italic, resid, value).toString();
    }

    private static class Serializer implements JsonSerializer<MatchList> {
        @Override
        public JsonElement serialize(MatchList src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject result = new JsonObject();
            JsonArray rulesArr = new JsonArray();

            for(Rule rule : src.mRules) {
                JsonObject ruleObject = new JsonObject();

                ruleObject.add("type", new JsonPrimitive(rule.getType().name()));
                ruleObject.add("value", new JsonPrimitive(rule.getValue().toString()));

                rulesArr.add(ruleObject);
            }

            result.add("rules", rulesArr);
            return result;
        }
    }

    private int deserialize(JsonObject object, int max_rules) {
        int num_rules = 0;

        try {
            JsonArray ruleArray = object.getAsJsonArray("rules");
            if(ruleArray == null)
                return -1;

            clear(false);

            for(JsonElement el: ruleArray) {
                JsonObject ruleObj = el.getAsJsonObject();
                String typeStr = ruleObj.get("type").getAsString();
                String val = ruleObj.get("value").getAsString();
                RuleType type;

                try {
                    type = RuleType.valueOf(typeStr);
                } catch (IllegalArgumentException e) {
                    // can happen if format is changed
                    if(typeStr.equals("ROOT_DOMAIN")) {
                        Log.i(TAG, String.format("ROOT_DOMAIN %s migrated", val));
                        type = RuleType.HOST;
                        mMigration = true;
                    } else {
                        e.printStackTrace();
                        continue;
                    }
                }

                if(type == RuleType.APP) {
                    // Handle migration from the old uid-based format
                    try {
                        int uid = Integer.parseInt(val);

                        AppDescriptor app = mResolver.getAppByUid(uid, 0);
                        if(app != null) {
                            val = app.getPackageName();
                            Log.i(TAG, String.format("UID %d resolved to package %s", uid, val));
                            mMigration = true;
                        } else {
                            Log.w(TAG, "Ignoring unknown UID " + uid);
                            continue;
                        }
                    } catch (NumberFormatException ignored) {
                        // ok, package name
                    }

                    // Validate the uid->package_name mapping (see AppsResolver for more details).
                    // If the uid is mapped to a different package name, we must update the MatchList
                    // otherwise the user may not be able to remove the rule (see #257).
                    AppDescriptor app = mResolver.getAppByPackage(val, 0);
                    if((app != null) && !app.getPackageName().equals(val)) {
                        Log.i(TAG, "The UID " + app.getUid() + " mapping has changed from " + val + " to " + app.getPackageName());
                        val = app.getPackageName();
                        mMigration = true;
                    }
                }

                if(addRule(new Rule(type, val), false)) {
                    num_rules += 1;

                    if((max_rules > 0) && (num_rules >= max_rules))
                        break;
                }
            }

            notifyListeners();
        } catch (IllegalArgumentException | ClassCastException e) {
            e.printStackTrace();
            return -1;
        }

        return num_rules;
    }

    public boolean addIp(String ip)       { return addRule(new Rule(RuleType.IP, ip)); }
    public boolean addHost(String info)   { return addRule(new Rule(RuleType.HOST, Utils.cleanDomain(info))); }
    public boolean addProto(String proto) { return addRule(new Rule(RuleType.PROTOCOL, proto)); }
    public boolean addCountry(String country_code) { return addRule(new Rule(RuleType.COUNTRY, country_code)); }
    public boolean addApp(String pkg)     { return addRule(new Rule(RuleType.APP, pkg)); }
    public boolean addApp(int uid) {
        AppDescriptor app = mResolver.getAppByUid(uid, 0);
        if(app == null) {
            Log.e(TAG, "could not resolve UID " + uid);
            return false;
        }

        // apps must be identified by their package name to work across installations
        return addApp(app.getPackageName());
    }

    public void removeIp(String ip)       { removeRule(new Rule(RuleType.IP, ip)); }
    public void removeHost(String info)   { removeRule(new Rule(RuleType.HOST, Utils.cleanDomain(info))); }
    public void removeProto(String proto) { removeRule(new Rule(RuleType.PROTOCOL, proto)); }
    public void removeCountry(String country_code) { removeRule(new Rule(RuleType.COUNTRY, country_code)); }
    public void removeApp(String pkg)     { removeRule(new Rule(RuleType.APP, pkg)); }
    public void removeApp(int uid) {
        AppDescriptor app = mResolver.getAppByUid(uid, 0);
        if(app == null) {
            Log.e(TAG, "could not resolve UID " + uid);
            return;
        }

        removeApp(app.getPackageName());
    }

    static private String matchKey(RuleType tp, Object val) {
        return tp + "@" + val;
    }

    private boolean addRule(Rule rule, boolean notify) {
        String value = rule.getValue().toString();
        String key = matchKey(rule.getType(), value);

        if(mMatches.containsKey(key))
            return false;

        if(rule.getType() == RuleType.APP) {
            // Need uid for match
            int uid = mResolver.getUid(value);
            if(uid == Utils.UID_NO_FILTER)
                return false;

            mPackageToUid.put(value, uid);
            mUids.add(uid);
        }

        mRules.add(rule);
        mMatches.put(key, rule);
        if(notify)
            notifyListeners();
        return true;
    }

    private boolean addRule(Rule rule) {
        return addRule(rule, true);
    }

    public int addRules(MatchList to_add) {
        int num_added = 0;

        for(Iterator<Rule> it = to_add.iterRules(); it.hasNext(); ) {
            Rule rule = it.next();

            if(addRule(rule, false))
                num_added++;
        }

        if(num_added > 0)
            notifyListeners();

        return num_added;
    }

    public void removeRule(Rule rule) {
        String val = rule.getValue().toString();
        String key = matchKey(rule.getType(), val);
        boolean removed = mRules.remove(rule);
        mMatches.remove(key);

        if(rule.getType() == RuleType.APP) {
            int uid = mResolver.getUid(val);
            if(uid != Utils.UID_NO_FILTER) {
                mPackageToUid.remove(val);
                mUids.remove(uid);
            } else
                Log.w(TAG, "removeRule: no uid found for package " + val);
        }

        if(removed)
            notifyListeners();
    }

    public boolean matchesApp(int uid) {
        // match apps based on their uid (faster) rather than their package name
        return mUids.contains(uid);
    }

    public boolean matchesIP(String ip) {
        return mMatches.containsKey(matchKey(RuleType.IP, ip));
    }

    public boolean matchesProto(String l7proto) {
        return mMatches.containsKey(matchKey(RuleType.PROTOCOL, l7proto));
    }

    public boolean matchesExactHost(String host) {
        host = Utils.cleanDomain(host);
        return mMatches.containsKey(matchKey(RuleType.HOST, host));
    }

    public boolean matchesHost(String host) {
        // Keep in sync with the native blacklist_match_domain
        host = Utils.cleanDomain(host);

        // exact domain match
        if(matchesExactHost(host))
            return true;

        // 2nd-level domain match
        String domain = Utils.getSecondLevelDomain(host);
        return !domain.equals(host) && mMatches.containsKey(matchKey(RuleType.HOST, domain));
    }

    public boolean matchesCountry(String country_code) {
        return mMatches.containsKey(matchKey(RuleType.COUNTRY, country_code));
    }

    public boolean matches(ConnectionDescriptor conn) {
        if(mMatches.isEmpty())
            return false;

        boolean hasInfo = ((conn.info != null) && (!conn.info.isEmpty()));
        return(matchesApp(conn.uid) ||
                matchesIP(conn.dst_ip) ||
                matchesProto(conn.l7proto) ||
                matchesCountry(conn.country) ||
                (hasInfo && matchesHost(conn.info)));
    }

    public Iterator<Rule> iterRules() {
        return mRules.iterator();
    }

    public void clear(boolean notify) {
        boolean hasRules = mRules.size() > 0;
        mRules.clear();
        mMatches.clear();
        mPackageToUid.clear();
        mUids.clear();

        if(notify && hasRules)
            notifyListeners();
    }

    public void clear() {
        clear(true);
    }

    public boolean isEmpty() {
        return(mRules.size() == 0);
    }

    public int getSize() {
        return mRules.size();
    }

    public String toJson(boolean pretty_print) {
        GsonBuilder builder = new GsonBuilder().registerTypeAdapter(getClass(), new Serializer());
        if(pretty_print)
            builder.setPrettyPrinting();
        Gson gson = builder.create();

        String serialized = gson.toJson(this);
        //Log.d(TAG, "toJson: " + serialized);

        return serialized;
    }

    public int fromJson(String json_str, int max_rules) {
        try {
            JsonElement el = JsonParser.parseString(json_str);
            if(!el.isJsonObject())
              return -1;

            return deserialize(el.getAsJsonObject(), max_rules);
        } catch (JsonSyntaxException e) {
            e.printStackTrace();
            return -1;
        }
    }

    public int fromJson(String json_str) {
        return fromJson(json_str, -1);
    }

    // can be used by a subclass to exempt specific app (e.g. Blocklist grace apps)
    protected boolean isExemptedApp(int uid) {
        return false;
    }

    /* Convert the MatchList into a ListDescriptor, which can be then loaded by JNI.
     * Only the following RuleTypes are supported: APP, IP, HOST.
     */
    public ListDescriptor toListDescriptor() {
        final ListDescriptor rv = new ListDescriptor();

        Iterator<MatchList.Rule> it = iterRules();
        while(it.hasNext()) {
            MatchList.Rule rule = it.next();
            MatchList.RuleType tp = rule.getType();
            String val = rule.getValue().toString();

            if(tp.equals(MatchList.RuleType.HOST))
                rv.hosts.add(val);
            else if(tp.equals(MatchList.RuleType.IP))
                rv.ips.add(val);
            else if(!tp.equals(MatchList.RuleType.APP)) // apps handled below
                Log.w(TAG, "ListDescriptor does not support RuleType " + tp.name());
        }

        // Apps are matched via their UID
        for(int uid: mUids) {
            if(!isExemptedApp(uid))
                rv.apps.add(Integer.toString(uid));
        }

        return rv;
    }

    public void addListChangeListener(ListChangeListener listener) {
        mListeners.add(listener);
    }

    public void removeListChangeListener(ListChangeListener listener) {
        mListeners.remove(listener);
    }

    private void notifyListeners() {
        for(ListChangeListener listener: mListeners)
            listener.onListChanged();
    }

    /* Call this whenever a package name -> uid mapping may have changed.
     * True is returned when the mapping has been updated. In such a case,
     * the caller must reload any native rules based on this MatchList. */
    public boolean uidMappingChanged(String pkg) {
        if(!mMatches.containsKey(matchKey(RuleType.APP, pkg)))
            return false;

        boolean changed = false;
        Integer old_uid = mPackageToUid.get(pkg);
        AppDescriptor app = mResolver.getAppByPackage(pkg, 0);

        if((old_uid != null) && ((app == null) || (app.getUid() != old_uid))) {
            Log.i(TAG, "Remove old UID mapping of " + pkg + ": " + old_uid);

            mPackageToUid.remove(pkg);
            mUids.remove(old_uid);
            changed = true;

            old_uid = null; // possibly add the new UID mapping below
        }

        if((old_uid == null) && (app != null)) {
            int new_uid = app.getUid();
            Log.i(TAG, "Add UID mapping of " + pkg + ": " + new_uid);

            mPackageToUid.put(pkg, new_uid);
            mUids.add(new_uid);
            changed = true;
        }

        return changed;
    }
}
