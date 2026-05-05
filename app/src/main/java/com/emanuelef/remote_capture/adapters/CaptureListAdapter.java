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

package com.emanuelef.remote_capture.adapters;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.CaptureList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CaptureListAdapter extends RecyclerView.Adapter<CaptureListAdapter.ViewHolder> {
    private final Context mContext;
    private final LayoutInflater mInflater;
    private final List<CaptureList.Capture> mCaptures = new ArrayList<>();
    private final Set<CaptureList.Capture> mSelected = new HashSet<>();
    private final int mSelectedColor;
    private CaptureList.Capture mContextMenuItem;
    private OnItemClickListener mClickListener;
    private View.OnLongClickListener mSelectionLongClickListener;

    public interface OnItemClickListener {
        void onItemClick(CaptureList.Capture capture, int position);
    }

    public CaptureListAdapter(Context context) {
        mContext = context;
        mInflater = LayoutInflater.from(context);

        TypedArray a = context.obtainStyledAttributes(new int[]{android.R.attr.colorControlHighlight});
        mSelectedColor = a.getColor(0, 0x40808080);
        a.recycle();
    }

    public void setItems(List<CaptureList.Capture> captures) {
        mCaptures.clear();
        mCaptures.addAll(captures);
        mSelected.retainAll(mCaptures);
        notifyDataSetChanged();
    }

    public void setOnItemClickListener(OnItemClickListener l) { mClickListener = l; }
    public void setSelectionLongClickListener(View.OnLongClickListener l) { mSelectionLongClickListener = l; }

    public Set<CaptureList.Capture> getSelected() { return mSelected; }
    public int getSelectedCount() { return mSelected.size(); }
    public CaptureList.Capture getContextMenuItem() { return mContextMenuItem; }

    public void toggleSelection(CaptureList.Capture capture) {
        int pos = mCaptures.indexOf(capture);
        if (pos < 0)
            return;
        if (!mSelected.add(capture))
            mSelected.remove(capture);
        notifyItemChanged(pos);
    }

    public void selectOnly(CaptureList.Capture capture) {
        mSelected.clear();
        if (mCaptures.contains(capture))
            mSelected.add(capture);
        notifyDataSetChanged();
    }

    public void clearSelection() {
        if (mSelected.isEmpty())
            return;
        mSelected.clear();
        notifyDataSetChanged();
    }

    public void selectAll() {
        if (mSelected.size() == mCaptures.size())
            return;
        mSelected.clear();
        mSelected.addAll(mCaptures);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() { return mCaptures.size(); }

    public CaptureList.Capture getItem(int pos) { return mCaptures.get(pos); }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = mInflater.inflate(R.layout.capture_list_item, parent, false);
        v.setLongClickable(true);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CaptureList.Capture capture = mCaptures.get(position);
        holder.bind(mContext, capture);

        if (mSelected.contains(capture))
            holder.itemView.setBackgroundColor(mSelectedColor);
        else
            holder.itemView.setBackgroundResource(holder.mDefaultBackground);

        holder.itemView.setOnClickListener(v -> {
            if (mClickListener != null)
                mClickListener.onItemClick(capture, holder.getAbsoluteAdapterPosition());
        });
        holder.itemView.setOnLongClickListener(v -> {
            if ((mSelectionLongClickListener != null) && mSelectionLongClickListener.onLongClick(v))
                return true;

            // see registerForContextMenu
            mContextMenuItem = capture;
            return false;
        });
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView size;
        final TextView meta;
        final ImageView appIcon;
        final TextView extraCount;
        final ImageView decryptionIcon;
        final int mDefaultBackground;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.capture_name);
            size = itemView.findViewById(R.id.capture_size);
            meta = itemView.findViewById(R.id.capture_meta);
            appIcon = itemView.findViewById(R.id.app_icon);
            extraCount = itemView.findViewById(R.id.app_extra_count);
            decryptionIcon = itemView.findViewById(R.id.decryption_icon);

            TypedValue tv = new TypedValue();
            itemView.getContext().getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            mDefaultBackground = tv.resourceId;
        }

        void bind(Context context, CaptureList.Capture capture) {
            name.setText(capture.name);
            size.setText(Utils.formatBytes(capture.size));

            String date = Utils.formatEpochShort(context, capture.startTime / 1000);
            String duration = Utils.formatDuration(context, capture.duration);
            String apps = CaptureList.formatTargetApps(context, capture.targetApps);
            meta.setText(context.getString(R.string.capture_list_info, date, duration, apps));

            decryptionIcon.setVisibility(capture.decrypted ? View.VISIBLE : View.GONE);

            bindIcon(context, capture);
        }

        private void bindIcon(Context context, CaptureList.Capture capture) {
            appIcon.setBackground(null);
            appIcon.setPadding(0, 0, 0, 0);

            Drawable icon = null;
            if (!capture.targetApps.isEmpty()) {
                CaptureList.TargetApp first = capture.targetApps.get(0);
                AppDescriptor app = AppsResolver.resolveInstalledApp(context.getPackageManager(), first.packageName(), 0);
                if (app != null)
                    icon = app.getIcon();
            }
            if (icon == null)
                icon = ContextCompat.getDrawable(context, R.drawable.ic_image);
            appIcon.setImageDrawable(icon);

            int extra = capture.targetApps.size() - 1;
            if (extra > 0) {
                extraCount.setText(context.getString(R.string.plus_n, extra));
                extraCount.setVisibility(View.VISIBLE);
            } else
                extraCount.setVisibility(View.GONE);
        }
    }
}
