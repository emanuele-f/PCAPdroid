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

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.fragments.ConnectionOverview;
import com.emanuelef.remote_capture.fragments.ConnectionPayload;
import com.emanuelef.remote_capture.interfaces.ConnectionsListener;
import com.emanuelef.remote_capture.interfaces.PayloadHostActivity;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.PayloadChunk;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;

public class ConnectionDetailsActivity extends PayloadExportActivity implements ConnectionsListener, PayloadHostActivity {
    private static final String TAG = "ConnectionDetails";
    public static final String CONN_ID_KEY = "conn_id";
    public static final String FILTERED_IDS_KEY = "filtered_ids";
    private static final int MAX_CHUNKS_TO_CHECK = 10;
    private ConnectionDescriptor mConn;
    private ViewPager2 mPager;
    private StateAdapter mPagerAdapter;
    private Handler mHandler;
    private int mCurChunks;
    private boolean mListenerSet;
    private boolean mHasPayload;
    private boolean mHasHttpTab;
    private boolean mHasWsTab;
    private final ArrayList<PayloadHostActivity.ConnUpdateListener> mListeners = new ArrayList<>();
    private int mConnId;
    private ArrayList<Integer> mFilteredIds;
    private int mFilteredIndex;
    private MenuItem mMenuPrev;
    private MenuItem mMenuNext;
    private MenuItem mMenuCopy;
    private MenuItem mMenuShare;
    private MenuItem mMenuDisplayAs;
    private Boolean mDisplayMode;

    private static final int POS_OVERVIEW = 0;
    private static final int POS_WEBSOCKET = 1;
    private static final int POS_HTTP = 2;
    private static final int POS_RAW_PAYLOAD = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        displayBackAction();
        setContentView(R.layout.tabs_activity_fixed);

        mConnId = getIntent().getIntExtra(CONN_ID_KEY, -1);

        mFilteredIds = getIntent().getIntegerArrayListExtra(FILTERED_IDS_KEY);
        mFilteredIndex = -1;

        if (mFilteredIds != null) {
            for (int i = 0; i < mFilteredIds.size(); i++) {
                if (mFilteredIds.get(i) == mConnId) {
                    mFilteredIndex = i;
                    break;
                }
            }
            Log.d(TAG, "Using filtered navigation: " + mFilteredIds.size() + " items, index=" + mFilteredIndex);
        }

        if(mConnId != -1) {
            ConnectionsRegister reg = CaptureService.getConnsRegister();
            if(reg != null) {
                mConn = reg.getConnById(mConnId);
                setTitle(String.format(getString(R.string.connection_number), mConnId + 1));
            }
        } else
            setTitle(R.string.connection_details);

        if(mConn == null) {
            Log.w(TAG, "Connection with ID " + mConnId + " not found");
            finish();
            return;
        }

        mHandler = new Handler(Looper.getMainLooper());

