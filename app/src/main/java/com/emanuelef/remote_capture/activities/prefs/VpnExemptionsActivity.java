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

package com.emanuelef.remote_capture.activities.prefs;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.activities.BaseActivity;
import com.emanuelef.remote_capture.fragments.AppsToggles;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.Prefs;

import java.util.HashSet;
import java.util.Set;

public class VpnExemptionsActivity extends BaseActivity {
    private VpnExceptionsFragment mFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.vpn_exemptions);
        setContentView(R.layout.fragment_activity);

        if(savedInstanceState != null)
            mFragment = (VpnExceptionsFragment) getSupportFragmentManager().getFragment(savedInstanceState, "fragment");
        if(mFragment == null)
            mFragment = new VpnExceptionsFragment();

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
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if(item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if(mFragment.onBackPressed())
            return;

        super.onBackPressed();
    }

    public static class VpnExceptionsFragment extends AppsToggles {
        private static final String TAG = "VpnExceptions";
        private final Set<String> mExcludedApps = new HashSet<>();
        private @Nullable SharedPreferences mPrefs;

        @Override
        public void onAttach(@NonNull Context context) {
            super.onAttach(context);
            mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
            assert mPrefs != null;

            mExcludedApps.clear();
            Set<String> saved = mPrefs.getStringSet(Prefs.PREF_VPN_EXCEPTIONS, null);
            if(saved != null) {
                Log.d(TAG, "Loading " + saved.size() + " exceptions");
                mExcludedApps.addAll(saved);
            }
        }

        @Override
        public void onDetach() {
            super.onDetach();
            mPrefs = null;
        }

        @Override
        protected Set<String> getCheckedApps() {
            return mExcludedApps;
        }

        @Override
        public void onAppToggled(AppDescriptor app, boolean checked) {
            String packageName = app.getPackageName();
            if(mExcludedApps.contains(packageName) == checked)
                return; // nothing to do

            if(checked)
                mExcludedApps.add(packageName);
            else
                mExcludedApps.remove(packageName);

            Log.d(TAG, "Saving " + mExcludedApps.size() + " exceptions");

            if(mPrefs == null)
                return;

            mPrefs.edit()
                    .putStringSet(Prefs.PREF_VPN_EXCEPTIONS, mExcludedApps)
                    .apply();
        }
    }
}
