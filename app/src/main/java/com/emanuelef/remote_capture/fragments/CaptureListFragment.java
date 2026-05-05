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

package com.emanuelef.remote_capture.fragments;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.core.graphics.Insets;
import androidx.core.view.MenuProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.CaptureListActivity;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.adapters.CaptureListAdapter;
import com.emanuelef.remote_capture.model.CaptureList;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CaptureListFragment extends Fragment {
    private static final String TAG = "CaptureListFragment";

    private CaptureList mCaptureList;
    private CaptureListAdapter mAdapter;
    private View mDiskInfo;
    private TextView mStorageValue;
    private ProgressBar mStorageBar;
    private TextView mSectionHeader;
    private TextView mEmptyText;
    private RecyclerView mRecycler;
    private ActionMode mActionMode;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.capture_list_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mDiskInfo = view.findViewById(R.id.disk_info);
        mStorageValue = view.findViewById(R.id.storage_value);
        mStorageBar = view.findViewById(R.id.storage_bar);
        mSectionHeader = view.findViewById(R.id.section_header);
        mEmptyText = view.findViewById(R.id.empty_text);
        mRecycler = view.findViewById(R.id.captures_list);

        Context ctx = requireContext();
        mCaptureList = new CaptureList(ctx);
        mAdapter = new CaptureListAdapter(ctx);
        mRecycler.setLayoutManager(new LinearLayoutManager(ctx));
        mRecycler.setAdapter(mAdapter);

        ViewGroup root = view.findViewById(R.id.capture_list_root);
        root.setClipToPadding(false);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() |
                    WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });

        mAdapter.setOnItemClickListener((capture, position) -> {
            if (mActionMode != null) {
                mAdapter.toggleSelection(capture);
                updateActionMode();
            } else {
                Intent intent = new Intent(requireContext(), MainActivity.class);
                intent.putExtra(CaptureListActivity.OPEN_PCAP_EXTRA, capture.uri);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
        });
        mAdapter.setSelectionLongClickListener(v -> {
            if (mActionMode != null) {
                int pos = mRecycler.getChildLayoutPosition(v);
                CaptureList.Capture capture = mAdapter.getItem(pos);
                mAdapter.toggleSelection(capture);
                updateActionMode();
                return true;
            }
            return false;
        });

        registerForContextMenu(mRecycler);

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menuInflater.inflate(R.menu.capture_list_menu, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.refresh) {
                    refresh();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        mCaptureList.reload();
        List<CaptureList.Capture> captures = mCaptureList.getCaptures();
        mAdapter.setItems(captures);

        boolean empty = captures.isEmpty();
        mDiskInfo.setVisibility(empty ? View.GONE : View.VISIBLE);
        mSectionHeader.setVisibility(empty ? View.GONE : View.VISIBLE);
        mRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        mEmptyText.setVisibility(empty ? View.VISIBLE : View.GONE);

        if (!empty) {
            mSectionHeader.setText(getString(R.string.captures_count, captures.size()));
            updateStorageInfo();
        }

        if (mActionMode != null)
            updateActionMode();
    }

    private void updateStorageInfo() {
        long used = mCaptureList.getTotalSize();
        long total = getDownloadFreeBytes() + used;

        if (total > 0) {
            mStorageValue.setText(getString(R.string.storage_used_value,
                    Utils.formatBytes(used), Utils.formatBytes(total)));
            int pcapProgress = (int) Math.min(1000, (used * 1000L) / total);
            mStorageBar.setProgress(pcapProgress);
        } else {
            mStorageValue.setText(Utils.formatBytes(used));
            mStorageBar.setProgress(0);
            mStorageBar.setSecondaryProgress(0);
        }
    }

    private long getDownloadFreeBytes() {
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if ((dir == null) || !dir.exists())
                dir = Environment.getExternalStorageDirectory();
            if (dir == null)
                return 0;
            return new StatFs(dir.getAbsolutePath()).getFreeBytes();
        } catch (Exception e) {
            Log.w(TAG, "getDownloadsStatFs: " + e.getMessage());
            return 0;
        }
    }

    @Override
    public void onCreateContextMenu(@NonNull ContextMenu menu, @NonNull View v,
                                    @Nullable ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        requireActivity().getMenuInflater().inflate(R.menu.capture_list_context_menu, menu);
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        CaptureList.Capture capture = mAdapter.getContextMenuItem();
        if (capture == null)
            return super.onContextItemSelected(item);

        int id = item.getItemId();
        if (id == R.id.select_capture) {
            enterSelectionMode(capture);
            return true;
        } else if (id == R.id.share_capture) {
            Utils.shareCapture(requireContext(), Uri.parse(capture.uri));
            return true;
        } else if (id == R.id.rename_capture) {
            renameCapture(capture);
            return true;
        } else if (id == R.id.delete_capture) {
            confirmDeleteSingle(capture);
            return true;
        }
        return super.onContextItemSelected(item);
    }

    private void enterSelectionMode(CaptureList.Capture initial) {
        if (mActionMode != null)
            return;
        mActionMode = ((AppCompatActivity) requireActivity()).startSupportActionMode(mActionModeCallback);
        mAdapter.selectOnly(initial);
        updateActionMode();
    }

    private void updateActionMode() {
        if (mActionMode == null)
            return;
        int n = mAdapter.getSelectedCount();
        if (n == 0) {
            mActionMode.finish();
            return;
        }
        mActionMode.setTitle(getString(R.string.n_selected, n));
    }

    private final ActionMode.Callback mActionModeCallback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, android.view.Menu menu) {
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.capture_list_cab, menu);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, android.view.Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, android.view.MenuItem item) {
            int id = item.getItemId();
            if (id == R.id.delete_entry) {
                confirmDeleteSelected();
                return true;
            } else if (id == R.id.select_all) {
                if (mAdapter.getSelectedCount() == mAdapter.getItemCount())
                    mode.finish();
                else {
                    mAdapter.selectAll();
                    updateActionMode();
                }
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mAdapter.clearSelection();
            mActionMode = null;
        }
    };

    private void renameCapture(CaptureList.Capture capture) {
        Context ctx = requireContext();
        String basename = Utils.removePcapExtension(capture.name);
        String ext = capture.name.substring(basename.length());

        EditText edit = new EditText(ctx);
        edit.setInputType(InputType.TYPE_CLASS_TEXT);
        edit.setText(basename);
        edit.setSelection(edit.getText().length());

        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        android.widget.FrameLayout container = new android.widget.FrameLayout(ctx);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(edit);

        new AlertDialog.Builder(ctx)
                .setTitle(R.string.rename)
                .setView(container)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    String newName = edit.getText().toString().trim() + ext;
                    if (!newName.isEmpty() && !newName.equals(capture.name)) {
                        try {
                            ContentValues values = new ContentValues();
                            values.put(MediaStore.MediaColumns.DISPLAY_NAME, newName);
                            ctx.getContentResolver().update(Uri.parse(capture.uri), values, null, null);
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to rename file: " + e.getMessage());
                        }
                        mCaptureList.rename(capture, newName);
                        refresh();
                    }
                })
                .setNegativeButton(R.string.cancel_action, (dialog, which) -> {})
                .show();
    }

    private void confirmDeleteSingle(CaptureList.Capture capture) {
        Set<CaptureList.Capture> set = new HashSet<>();
        set.add(capture);
        confirmDelete(set);
    }

    private void confirmDeleteSelected() {
        confirmDelete(new HashSet<>(mAdapter.getSelected()));
    }

    private void confirmDelete(Set<CaptureList.Capture> toDelete) {
        if (toDelete.isEmpty())
            return;

        new AlertDialog.Builder(requireContext())
                .setMessage(R.string.captures_delete_confirm)
                .setCancelable(true)
                .setPositiveButton(R.string.yes, (dialog, which) -> {
                    deleteCaptures(toDelete);
                    if (mActionMode != null)
                        mActionMode.finish();
                    refresh();
                })
                .setNegativeButton(R.string.no, (dialog, which) -> {})
                .show();
    }

    private void deleteCaptures(Set<CaptureList.Capture> captures) {
        Context ctx = requireContext();
        List<CaptureList.Capture> removed = new ArrayList<>();

        for (CaptureList.Capture c: captures) {
            if (deleteCaptureFile(ctx, Uri.parse(c.uri)))
                removed.add(c);
            else
                Utils.showToast(ctx, R.string.delete_error);
        }

        if (!removed.isEmpty())
            mCaptureList.remove(removed);
    }

    private boolean deleteCaptureFile(Context ctx, Uri uri) {
        if (uri == null)
            return false;

        String fpath = Utils.uriToFilePath(ctx, uri);
        if (fpath != null) {
            try {
                File f = new File(fpath);
                if (!f.exists() || f.delete())
                    return true;
            } catch (Exception e) {
                Log.w(TAG, "deleteCaptureFile (file): " + e.getMessage());
            }
        }
        try {
            ctx.getContentResolver().delete(uri, null, null);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "deleteCaptureFile (resolver): " + e.getMessage());
            return false;
        }
    }

    public boolean onBackPressed() {
        if (mActionMode != null) {
            mActionMode.finish();
            return true;
        }
        return false;
    }
}
