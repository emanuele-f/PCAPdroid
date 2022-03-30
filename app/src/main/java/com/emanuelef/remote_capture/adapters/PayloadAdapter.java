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

package com.emanuelef.remote_capture.adapters;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.HTTPReassembly;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.fragments.ConnectionPayload.Direction;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.PayloadChunk;
import com.emanuelef.remote_capture.model.PayloadChunk.ChunkType;
import com.emanuelef.remote_capture.model.Prefs;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/* An adapter to show PayloadChunk items.
 * Each item is wrapped into an AdapterChunk. An item can either be collapsed or expanded.
 * Since the text of a chunk can be very long (hundreds of KB) and rendering it would freeze the UI,
 * it is split into pages of VISUAL_PAGE_SIZE. */
public class PayloadAdapter extends RecyclerView.Adapter<PayloadAdapter.PayloadViewHolder> implements HTTPReassembly.ReassemblyListener {
    public static final int COLLAPSE_CHUNK_SIZE = 1500;
    public static final int VISUAL_PAGE_SIZE = 4020; // must be a multiple of 67 to avoid splitting the hexdump
    private final LayoutInflater mLayoutInflater;
    private final ConnectionDescriptor mConn;
    private final Context mContext;
    private final Direction mDir;
    private int mHandledChunks;
    private final ArrayList<AdapterChunk> mChunks = new ArrayList<>();
    private final HTTPReassembly mHttp;

    public PayloadAdapter(Context context, ConnectionDescriptor conn, Direction dir) {
        mLayoutInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mConn = conn;
        mContext = context;
        mDir = dir;

        // Note: in minimal mode, only the first chunk is captured, so don't reassemble them
        mHttp = new HTTPReassembly(CaptureService.getCurPayloadMode() == Prefs.PayloadMode.FULL, this);
        handleChunksAdded(mConn.getNumPayloadChunks());
    }

    private class AdapterChunk {
        private final PayloadChunk mChunk;
        private String mTheText;
        private boolean mIsExpanded;
        private int mNumPages = 1;
        public final int originalPos;

        AdapterChunk(PayloadChunk _chunk, int pos) {
            mChunk = _chunk;
            originalPos = pos;
        }

        boolean canBeExpanded() {
            return mChunk.payload.length > COLLAPSE_CHUNK_SIZE;
        }

        boolean isExpanded() {
            return mIsExpanded;
        }

        int getNumPages() {
            return mNumPages;
        }

        PayloadChunk getPayloadChunk() {
            return mChunk;
        }

        private void makeText() {
            int dump_len = mIsExpanded ? mChunk.payload.length : Math.min(mChunk.payload.length, COLLAPSE_CHUNK_SIZE);

            if(isPayloadTab())
                mTheText = Utils.hexdump(mChunk.payload, 0, dump_len);
            else
                mTheText = new String(mChunk.payload, 0, dump_len, StandardCharsets.UTF_8);
        }

        void expand() {
            assert(!mIsExpanded);

            mIsExpanded = true;
            makeText();

            // round up div
            mNumPages = (mTheText.length() + VISUAL_PAGE_SIZE - 1) / VISUAL_PAGE_SIZE;
        }

        // collapses the item and returns the old number of pages
        void collapse() {
            assert(mIsExpanded);

            mIsExpanded = false;
            mTheText = null;

            mNumPages = 1;
        }

        String getText(int start, int end) {
            if(mTheText == null)
                makeText();

            if((start == 0) && (end >= mTheText.length() - 1)) {
                return mTheText;
            }

            return mTheText.substring(start, end);
        }

        Page getPage(int pageIdx) {
            assert(pageIdx < mNumPages);

            if(mTheText == null)
                makeText();

            if(!mIsExpanded)
                return new Page(this, 0, mTheText.length() - 1, true);
            else
                return new Page(this, pageIdx * VISUAL_PAGE_SIZE,
                        Math.min(((pageIdx + 1) * VISUAL_PAGE_SIZE) - 1, mTheText.length() - 1),
                        pageIdx == (mNumPages - 1));
        }
    }

    private static class Page {
        AdapterChunk adaptChunk;
        int textStart;
        int textEnd;
        boolean isLast;

        Page(AdapterChunk _adaptChunk, int _textStart, int _textEnd, boolean _isLast) {
            adaptChunk = _adaptChunk;
            textStart = _textStart;
            textEnd = _textEnd;
            isLast = _isLast;
        }

        boolean isFirst() {
            return (textStart == 0);
        }

        String getText() {
            return adaptChunk.getText(textStart, textEnd);
        }
    }

    protected static class PayloadViewHolder extends RecyclerView.ViewHolder {
        TextView header;
        TextView dump;
        ImageView expandButton;

