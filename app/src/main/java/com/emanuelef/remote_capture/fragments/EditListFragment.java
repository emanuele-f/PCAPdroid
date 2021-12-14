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

import android.content.Intent;
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

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.adapters.ListEditAdapter;
import com.emanuelef.remote_capture.model.ListInfo;
import com.emanuelef.remote_capture.model.MatchList;

import java.util.ArrayList;
import java.util.Iterator;

public class EditListFragment extends Fragment {
    private ListEditAdapter mAdapter;
    private TextView mEmptyText;
    private ArrayList<MatchList.Rule> mSelected = new ArrayList<>();
    private MatchList mList;
    private ListInfo mListInfo;
    private ListView mListView;
    private boolean mChanged;
    private static final String TAG = "EditListFragment";
    private static final String LIST_TYPE_ARG = "list_type";

    public static EditListFragment newInstance(ListInfo.Type list) {
        EditListFragment fragment = new EditListFragment();
        Bundle args = new Bundle();
        args.putSerializable(LIST_TYPE_ARG, list);

        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        setHasOptionsMenu(true);
        return inflater.inflate(R.layout.edit_list_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mListView = view.findViewById(R.id.listview);
        mEmptyText = view.findViewById(R.id.list_empty);

        assert getArguments() != null;
        mListInfo = new ListInfo((ListInfo.Type)getArguments().getSerializable(LIST_TYPE_ARG));
        mList = mListInfo.getList();

        mAdapter = new ListEditAdapter(requireContext(), mList.iterRules());
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
                inflater.inflate(R.menu.list_edit_cab, menu);
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
                        mList.clear();
                        mList.save();
                    } else {
                        for(MatchList.Rule item : mSelected)
                            mAdapter.remove(item);
                        updateList();
                    }

                    if(mListInfo.getType() == ListInfo.Type.MALWARE_WHITELIST) {
                        ConnectionsRegister reg = CaptureService.getConnsRegister();
                        if(reg != null)
                            reg.refreshConnectionsWhitelist();
                    } else if(mListInfo.getType() == ListInfo.Type.BLOCKLIST) {
                        if(CaptureService.isServiceActive())
                            CaptureService.requireInstance().reloadBlocklist();
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

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.list_edit_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        ListView lv = requireActivity().findViewById(R.id.listview);

        if(lv == null)
            return false;

        if(id == R.id.copy_to_clipboard) {
            String contents = Utils.adapter2Text((ListEditAdapter)lv.getAdapter());
            Utils.copyToClipboard(requireContext(), contents);
            return true;
        } else if(id == R.id.share) {
            String contents = Utils.adapter2Text((ListEditAdapter)lv.getAdapter());
            Utils.shareText(requireContext(), getString(mListInfo.getTitle()), contents);
            return true;
        } else if(id == R.id.show_hint) {
            Utils.showHelpDialog(requireContext(), mListInfo.getHelpString());
            return true;
        }

        return false;
    }

    private void recheckListSize() {
        mEmptyText.setVisibility((mAdapter.getCount() == 0) ? View.VISIBLE : View.GONE);
    }

    private void updateList() {
        ArrayList<MatchList.Rule> toRemove = new ArrayList<>();
        Iterator<MatchList.Rule> iter = mList.iterRules();

        // Remove the mList rules which are not in the adapter dataset
        while(iter.hasNext()) {
            MatchList.Rule rule = iter.next();

            if (mAdapter.getPosition(rule) < 0)
                toRemove.add(rule);
        }

        if(toRemove.size() > 0) {
            mList.removeRules(toRemove);
            mList.save();
        }
    }
}
