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
 * Copyright 2020-26 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.activities;

import android.view.MenuItem;

/**
 * Interface for fragments to handle menu actions delegated from ConnectionDetailsActivity.
 * This allows the activity to centralize menu handling while fragments handle their specific actions.
 */
public interface MenuActionHandler {
    /**
     * Handle a menu item selection for this fragment.
     * @param item The menu item that was selected
     * @return true if the menu item was handled, false otherwise
     */
    boolean handleMenuAction(MenuItem item);
}
