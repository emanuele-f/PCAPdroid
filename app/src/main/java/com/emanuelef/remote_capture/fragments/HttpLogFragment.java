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

package com.emanuelef.remote_capture.fragments;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.graphics.Insets;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.HttpLog;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.HttpDetailsActivity;
import com.emanuelef.remote_capture.activities.HttpLogFilterActivity;
import com.emanuelef.remote_capture.adapters.HttpLogAdapter;
import com.emanuelef.remote_capture.model.HttpLogFilterDescriptor;
import com.emanuelef.remote_capture.views.EmptyRecyclerView;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;

public class HttpLogFragment extends Fragment implements HttpLog.Listener, MenuProvider, SearchView.OnQueryTextListener {
    private static final String TAG = "HttpLogFragment";
    private TextView mEmptyText;
    private HttpLogAdapter mAdapter;
    private EmptyRecyclerView mRecyclerView;
    private FloatingActionButton mFabDown;
    private int mFabDownMargin = 0;
    private MenuItem mMenuItemSearch;
    private SearchView mSearchView;
    private Handler mHandler;
    private ChipGroup mActiveFilter;
    private Slider mSizeSlider;
    private boolean mSizeSliderActive = false;

    private String mQueryToApply;
    private AppsResolver mApps;
    private boolean autoScroll;

    private final ActivityResultLauncher<Intent> filterLauncher =
            registerForActivityResult(new StartActivityForResult(), this::filterResult);

    @Override
    public void onResume() {
        super.onResume();

        refreshEmptyText();

        registerHttpListener();
        mRecyclerView.setEmptyView(mEmptyText); // after registerConnsListener, when the adapter is populated

        if (mAdapter != null) {
            boolean visible = mAdapter.mFilter.minPayloadSize >= 1024;
            mSizeSlider.setVisibility(visible ? View.VISIBLE : View.GONE);
            mSizeSlider.setLabelBehavior(visible ? LabelFormatter.LABEL_VISIBLE : LabelFormatter.LABEL_GONE);
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        unregisterHttpListener();
        mRecyclerView.setEmptyView(null);

        if(mSearchView != null)
            mQueryToApply = mSearchView.getQuery().toString();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if(mSearchView != null)
            outState.putString("search", mSearchView.getQuery().toString());
        if(mAdapter != null)
            outState.putSerializable("http_log_filter_desc", mAdapter.mFilter);
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        requireActivity().addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        return inflater.inflate(R.layout.connections, container, false);
    }

    private void registerHttpListener() {
        HttpLog httpLog = CaptureService.getHttpLog();

        if (httpLog != null)
            httpLog.setListener(this);

        if (mAdapter != null) {
            mAdapter.onHttpRequestsClear();
            recheckScroll();
            scrollToBottom();
        }
    }

    private void unregisterHttpListener() {
        HttpLog httpLog = CaptureService.getHttpLog();

        if (httpLog != null)
            httpLog.setListener(null);

        if (mAdapter != null) {
            mAdapter.onHttpRequestsClear();
            recheckScroll();
            scrollToBottom();
        }
    }

    private void refreshEmptyText() {
        if((CaptureService.getHttpLog() != null) || CaptureService.isServiceActive())
            mEmptyText.setText(mAdapter.hasFilter() ? R.string.no_matches_found : R.string.no_requests);
        else
            mEmptyText.setText(R.string.capture_not_running_status);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mHandler = new Handler(Looper.getMainLooper());
        mFabDown = view.findViewById(R.id.fabDown);
        mRecyclerView = view.findViewById(R.id.connections_view);
        EmptyRecyclerView.MyLinearLayoutManager layoutMan = new EmptyRecyclerView.MyLinearLayoutManager(requireContext());
        mRecyclerView.setLayoutManager(layoutMan);
        mApps = new AppsResolver(requireContext());

        mEmptyText = view.findViewById(R.id.no_connections);
        mSizeSlider = view.findViewById(R.id.size_slider);
        mSizeSlider.setLabelFormatter(value -> Utils.formatBytes(((long) value) * 1024));
        mSizeSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (mAdapter != null) {
                mAdapter.mFilter.minPayloadSize = ((long) value) * 1024;
                refreshFilteredRequests();
            }
        });
        mSizeSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                mSizeSliderActive = true;
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                if (slider.getValue() == 0) {
                    slider.setVisibility(View.GONE);
                    slider.setLabelBehavior(LabelFormatter.LABEL_GONE);
                }

