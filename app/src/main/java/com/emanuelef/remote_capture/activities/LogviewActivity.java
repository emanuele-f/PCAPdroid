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

package com.emanuelef.remote_capture.activities;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.fragments.LogviewFragment;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class LogviewActivity extends BaseActivity implements MenuProvider {
    private static final String TAG = "LogviewActivity";
    private ViewPager2 mPager;
    private StateAdapter mPagerAdapter;

    private static final int POS_APP_LOG = 0;
    private static final int POS_ROOT_LOG = 1;
    private static final int POS_MITM_LOG = 2;
    private static final int NUM_POS = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.app_log);
        setContentView(R.layout.tabs_activity_fixed);
        addMenuProvider(this);

        mPager = findViewById(R.id.pager);
        setupTabs();
    }

    private void setupTabs() {
        mPagerAdapter = new StateAdapter(this);
        mPager.setAdapter(mPagerAdapter);

        new TabLayoutMediator(findViewById(R.id.tablayout), mPager, (tab, position) ->
                tab.setText(getString(mPagerAdapter.getPageTitle(position)))
        ).attach();
    }

    private static class StateAdapter extends FragmentStateAdapter {
        final String mCacheDir;

        StateAdapter(final FragmentActivity fa) {
            super(fa);
            mCacheDir = fa.getCacheDir().getAbsolutePath();
        }

        @NonNull
        @Override
        public Fragment createFragment(int pos) {
            switch (pos) {
                case POS_APP_LOG:
                    return LogviewFragment.newInstance(mCacheDir + "/" + Log.DEFAULT_LOGGER_PATH);
                case POS_ROOT_LOG:
                    return LogviewFragment.newInstance(mCacheDir + "/" + Log.ROOT_LOGGER_PATH);
                case POS_MITM_LOG:
                default:
                    return LogviewFragment.newInstance(mCacheDir + "/" + Log.MITM_LOGGER_PATH);
            }
        }

        @Override
        public int getItemCount() {
            return NUM_POS;
        }

        public int getPageTitle(final int pos) {
            switch (pos) {
                case POS_APP_LOG:
                    return R.string.app;
                case POS_ROOT_LOG:
                    return R.string.root;
                case POS_MITM_LOG:
                default:
                    return R.string.mitm_addon;
            }
        }
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

                focusOverride = findViewById(R.id.scrollView);

                if (focusOverride != null) {
                    focusOverride.requestFocus();
                    return true;
                }
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.log_menu, menu);
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        LogviewFragment fragment = (LogviewFragment) getFragmentAtPos(mPager.getCurrentItem());
        if(fragment == null)
            return false;

        String logText = fragment.getLog();

        if(id == R.id.reload) {
            fragment.reloadLog();
            return true;
        } else if(id == R.id.copy_to_clipboard) {
            Utils.copyToClipboard(this, logText);
            return true;
        } else if(id == R.id.share) {
            Utils.shareText(this, getString(R.string.app_log), logText);
            return true;
        }

        return false;
    }
}
