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
 * Copyright 2020-24 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.activities;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.HttpLog;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.fragments.HttpPayloadFragment;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

public class HttpDetailsActivity extends PayloadExportActivity {
    private static final String TAG = "HttpRequestDetailsActivity";
    public static final String HTTP_REQ_POS_KEY = "req_pos";
    private HttpLog.HttpRequest mHttpReq;
    private ViewPager2 mPager;
    private StateAdapter mPagerAdapter;

    private static final int POS_REQUEST = 0;
    private static final int POS_REPLY = 1;
    private static final int POS_COUNT = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.http_details);
        displayBackAction();
        setContentView(R.layout.tabs_activity_fixed);

        int req_pos = getIntent().getIntExtra(HTTP_REQ_POS_KEY, -1);
        if(req_pos != -1) {
            HttpLog httpLog = CaptureService.getHttpLog();
            if(httpLog != null)
                mHttpReq = httpLog.getRequest(req_pos);
        }

        if(mHttpReq == null) {
            Log.w(TAG, "HTTP request with position " + req_pos + " not found");
            finish();
            return;
        }

        mPager = findViewById(R.id.pager);
        Utils.fixViewPager2Insets(mPager);
        setupTabs();
    }

    private void setupTabs() {
        mPagerAdapter = new StateAdapter(this);
        mPager.setAdapter(mPagerAdapter);

        var tabLayout = (TabLayout) findViewById(R.id.tablayout);
        Utils.fixScrollableTabLayoutInsets(tabLayout);
        new TabLayoutMediator(tabLayout, mPager, (tab, position) ->
                tab.setText(getString(mPagerAdapter.getPageTitle(position)))
        ).attach();
    }

    private class StateAdapter extends FragmentStateAdapter {
        StateAdapter(final FragmentActivity fa) { super(fa); }

        @NonNull
        @Override
        public Fragment createFragment(int pos) {
            //Log.d(TAG, "createFragment");
            int req_pos = mHttpReq.getPosition();

            switch (pos) {
                case POS_REPLY:
                    return HttpPayloadFragment.newInstance(req_pos, true);
                case POS_REQUEST:
                default:
                    return HttpPayloadFragment.newInstance(req_pos, false);
            }
        }

        @Override
        public int getItemCount() {
            // Only show response tab if there's a reply
            return (mHttpReq.reply != null) ? POS_COUNT : 1;
        }

        public int getPageTitle(final int pos) {
            switch (pos) {
                case POS_REPLY:
                    return R.string.response;
                default:
                    return R.string.request;
            }
        }
    }
}
