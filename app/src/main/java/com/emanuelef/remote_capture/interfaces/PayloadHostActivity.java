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
 * Copyright 2026 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.interfaces;

import com.emanuelef.remote_capture.adapters.PayloadAdapter;

/**
 * Interface for activities that host payload display fragments (like ConnectionPayload).
 * This allows the same fragment to be used in different activity contexts.
 */
public interface PayloadHostActivity extends PayloadAdapter.ExportPayloadHandler {
    /**
     * Called when the fragment needs to update menu visibility based on the current state.
     */
    void updateMenuVisibility();

    /**
     * Interface for receiving connection update notifications.
     */
    interface ConnUpdateListener {
        void connectionUpdated();
    }

    /**
     * Register for live connection updates. Override in activities that support live updates.
     * Default implementation does nothing.
     */
    default void addConnUpdateListener(ConnUpdateListener listener) {}

    /**
     * Unregister from live connection updates.
     * Default implementation does nothing.
     */
    default void removeConnUpdateListener(ConnUpdateListener listener) {}
}
