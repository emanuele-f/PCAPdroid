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

package com.emanuelef.remote_capture.activities.prefs;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.TextView;

import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.activities.BaseActivity;
import com.emanuelef.remote_capture.adapters.CtrlPermissionsAdapter;
import com.emanuelef.remote_capture.model.CtrlPermissions;

import java.util.ArrayList;

public class EditCtrlPermissions extends BaseActivity {
    private static final String TAG = "EditCtrlPermissions";
    private TextView mEmptyText;
    private CtrlPermissionsAdapter mAdapter;
    private ListView mListView;
    private CtrlPermissions mPermissions;
    private final ArrayList<CtrlPermissions.Rule> mSelected = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTitle(R.string.control_permissions);
        setContentView(R.layout.simple_list);

        mEmptyText = findViewById(R.id.list_empty);
        mListView = findViewById(R.id.listview);

        mPermissions = PCAPdroid.getInstance().getCtrlPermissions();
        mAdapter = new CtrlPermissionsAdapter(this, mPermissions);
        mListView.setAdapter(mAdapter);
        mListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        mListView.setMultiChoiceModeListener(new AbsListView.MultiChoiceModeListener() {

            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                MenuInflater inflater = getMenuInflater();
                inflater.inflate(R.menu.list_edit_cab, menu);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) { return false; }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int id = item.getItemId();

                if(id == R.id.delete_entry) {
                    if(mSelected.size() >= mAdapter.getCount()) {
                        mAdapter.clear();
                        mPermissions.removeAll();
                    } else {
                        for(CtrlPermissions.Rule rule : mSelected) {
                            mAdapter.remove(rule);
                            mPermissions.remove(rule.package_name);
                        }
                    }

                    mode.finish();
                    recheckListSize();
                    return true;
                } else if(id == R.id.select_all) {
                    if(mSelected.size() >= mAdapter.getCount())
                        mode.finish();
                    else {
                        for(int i=0; i<mAdapter.getCount(); i++) {
                            if(!mListView.isItemChecked(i))
                                mListView.setItemChecked(i, true);
                        }
                    }
                }

                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                mSelected.clear();
            }

            @Override
            public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
                CtrlPermissions.Rule item = mAdapter.getItem(position);

                if(checked)
                    mSelected.add(item);
                else
                    mSelected.remove(item);

                mode.setTitle(getString(R.string.n_selected, mSelected.size()));
            }
        });

        recheckListSize();
    }

    private void recheckListSize() {
        mEmptyText.setVisibility((mAdapter.getCount() == 0) ? View.VISIBLE : View.GONE);
    }
}
