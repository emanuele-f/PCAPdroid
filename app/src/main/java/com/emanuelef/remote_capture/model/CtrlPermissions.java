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
import android.util.ArrayMap;

import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.Iterator;
import java.util.Map;

public class CtrlPermissions {
    private static final String PREF_NAME = "ctrl_perms";
    private final ArrayMap<String, Rule> mRules = new ArrayMap<>();
    private final SharedPreferences mPrefs;

    public enum ConsentType {
        UNSPECIFIED,
        ALLOW,
        DENY,
    }

    public static class Rule {
        public final String package_name;
        public final ConsentType consent;

        public Rule(String _package_name, ConsentType tp) {
            package_name = _package_name;
            consent = tp;
        }
    }

    public CtrlPermissions(Context ctx) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        reload();
    }

    public void reload() {
        String serialized = mPrefs.getString(PREF_NAME, "");
        //Log.d(TAG, serialized);

        if(!serialized.isEmpty()) {
            JsonObject obj = JsonParser.parseString(serialized).getAsJsonObject();
            deserialize(obj);
        } else
            mRules.clear();
    }

    private void deserialize(JsonObject object) {
        mRules.clear();

        JsonObject rules = object.getAsJsonObject("rules");
        if(rules == null)
            return;

        for(Map.Entry<String, JsonElement> rule: rules.entrySet()) {
            if(rule.getValue().isJsonPrimitive() && rule.getValue().getAsJsonPrimitive().isString()) {
                String val = rule.getValue().getAsJsonPrimitive().getAsString();

                try {
                    ConsentType tp = ConsentType.valueOf(val);
                    mRules.put(rule.getKey(), new Rule(rule.getKey(), tp));
                } catch (IllegalArgumentException ignored) {}
            }
        }
    }

    private static class Serializer implements JsonSerializer<CtrlPermissions> {
        @Override
        public JsonElement serialize(CtrlPermissions src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject result = new JsonObject();
            JsonObject rulesObj = new JsonObject();

            for(Rule rule: src.mRules.values()) {
                rulesObj.add(rule.package_name, new JsonPrimitive(rule.consent.toString()));
            }

            result.add("rules", rulesObj);
            return result;
        }
    }

    private void save() {
        Gson gson = new GsonBuilder().registerTypeAdapter(getClass(), new Serializer())
                .create();

        String serialized = gson.toJson(this);
        //Log.d(TAG, "json: " + serialized);

        mPrefs.edit()
                .putString(PREF_NAME, serialized)
                .apply();
    }

    public void add(String package_name, ConsentType tp) {
        mRules.put(package_name, new Rule(package_name, tp));
        save();
    }

    public void remove(String package_name) {
        mRules.remove(package_name);
        save();
    }

    public void removeAll() {
        mRules.clear();
        save();
    }

    public Iterator<Rule> iterRules() {
        return mRules.values().iterator();
    }

    public boolean hasRules() {
        return !mRules.isEmpty();
    }

    public ConsentType getConsent(String package_name) {
        Rule rule = mRules.get(package_name);
        if(rule == null)
            return ConsentType.UNSPECIFIED;
        return rule.consent;
    }
}
