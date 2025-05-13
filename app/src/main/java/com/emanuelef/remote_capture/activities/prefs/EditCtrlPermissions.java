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
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.view.MenuProvider;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.BaseActivity;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.adapters.CtrlPermissionsAdapter;
import com.emanuelef.remote_capture.model.CtrlPermissions;
import com.emanuelef.remote_capture.model.Prefs;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;

public class EditCtrlPermissions extends BaseActivity implements MenuProvider {
    private static final String TAG = "EditCtrlPermissions";
    private TextView mEmptyText;
    private CtrlPermissionsAdapter mAdapter;
    private ListView mListView;
    private CtrlPermissions mPermissions;
    private MenuItem mShowApiKey;
    private final ArrayList<CtrlPermissions.Rule> mSelected = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setTitle(R.string.control_permissions);
        setContentView(R.layout.simple_list_activity);
        addMenuProvider(this);

        findViewById(R.id.simple_list).setFitsSystemWindows(true);
        mEmptyText = findViewById(R.id.list_empty);
        mEmptyText.setText(R.string.no_permissions_set_info);
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
        Utils.fixListviewInsetsBottom(mListView);

        recheckListSize();
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.ctrl_permissions_menu, menu);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        mShowApiKey = menu.findItem(R.id.show_api_key);

        if (Prefs.getApiKey(prefs).isEmpty())
            mShowApiKey.setVisible(false);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.user_guide) {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(MainActivity.API_DOCS_URL));
            Utils.startActivity(this, browserIntent);
            return true;
        } else if (id == R.id.generate_api_key) {
            generateApiKey(false);
            return true;
        } else if (id == R.id.show_api_key) {
            showApiKey();
            return true;
        }

        return false;
    }

    private void recheckListSize() {
        mEmptyText.setVisibility((mAdapter.getCount() == 0) ? View.VISIBLE : View.GONE);
    }

    private void generateApiKey(boolean confirmOverwrite) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!confirmOverwrite && !Prefs.getApiKey(prefs).isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.warning)
                    .setMessage(R.string.api_key_discard_confirm)
                    .setPositiveButton(R.string.ok, (dialog, whichButton) -> generateApiKey(true))
                    .setNegativeButton(R.string.cancel_action, (dialog, whichButton) -> {})
                    .show();
            return;
        }

        final String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        final int key_length = 32;
        SecureRandom random = new SecureRandom();
        StringBuilder apiKey = new StringBuilder(key_length);
        for (int i = 0; i < key_length; i++) {
            int index = random.nextInt(chars.length());
            apiKey.append(chars.charAt(index));
        }

        prefs.edit()
                .putString(Prefs.PREF_API_KEY, apiKey.toString())
                .apply();

        if (mShowApiKey != null)
            mShowApiKey.setVisible(true);
        showApiKey();
    }

    private void showApiKey() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String key = Prefs.getApiKey(prefs);
        if (key.isEmpty())
            return;

        new AlertDialog.Builder(this)
                .setTitle(R.string.api_key)
                .setMessage(key)
                .setPositiveButton(R.string.ok, (dialogInterface, i) -> {})
                .setNeutralButton(R.string.copy_to_clipboard, (dialogInterface, i) ->
                        Utils.copyToClipboard(this, key)).show();
    }
}
