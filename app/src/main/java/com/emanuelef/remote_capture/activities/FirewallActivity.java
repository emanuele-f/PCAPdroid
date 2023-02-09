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

package com.emanuelef.remote_capture.activities;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.fragments.EditListFragment;
import com.emanuelef.remote_capture.fragments.FirewallStatus;
import com.emanuelef.remote_capture.model.ListInfo;
import com.emanuelef.remote_capture.model.Prefs;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class FirewallActivity extends BaseActivity {
    private static final String TAG = "Firewall";
    private ViewPager2 mPager;
    private StateAdapter mPagerAdapter;
    private boolean mHasWhitelist = false;
    private SharedPreferences mPrefs;

    private static final int POS_STATUS = 0;
    private static final int POS_BLOCKLIST = 1;
    private static final int POS_WHITELIST = 2;
    private static final int TOTAL_COUNT = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.firewall);
        setContentView(R.layout.tabs_activity);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPager = findViewById(R.id.pager);
        setupTabs();
    }

    private class StateAdapter extends FragmentStateAdapter {
        StateAdapter(final FragmentActivity fa) { super(fa); }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            Log.d(TAG, "createFragment");

            switch (position) {
                default: // Deliberate fall-through to status tab
                case POS_STATUS:
                    return new FirewallStatus();
                case POS_BLOCKLIST:
                    return EditListFragment.newInstance(ListInfo.Type.BLOCKLIST);
                case POS_WHITELIST:
                    return EditListFragment.newInstance(ListInfo.Type.FIREWALL_WHITELIST);
            }
        }

        @Override
        public int getItemCount() {  return mHasWhitelist ? TOTAL_COUNT : (TOTAL_COUNT - 1);  }

        public int getPageTitle(final int position) {
            switch (position) {
                default: // Deliberate fall-through to status tab
                case POS_STATUS:
                    return R.string.status;
                case POS_BLOCKLIST:
                    return R.string.blocklist;
                case POS_WHITELIST:
                    return R.string.whitelist;
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    public void recheckTabs() {
        boolean whitelist_mode = Prefs.isFirewallWhitelistMode(mPrefs);
        if(mHasWhitelist == whitelist_mode)
            return;

        mHasWhitelist = whitelist_mode;
        mPagerAdapter.notifyDataSetChanged();
    }

    private void setupTabs() {
        mPagerAdapter = new StateAdapter(this);
        mPager.setAdapter(mPagerAdapter);

        new TabLayoutMediator(findViewById(R.id.tablayout), mPager, (tab, position) ->
                tab.setText(getString(mPagerAdapter.getPageTitle(position)))
        ).attach();

        recheckTabs();

        // TODO fix DPAD navigation on Android TV, see MainActivity.onKeyDown
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // This is required to properly handle the DPAD down press on Android TV, to properly
        // focus the tab content
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            View view = getCurrentFocus();

            Log.d(TAG, "onKeyDown focus " + view.getClass().getName());

            if (view instanceof TabLayout.TabView) {
                int pos = mPager.getCurrentItem();
                View focusOverride = null;

                Log.d(TAG, "TabLayout.TabView focus pos " + pos);

                if (pos == POS_STATUS)
                    focusOverride = findViewById(R.id.firewall_status);
                else if ((pos == POS_BLOCKLIST) || (pos == POS_WHITELIST))
                    focusOverride = findViewById(R.id.listview);

                if (focusOverride != null) {
                    focusOverride.requestFocus();
                    return true;
                }
            }
        }

        return super.onKeyDown(keyCode, event);
    }
}