        mPager = findViewById(R.id.pager);
        Utils.fixViewPager2Insets(mPager);
        setupTabs();
    }

    @Override
    public void onResume() {
        super.onResume();
        registerConnsListener();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnsListener();
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

        mCurChunks = 0;
        recheckTabs();
    }

    private class StateAdapter extends FragmentStateAdapter {
        StateAdapter(final FragmentActivity fa) { super(fa); }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            //Log.d(TAG, "createFragment");
            int pos = getVisibleTabsPositions()[position];
            int conn_id = mConn.incr_id;

            switch (pos) {
                case POS_WEBSOCKET:
                    return ConnectionPayload.newInstance(PayloadChunk.ChunkType.WEBSOCKET, conn_id);
                case POS_HTTP:
                    return ConnectionPayload.newInstance(PayloadChunk.ChunkType.HTTP, conn_id);
                case POS_RAW_PAYLOAD:
                    return ConnectionPayload.newInstance(PayloadChunk.ChunkType.RAW, conn_id);
                case POS_OVERVIEW:
                default:
                    return ConnectionOverview.newInstance(conn_id);
            }
        }

        @Override
        public int getItemCount() {  return 1 + (mHasPayload ? 1 : 0) + (mHasHttpTab ? 1 : 0) + (mHasWsTab ? 1 : 0);  }

        public int getPageTitle(final int position) {
            int pos = getVisibleTabsPositions()[position];

            switch (pos) {
                case POS_WEBSOCKET:
                    return R.string.websocket;
                case POS_HTTP:
                    return R.string.http;
                case POS_RAW_PAYLOAD:
                    return R.string.payload;
                case POS_OVERVIEW:
                default:
                    return R.string.overview;
            }
        }

        public int[] getVisibleTabsPositions() {
            int[] visible = new int[getItemCount()];
            int i = 0;

            visible[i++] = POS_OVERVIEW;

            if(mHasWsTab)
                visible[i++] = POS_WEBSOCKET;
            if(mHasHttpTab)
                visible[i++] = POS_HTTP;
            if(mHasPayload)
                visible[i] = POS_RAW_PAYLOAD;

            return visible;
        }
    }

    private void registerConnsListener() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if((reg != null) && !mListenerSet) {
            if(mConn.status < ConnectionDescriptor.CONN_STATUS_CLOSED) {
                Log.d(TAG, "Adding connections listener");
                reg.addListener(this);
                mListenerSet = true;
            }
        }

        dispatchConnUpdate();
    }

    private void unregisterConnsListener() {
        if(mListenerSet) {
            ConnectionsRegister reg = CaptureService.getConnsRegister();

            if(reg != null) {
                Log.d(TAG, "Removing connections listener");
                reg.removeListener(this);
            }

            mListenerSet = false;
        }
    }

    @Override
    public void connectionsChanges(int num_connetions) {}

    @Override
    public void connectionsAdded(int start, ConnectionDescriptor []conns) {}

    @Override
    public void connectionsRemoved(int start, ConnectionDescriptor []conns) {}

    @Override
    public void connectionsUpdated(int[] positions) {
        ConnectionsRegister reg = CaptureService.getConnsRegister();

        if(reg == null)
            return;

        for(int pos : positions) {
            ConnectionDescriptor conn = reg.getConn(pos);

            if((conn != null) && (conn.incr_id == mConn.incr_id)) {
                mHandler.post(this::dispatchConnUpdate);
                break;
            }
        }
    }

    @Override
    public void addConnUpdateListener(PayloadHostActivity.ConnUpdateListener listener) {
        mListeners.add(listener);
    }

    @Override
    public void removeConnUpdateListener(PayloadHostActivity.ConnUpdateListener listener) {
        mListeners.remove(listener);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void recheckTabs() {
        if(mHasHttpTab && mHasWsTab)
            return;

        int max_check = Math.min(mConn.getNumPayloadChunks(), MAX_CHUNKS_TO_CHECK);
        boolean changed = false;

        if(!mHasPayload && (max_check > 0)) {
            mHasPayload = true;
            changed = true;
        }

        for(int i=mCurChunks; i<max_check; i++) {
            PayloadChunk chunk = mConn.getPayloadChunk(i);
            if(chunk == null)
                continue;

            if(!mHasHttpTab && (chunk.type == PayloadChunk.ChunkType.HTTP)) {
                mHasHttpTab = true;
                changed = true;
            } else if (!mHasWsTab && (chunk.type == PayloadChunk.ChunkType.WEBSOCKET)) {
                mHasWsTab = true;
                changed = true;
            }
        }

        if(changed)
            mPagerAdapter.notifyDataSetChanged();

        mCurChunks = max_check;
    }

    private void dispatchConnUpdate() {
        for(PayloadHostActivity.ConnUpdateListener listener: mListeners)
            listener.connectionUpdated();

        if((mCurChunks < MAX_CHUNKS_TO_CHECK) && (mConn.getNumPayloadChunks() > mCurChunks))
            recheckTabs();

        if(mConn.status >= ConnectionDescriptor.CONN_STATUS_CLOSED)
            unregisterConnsListener();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.connection_details_menu, menu);

        mMenuPrev = menu.findItem(R.id.navigate_before);
        mMenuNext = menu.findItem(R.id.navigate_next);
        mMenuCopy = menu.findItem(R.id.copy_to_clipboard);
        mMenuShare = menu.findItem(R.id.share);
        mMenuDisplayAs = menu.findItem(R.id.display_as);

        updateNavigationButtons();
        updateMenuVisibility();
        return true;
    }

    private void updateNavigationButtons() {
        if(mMenuPrev == null || mMenuNext == null)
            return;

        ArrayList<Integer> ids = (mFilteredIds != null) ? mFilteredIds : getAllConnectionIds();
        boolean hasPrev = false;
        boolean hasNext = false;

        if(ids != null) {
            int currentIndex = ids.indexOf(mConnId);
            if(currentIndex >= 0) {
                hasPrev = currentIndex > 0;
                hasNext = currentIndex < ids.size() - 1;
            }
        }

        mMenuPrev.setEnabled(hasPrev);
        if(mMenuPrev.getIcon() != null)
            mMenuPrev.getIcon().setAlpha(hasPrev ? 255 : 80);

        mMenuNext.setEnabled(hasNext);
        if(mMenuNext.getIcon() != null)
            mMenuNext.getIcon().setAlpha(hasNext ? 255 : 80);
    }

    public void updateMenuVisibility() {
        if(mMenuCopy == null || mMenuShare == null || mMenuDisplayAs == null)
            return;

        int currentTab = mPager.getCurrentItem();
        int[] visibleTabs = mPagerAdapter.getVisibleTabsPositions();
        int currentPos = (currentTab < visibleTabs.length) ? visibleTabs[currentTab] : POS_OVERVIEW;

        boolean isOverview = (currentPos == POS_OVERVIEW);
        mMenuCopy.setVisible(isOverview);
        mMenuShare.setVisible(isOverview);

        boolean isPayload = (currentPos == POS_WEBSOCKET || currentPos == POS_HTTP || currentPos == POS_RAW_PAYLOAD);
        mMenuDisplayAs.setVisible(isPayload);

        if(isPayload) {
            Fragment currentFragment = getCurrentFragment();
            if(currentFragment instanceof ConnectionPayload payloadFragment) {
                if(mDisplayMode == null)
                    mDisplayMode = payloadFragment.guessDisplayAsPrintable();

                payloadFragment.setDisplayMode(mDisplayMode);

                if(mDisplayMode) {
                    mMenuDisplayAs.setTitle(R.string.display_as_hexdump);
                } else {
                    mMenuDisplayAs.setTitle(R.string.display_as_text);
                }
            }
        }
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

    private Fragment getCurrentFragment() {
        int currentTab = mPager.getCurrentItem();
        String tag = "f" + mPagerAdapter.getItemId(currentTab);
        return getSupportFragmentManager().findFragmentByTag(tag);
    }

    private void navigateToPrevious() {
        ArrayList<Integer> ids = (mFilteredIds != null) ? mFilteredIds : getAllConnectionIds();
        if(ids == null)
            return;

        int currentIndex = ids.indexOf(mConnId);
        if(currentIndex > 0) {
            mConnId = ids.get(currentIndex - 1);
            if(mFilteredIds != null)
                mFilteredIndex = currentIndex - 1;
            loadConnection();
        }
    }

    private void navigateToNext() {
        ArrayList<Integer> ids = (mFilteredIds != null) ? mFilteredIds : getAllConnectionIds();
        if(ids == null)
            return;

        int currentIndex = ids.indexOf(mConnId);
        if(currentIndex >= 0 && currentIndex < ids.size() - 1) {
            mConnId = ids.get(currentIndex + 1);
            if(mFilteredIds != null)
                mFilteredIndex = currentIndex + 1;
            loadConnection();
        }
    }

    private ArrayList<Integer> getAllConnectionIds() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();
        if(reg == null)
            return null;

        ArrayList<Integer> ids = new ArrayList<>();
        synchronized (reg) {
            for(int i = 0; i < reg.getConnCount(); i++) {
                ConnectionDescriptor conn = reg.getConn(i);
                if(conn != null)
                    ids.add(conn.incr_id);
            }
        }
        return ids;
    }

    private void loadConnection() {
        ConnectionsRegister reg = CaptureService.getConnsRegister();
        if(reg != null) {
            mConn = reg.getConnById(mConnId);

            if(mConn != null) {
                setTitle(String.format(getString(R.string.connection_number), mConnId + 1));

                unregisterConnsListener();

                int currentTab = mPager.getCurrentItem();

                mHasPayload = false;
                mHasHttpTab = false;
                mHasWsTab = false;
                mCurChunks = 0;

                setupTabs();

                int newItemCount = mPagerAdapter.getItemCount();
                if (currentTab < newItemCount) {
                    mPager.setCurrentItem(currentTab, false);
                } else {
                    mPager.setCurrentItem(0, false);
                }

                if(mConn.status < ConnectionDescriptor.CONN_STATUS_CLOSED)
                    registerConnsListener();

                updateNavigationButtons();
                updateMenuVisibility();
            } else {
                Log.w(TAG, "Connection with ID " + mConnId + " not found");
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

                if (pos == POS_OVERVIEW)
                    focusOverride = findViewById(R.id.connection_overview);
                else
                    focusOverride = findViewById(R.id.payload);

                if (focusOverride != null) {
                    focusOverride.requestFocus();
                    return true;
                }
            }
        }

        return super.onKeyDown(keyCode, event);
    }
}
