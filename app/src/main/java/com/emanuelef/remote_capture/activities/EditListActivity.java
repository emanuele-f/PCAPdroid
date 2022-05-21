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

package com.emanuelef.remote_capture.activities;

import android.os.Bundle;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.fragments.EditListFragment;
import com.emanuelef.remote_capture.model.ListInfo;
import com.emanuelef.remote_capture.model.MatchList;

/* An activity to edit a MatchList, specified via LIST_INFO_EXTRA */
public class EditListActivity extends BaseActivity {
    private static final String TAG = "EditListActivity";
    public static final String LIST_TYPE_EXTRA = "list_type";
    private ListInfo mListInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        assert(getIntent() != null);
        ListInfo.Type ltype = (ListInfo.Type) getIntent().getSerializableExtra(LIST_TYPE_EXTRA);
        assert(ltype != null);
        mListInfo = new ListInfo(ltype);

        setTitle(mListInfo.getTitle());
        setContentView(R.layout.fragment_activity);

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment, mListInfo.newFragment())
                .commit();
    }

    public MatchList getList() {
        return mListInfo.getList();
    }
}
