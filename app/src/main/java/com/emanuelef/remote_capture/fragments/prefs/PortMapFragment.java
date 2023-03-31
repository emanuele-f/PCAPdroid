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

package com.emanuelef.remote_capture.fragments.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.adapters.PortMappingAdapter;
import com.emanuelef.remote_capture.model.PortMapping;
import com.emanuelef.remote_capture.model.PortMapping.PortMap;
import com.emanuelef.remote_capture.model.Prefs;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;


public class PortMapFragment extends Fragment implements MenuProvider {
    private static final String TAG = "PortMapFragment";
    private PortMappingAdapter mAdapter;
    private TextView mEmptyText;
    private ListView mListView;
    private PortMapping mPortMap;
    private ArrayList<PortMap> mSelected = new ArrayList<>();

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
        mPortMap = new PortMapping(requireContext());

        mAdapter = new PortMappingAdapter(requireContext(), mPortMap);
        mListView.setAdapter(mAdapter);
        mListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        mListView.setMultiChoiceModeListener(new AbsListView.MultiChoiceModeListener() {
            @Override
            public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
                PortMap item = mAdapter.getItem(position);

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
            }
        });

        recheckListSize();
    }

    private void recheckListSize() {
        mEmptyText.setVisibility((mAdapter.getCount() == 0) ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.port_mapping_menu, menu);

        SwitchCompat toggle = (SwitchCompat) menu.findItem(R.id.toggle_btn).getActionView();
        toggle.setChecked(Prefs.isPortMappingEnabled(PreferenceManager.getDefaultSharedPreferences(requireContext())));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

            if(isChecked == Prefs.isPortMappingEnabled(prefs))
                return; // not changed

            Log.d(TAG, "Port mapping is now " + (isChecked ? "enabled" : "disabled"));
            Prefs.setPortMappingEnabled(prefs, isChecked);
        });
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
        if(menuItem.getItemId() == R.id.add_mapping) {
            openAddDialog();
            return true;
        }

        return false;
    }

    private void openAddDialog() {
        Context ctx = requireContext();
        LayoutInflater inflater = LayoutInflater.from(ctx);

        View view = inflater.inflate(R.layout.add_port_mapping_dialog, null);

        final String[] protocols = {"TCP", "UDP"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ctx, R.layout.dropdown_item, protocols);
        AutoCompleteTextView protoField = (AutoCompleteTextView) view.findViewById(R.id.proto);
        protoField.setText(protocols[0]);
        protoField.setAdapter(adapter);

        ((TextInputEditText) view.findViewById(R.id.redirect_ip)).setText("127.0.0.1");

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setView(view)
                .setTitle(R.string.port_mapping)
                .setPositiveButton(R.string.add_action, (dialogInterface, i) -> {})
                .setNegativeButton(R.string.cancel_action, (dialogInterface, i) -> {})
                .show();
        dialog.setCanceledOnTouchOutside(false);

        // custom dismiss logic
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            .setOnClickListener(v -> {
                PortMap mapping = validateAddDialog(view);
                if(mapping == null)
                    return;

                boolean exists = !mPortMap.add(mapping);
                if(exists)
                    Utils.showToastLong(requireContext(), R.string.port_mapping_exists);
                else {
                    mPortMap.save();
                    mAdapter.add(mapping);
                    recheckListSize();
                }

                dialog.dismiss();
            });
    }

    private PortMap validateAddDialog(View view) {
        TextInputEditText origPortField = (TextInputEditText) view.findViewById(R.id.orig_port);
        TextInputEditText redirectIpField = (TextInputEditText) view.findViewById(R.id.redirect_ip);
        TextInputEditText redirectPortField = (TextInputEditText) view.findViewById(R.id.redirect_port);

        String origPort = Objects.requireNonNull(origPortField.getText()).toString();
        String redirectIp = Objects.requireNonNull(redirectIpField.getText()).toString();
        String redirectPort = Objects.requireNonNull(redirectPortField.getText()).toString();
        String proto = ((AutoCompleteTextView) view.findViewById(R.id.proto)).getText().toString();

        if(origPort.isEmpty()) {
            origPortField.setError(getString(R.string.required));
            return null;
        }
        if(!Utils.validatePort(origPort)) {
            origPortField.setError(getString(R.string.invalid));
            return null;
        }

        if(redirectIp.isEmpty()) {
            redirectIpField.setError(getString(R.string.required));
            return null;
        }
        if(!Utils.validateIpAddress(redirectIp)) {
            redirectIpField.setError(getString(R.string.invalid));
            return null;
        }

        if(redirectPort.isEmpty()) {
            redirectPortField.setError(getString(R.string.required));
            return null;
        }
        if(!Utils.validatePort(redirectPort)) {
            redirectPortField.setError(getString(R.string.invalid));
            return null;
        }

        return new PortMap(
                proto.equals("TCP") ? 6 : 17, Integer.parseInt(origPort),
                Integer.parseInt(redirectPort), redirectIp);
    }

    private void confirmDelete(ActionMode mode) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setMessage(R.string.items_delete_confirm);
        builder.setCancelable(true);
        builder.setPositiveButton(R.string.yes, (dialog, which) -> {
            if(mSelected.size() >= mAdapter.getCount()) {
                mAdapter.clear();
                mPortMap.clear();
                mPortMap.save();
            } else {
                for(PortMap item : mSelected)
                    mAdapter.remove(item);
                updateMappingsFromAdapter();
            }

            mode.finish();
            recheckListSize();
        });
        builder.setNegativeButton(R.string.no, (dialog, whichButton) -> {});

        final AlertDialog alert = builder.create();
        alert.setCanceledOnTouchOutside(true);
        alert.show();
    }

    private void updateMappingsFromAdapter() {
        ArrayList<PortMap> toRemove = new ArrayList<>();
        Iterator<PortMap> iter = mPortMap.iter();

        // Remove the mList rules which are not in the adapter dataset
        while(iter.hasNext()) {
            PortMap mapping = iter.next();

            if (mAdapter.getPosition(mapping) < 0)
                toRemove.add(mapping);
        }

        if(toRemove.size() > 0) {
            for(PortMap mapping: toRemove)
                mPortMap.remove(mapping);
            mPortMap.save();
        }
    }
}
