package com.emanuelef.remote_capture.model;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;

public class PortMappings {
    private static final String TAG = "PortMappings";
    private final SharedPreferences mPrefs;
    private ArrayList<PortMap> mMappings = new ArrayList<>();

    public static class PortMap {
        public final int ipproto;
        public final int orig_port;
        public final int redirect_port;
        public final String redirect_ip;

        public PortMap(int proto, int port, int r_port, String r_host) {
            ipproto = proto;
            orig_port = port;
            redirect_port = r_port;
            redirect_ip = r_host;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PortMap portMap = (PortMap) o;
            return ipproto == portMap.ipproto && orig_port == portMap.orig_port &&
                    redirect_port == portMap.redirect_port && redirect_ip.equals(portMap.redirect_ip);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ipproto, orig_port, redirect_port, redirect_ip);
        }
    }

    public PortMappings(Context ctx) {
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        reload();
    }

    public void clear() {
        mMappings.clear();
    }

    public void save() {
        mPrefs.edit()
                .putString(Prefs.PREF_PORT_MAPPING, toJson(false))
                .apply();
    }

    public void reload() {
        String serialized = mPrefs.getString(Prefs.PREF_PORT_MAPPING, "");
        if(!serialized.isEmpty())
            fromJson(serialized);
        else
            clear();
    }

    public boolean fromJson(String json_str) {
        try {
            Type listOfMyClassObject = new TypeToken<ArrayList<PortMap>>() {}.getType();
            Gson gson = new Gson();
            mMappings = gson.fromJson(json_str, listOfMyClassObject);
            return true;
        } catch (JsonParseException e) {
            e.printStackTrace();
            return false;
        }
    }

    public String toJson(boolean pretty_print) {
        GsonBuilder builder = new GsonBuilder();
        if(pretty_print)
            builder.setPrettyPrinting();
        Gson gson = builder.create();

        String serialized = gson.toJson(mMappings);
        //Log.d(TAG, "toJson: " + serialized);

        return serialized;
    }

    // returns false if the mapping already exists
    public boolean add(PortMap mapping) {
        if(mMappings.contains(mapping))
            return false;

        mMappings.add(mapping);
        return true;
    }

    public boolean remove(PortMap mapping) {
        return mMappings.remove(mapping);
    }

    public Iterator<PortMap> iter() {
        return mMappings.iterator();
    }
}
