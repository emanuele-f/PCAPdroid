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

import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.model.ConnectionDescriptor.Status;

import java.io.Serializable;

public class FilterDescriptor implements Serializable {
    public Status status = Status.STATUS_INVALID;
    public boolean showMasked = false;

    public boolean isSet() {
        return (status != Status.STATUS_INVALID)
                || (!showMasked && !PCAPdroid.getInstance().getVisualizationMask().isEmpty());
    }

    public boolean matches(ConnectionDescriptor conn) {
        return (showMasked || !PCAPdroid.getInstance().getVisualizationMask().matches(conn))
                && ((status == Status.STATUS_INVALID) || (conn.getStatus().equals(status)));
    }
}
