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
 * Copyright 2026 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.model;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArrayMap;
import androidx.collection.ArraySet;

import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.Blacklists;
import com.emanuelef.remote_capture.BuildConfig;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.PersistableUriPermission;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.Set;

/* A portable snapshot of the app preferences, used to move the configuration to another device
 * or to another build of PCAPdroid (e.g. from F-Droid to the Play Store) */
public class SettingsBackup {
    private static final String TAG = "SettingsBackup";
    public static final int VERSION = 1;
    public static final String LICENSE_KEY = "license";

    private static final Set<String> EXCLUDED_KEYS = new ArraySet<>();

    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_INT = "int";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_FLOAT = "float";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_STRING_SET = "string_set";

    static {
        // the mitm addon and its CA certificate must be set up again on the new installation
        EXCLUDED_KEYS.add(Prefs.PREF_TLS_DECRYPTION_SETUP_DONE);
        EXCLUDED_KEYS.add(Prefs.PREF_CA_INSTALLATION_SKIPPED);

        EXCLUDED_KEYS.add(Prefs.PREF_APP_VERSION);
        EXCLUDED_KEYS.add(PersistableUriPermission.PREF_KEY);
        EXCLUDED_KEYS.add(Blacklists.PREF_BLACKLISTS_STATUS);
        EXCLUDED_KEYS.addAll(Billing.BILLING_STATE_KEYS);
    }

    private final ArrayMap<String, Object> mSettings = new ArrayMap<>();
    private int mAppVersion;
    private long mCreated;

    public static boolean isExcluded(String key) {
        return EXCLUDED_KEYS.contains(key) || key.startsWith(Billing.SKU_PREF_PREFIX);
    }

    private static boolean requiresMerge(String key) {
        return key.equals(Prefs.PREF_CAPTURE_LIST);
    }

    public static String serialize(SharedPreferences prefs) {
        JsonObject settings = new JsonObject();

        for (Map.Entry<String, ?> entry: prefs.getAll().entrySet()) {
            if (isExcluded(entry.getKey()))
                continue;

            JsonObject encoded = encode(entry.getValue());
            if (encoded != null)
                settings.add(entry.getKey(), encoded);
        }

        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("app_version", BuildConfig.VERSION_CODE);
        root.addProperty("created", System.currentTimeMillis());
        root.add("settings", settings);

        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    public static @Nullable SettingsBackup fromJson(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            int version = root.getAsJsonPrimitive("version").getAsInt();
            if (version > VERSION) {
                Log.w(TAG, "unsupported backup version: " + version);
                return null;
            }

            SettingsBackup rv = new SettingsBackup();
            rv.mAppVersion = root.getAsJsonPrimitive("app_version").getAsInt();
            rv.mCreated = root.getAsJsonPrimitive("created").getAsLong();

            for (Map.Entry<String, JsonElement> entry: root.getAsJsonObject("settings").entrySet()) {
                Object value = decode(entry.getValue().getAsJsonObject());
                if (value != null)
                    rv.mSettings.put(entry.getKey(), value);
            }

            if (rv.mSettings.isEmpty())
                return null;

            return rv;
        } catch (RuntimeException e) {
            Log.e(TAG, "fromJson: " + e.getMessage());
            return null;
        }
    }

    private static @Nullable JsonObject encode(Object value) {
        JsonObject rv = new JsonObject();

        if (value instanceof Boolean) {
            rv.addProperty("type", TYPE_BOOLEAN);
            rv.addProperty("value", (Boolean) value);
        } else if (value instanceof Integer) {
            rv.addProperty("type", TYPE_INT);
            rv.addProperty("value", (Integer) value);
        } else if (value instanceof Long) {
            rv.addProperty("type", TYPE_LONG);
            rv.addProperty("value", (Long) value);
        } else if (value instanceof Float) {
            rv.addProperty("type", TYPE_FLOAT);
            rv.addProperty("value", (Float) value);
        } else if (value instanceof String) {
            rv.addProperty("type", TYPE_STRING);
            rv.addProperty("value", (String) value);
        } else if (value instanceof Set) {
            JsonArray items = new JsonArray();
            for (Object item: (Set<?>) value)
                items.add(item.toString());

            rv.addProperty("type", TYPE_STRING_SET);
            rv.add("value", items);
        } else {
            Log.w(TAG, "unhandled preference type: " + value.getClass().getName());
            return null;
        }

        return rv;
    }

    private static @Nullable Object decode(JsonObject obj) {
        JsonElement type = obj.get("type");
        JsonElement value = obj.get("value");
        if ((type == null) || (value == null))
            return null;

        JsonPrimitive primitive = value.isJsonPrimitive() ? value.getAsJsonPrimitive() : null;

        switch (type.getAsString()) {
            case TYPE_BOOLEAN:
                return ((primitive != null) && primitive.isBoolean()) ? value.getAsBoolean() : null;
            case TYPE_INT:
                return ((primitive != null) && primitive.isNumber()) ? value.getAsInt() : null;
            case TYPE_LONG:
                return ((primitive != null) && primitive.isNumber()) ? value.getAsLong() : null;
            case TYPE_FLOAT:
                return ((primitive != null) && primitive.isNumber()) ? value.getAsFloat() : null;
            case TYPE_STRING:
                return ((primitive != null) && primitive.isString()) ? value.getAsString() : null;
            case TYPE_STRING_SET:
                if (!value.isJsonArray())
                    return null;

                ArraySet<String> items = new ArraySet<>();
                for (JsonElement item: value.getAsJsonArray()) {
                    if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString())
                        return null;

                    items.add(item.getAsString());
                }
                return items;
        }

        Log.w(TAG, "unhandled backup type: " + type.getAsString());
        return null;
    }

    /* Replaces the current preferences with the bundled ones. The excluded keys keep the value they
     * have on this device */
    @SuppressLint("ApplySharedPref")
    @SuppressWarnings("unchecked")
    public void apply(SharedPreferences prefs) {
        Map<String, ?> current = prefs.getAll();
        SharedPreferences.Editor editor = prefs.edit();

        for (Map.Entry<String, ?> entry: current.entrySet()) {
            String key = entry.getKey();
            Object imported = mSettings.get(key);

            if (!isExcluded(key) && !requiresMerge(key)
                    && ((imported == null) || hasCompatibleType(entry.getValue(), imported)))
                editor.remove(key);
        }

        for (int i = 0; i < mSettings.size(); i++) {
            String key = mSettings.keyAt(i);
            Object value = mSettings.valueAt(i);

            if (isExcluded(key) || requiresMerge(key))
                continue;

            Object oldValue = current.get(key);
            if ((oldValue != null) && !hasCompatibleType(oldValue, value)) {
                Log.w(TAG, "ignoring type change for preference " + key);
                continue;
            }

            if (value instanceof Boolean)
                editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer)
                editor.putInt(key, (Integer) value);
            else if (value instanceof Long)
                editor.putLong(key, (Long) value);
            else if (value instanceof Float)
                editor.putFloat(key, (Float) value);
            else if (value instanceof String)
                editor.putString(key, (String) value);
            else if (value instanceof Set)
                editor.putStringSet(key, (Set<String>) value);
        }

        editor.commit();
    }

    private static boolean hasCompatibleType(Object oldValue, Object newValue) {
        return ((oldValue instanceof Set) && (newValue instanceof Set))
                || oldValue.getClass().equals(newValue.getClass());
    }

    public long getCreationTime() {
        return mCreated;
    }

    public @NonNull String getString(String key) {
        Object value = mSettings.get(key);
        return (value instanceof String) ? (String) value : "";
    }
}
