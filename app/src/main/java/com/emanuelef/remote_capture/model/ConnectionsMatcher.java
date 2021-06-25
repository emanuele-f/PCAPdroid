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
import android.graphics.Typeface;
import android.text.style.StyleSpan;

import androidx.annotation.Nullable;

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

public class ConnectionsMatcher {
    private static final String TAG = "ConnectionsMatcher";
    private static final StyleSpan italic = new StyleSpan(Typeface.ITALIC);
    private final Context mContext;
    private ArrayList<Item> mItems = new ArrayList<>();
    private final HashMap<String, Item> mMatches = new HashMap<>();

    public enum ItemType {
        APP,
        IP,
        HOST,
        ROOT_DOMAIN,
        PROTOCOL
    }

    public static class Item {
        private final String mLabel;
        private final ItemType mType;
        private final Object mValue;

        Item(ItemType tp, Object value, String label) {
            mLabel = label;
            mType = tp;
            mValue = value;
        }

        public String getLabel() {
            return mLabel;
        }

        public ItemType getType() {
            return mType;
        }

        public Object getValue() {
            return mValue;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if(!(obj instanceof Item))
                return super.equals(obj);

            Item other = (Item) obj;
            return((mType == other.mType) && (mValue.equals(other.mValue)));
        }
    }

    public ConnectionsMatcher(Context ctx) {
        mContext = ctx;
    }

    public static String getLabel(Context ctx, ItemType tp, String value) {
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

    private static class Serializer implements JsonSerializer<ConnectionsMatcher> {
        @Override
        public JsonElement serialize(ConnectionsMatcher src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject result = new JsonObject();
            JsonArray itemsArr = new JsonArray();

            for(Item item : src.mItems) {
                JsonObject itemObject = new JsonObject();

                itemObject.add("type", new JsonPrimitive(item.getType().name()));
                itemObject.add("value", new JsonPrimitive(item.getValue().toString()));

                itemsArr.add(itemObject);
            }

            result.add("items", itemsArr);
            return result;
        }
    }

    private void deserialize(JsonObject object) {
        mItems = new ArrayList<>();
        mMatches.clear();

        JsonArray itemArray = object.getAsJsonArray("items");
        AppsResolver resolver = new AppsResolver(mContext);

        for(JsonElement el: itemArray) {
            JsonObject itemObj = el.getAsJsonObject();
            ItemType type;

            try {
                type = ItemType.valueOf(itemObj.get("type").getAsString());
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
                continue;
            }

            String val = itemObj.get("value").getAsString();
            String valLabel = val;

            if(type == ItemType.APP) {
                AppDescriptor app = resolver.get(Integer.parseInt(val));

                if(app != null)
                    valLabel = app.getName();
            }

            String label = getLabel(mContext, type, valLabel);
            addItem(new Item(type, val, label));
        }
    }

    public void addApp(int uid, String label)        { addItem(new Item(ItemType.APP, uid, label)); }
    public void addIp(String ip, String label)       { addItem(new Item(ItemType.IP, ip, label)); }
    public void addHost(String info, String label)   { addItem(new Item(ItemType.HOST, info, label)); }
    public void addProto(String proto, String label) { addItem(new Item(ItemType.PROTOCOL, proto, label)); }
    public void addRootDomain(String domain, String label) { addItem(new Item(ItemType.ROOT_DOMAIN, domain, label)); }

    static private String matchKey(ItemType tp, Object val) {
        return tp + "@" + val;
    }

    private void addItem(Item item) {
        String key = matchKey(item.getType(), item.getValue().toString());

        if(!mMatches.containsKey(key)) {
            mItems.add(item);
            mMatches.put(key, item);
        }
    }

    public void removeItems(List<Item> items) {
        mItems.removeAll(items);

        for(Item item: items) {
            String key = matchKey(item.getType(), item.getValue().toString());
            mMatches.remove(key);
        }
    }

    public boolean matches(ConnectionDescriptor conn) {
        boolean hasInfo = ((conn.info != null) && (!conn.info.isEmpty()));

        return(mMatches.containsKey(matchKey(ItemType.APP, conn.uid)) ||
                mMatches.containsKey(matchKey(ItemType.IP, conn.dst_ip)) ||
                mMatches.containsKey(matchKey(ItemType.PROTOCOL, conn.l7proto)) ||
                (hasInfo && mMatches.containsKey(matchKey(ItemType.HOST, conn.info))) ||
                (hasInfo && mMatches.containsKey(matchKey(ItemType.ROOT_DOMAIN, Utils.getRootDomain(conn.info)))));
    }

    public Iterator<Item> iterItems() {
        return mItems.iterator();
    }

    public void clear() {
        mItems.clear();
        mMatches.clear();
    }

    public boolean isEmpty() {
        return(mItems.size() == 0);
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
