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

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.model.ConnectionDescriptor.Status;
import com.emanuelef.remote_capture.model.ConnectionDescriptor.DecryptionStatus;
import com.emanuelef.remote_capture.model.ConnectionDescriptor.FilteringStatus;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.io.Serializable;

public class FilterDescriptor implements Serializable {
    public Status status;
    public boolean showMasked;
    public boolean onlyBlacklisted;
    public boolean onlyCleartext;
    public FilteringStatus filteringStatus;
    public DecryptionStatus decStatus;
    public String iface;
    public int uid = -2; // this is persistent and used internally (AppDetailsActivity)

    public FilterDescriptor() {
        clear();
        assert(!isSet());
    }

    public boolean isSet() {
        return (status != Status.STATUS_INVALID)
                || (decStatus != DecryptionStatus.INVALID)
                || (filteringStatus != FilteringStatus.INVALID)
                || (iface != null)
                || onlyBlacklisted
                || onlyCleartext
                || (uid != -2)
                || (!showMasked && !PCAPdroid.getInstance().getVisualizationMask().isEmpty());
    }

    public boolean matches(ConnectionDescriptor conn) {
        return (showMasked || !PCAPdroid.getInstance().getVisualizationMask().matches(conn))
                && (!onlyBlacklisted || conn.isBlacklisted())
                && (!onlyCleartext || conn.isCleartext())
                && ((status == Status.STATUS_INVALID) || (conn.getStatus().equals(status)))
                && ((decStatus == DecryptionStatus.INVALID) || (conn.getDecryptionStatus() == decStatus))
                && ((filteringStatus == FilteringStatus.INVALID) || ((filteringStatus == FilteringStatus.BLOCKED) == conn.is_blocked))
                && ((iface == null) || (CaptureService.getInterfaceName(conn.ifidx).equals(iface)))
                && ((uid == -2) || (uid == conn.uid));
    }

    private void addChip(LayoutInflater inflater, ChipGroup group, int id, String text) {
        Chip chip = (Chip) inflater.inflate(R.layout.active_filter_chip, group, false);
        chip.setId(id);
        chip.setText(text.toLowerCase());
        group.addView(chip);
    }

    public void toChips(LayoutInflater inflater, ChipGroup group) {
        Context ctx = inflater.getContext();

        if(!showMasked)
            addChip(inflater, group, R.id.not_hidden, ctx.getString(R.string.not_hidden_filter));
        if(onlyBlacklisted)
            addChip(inflater, group, R.id.blacklisted, ctx.getString(R.string.malicious_connection_filter));
        if(onlyCleartext)
            addChip(inflater, group, R.id.only_cleartext, ctx.getString(R.string.cleartext_connection));
        if(status != Status.STATUS_INVALID) {
            String label = String.format(ctx.getString(R.string.status_filter), ConnectionDescriptor.getStatusLabel(status, ctx));
            addChip(inflater, group, R.id.status_ind, label);
        }
        if(decStatus != DecryptionStatus.INVALID) {
            String label = String.format(ctx.getString(R.string.decryption_filter), ConnectionDescriptor.getDecryptionStatusLabel(decStatus, ctx));
            addChip(inflater, group, R.id.decryption_status, label);
        }
        if(filteringStatus != FilteringStatus.INVALID) {
            String label = ctx.getString(R.string.firewall_filter, ctx.getString((filteringStatus == FilteringStatus.BLOCKED) ?
                    R.string.blocked_connection_filter : R.string.allowed_connection_filter));
            addChip(inflater, group, R.id.firewall, label);
        }
        if(iface != null)
            addChip(inflater, group, R.id.capture_interface, String.format(ctx.getString(R.string.interface_filter), iface));
    }

    public void clear(int filter_id) {
        if(filter_id == R.id.not_hidden)
            showMasked = true;
        else if(filter_id == R.id.blacklisted)
            onlyBlacklisted = false;
        else if(filter_id == R.id.only_cleartext)
            onlyCleartext = false;
        else if(filter_id == R.id.status_ind)
            status = Status.STATUS_INVALID;
        else if(filter_id == R.id.decryption_status)
            decStatus = DecryptionStatus.INVALID;
        else if(filter_id == R.id.firewall)
            filteringStatus = FilteringStatus.INVALID;
        else if(filter_id == R.id.capture_interface)
            iface = null;
    }

    public void clear() {
        showMasked = true;
        onlyBlacklisted = false;
        onlyCleartext = false;
        status = Status.STATUS_INVALID;
        decStatus = DecryptionStatus.INVALID;
        filteringStatus = FilteringStatus.INVALID;
        iface = null;
    }
}
