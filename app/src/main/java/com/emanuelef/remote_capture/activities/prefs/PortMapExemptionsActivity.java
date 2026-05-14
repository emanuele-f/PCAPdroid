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
 * Copyright 2026 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.activities.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.ArraySet;
import androidx.core.view.MenuProvider;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.BaseActivity;
import com.emanuelef.remote_capture.fragments.AppsToggles;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.Prefs;

import java.util.Set;

public class PortMapExemptionsActivity extends BaseActivity implements MenuProvider {
    private PortMapExemptionsFragment mFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.port_map_exemptions);
        setContentView(R.layout.fragment_activity);
        addMenuProvider(this);
        displayBackAction();

        if(savedInstanceState != null)
            mFragment = (PortMapExemptionsFragment) getSupportFragmentManager().getFragment(savedInstanceState, "fragment");
        if(mFragment == null)
            mFragment = new PortMapExemptionsFragment();

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment, mFragment)
                .commit();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        getSupportFragmentManager().putFragment(outState, "fragment", mFragment);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.hint_menu, menu);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        if(item.getItemId() == R.id.show_hint) {
            Utils.showHelpDialog(this, R.string.port_map_exemptions_help);
            return true;
        }

        return false;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if(mFragment.onBackPressed())
            return;

        super.onBackPressed();
    }

    public static class PortMapExemptionsFragment extends AppsToggles {
        private static final String TAG = "PortMapExemptions";
        private final Set<String> mExemptedApps = new ArraySet<>();
        private @Nullable SharedPreferences mPrefs;

        @Override
        public void onAttach(@NonNull Context context) {
            super.onAttach(context);
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            assert mPrefs != null;

            mExemptedApps.clear();
            Set<String> saved = mPrefs.getStringSet(Prefs.PREF_PORT_MAPPING_EXEMPTIONS, null);
            if(saved != null) {
                Log.d(TAG, "Loading " + saved.size() + " exemptions");
                mExemptedApps.addAll(saved);
            }
        }

        @Override
        public void onDetach() {
            super.onDetach();
            mPrefs = null;
        }

        @Override
        protected Set<String> getCheckedApps() {
            return mExemptedApps;
        }

        @Override
        public void onAppToggled(AppDescriptor app, boolean checked) {
            String packageName = app.getPackageName();
            if(mExemptedApps.contains(packageName) == checked)
                return;

            if(checked)
                mExemptedApps.add(packageName);
            else
                mExemptedApps.remove(packageName);

            Log.d(TAG, "Saving " + mExemptedApps.size() + " exemptions");

            if(mPrefs == null)
                return;

            mPrefs.edit()
                    .putStringSet(Prefs.PREF_PORT_MAPPING_EXEMPTIONS, mExemptedApps)
                    .apply();
        }
    }
}
