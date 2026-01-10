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
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

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

import java.util.ArrayList;

public class HttpDetailsActivity extends PayloadExportActivity {
    private static final String TAG = "HttpRequestDetailsActivity";
    public static final String HTTP_REQ_POS_KEY = "req_pos";
    public static final String FILTERED_POSITIONS_KEY = "filtered_positions";
    private HttpLog.HttpRequest mHttpReq;
    private ViewPager2 mPager;
    private StateAdapter mPagerAdapter;
    private int mReqPos;
    private ArrayList<Integer> mFilteredPositions;
    private int mFilteredIndex;
    private MenuItem mMenuPrev;
    private MenuItem mMenuNext;
    private MenuItem mMenuDisplayAs;
    private Boolean mDisplayMode;

    private static final int POS_REQUEST = 0;
    private static final int POS_REPLY = 1;
    private static final int POS_COUNT = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        displayBackAction();
        setContentView(R.layout.tabs_activity_fixed);

        mReqPos = getIntent().getIntExtra(HTTP_REQ_POS_KEY, -1);

        // Get filtered positions if provided
        mFilteredPositions = getIntent().getIntegerArrayListExtra(FILTERED_POSITIONS_KEY);
        mFilteredIndex = -1;

        // Find the index of the current position in the filtered list
        if (mFilteredPositions != null) {
            for (int i = 0; i < mFilteredPositions.size(); i++) {
                if (mFilteredPositions.get(i) == mReqPos) {
                    mFilteredIndex = i;
                    break;
                }
            }
            Log.d(TAG, "Using filtered navigation: " + mFilteredPositions.size() + " items, index=" + mFilteredIndex);
        }

        if(mReqPos != -1) {
            setTitle(String.format(getString(R.string.http_request_number), mReqPos + 1));

            HttpLog httpLog = CaptureService.getHttpLog();
            if(httpLog != null)
                mHttpReq = httpLog.getRequest(mReqPos);
        } else
            setTitle(R.string.http_requests);

        if(mHttpReq == null) {
            Log.w(TAG, "HTTP request with position " + mReqPos + " not found");
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

        mPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateMenuVisibility();
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.http_details_menu, menu);

        mMenuPrev = menu.findItem(R.id.navigate_before);
        mMenuNext = menu.findItem(R.id.navigate_next);
        mMenuDisplayAs = menu.findItem(R.id.display_as);

        updateNavigationButtons();
        updateMenuVisibility();
        return true;
    }

    private void updateNavigationButtons() {
        if(mMenuPrev == null || mMenuNext == null)
            return;

        boolean hasPrev, hasNext;

        if (mFilteredPositions != null) {
            // Using filtered navigation
            hasPrev = mFilteredIndex > 0;
            hasNext = mFilteredIndex < mFilteredPositions.size() - 1;
        } else {
            // Using unfiltered navigation
            HttpLog httpLog = CaptureService.getHttpLog();
            int httpLogSize = (httpLog != null) ? httpLog.getSize() : 0;
            hasPrev = mReqPos > 0;
            hasNext = mReqPos < httpLogSize - 1;
        }

        mMenuPrev.setEnabled(hasPrev);
        if(mMenuPrev.getIcon() != null)
            mMenuPrev.getIcon().setAlpha(hasPrev ? 255 : 80);

        mMenuNext.setEnabled(hasNext);
        if(mMenuNext.getIcon() != null)
            mMenuNext.getIcon().setAlpha(hasNext ? 255 : 80);
    }

    public void updateMenuVisibility() {
        if(mMenuDisplayAs == null)
            return;

        mMenuDisplayAs.setVisible(true);

        Fragment currentFragment = getCurrentFragment();
        if(currentFragment instanceof HttpPayloadFragment) {
            HttpPayloadFragment payloadFragment = (HttpPayloadFragment) currentFragment;

            if(mDisplayMode == null) {
                mDisplayMode = true;
            }

            payloadFragment.setDisplayMode(mDisplayMode);

            if(mDisplayMode) {
                mMenuDisplayAs.setTitle(R.string.display_as_hexdump);
            } else {
                mMenuDisplayAs.setTitle(R.string.display_as_text);
            }
        }
    }

    private Fragment getCurrentFragment() {
        int currentTab = mPager.getCurrentItem();
        String tag = "f" + mPagerAdapter.getItemId(currentTab);
        return getSupportFragmentManager().findFragmentByTag(tag);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();

        if(itemId == R.id.navigate_before) {
            navigateToPrevious();
            return true;
        } else if(itemId == R.id.navigate_next) {
            navigateToNext();
            return true;
        } else if(itemId == R.id.display_as) {
            if(mDisplayMode != null) {
                mDisplayMode = !mDisplayMode;
                updateMenuVisibility();
            }
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void navigateToPrevious() {
        if (mFilteredPositions != null) {
            if (mFilteredIndex > 0) {
                mFilteredIndex--;
                mReqPos = mFilteredPositions.get(mFilteredIndex);
                loadHttpRequest();
            }
        } else {
            if (mReqPos > 0) {
                mReqPos--;
                loadHttpRequest();
            }
        }
    }

    private void navigateToNext() {
        if (mFilteredPositions != null) {
            if (mFilteredIndex < mFilteredPositions.size() - 1) {
                mFilteredIndex++;
                mReqPos = mFilteredPositions.get(mFilteredIndex);
                loadHttpRequest();
            }
        } else {
            HttpLog httpLog = CaptureService.getHttpLog();
            int httpLogSize = (httpLog != null) ? httpLog.getSize() : 0;

            if (mReqPos < httpLogSize - 1) {
                mReqPos++;
                loadHttpRequest();
            }
        }
    }

    private void loadHttpRequest() {
        HttpLog httpLog = CaptureService.getHttpLog();
        if(httpLog != null) {
            mHttpReq = httpLog.getRequest(mReqPos);

            if(mHttpReq != null) {
                setTitle(String.format(getString(R.string.http_request_number), mReqPos + 1));

                int currentTab = mPager.getCurrentItem();

                setupTabs();

                // Restore tab position if still valid for the new request
                int newItemCount = mPagerAdapter.getItemCount();
                if (currentTab < newItemCount) {
                    // Tab is still valid, restore it
                    mPager.setCurrentItem(currentTab, false);
                } else {
                    // Tab not available (e.g., no response), fall back to first tab
                    mPager.setCurrentItem(0, false);
                }

                updateNavigationButtons();
                updateMenuVisibility();
            } else {
                Log.w(TAG, "HTTP request with position " + mReqPos + " not found");
            }
        }
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
