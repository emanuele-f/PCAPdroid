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

import java.util.ArrayList;
import java.util.Iterator;

public class ConnectionsMatcher {
    private final ArrayList<Item> mItems = new ArrayList<>();

    public static abstract class Item {
        protected String mLabel;

        Item(String label) {
            mLabel = label;
        }

        public String getLabel() {
            return mLabel;
        }

        abstract boolean matches(ConnectionDescriptor conn);
    }

    private static class AppItem extends Item {
        int mUid;
        AppItem(int uid, String label) { super(label); mUid = uid; }
        public boolean matches(ConnectionDescriptor conn) {  return conn.uid == mUid; }
    }

    private static class IpItem extends Item {
        String mIp;
        IpItem(String ip, String label) { super(label); mIp = ip; }
        public boolean matches(ConnectionDescriptor conn) {  return conn.dst_ip.equals(mIp); }
    }

    private static class HostItem extends Item {
        String mInfo;
        HostItem(String info, String label) { super(label); mInfo = info; }
        public boolean matches(ConnectionDescriptor conn) {  return conn.info.equals(mInfo); }
    }

    private static class ProtoItem extends Item {
        String mProto;
        ProtoItem(String info, String label) { super(label); mProto = info; }
        public boolean matches(ConnectionDescriptor conn) {  return conn.l7proto.equals(mProto); }
    }

    public void addApp(int uid, String label) { mItems.add(new AppItem(uid, label)); }
    public void addIp(String ip, String label) { mItems.add(new IpItem(ip, label)); }
    public void addHost(String info, String label) { mItems.add(new HostItem(info, label)); }
    public void addProto(String proto, String label) { mItems.add(new ProtoItem(proto, label)); }

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
}
