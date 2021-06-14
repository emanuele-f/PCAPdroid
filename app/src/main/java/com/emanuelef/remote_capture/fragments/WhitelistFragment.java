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

import android.app.AlertDialog;
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

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.adapters.WhitelistEditAdapter;
import com.emanuelef.remote_capture.model.ConnectionsMatcher;
import com.emanuelef.remote_capture.model.Whitelist;

import java.util.ArrayList;
import java.util.Iterator;

public class WhitelistFragment extends Fragment {
    private WhitelistEditAdapter mAdapter;
    private TextView mEmptyText;
    private ArrayList<ConnectionsMatcher.Item> mSelected = new ArrayList<>();
    private Whitelist mWhitelist;
    private ListView mWhitelistView;
    private static final String TAG = "WhitelistFragment";

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.whitelist_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mWhitelistView = view.findViewById(R.id.whitelist);
        mEmptyText = view.findViewById(R.id.whitelist_empty);
        mWhitelist = new Whitelist(view.getContext());
        mWhitelist.reload();

        mAdapter = new WhitelistEditAdapter(requireContext(), mWhitelist.iterItems());
        mWhitelistView.setAdapter(mAdapter);
        mWhitelistView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        mWhitelistView.setMultiChoiceModeListener(new AbsListView.MultiChoiceModeListener() {
            @Override
            public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
                ConnectionsMatcher.Item item = mAdapter.getItem(position);

                if(checked)
                    mSelected.add(item);
                else
                    mSelected.remove(item);

                mode.setTitle(getString(R.string.n_selected, mSelected.size()));
            }

            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                MenuInflater inflater = requireActivity().getMenuInflater();
                inflater.inflate(R.menu.whitelist_cab, menu);
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
                        mWhitelist.clear();
                        mWhitelist.save();
                    } else {
                        for(ConnectionsMatcher.Item item : mSelected)
                            mAdapter.remove(item);
                        updateWhitelist();
                    }

                    mode.finish();
                    recheckWhitelistSize();
                    return true;
                } else if(id == R.id.select_all) {
                    if(mSelected.size() >= mAdapter.getCount())
                        mode.finish();
                    else {
                        for(int i=0; i<mAdapter.getCount(); i++) {
                            if(!mWhitelistView.isItemChecked(i))
                                mWhitelistView.setItemChecked(i, true);
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

        recheckWhitelistSize();
    }

    private void recheckWhitelistSize() {
        mEmptyText.setVisibility((mAdapter.getCount() == 0) ? View.VISIBLE : View.GONE);
    }

    private void updateWhitelist() {
        ArrayList<ConnectionsMatcher.Item> toRemove = new ArrayList<>();

        Iterator<ConnectionsMatcher.Item> iter = mWhitelist.iterItems();

        // Remove the whitelisted items which are not in the adapter dataset
        while(iter.hasNext()) {
            ConnectionsMatcher.Item item = iter.next();

            if (mAdapter.getPosition(item) < 0)
                toRemove.add(item);
        }

        if(toRemove.size() > 0) {
            mWhitelist.removeItems(toRemove);
            mWhitelist.save();
        }
    }
}
