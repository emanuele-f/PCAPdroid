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

import androidx.annotation.NonNull;

import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;


public class ListInfo {
    private final Type mType;

    public enum Type {
        VISUALIZATION_MASK,
        MALWARE_WHITELIST,
    }

    public ListInfo(Type tp) {
        mType = tp;
    }

    public @NonNull MatchList getList() {
        switch(mType) {
            case VISUALIZATION_MASK:
                return PCAPdroid.getInstance().getVisualizationMask();
            case MALWARE_WHITELIST:
                return PCAPdroid.getInstance().getMalwareWhitelist();
        }

        assert false;
        return null;
    }

    public int getTitle() {
        switch(mType) {
            case VISUALIZATION_MASK:
                return R.string.edit_hidden_connections;
            case MALWARE_WHITELIST:
                return R.string.edit_malware_whitelist;
        }

        assert false;
        return 0;
    }

    public int getShareSubject() {
        switch(mType) {
            case VISUALIZATION_MASK:
                return R.string.hidden_connections_rules;
            case MALWARE_WHITELIST:
                return R.string.malware_whitelist_rules;
        }

        assert false;
        return 0;
    }
}
