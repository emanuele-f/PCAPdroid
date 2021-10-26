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
import android.view.LayoutInflater;

import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.model.ConnectionDescriptor.Status;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.io.Serializable;

public class FilterDescriptor implements Serializable {
    public Status status = Status.STATUS_INVALID;
    public boolean showMasked = true;
    public boolean onlyBlacklisted = false;

    public boolean isSet() {
        return (status != Status.STATUS_INVALID)
                || onlyBlacklisted
                || (!showMasked && !PCAPdroid.getInstance().getVisualizationMask().isEmpty());
    }

    public boolean matches(ConnectionDescriptor conn) {
        return (showMasked || !PCAPdroid.getInstance().getVisualizationMask().matches(conn))
                && (!onlyBlacklisted || conn.isBlacklisted())
                && ((status == Status.STATUS_INVALID) || (conn.getStatus().equals(status)));
    }

    private void addChip(LayoutInflater inflater, ChipGroup group, int id, String text) {
        Chip chip = (Chip) inflater.inflate(R.layout.active_filter_chip, group, false);
        chip.setId(id);
        chip.setText(text);
        group.addView(chip);
    }

    public void toChips(LayoutInflater inflater, ChipGroup group) {
        Context ctx = inflater.getContext();

        if(!showMasked)
            addChip(inflater, group, R.id.hide_masked, ctx.getString(R.string.not_hidden_filter));
        if(onlyBlacklisted)
            addChip(inflater, group, R.id.blacklisted, ctx.getString(R.string.malicious_filter));
        if(status != Status.STATUS_INVALID) {
            String label = String.format(ctx.getString(R.string.status_filter), ConnectionDescriptor.getStatusLabel(status, ctx));
            addChip(inflater, group, R.id.status_ind, label);
        }
    }

    public void clear(int filter_id) {
        if(filter_id == R.id.hide_masked)
            showMasked = true;
        else if(filter_id == R.id.blacklisted)
            onlyBlacklisted = false;
        else if(filter_id == R.id.status_ind)
            status = Status.STATUS_INVALID;
    }
}
