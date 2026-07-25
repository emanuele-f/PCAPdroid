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

package com.emanuelef.remote_capture.activities;

import android.os.Bundle;

import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.fragments.CaptureListFragment;

public class CaptureListActivity extends BaseActivity {
    public static final String OPEN_PCAP_EXTRA = "open_pcap_uri";
    private CaptureListFragment mFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.capture_list);
        setContentView(R.layout.fragment_activity);
        displayBackAction();

        if (savedInstanceState != null)
            mFragment = (CaptureListFragment) getSupportFragmentManager().getFragment(savedInstanceState, "fragment");
        if (mFragment == null)
            mFragment = new CaptureListFragment();

        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment, mFragment)
                .commit();
    }

    @Override
    protected void onSaveInstanceState(@androidx.annotation.NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mFragment.isAdded())
            getSupportFragmentManager().putFragment(outState, "fragment", mFragment);
    }
}
