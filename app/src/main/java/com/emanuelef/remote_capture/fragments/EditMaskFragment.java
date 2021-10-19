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

package com.emanuelef.remote_capture.fragments;

import android.os.Bundle;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.adapters.MaskEditAdapter;
import com.emanuelef.remote_capture.model.MatchList;

import java.util.ArrayList;
import java.util.Iterator;

public class EditMaskFragment extends Fragment {
    private MaskEditAdapter mAdapter;
    private TextView mEmptyText;
    private ArrayList<MatchList.Rule> mSelected = new ArrayList<>();
    private MatchList mMask;
    private ListView mListView;
    private static final String TAG = "MaskEditFragment";

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.edit_mask_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mListView = view.findViewById(R.id.listview);
        mEmptyText = view.findViewById(R.id.mask_empty);
        mMask = PCAPdroid.getInstance().getVisualizationMask();

        mAdapter = new MaskEditAdapter(requireContext(), mMask.iterRules());
        mListView.setAdapter(mAdapter);
        mListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        mListView.setMultiChoiceModeListener(new AbsListView.MultiChoiceModeListener() {
            @Override
            public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
                MatchList.Rule item = mAdapter.getItem(position);

                if(checked)
                    mSelected.add(item);
                else
                    mSelected.remove(item);

                mode.setTitle(getString(R.string.n_selected, mSelected.size()));
            }

            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                MenuInflater inflater = requireActivity().getMenuInflater();
                inflater.inflate(R.menu.mask_edit_cab, menu);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem menuItem) {
                int id = menuItem.getItemId();

                if(id == R.id.delete_entry) {
                    if(mSelected.size() >= mAdapter.getCount()) {
                        mAdapter.clear();
                        mMask.clear();
                        mMask.save();
                    } else {
                        for(MatchList.Rule item : mSelected)
                            mAdapter.remove(item);
                        updateMask();
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

                    return true;
                } else
                    return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                mSelected = new ArrayList<>();
            }
        });

        recheckListSize();
    }

    private void recheckListSize() {
        mEmptyText.setVisibility((mAdapter.getCount() == 0) ? View.VISIBLE : View.GONE);
    }

    private void updateMask() {
        ArrayList<MatchList.Rule> toRemove = new ArrayList<>();

        Iterator<MatchList.Rule> iter = mMask.iterRules();

        // Remove the mMask rules which are not in the adapter dataset
        while(iter.hasNext()) {
            MatchList.Rule rule = iter.next();

            if (mAdapter.getPosition(rule) < 0)
                toRemove.add(rule);
        }

        if(toRemove.size() > 0) {
            mMask.removeRules(toRemove);
            mMask.save();
        }
    }
}
