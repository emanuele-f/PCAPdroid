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

package com.emanuelef.remote_capture.views;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

// Adapter from https://gist.github.com/AlexZhukovich/537eaa1e3c82ef9f5d5cd22efdc80c54#file-emptyrecyclerview-java
public class EmptyRecyclerView extends RecyclerView {
    private View mEmptyView;

    /* Workaround for crash "java.lang.IndexOutOfBoundsException: Inconsistency detected. Invalid item position 0(offset:-1)".
     * See https://stackoverflow.com/questions/30220771/recyclerview-inconsistency-detected-invalid-item-position .
     * It can be reproduced by setting CaptureService.CONNECTIONS_LOG_SIZE = 4 and triggering a rollover right after inserting
     * item 3 in the register. It may take several tries to reproduce.
     * Possibly related issues:
     *  - https://issuetracker.google.com/issues?q=componentid:192731%2B%20IndexOutOfBoundsException%20Invalid%20item%20position
     * Another way to fix the issue is to disable the item animations via setItemAnimator(null).
     */
    public static class MyLinearLayoutManager extends LinearLayoutManager {
        public MyLinearLayoutManager(Context context) {
            super(context);
        }

        @Override
        public boolean supportsPredictiveItemAnimations() {
            return false;
        }
    }

    public EmptyRecyclerView(Context context) {
        super(context);
        init();
    }

    public EmptyRecyclerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EmptyRecyclerView(Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        // Disable the item change animation since it cancels the touch event, making it impossible
        // to long click a continuously refreshed item.
        // https://stackoverflow.com/questions/58628885/handle-touch-events-for-recyclerview-with-frequently-changing-data
        ItemAnimator animator = getItemAnimator();
        if(animator instanceof SimpleItemAnimator)
            ((SimpleItemAnimator)animator).setSupportsChangeAnimations(false);
    }

    private void initEmptyView() {
        if (mEmptyView != null) {
            mEmptyView.setVisibility(
                    getAdapter() == null || getAdapter().getItemCount() == 0 ? VISIBLE : GONE);
            EmptyRecyclerView.this.setVisibility(
                    getAdapter() == null || getAdapter().getItemCount() == 0 ? GONE : VISIBLE);
        }
    }

    final AdapterDataObserver observer = new AdapterDataObserver() {
        @Override
        public void onChanged() {
            super.onChanged();
            initEmptyView();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            super.onItemRangeInserted(positionStart, itemCount);
            initEmptyView();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            super.onItemRangeRemoved(positionStart, itemCount);
            initEmptyView();
        }
    };

    @Override
    public void setAdapter(Adapter adapter) {
        Adapter oldAdapter = getAdapter();
        super.setAdapter(adapter);

        if (oldAdapter != null) {
            oldAdapter.unregisterAdapterDataObserver(observer);
        }

        if (adapter != null) {
            adapter.registerAdapterDataObserver(observer);
        }

        initEmptyView();
    }

    public void setEmptyView(View view) {
        this.mEmptyView = view;
        initEmptyView();
    }
}