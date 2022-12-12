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
 * Copyright 2020-22 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.fragments;

import android.app.Activity;
import android.content.Context;
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
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.adapters.ListEditAdapter;
import com.emanuelef.remote_capture.model.ListInfo;
import com.emanuelef.remote_capture.model.MatchList;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Scanner;

public class EditListFragment extends Fragment implements MatchList.ListChangeListener, MenuProvider {
    private ListEditAdapter mAdapter;
    private TextView mEmptyText;
    private ArrayList<MatchList.Rule> mSelected = new ArrayList<>();
    private MatchList mList;
    private ListInfo mListInfo;
    private ListView mListView;
    private boolean mIsOwnUpdate;
    private ActionMode mActionMode;
    private static final String TAG = "EditListFragment";
    private static final String LIST_TYPE_ARG = "list_type";

    private final ActivityResultLauncher<Intent> exportLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::exportResult);
    private final ActivityResultLauncher<Intent> importLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::importResult);

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
        requireActivity().addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        return inflater.inflate(R.layout.simple_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mListView = view.findViewById(R.id.listview);
        mEmptyText = view.findViewById(R.id.list_empty);

        assert getArguments() != null;
        mListInfo = new ListInfo(Utils.getSerializable(getArguments(), LIST_TYPE_ARG, ListInfo.Type.class));
        mList = mListInfo.getList();
        mList.addListChangeListener(this);

        mAdapter = new ListEditAdapter(requireContext());
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
                mActionMode = mode;
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
                    confirmDelete(mode);
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
                mActionMode = null;
            }
        });

        mAdapter.reload(mList.iterRules());
        recheckListSize();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mList.removeListChangeListener(this);
    }

    private void confirmDelete(ActionMode mode) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setMessage(R.string.rules_delete_confirm);
        builder.setCancelable(true);
        builder.setPositiveButton(R.string.yes, (dialog, which) -> {
            if(mSelected.size() >= mAdapter.getCount()) {
                mAdapter.clear();
                mList.clear();
                mList.save();
            } else {
                for(MatchList.Rule item : mSelected)
                    mAdapter.remove(item);
                updateListFromAdapter();
            }

            mode.finish();
            reloadListRules();
            recheckListSize();
        });
        builder.setNegativeButton(R.string.no, (dialog, whichButton) -> {});

        final AlertDialog alert = builder.create();
        alert.setCanceledOnTouchOutside(true);
        alert.show();
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.list_edit_menu, menu);

        if(!Utils.supportsFileDialog(requireContext())) {
            menu.findItem(R.id.action_import).setVisible(false);
            menu.findItem(R.id.action_export).setVisible(false);
        }

        if(mListInfo.getHelpString() <= 0)
            menu.findItem(R.id.show_hint).setVisible(false);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        ListView lv = requireActivity().findViewById(R.id.listview);

        if(lv == null)
            return false;

        if(id == R.id.action_export) {
            if(mList.isEmpty())
                Utils.showToastLong(requireContext(), R.string.no_rules_to_export);
            else
                startExport();
            return true;
        } else if(id == R.id.action_import) {
            startImport();
            return true;
        } else if(id == R.id.copy_to_clipboard) {
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

    private void reloadListRules() {
        if(mListInfo.getType() == ListInfo.Type.MALWARE_WHITELIST)
            CaptureService.reloadMalwareWhitelist();
        else if(mListInfo.getType() == ListInfo.Type.BLOCKLIST) {
            if(CaptureService.isServiceActive())
                CaptureService.requireInstance().reloadBlocklist();
        } else if(mListInfo.getType() == ListInfo.Type.FIREWALL_WHITELIST) {
            if(CaptureService.isServiceActive())
                CaptureService.requireInstance().reloadFirewallWhitelist();
        }
    }

    private void updateListFromAdapter() {
        ArrayList<MatchList.Rule> toRemove = new ArrayList<>();
        Iterator<MatchList.Rule> iter = mList.iterRules();

        // Remove the mList rules which are not in the adapter dataset
        while(iter.hasNext()) {
            MatchList.Rule rule = iter.next();

            if (mAdapter.getPosition(rule) < 0)
                toRemove.add(rule);
        }

        if(toRemove.size() > 0) {
            for(MatchList.Rule rule: toRemove)
                mList.removeRule(rule);
            mList.save();
        }

        mIsOwnUpdate = true;
    }

    private String getExportName() {
        String fname = getString(mListInfo.getTitle()).toLowerCase().replaceAll(" ", "_");
        return "PCAPdroid_" + fname + ".json";
    }

    private void startExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, getExportName());

        Utils.launchFileDialog(requireContext(), intent, exportLauncher);
    }

    private void exportResult(final ActivityResult result) {
        if(result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Context context = requireContext();
            String data = mList.toJson(true);

            try(OutputStream out = context.getContentResolver().openOutputStream(result.getData().getData(), "rwt")) {
                try(PrintWriter printer = new PrintWriter(out)) {
                    printer.print(data);
                    Utils.showToast(context, R.string.save_ok);
                }
            } catch (IOException e) {
                e.printStackTrace();
                Utils.showToastLong(context, R.string.export_failed);
            }
        }
    }

    private void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, getExportName());

        Utils.launchFileDialog(requireContext(), intent, importLauncher);
    }

    private void importResult(final ActivityResult result) {
        if(result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            Context context = requireContext();

            try(InputStream in = context.getContentResolver().openInputStream(result.getData().getData())) {
                try(Scanner s = new Scanner(in).useDelimiter("\\A")) {
                    String data = s.hasNext() ? s.next() : "";
                    MatchList rules = new MatchList(context, "");

                    if(rules.fromJson(data) && !rules.isEmpty()) {
                        if(!mList.isEmpty())
                            confirmImport(rules);
                        else
                            importRules(rules);
                    } else
                        Utils.showToastLong(context, R.string.invalid_backup);
                }
            } catch (IOException e) {
                e.printStackTrace();
                Utils.showToastLong(context, R.string.import_failed);
            }
        }
    }

    private void confirmImport(MatchList rules) {
        Context context = requireContext();

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.import_action);
        builder.setMessage(R.string.rules_merge_msg);
        builder.setCancelable(true);
        builder.setPositiveButton(R.string.keep_action, (dialog, which) -> importRules(rules));
        builder.setNegativeButton(R.string.discard_action, (dialog, which) -> {
            mList.clear(false);
            importRules(rules);
        });

        final AlertDialog alert = builder.create();
        alert.setCanceledOnTouchOutside(false);
        alert.show();
    }

    private void importRules(MatchList to_add) {
        Context context = requireContext();
        int num_imported = mList.addRules(to_add);

        mList.save();
        reloadListRules();

        String msg = String.format(context.getResources().getString(R.string.rules_import_success), num_imported);
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onListChanged() {
        if(mIsOwnUpdate) {
            mIsOwnUpdate = false;
            return;
        }

        Log.d(TAG, "onListChanged");

        if(mActionMode != null) {
            mActionMode.finish();
            mActionMode = null;
        }

        // reload view
        mAdapter.reload(mList.iterRules());
        mListView.setAdapter(mAdapter);
        recheckListSize();
    }
}
