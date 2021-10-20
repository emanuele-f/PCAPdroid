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
import java.util.HashMap;
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
    private final HashMap<String, Rule> mMatches = new HashMap<>();

    public enum RuleType {
        APP,
        IP,
        HOST,
        ROOT_DOMAIN,
        PROTOCOL
    }

    public static class Rule {
        private final String mLabel;
        private final RuleType mType;
        private final Object mValue;

        Rule(RuleType tp, Object value, String label) {
            mLabel = label;
            mType = tp;
            mValue = value;
        }

        public Rule(Context ctx, RuleType tp, Object value) {
            this(tp, value, MatchList.getLabel(ctx, tp, value.toString()));
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

    public static String getLabel(Context ctx, RuleType tp, String value) {
        int resid;

        switch(tp) {
            case APP:           resid = R.string.app_val; break;
            case IP:            resid = R.string.ip_address_val; break;
            case ROOT_DOMAIN:   value = "*" + value; // fallthrough
            case HOST:          resid = R.string.host_val; break;
            case PROTOCOL:      resid = R.string.protocol_val; break;
            default:
                return "";
        }

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

        AppsResolver resolver = new AppsResolver(mContext);

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
            String valLabel = val;

            if(type == RuleType.APP) {
                AppDescriptor app = resolver.get(Integer.parseInt(val), 0);

                if(app != null)
                    valLabel = app.getName();
            }

            String label = getLabel(mContext, type, valLabel);
            addRule(new Rule(type, val, label));
        }
    }

    public void addApp(int uid, String label)        { addRule(new Rule(RuleType.APP, uid, label)); }
    public void addIp(String ip, String label)       { addRule(new Rule(RuleType.IP, ip, label)); }
    public void addHost(String info, String label)   { addRule(new Rule(RuleType.HOST, info, label)); }
    public void addProto(String proto, String label) { addRule(new Rule(RuleType.PROTOCOL, proto, label)); }
    public void addRootDomain(String domain, String label) { addRule(new Rule(RuleType.ROOT_DOMAIN, domain, label)); }

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

    public boolean matches(ConnectionDescriptor conn) {
        boolean hasInfo = ((conn.info != null) && (!conn.info.isEmpty()));

        return(mMatches.containsKey(matchKey(RuleType.APP, conn.uid)) ||
                mMatches.containsKey(matchKey(RuleType.IP, conn.dst_ip)) ||
                mMatches.containsKey(matchKey(RuleType.PROTOCOL, conn.l7proto)) ||
                (hasInfo && mMatches.containsKey(matchKey(RuleType.HOST, conn.info))) ||
                (hasInfo && mMatches.containsKey(matchKey(RuleType.ROOT_DOMAIN, Utils.getRootDomain(conn.info)))));
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
