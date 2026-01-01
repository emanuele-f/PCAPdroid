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
 * Copyright 2020-24 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.model;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import com.emanuelef.remote_capture.HttpLog;
import com.emanuelef.remote_capture.R;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.io.Serializable;

public class HttpLogFilterDescriptor implements Serializable {
    public String method = null;
    public String contentType = null;
    public Integer httpStatus = null;
    public long minPayloadSize = 0;

    public HttpLogFilterDescriptor() {
        clear();
        assert(!isSet());
    }

    public boolean isSet() {
        return (method != null)
                || (contentType != null)
                || (httpStatus != null)
                || (minPayloadSize > 0);
    }

    public boolean matches(HttpLog.HttpRequest req) {
        // Method filter
        if (method != null) {
            if (!req.method.equalsIgnoreCase(method))
                return false;
        }

        // Content-type filter
        if (contentType != null) {
            if (req.reply == null || req.reply.contentType == null || !req.reply.contentType.equals(contentType))
                return false;
        }

        // HTTP status filter
        if (httpStatus != null) {
            if (req.reply == null || req.reply.responseCode != httpStatus)
                return false;
        }

        // Payload size filter
        if (minPayloadSize > 0) {
            int totalSize = (req.reply != null) ? (req.bodyLength + req.reply.bodyLength) : req.bodyLength;
            if (totalSize < minPayloadSize)
                return false;
        }

        return true;
    }

    private void addChip(LayoutInflater inflater, ChipGroup group, int id, String text) {
        Chip chip = (Chip) inflater.inflate(R.layout.active_filter_chip, group, false);
        chip.setId(id);
        chip.setText(text.toLowerCase());
        group.addView(chip);
    }

    public void toChips(LayoutInflater inflater, ChipGroup group) {
        Context ctx = inflater.getContext();

        if (method != null) {
            String label = String.format(ctx.getString(R.string.method_filter), method);
            addChip(inflater, group, R.id.http_method_filter, label);
        }

        if (contentType != null) {
            String label = String.format(ctx.getString(R.string.content_type_filter), contentType);
            addChip(inflater, group, R.id.http_content_type_filter, label);
        }

        if (httpStatus != null) {
            String label = String.format(ctx.getString(R.string.status_filter), httpStatus);
            addChip(inflater, group, R.id.http_status_filter, label);
        }

        group.setVisibility(group.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    // clear one of the filters from toChips
    public void clear(int filter_id) {
        if (filter_id == R.id.http_method_filter)
            method = null;
        else if (filter_id == R.id.http_content_type_filter)
            contentType = null;
        else if (filter_id == R.id.http_status_filter)
            httpStatus = null;
    }

    public void clear() {
        method = null;
        contentType = null;
        httpStatus = null;
        minPayloadSize = 0;
    }
}
