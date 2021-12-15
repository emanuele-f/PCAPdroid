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
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.AppsResolver;
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
    private final ArrayList<Rule> mRules = new ArrayList<>();
    private final ArrayMap<String, Rule> mMatches = new ArrayMap<>();

    public enum RuleType {
        APP,
        IP,
        HOST,
        ROOT_DOMAIN,
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

    public MatchList(Context ctx, String pref_name) {
        mContext = ctx;
        mPrefName = pref_name; // The preference to bake the list rules
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        reload();
    }

    public void reload() {
        String serialized = mPrefs.getString(mPrefName, "");
        //Log.d(TAG, serialized);

        if(!serialized.isEmpty())
            fromJson(serialized);
        else
            clear();
    }

    public void save() {
        mPrefs.edit()
                .putString(mPrefName, toJson())
                .apply();
    }

    public static String getRuleLabel(Context ctx, RuleType tp, String value) {
        int resid;

        switch(tp) {
            case APP:           resid = R.string.app_val; break;
            case IP:            resid = R.string.ip_address_val; break;
            case ROOT_DOMAIN:   value = "*" + value; // fallthrough
            case HOST:          resid = R.string.host_val; break;
            case PROTOCOL:      resid = R.string.protocol_val; break;
            case COUNTRY:       resid = R.string.country_val; break;
            default:
                return "";
        }

        if(tp == RuleType.APP) {
            AppsResolver resolver = new AppsResolver(ctx);
            AppDescriptor app = resolver.get(Integer.parseInt(value), 0);

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

    private void deserialize(JsonObject object) {
        clear();

        JsonArray ruleArray = object.getAsJsonArray("rules");
        if(ruleArray == null)
            return;

        for(JsonElement el: ruleArray) {
            JsonObject ruleObj = el.getAsJsonObject();
            RuleType type;

            try {
                type = RuleType.valueOf(ruleObj.get("type").getAsString());
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
                continue;
            }

            String val = ruleObj.get("value").getAsString();
            addRule(new Rule(type, val));
        }
    }

    public void addApp(int uid)        { addRule(new Rule(RuleType.APP, uid)); }
    public void addIp(String ip)       { addRule(new Rule(RuleType.IP, ip)); }
    public void addHost(String info)   { addRule(new Rule(RuleType.HOST, Utils.cleanDomain(info))); }
    public void addProto(String proto) { addRule(new Rule(RuleType.PROTOCOL, proto)); }
    public void addRootDomain(String domain)    { addRule(new Rule(RuleType.ROOT_DOMAIN, domain)); }
    public void addCountry(String country_code) { addRule(new Rule(RuleType.COUNTRY, country_code)); }

    static private String matchKey(RuleType tp, Object val) {
        return tp + "@" + val;
    }

    private void addRule(Rule rule) {
        String key = matchKey(rule.getType(), rule.getValue().toString());

        if(!mMatches.containsKey(key)) {
            mRules.add(rule);
            mMatches.put(key, rule);
        }
    }

    public void removeRules(List<Rule> rules) {
        mRules.removeAll(rules);

        for(Rule rule: rules) {
            String key = matchKey(rule.getType(), rule.getValue().toString());
            mMatches.remove(key);
        }
    }

    public boolean matchesApp(int uid) {
        return mMatches.containsKey(matchKey(RuleType.APP, uid));
    }

    public boolean matchesIP(String ip) {
        return mMatches.containsKey(matchKey(RuleType.IP, ip));
    }

    public boolean matchesProto(String l7proto) {
        return mMatches.containsKey(matchKey(RuleType.PROTOCOL, l7proto));
    }

    public boolean matchesHost(String host) {
        return mMatches.containsKey(matchKey(RuleType.HOST, Utils.cleanDomain(host)));
    }

    public boolean matchesRootDomain(String root_domain) {
        return mMatches.containsKey(matchKey(RuleType.ROOT_DOMAIN, root_domain));
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
                (hasInfo && matchesHost(conn.info))) ||
                (hasInfo && matchesRootDomain(Utils.getRootDomain(conn.info)));
    }

    public Iterator<Rule> iterRules() {
        return mRules.iterator();
    }

    public void clear() {
        mRules.clear();
        mMatches.clear();
    }

    public boolean isEmpty() {
        return(mRules.size() == 0);
    }

    public String toJson() {
        Gson gson = new GsonBuilder().registerTypeAdapter(getClass(), new Serializer())
                .create();

        String serialized = gson.toJson(this);
        //Log.d(TAG, "toJson: " + serialized);

        return serialized;
    }

    public void fromJson(String json_str) {
        JsonObject obj = JsonParser.parseString(json_str).getAsJsonObject();
        deserialize(obj);
    }
}
