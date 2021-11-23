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

import androidx.core.content.ContextCompat;

import com.emanuelef.remote_capture.R;

public class BlacklistDescriptor {
    public final String label;
    public final Type type;    // NOTE: used via JNI
    public final String fname; // NOTE: used via JNI
    public final String url;
    long mLastUpdate = 0;
    boolean mUpToDate = false;
    boolean mUpdating = false;
    public boolean loaded = false;
    public int num_rules = 0;

    // NOTE: used via JNI
    public enum Type {
        IP_BLACKLIST,
        DOMAIN_BLACKLIST,
    }

    public enum Status {
        NOT_LOADED,
        OUTDATED,
        UPDATING,
        UP_TO_DATE
    }

    public BlacklistDescriptor(String label, Type type, String fname, String url) {
        this.label = label;
        this.type = type;
        this.fname = fname;
        this.url = url;
    }

    public void setUpdating() {
        mUpdating = true;
        mUpToDate = false;
    }

    public void setOutdated() {
        mUpdating = false;
        mUpToDate = false;
    }

    public void setUpdated(long now) {
        mUpdating = false;
        mLastUpdate = now;
        mUpToDate = (mLastUpdate != 0);
    }

    public long getLastUpdate() {
        return mLastUpdate;
    }

    public boolean isUpToDate() {
        return mUpToDate;
    }

    public Status getStatus() {
        if(mUpdating)
            return Status.UPDATING;
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
            case UPDATING:
                id = R.string.status_updating;
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
            case UPDATING:
                id = R.color.in_progress;
                break;
            case UP_TO_DATE:
                id = R.color.ok;
                break;
        }

        return ContextCompat.getColor(ctx, id);
    }

    public String getTypeLabel(Context ctx) {
        int id = (type == Type.IP_BLACKLIST) ? R.string.blacklist_type_ip : R.string.blacklist_type_domain;
        return ctx.getString(id);
    }
}