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
 * Copyright 2020-26 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.adapters;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.HttpLog;
import com.emanuelef.remote_capture.HttpLog.HttpRequest;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.HttpLogFilterDescriptor;

import java.util.ArrayList;

public class HttpLogAdapter extends RecyclerView.Adapter<HttpLogAdapter.ViewHolder> implements HttpLog.Listener {
    private static final String TAG = "HttpLogAdapter";
    private final LayoutInflater mLayoutInflater;
    private final Drawable mUnknownIcon;
    private View.OnClickListener mListener;
    private final AppsResolver mAppsResolver;
    private final Context mContext;
    private HttpRequest mSelectedItem;

    // maps a positions from HttpLog to mFilteredReqs.
    private final SparseIntArray mIdToFilteredPos;

    private ArrayList<HttpRequest> mFilteredReqs;
    private String mSearch;
    public HttpLogFilterDescriptor mFilter;

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView appName;
        TextView methodAndPath;
        TextView protoAndHost;
        TextView contentType;
        TextView reqTime;
        TextView httpStatus;
        TextView payloadSize;

        ViewHolder(View itemView) {
            super(itemView);

            icon = itemView.findViewById(R.id.icon);
            appName = itemView.findViewById(R.id.app_name);
            methodAndPath = itemView.findViewById(R.id.method_and_path);
            protoAndHost = itemView.findViewById(R.id.proto_and_host);
            contentType = itemView.findViewById(R.id.content_type);
            reqTime = itemView.findViewById(R.id.req_time);
            httpStatus = itemView.findViewById(R.id.http_status);
            payloadSize = itemView.findViewById(R.id.payload_size);
        }

