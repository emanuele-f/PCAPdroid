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

import com.emanuelef.remote_capture.R;

public class BlacklistDescriptor {
    public final String label;
    public final Type tp;
    public final String fname;
    public final String url;
    long mLastUpdate = 0;
    boolean mUpToDate = false;
    public boolean loaded = false;
    public int num_domain_rules = 0;
    public int num_ip_rules = 0;

    public enum Type {
        IP_BLACKLIST,
        DOMAIN_BLACKLIST,
    }

    public enum Status {
        NOT_LOADED,
        OUTDATED,
        UP_TO_DATE
    }

    public BlacklistDescriptor(String label, Type tp, String fname, String url) {
        this.label = label;
        this.tp = tp;
        this.fname = fname;
        this.url = url;
    }

    public void setOutdated() {
        mUpToDate = false;
    }

    public void setUpdated(long now) {
        mLastUpdate = now;
        mUpToDate = true;
    }

    public long getLastUpdate() {
        return mLastUpdate;
    }

    public boolean isUpToDate() {
        return mUpToDate;
    }

    public Status getStatus() {
        if(!loaded)
            return Status.NOT_LOADED;
        if(!mUpToDate)
            return Status.OUTDATED;
        return Status.UP_TO_DATE;
    }

    public String getStatusLabel(Context ctx) {
        int id = -1;

        switch(getStatus()) {
            case NOT_LOADED:
                id = R.string.status_not_loaded;
                break;
            case OUTDATED:
                id = R.string.status_outdated;
                break;
            case UP_TO_DATE:
                id = R.string.status_uptodate;
                break;
        }

        return ctx.getString(id);
    }

    public int getStatusColor(Context ctx) {
        int id = -1;

        switch(getStatus()) {
            case NOT_LOADED:
                id = R.color.danger;
                break;
            case OUTDATED:
                id = R.color.warning;
                break;
            case UP_TO_DATE:
                id = R.color.ok;
                break;
        }

        return ctx.getResources().getColor(id);
    }

    public String getTypeLabel(Context ctx) {
        int id = (tp == Type.IP_BLACKLIST) ? R.string.blacklist_type_ip : R.string.blacklist_type_domain;
        return ctx.getString(id);
    }
}