                mSizeSliderActive = false;
                recheckMaxPayloadSize();
            }
        });

        mActiveFilter = view.findViewById(R.id.active_filter);
        mActiveFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if(mAdapter != null) {
                for(int checkedId: checkedIds)
                    mAdapter.mFilter.clear(checkedId);
                refreshFilteredRequests();
            }
        });

        mAdapter = new HttpLogAdapter(requireContext(), mApps);
        mRecyclerView.setAdapter(mAdapter);

        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(mRecyclerView.getContext(),
                layoutMan.getOrientation());
        mRecyclerView.addItemDecoration(dividerItemDecoration);

        mAdapter.setClickListener(v -> {
            int pos = mRecyclerView.getChildLayoutPosition(v);
            HttpLog.HttpRequest item = mAdapter.getItem(pos);

            if(item != null) {
                Intent intent = new Intent(requireContext(), HttpDetailsActivity.class);
                intent.putExtra(HttpDetailsActivity.HTTP_REQ_POS_KEY, item.getPosition());
                startActivity(intent);
            }
        });

        autoScroll = true;
        showFabDown(false);

        ViewCompat.setOnApplyWindowInsetsListener(view.findViewById(R.id.linearlayout), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                    WindowInsetsCompat.Type.displayCutout());

            v.setPadding(insets.left, insets.top, insets.right, 0);

            // only consume the top inset
            return windowInsets.inset(insets.left, insets.top, insets.right, 0);
        });

        mFabDown.setOnClickListener(v -> scrollToBottom());
        ViewCompat.setOnApplyWindowInsetsListener(mFabDown, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                    WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());

            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            if (mFabDownMargin == 0)
                // save base margin from the layout
                mFabDownMargin = mlp.bottomMargin;

            mlp.bottomMargin = mFabDownMargin + insets.bottom;
            v.setLayoutParams(mlp);

            return WindowInsetsCompat.CONSUMED;
        });

        mRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                recheckScroll();
            }
        });

        if(savedInstanceState != null) {
            String search = savedInstanceState.getString("search");

            if((search != null) && !search.isEmpty())
                mQueryToApply = search;

            if(savedInstanceState.containsKey("http_log_filter_desc"))
                mAdapter.mFilter = Utils.getSerializable(savedInstanceState, "http_log_filter_desc", HttpLogFilterDescriptor.class);
        }
        refreshActiveFilter();

        CaptureService.observeStatus(this, serviceStatus -> {
            if(serviceStatus == CaptureService.ServiceStatus.STARTED) {
                unregisterHttpListener();
                registerHttpListener();

                autoScroll = true;
                showFabDown(false);
                mEmptyText.setText(R.string.no_requests);
                mApps.clear();
            }
        });
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.http_log_menu, menu);

        mMenuItemSearch = menu.findItem(R.id.search);

        mSearchView = (SearchView) mMenuItemSearch.getActionView();
        mSearchView.setOnQueryTextListener(this);

        if((mQueryToApply != null) && (!mQueryToApply.isEmpty())) {
            String query = mQueryToApply;
            mQueryToApply = null;
            setQuery(query);
        }
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.edit_filter) {
            Intent intent = new Intent(requireContext(), HttpLogFilterActivity.class);
            intent.putExtra(HttpLogFilterActivity.FILTER_DESCRIPTOR, mAdapter.mFilter);
            filterLauncher.launch(intent);
            return true;
        }

        return false;
    }

    private void setQuery(String query) {
        Utils.setSearchQuery(mSearchView, mMenuItemSearch, query);
    }

    @Override
    public boolean onQueryTextSubmit(String query) { return true; }

    @Override
    public boolean onQueryTextChange(String newText) {
        mAdapter.setSearch(newText);
        recheckScroll();
        refreshEmptyText();
        return true;
    }

    // NOTE: dispatched from activity, returns true if handled
    public boolean onBackPressed() {
        return Utils.backHandleSearchview(mSearchView);
    }

    @Override
    public void onHttpRequestAdded(int pos) {
        Utils.runOnUi(() -> {
            if (mAdapter != null) {
                mAdapter.onHttpRequestAdded(pos);

                recheckScroll();
                if (autoScroll)
                    scrollToBottom();
            }
        }, mHandler);
    }

    @Override
    public void onHttpRequestUpdated(int pos) {
        Utils.runOnUi(() -> {
            if (mAdapter != null)
                mAdapter.onHttpRequestUpdated(pos);
        }, mHandler);
    }

    @Override
    public void onHttpRequestsClear() {
        Utils.runOnUi(() -> {
            if (mAdapter != null)
                mAdapter.onHttpRequestsClear();
        }, mHandler);
    }

    private void recheckScroll() {
        final EmptyRecyclerView.MyLinearLayoutManager layoutMan = (EmptyRecyclerView.MyLinearLayoutManager) mRecyclerView.getLayoutManager();
        assert layoutMan != null;
        int first_visibile_pos = layoutMan.findFirstCompletelyVisibleItemPosition();
        int last_visible_pos = layoutMan.findLastCompletelyVisibleItemPosition();
        int last_pos = mAdapter.getItemCount() - 1;
        boolean reached_bottom = (last_visible_pos >= last_pos);
        boolean is_scrolling = (first_visibile_pos != 0) || (!reached_bottom);

        if(is_scrolling) {
            if(reached_bottom) {
                autoScroll = true;
                showFabDown(false);
            } else {
                autoScroll = false;
                showFabDown(true);
            }
        } else
            showFabDown(false);
    }

    private void showFabDown(boolean visible) {
        // compared to setVisibility, .show/.hide provide animations and also properly clear the AnchorId
        if(visible)
            mFabDown.show();
        else
            mFabDown.hide();
    }

    private void scrollToBottom() {
        int last_pos = mAdapter.getItemCount() - 1;
        mRecyclerView.scrollToPosition(last_pos);

        showFabDown(false);
    }

    private void refreshActiveFilter() {
        if(mAdapter == null)
            return;

        mActiveFilter.removeAllViews();
        mAdapter.mFilter.toChips(getLayoutInflater(), mActiveFilter);

        // minPayloadSize slider
        long minSizeKB = mAdapter.mFilter.minPayloadSize / 1024;
        boolean sliderVisible = false;
        HttpLog httpLog = CaptureService.getHttpLog();

        if ((httpLog != null) && (minSizeKB > 0)) {
            long maxSizeKb = getMaxPayloadSize() / 1024;
            maxSizeKb = Math.max(maxSizeKb, minSizeKB);

            if (maxSizeKb >= 2) {
                mSizeSlider.setValueTo(maxSizeKb);
                mSizeSlider.setValue(minSizeKB);
                sliderVisible = true;
            }
        }

        if (sliderVisible && (mSizeSlider.getVisibility() != View.VISIBLE)) {
            mSizeSlider.setVisibility(View.VISIBLE);
            mSizeSlider.setLabelBehavior(LabelFormatter.LABEL_VISIBLE);
        }
    }

    private long getMaxPayloadSize() {
        HttpLog httpLog = CaptureService.getHttpLog();
        if (httpLog == null)
            return 0;

        long maxSize = 0;
        synchronized (httpLog) {
            for (int i = 0; i < httpLog.size(); i++) {
                HttpLog.HttpRequest req = httpLog.getRequest(i);
                if (req != null) {
                    int totalSize = (req.reply != null) ? (req.bodyLength + req.reply.bodyLength) : req.bodyLength;
                    if (totalSize > maxSize)
                        maxSize = totalSize;
                }
            }
        }
        return maxSize;
    }

    private void recheckMaxPayloadSize() {
        if ((mSizeSlider.getVisibility() == View.VISIBLE) && !mSizeSliderActive) {
            long maxSizeKB = getMaxPayloadSize() / 1024;

            if (maxSizeKB > mSizeSlider.getValueTo())
                mSizeSlider.setValueTo(maxSizeKB);
        }
    }

    private void refreshFilteredRequests() {
        mAdapter.refreshFilteredItems();
        refreshActiveFilter();
        recheckScroll();
    }

    private void filterResult(final ActivityResult result) {
        if(result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            HttpLogFilterDescriptor descriptor = Utils.getSerializableExtra(result.getData(), HttpLogFilterActivity.FILTER_DESCRIPTOR, HttpLogFilterDescriptor.class);
            if(descriptor != null) {
                mAdapter.mFilter = descriptor;
                mAdapter.refreshFilteredItems();
                refreshActiveFilter();
            }
        }
    }
}
