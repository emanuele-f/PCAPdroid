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

import com.emanuelef.remote_capture.BuildConfig;
import com.emanuelef.remote_capture.Log;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* A portable snapshot of the app preferences, used to move the configuration to another device
 * or to another build of PCAPdroid (e.g. from F-Droid to the Play Store) */
public class SettingsBackup {
    private static final String TAG = "SettingsBackup";
    public static final int VERSION = 1;
    public static final String LICENSE_KEY = "license";

    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_INT = "int";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_FLOAT = "float";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_STRING_SET = "string_set";

    private final ArrayMap<String, Object> mSettings = new ArrayMap<>();
    private final ArrayMap<String, String> mSkipped = new ArrayMap<>();
    private int mAppVersion;
    private long mCreated;

    public static String serialize(SharedPreferences prefs) {
        JsonObject settings = new JsonObject();

        for (Map.Entry<String, ?> entry: prefs.getAll().entrySet()) {
            String key = entry.getKey();

            if (PrefsSchema.isExcludedFromBackup(key) || !PrefsSchema.isKnown(key))
                continue;

            JsonObject encoded = encode(entry.getValue());
            if (encoded != null)
                settings.add(key, encoded);
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
                String key = entry.getKey();
                if (PrefsSchema.isExcludedFromBackup(key))
                    continue;

                PrefsSchema.Type type = PrefsSchema.getType(key);
                if (type == null) {
                    rv.skip(key, entry.getValue(), "unknown preference");
                    continue;
                }

                Object value = decode(type, entry.getValue());
                if (value == null) {
                    rv.skip(key, entry.getValue(), "invalid encoding");
                    continue;
                }

                String error = PrefsSchema.validate(key, value);
                if (error != null) {
                    rv.skip(key, entry.getValue(), error);
                    continue;
                }

                rv.mSettings.put(key, value);
            }

            if (rv.mSettings.isEmpty())
                return null;

            return rv;
        } catch (RuntimeException e) {
            Log.e(TAG, "fromJson: " + e.getMessage());
            return null;
        }
    }

    private void skip(String key, JsonElement encoded, String reason) {
        Log.w(TAG, "skipping \"" + key + "\": " + reason);
        mSkipped.put(key, valueToString(encoded));
    }

    // the raw value of a preference which could not be decoded, only meant to be shown to the user
    private static String valueToString(JsonElement encoded) {
        JsonElement value = encoded.isJsonObject() ? encoded.getAsJsonObject().get("value") : null;
        if (value == null)
            value = encoded;

        return isString(value) ? value.getAsString() : value.toString();
    }

    private static @Nullable JsonObject encode(Object value) {
        JsonObject rv = new JsonObject();

        // note: the "type" property is currently kept to avoid changing the format,
        // but it's not actually used at import time
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

    private static @Nullable Object decode(PrefsSchema.Type type, JsonElement encoded) {
        if (!encoded.isJsonObject())
            return null;

        JsonElement value = encoded.getAsJsonObject().get("value");
        if (value == null)
            return null;

        // Gson converts between primitive types (e.g. "abc".getAsBoolean() is false), which would
        // silently turn a wrong value into a valid one
        switch (type) {
            case BOOLEAN:   return isBoolean(value) ? value.getAsBoolean() : null;
            case INT:       return isNumber(value) ? parseInt(value.getAsString()) : null;
            case STRING:
            case JSON:      return isString(value) ? value.getAsString() : null;
            case STRING_SET:
                if (!value.isJsonArray())
                    return null;

                ArraySet<String> items = new ArraySet<>();
                for (JsonElement item: value.getAsJsonArray()) {
                    if (!isString(item))
                        return null;
                    items.add(item.getAsString());
                }
                return items;
        }

        return null;
    }

    private static boolean isBoolean(JsonElement el) {
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean();
    }

    private static boolean isNumber(JsonElement el) {
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber();
    }

    private static boolean isString(JsonElement el) {
        return el.isJsonPrimitive() && el.getAsJsonPrimitive().isString();
    }

    // unlike getAsInt, rejects decimals and out of range values instead of truncating them
    private static @Nullable Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /* Replaces the current preferences with the bundled ones. The excluded keys keep the value they
     * have on this device */
    @SuppressLint("ApplySharedPref")
    @SuppressWarnings("unchecked")
    public void apply(SharedPreferences prefs) {
        SharedPreferences.Editor editor = prefs.edit();

        for (String key: prefs.getAll().keySet()) {
            if (!PrefsSchema.isExcludedFromBackup(key) && !PrefsSchema.requiresBackupMerge(key))
                editor.remove(key);
        }

        for (int i = 0; i < mSettings.size(); i++) {
            String key = mSettings.keyAt(i);
            Object value = mSettings.valueAt(i);

            if (PrefsSchema.requiresBackupMerge(key))
                continue;

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

    public long getCreationTime() {
        return mCreated;
    }

    public List<String> getChangedKeys(SharedPreferences prefs) {
        ArrayList<String> rv = new ArrayList<>();
        Map<String, ?> current = prefs.getAll();

        for (int i = 0; i < mSettings.size(); i++) {
            String key = mSettings.keyAt(i);

            if (!mSettings.valueAt(i).equals(current.get(key)))
                rv.add(key);
        }

        return rv;
    }

    public @NonNull String getString(String key) {
        Object value = mSettings.get(key);
        return (value instanceof String) ? (String) value : "";
    }

    /* The preferences of the backup which were not imported, as they are unknown or invalid,
     * mapped to their raw value */
    public @NonNull Map<String, String> getSkippedPrefs() {
        return mSkipped;
    }

    public @NonNull String getValueAsString(String key) {
        Object value = mSettings.get(key);
        return (value != null) ? value.toString() : "";
    }
}
