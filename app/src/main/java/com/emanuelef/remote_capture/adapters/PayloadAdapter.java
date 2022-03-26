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
import androidx.collection.ArraySet;
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

public class PayloadAdapter extends RecyclerView.Adapter<PayloadAdapter.PayloadViewHolder> implements HTTPReassembly.ReassemblyListener {
    public static final int COLLAPSE_CHUNK_SIZE = 1500;
    private final LayoutInflater mLayoutInflater;
    private final ConnectionDescriptor mConn;
    private final Context mContext;
    private final Direction mDir;
    private int mHandledChunks;
    private final ArrayList<PayloadChunk> mChunks = new ArrayList<>();
    private final HTTPReassembly mHttp;
    private final ArraySet<Integer> mExpandedPos = new ArraySet<>();

    public PayloadAdapter(Context context, ConnectionDescriptor conn, Direction dir) {
        mLayoutInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mConn = conn;
        mContext = context;
        mDir = dir;

        // Note: in minimal mode, only the first chunk is captured, so don't reassemble them
        mHttp = new HTTPReassembly(CaptureService.getCurPayloadMode() == Prefs.PayloadMode.FULL, this);
        handleChunksAdded(mConn.getNumPayloadChunks());
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

            if(mExpandedPos.contains(pos))
                mExpandedPos.remove(pos);
            else
                mExpandedPos.add(pos);

            notifyItemChanged(pos);
        });

        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull PayloadViewHolder holder, int position) {
        PayloadChunk chunk = getItem(position);
        Locale locale = Utils.getPrimaryLocale(mContext);
        String prefix = "";

        if(!isPayloadTab()) {
            // NOTE: do not add the prefix in the "Payload" tab, as the chunk is not analyzed by the
            // HTTPReassembly
            if(chunk.type == ChunkType.HTTP)
                prefix = "HTTP_";
            else if(chunk.type == ChunkType.WEBSOCKET)
                prefix = "WS_";
        }

        holder.header.setText(String.format(locale,
                "#%d [%s%s] %s — %s", position + 1,
                prefix, chunk.is_sent ? "TX" : "RX",
                (new SimpleDateFormat("HH:mm:ss.SSS", locale)).format(new Date(chunk.timestamp)),
                Utils.formatBytes(chunk.payload.length)));

        boolean is_expanded = mExpandedPos.contains(position);

        if(chunk.payload.length > COLLAPSE_CHUNK_SIZE) {
            holder.expandButton.setVisibility(View.VISIBLE);
            holder.expandButton.setRotation(is_expanded ? 180 : 0);
        } else
            holder.expandButton.setVisibility(View.GONE);

        int dump_len = is_expanded ? chunk.payload.length : Math.min(chunk.payload.length, COLLAPSE_CHUNK_SIZE);
        String dump;

        if(isPayloadTab())
            dump = Utils.hexdump(chunk.payload, 0, dump_len);
        else
            dump = new String(chunk.payload, 0, dump_len, StandardCharsets.UTF_8);

        holder.dump.setText(dump);

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
        return mChunks.size();
    }

    public PayloadChunk getItem(int pos) {
        if((pos < 0) || (pos > mChunks.size()))
            return null;

        return mChunks.get(pos);
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
                    mChunks.add(chunk);
                    notifyItemInserted(mChunks.size() - 1);
                }
            }
        }

        mHandledChunks = tot_chunks;
    }

    @Override
    public void onChunkReassembled(PayloadChunk chunk) {
        mChunks.add(chunk);
        notifyItemInserted(mChunks.size() - 1);
    }
}