        public void bindItem(HttpRequest req, Context ctx, AppsResolver apps, Drawable unknownIcon) {
            AppDescriptor app = apps.getAppByUid(req.conn.uid, 0);

            Drawable appIcon = ((app != null) && (app.getIcon() != null)) ? app.getIcon() : unknownIcon;
            icon.setImageDrawable(appIcon);

            String info_txt = (app != null) ? app.getName() : Integer.toString(req.conn.uid);
            appName.setText(info_txt);

            methodAndPath.setText(String.format("%s %s", req.method, req.path));
            protoAndHost.setText(req.getProtoAndHost());
            contentType.setText((req.reply != null) ? req.reply.contentType : "");
            reqTime.setText(Utils.formatEpochShort(ctx, req.timestamp / 1000));
            httpStatus.setText(req.decryptionError.isEmpty() ? getResponseCodeText(ctx, req) :
                    ctx.getString(R.string.decryption_error));

            int tot_length = (req.reply != null) ? (req.bodyLength + req.reply.bodyLength) : req.bodyLength;
            payloadSize.setText(Utils.formatBytes(tot_length));
            httpStatus.setTextColor(ContextCompat.getColor(ctx, req.decryptionError.isEmpty() ?
                    getResponseCodeColor(req) : R.color.statusError));
        }
    }

    private static String getResponseCodeText(Context ctx, HttpRequest req) {
        if ((req.reply != null) && (req.reply.responseCode > 0))
            return String.format(Utils.getPrimaryLocale(ctx), "%d %s", req.reply.responseCode, req.reply.responseStatus);
        else if (req.httpRst)
            return "RST_STREAM";
        else
            return "—";
    }

    private static int getResponseCodeColor(HttpRequest req) {
        int color = R.color.colorTabText;

        if (req.reply != null) {
            int code = req.reply.responseCode;

            if((code >= 200) && (code <= 299))
                color = R.color.statusOpen;
            else if((code >= 300) && (code <= 399))
                color = R.color.lightGray;
            else if((code >= 400) && (code <= 599))
                color = R.color.statusError;
        } else if (req.httpRst)
            color = R.color.statusError;

        return color;
    }

    public HttpLogAdapter(Context context, AppsResolver resolver) {
        mContext = context;
        mAppsResolver = resolver;
        mLayoutInflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mUnknownIcon = ContextCompat.getDrawable(context, R.drawable.ic_image);
        mIdToFilteredPos = new SparseIntArray();
        mListener = null;
        mFilteredReqs = null;
        mSearch = null;
        mFilter = new HttpLogFilterDescriptor();
        setHasStableIds(true);

        refreshFilteredItems();
    }

    @Override
    public int getItemCount() {
        if (mFilteredReqs != null)
            return mFilteredReqs.size();

        HttpLog httpLog = CaptureService.getHttpLog();
        return((httpLog != null) ? httpLog.getSize() : 0);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.http_req_item, parent, false);

        if(mListener != null)
            view.setOnClickListener(mListener);

        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HttpRequest item = getItem(position);
        if(item == null) {
            Log.w(TAG, "bad position: " + position);
            return;
        }

        holder.bindItem(item, mContext, mAppsResolver, mUnknownIcon);
    }

    @Override
    public long getItemId(int pos) {
        HttpRequest item = getItem(pos);

        return ((item != null) ? item.getPosition() : -1);
    }

    public @Nullable HttpRequest getItem(int pos) {
        if(mFilteredReqs != null) {
            if((pos < 0) || (pos >= mFilteredReqs.size())) {
                Log.w(TAG, "getItem(filtered): bad position: " + pos);
                return null;
            }
            return mFilteredReqs.get(pos);
        }

        HttpLog httpLog = CaptureService.getHttpLog();
        if((httpLog == null) || (pos < 0) || (pos >= httpLog.getSize())) {
            Log.w(TAG, "getItem: bad position: " + pos);
            return null;
        }

        return httpLog.getRequest(pos);
    }

    @Override
    public void onHttpRequestAdded(int pos) {
        Log.d(TAG, "onHttpRequestAdded " + pos);
        HttpLog httpLog = CaptureService.getHttpLog();
        if (httpLog == null)
            return;

        HttpRequest req = httpLog.getRequest(pos);
        if (req == null)
            return;

        if (mFilteredReqs != null) {
            if (matches(req)) {
                int filtered_pos = mFilteredReqs.size();
                mIdToFilteredPos.put(pos, filtered_pos);
                mFilteredReqs.add(req);
                notifyItemInserted(filtered_pos);
            }
        } else
            notifyItemInserted(pos);
    }

    @Override
    public void onHttpRequestUpdated(int pos) {
        if (mFilteredReqs == null) {
            if ((pos >= 0) && (pos < getItemCount()))
                notifyItemChanged(pos);
            return;
        }

        int filtered_pos = mIdToFilteredPos.get(pos, -1);
        if (filtered_pos != -1) {
            notifyItemChanged(filtered_pos);
            return;
        }

        HttpLog httpLog = CaptureService.getHttpLog();
        if (httpLog == null)
            return;

        HttpRequest req = httpLog.getRequest(pos);
        if ((req != null) && matches(req)) {
            int new_pos = mFilteredReqs.size();
            mIdToFilteredPos.put(pos, new_pos);
            mFilteredReqs.add(req);
            notifyItemInserted(new_pos);
        }
    }

    @Override
    public void onHttpRequestsClear() {
        mSelectedItem = null;
        refreshFilteredItems();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void refreshFilteredItems() {
        mIdToFilteredPos.clear();
        mFilteredReqs = null;

        final HttpLog httpLog = CaptureService.getHttpLog();
        if(httpLog != null) {
            Log.d(TAG, "refreshFilteredConn (" + httpLog.getSize() + ") unfiltered");

            if (hasFilter()) {
                int pos = 0;
                mFilteredReqs = new ArrayList<>();

                // Synchronize to improve performance of getConn
                synchronized (httpLog) {
                    for (int i = 0; i < httpLog.getSize(); i++) {
                        HttpRequest req = httpLog.getRequest(i);

                        if ((req != null) && matches(req)) {
                            mFilteredReqs.add(req);
                            mIdToFilteredPos.put(i, pos++);
                        }
                    }
                }

                Log.d(TAG, "refreshFilteredItems: " + mFilteredReqs.size() + " items matched");
            }
        }

        notifyDataSetChanged();
    }

    private boolean matches(HttpRequest req) {
        boolean searchMatch = (mSearch == null) || req.matches(mSearch);
        boolean filterMatch = !mFilter.isSet() || mFilter.matches(req);
        return searchMatch && filterMatch;
    }

    public void setSearch(String text) {
        mSearch = text;
        refreshFilteredItems();
    }

    public void setClickListener(View.OnClickListener listener) {
        mListener = listener;
    }

    public HttpRequest getSelectedItem() {
        return mSelectedItem;
    }

    public boolean hasFilter() {
        return (mSearch != null) || mFilter.isSet();
    }

    public ArrayList<Integer> getFilteredPositions() {
        if (mFilteredReqs == null)
            return null;

        ArrayList<Integer> positions = new ArrayList<>(mFilteredReqs.size());
        for (HttpRequest req : mFilteredReqs) {
            positions.add(req.getPosition());
        }
        return positions;
    }
}
