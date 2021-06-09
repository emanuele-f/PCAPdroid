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

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;

public class ConnectionsMatcher {
    private static final String TAG = "ConnectionsMatcher";
    private ArrayList<Item> mItems = new ArrayList<>();

    public static abstract class Item {
        protected String mLabel;

        Item(String label) {
            mLabel = label;
        }

        public String getLabel() {
            return mLabel;
        }

        abstract public String getValue();
        abstract boolean matches(ConnectionDescriptor conn);
    }

    private static class AppItem extends Item {
        int mUid;
        AppItem(int uid, String label) { super(label); mUid = uid; }
        public boolean matches(ConnectionDescriptor conn) {  return conn.uid == mUid; }
        public String getValue() { return Integer.toString(mUid); }
    }

    private static class IpItem extends Item {
        String mIp;
        IpItem(String ip, String label) { super(label); mIp = ip; }
        public boolean matches(ConnectionDescriptor conn) {  return conn.dst_ip.equals(mIp); }
        public String getValue() { return mIp; }
    }

    private static class HostItem extends Item {
        String mInfo;
        HostItem(String info, String label) { super(label); mInfo = info; }
        public boolean matches(ConnectionDescriptor conn) {  return conn.info.equals(mInfo); }
        public String getValue() { return mInfo; }
    }

    private static class ProtoItem extends Item {
        String mProto;
        ProtoItem(String info, String label) { super(label); mProto = info; }
        public boolean matches(ConnectionDescriptor conn) {  return conn.l7proto.equals(mProto); }
        public String getValue() { return mProto; }
    }

    private static class Serializer implements JsonSerializer<ConnectionsMatcher> {
        @Override
        public JsonElement serialize(ConnectionsMatcher src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject result = new JsonObject();
            JsonArray itemsArr = new JsonArray();

            for(Item item : src.mItems) {
                JsonObject itemObject = new JsonObject();

                itemObject.add("type", new JsonPrimitive(item.getClass().getSimpleName()));
                itemObject.add("label", new JsonPrimitive(item.getLabel()));
                itemObject.add("value", new JsonPrimitive(item.getValue()));

                itemsArr.add(itemObject);
            }

            result.add("items", itemsArr);
            return result;
        }
    }

    private void deserialize(JsonObject object) {
        mItems = new ArrayList<>();
        JsonArray itemArray = object.getAsJsonArray("items");

        String appItemClass = AppItem.class.getSimpleName();
        String ipItemClass = IpItem.class.getSimpleName();
        String hostItemClass = HostItem.class.getSimpleName();
        String protoItemClass = ProtoItem.class.getSimpleName();

        for(JsonElement el: itemArray) {
            JsonObject itemObj = el.getAsJsonObject();

            String type = itemObj.get("type").getAsString();
            String label = itemObj.get("label").getAsString();
            JsonElement val = itemObj.get("value");

            if(type.equals(appItemClass))
                addApp(val.getAsInt(), label);
            else if(type.equals(ipItemClass))
                addIp(val.getAsString(), label);
            else if(type.equals(hostItemClass))
                addHost(val.getAsString(), label);
            else if(type.equals(protoItemClass))
                addProto(val.getAsString(), label);
            else
                Log.w(TAG, "unknown item type: " + type);
        }
    }

    public void addApp(int uid, String label)        { addItem(new AppItem(uid, label)); }
    public void addIp(String ip, String label)       { addItem(new IpItem(ip, label)); }
    public void addHost(String info, String label)   { addItem(new HostItem(info, label)); }
    public void addProto(String proto, String label) { addItem(new ProtoItem(proto, label)); }

    private void addItem(Item item) {
        // Avoid duplicates
        if(!hasItem(item))
            mItems.add(item);
    }

    private boolean hasItem(Item search) {
        for(Item item : mItems) {
            if(item.mLabel.equals(search.mLabel))
                return true;
        }

        return false;
    }

    public boolean matches(ConnectionDescriptor conn) {
        for(Item item : mItems) {
            if(item.matches(conn))
                return true;
        }

        return false;
    }

    public Iterator<Item> iterItems() {
        return mItems.iterator();
    }

    public void clear() {
        mItems.clear();
    }

    public boolean isEmpty() {
        return(mItems.size() == 0);
    }

    public String toJson() {
        Gson gson = new GsonBuilder().registerTypeAdapter(ConnectionsMatcher.class, new Serializer())
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