        public PayloadViewHolder(View view) {
            super(view);

            header = view.findViewById(R.id.header);
            dump = view.findViewById(R.id.dump);
            expandButton = view.findViewById(R.id.expand_button);
        }
    }

    @NonNull
    @Override
    public PayloadViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = mLayoutInflater.inflate(R.layout.payload_item, parent, false);
        PayloadViewHolder holder = new PayloadViewHolder(view);

        holder.expandButton.setOnClickListener(v -> {
            int pos = holder.getAbsoluteAdapterPosition();
            Page page = getItem(pos);

            if(page.adaptChunk.isExpanded()) {
                int numPages = page.adaptChunk.getNumPages();
                page.adaptChunk.collapse();
                notifyItemRangeRemoved(pos - (numPages - 1), numPages - 1);
            } else {
                page.adaptChunk.expand();
                notifyItemRangeInserted(pos + 1, page.adaptChunk.getNumPages() - 1);
            }

            notifyItemChanged(pos);
        });

        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull PayloadViewHolder holder, int position) {
        Page page = getItem(position);
        Locale locale = Utils.getPrimaryLocale(mContext);
        String prefix = "";
        PayloadChunk chunk = page.adaptChunk.getPayloadChunk();

        if(page.isFirst()) {
            // Show the header for the first page
            if(!isPayloadTab()) {
                // NOTE: do not add the prefix in the "Payload" tab, as the chunk is not analyzed by the
                // HTTPReassembly
                if(chunk.type == ChunkType.HTTP)
                    prefix = "HTTP_";
                else if(chunk.type == ChunkType.WEBSOCKET)
                    prefix = "WS_";
            }

            holder.header.setText(String.format(locale,
                    "#%d [%s%s] %s — %s", page.adaptChunk.originalPos + 1,
                    prefix, chunk.is_sent ? "TX" : "RX",
                    (new SimpleDateFormat("HH:mm:ss.SSS", locale)).format(new Date(chunk.timestamp)),
                    Utils.formatBytes(chunk.payload.length)));
            holder.header.setVisibility(View.VISIBLE);
        } else
            holder.header.setVisibility(View.GONE);

        if(page.isLast && page.adaptChunk.canBeExpanded()) {
            holder.expandButton.setVisibility(View.VISIBLE);
            holder.expandButton.setRotation(page.adaptChunk.isExpanded() ? 180 : 0);
        } else
            holder.expandButton.setVisibility(View.GONE);

        holder.dump.setText(page.getText());

        if(chunk.is_sent) {
            holder.dump.setBackgroundResource(R.color.sentPayloadBg);
            holder.dump.setTextColor(ContextCompat.getColor(mContext, R.color.sentPayloadFg));
        } else {
            holder.dump.setBackgroundResource(R.color.rcvdPayloadBg);
            holder.dump.setTextColor(ContextCompat.getColor(mContext, R.color.rcvdPayloadFg));
        }
    }

    @Override
    public int getItemCount() {
        int count = 0;

        for(AdapterChunk aChunk: mChunks)
            count += aChunk.getNumPages();

        return count;
    }

    public Page getItem(int pos) {
        if(pos < 0)
            return null;

        int count = 0;
        int i;

        // Find the AdapterChunk for the given page pos
        for(i=0; i < mChunks.size(); i++) {
            AdapterChunk aChunk = mChunks.get(i);
            int new_count = count + aChunk.getNumPages();

            if((pos >= count) && (pos < new_count))
                break;

            count = new_count;
        }

        if(i >= mChunks.size())
            return null;

        int pageIdx = pos - count;
        return mChunks.get(i).getPage(pageIdx);
    }

    private boolean isPayloadTab() {
        return(mDir == Direction.BOTH);
    }

    public void handleChunksAdded(int tot_chunks) {
        for(int i = mHandledChunks; i<tot_chunks; i++) {
            PayloadChunk chunk = mConn.getPayloadChunk(i);

            if((mDir == Direction.BOTH) ||
                    (mDir == Direction.REQUEST_ONLY && chunk.is_sent) ||
                    (mDir == Direction.RESPONSE_ONLY && !chunk.is_sent)) {
                if(!isPayloadTab() && (chunk.type == ChunkType.HTTP))
                    mHttp.handleChunk(chunk); // will call onChunkReassembled
                else {
                    int insert_pos = getItemCount();
                    mChunks.add(new AdapterChunk(chunk, mChunks.size()));
                    notifyItemInserted(insert_pos);
                }
            }
        }

        mHandledChunks = tot_chunks;
    }

    @Override
    public void onChunkReassembled(PayloadChunk chunk) {
        int insert_pos = getItemCount();
        mChunks.add(new AdapterChunk(chunk, mChunks.size()));
        notifyItemInserted(insert_pos);
    }
}
