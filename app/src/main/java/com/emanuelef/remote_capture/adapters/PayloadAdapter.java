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

package com.emanuelef.remote_capture.adapters;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.CheckResult;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.HTTPReassembly;
import com.emanuelef.remote_capture.HttpLog;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.PayloadChunk;
import com.emanuelef.remote_capture.model.PayloadChunk.ChunkType;
import com.emanuelef.remote_capture.model.Prefs;
import com.google.android.material.button.MaterialButton;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/* An adapter to show PayloadChunk items.
 * Each item is wrapped into an AdapterChunk. An item can either be collapsed or expanded.
 * Since the text of a chunk can be very long (hundreds of KB) and rendering it would freeze the UI,
 * it is split into pages of VISUAL_PAGE_SIZE. */
public class PayloadAdapter extends RecyclerView.Adapter<PayloadAdapter.PayloadViewHolder> implements HTTPReassembly.ReassemblyListener {
    private static final String TAG = "PayloadAdapter";
    public static final int COLLAPSE_CHUNK_SIZE = 1500;
    public static final int VISUAL_PAGE_SIZE = 4020; // must be a multiple of 67 to avoid splitting the hexdump
    private final LayoutInflater mLayoutInflater;
    private final ConnectionDescriptor mConn;
    private final Context mContext;
    private final ChunkType mMode;
    private int mHandledChunks;
    private AdapterChunk mUnrepliedHttpReq = null;
    private final ArrayList<AdapterChunk> mChunks = new ArrayList<>();
    private final HTTPReassembly mHttpReq;
    private final HTTPReassembly mHttpRes;
    private final boolean mSupportsFileDialog;
    private final PayloadChunk mSingleChunk;
    private boolean mShowAsPrintable;
    private ExportPayloadHandler mExportHandler;

    public interface ExportPayloadHandler {
        void exportPayload(String payload);
        void exportPayload(byte[] payload, String contentType, String fname);
    }

    /* if singleChunk is set, this adapter will only show that chunk */
    private PayloadAdapter(Context context, ConnectionDescriptor conn, ChunkType mode,
                          boolean showAsPrintable, PayloadChunk singleChunk) {
        mLayoutInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mConn = conn;
        mContext = context;
        mMode = mode;
        mShowAsPrintable = showAsPrintable;
        mSupportsFileDialog = Utils.supportsFileDialog(context);
        mSingleChunk = singleChunk;

        if (mSingleChunk == null) {
            // Note: in minimal mode, only the first chunk is captured, so don't reassemble them
            boolean reassemble = (CaptureService.getCurPayloadMode() == Prefs.PayloadMode.FULL);

            // each direction must have its separate reassembly
            mHttpReq = new HTTPReassembly(reassemble, this);
            mHttpRes = new HTTPReassembly(reassemble, this);

            handleChunksAdded(mConn.getNumPayloadChunks());
        } else {
            mHttpReq = null;
            mHttpRes = null;

            mChunks.add(new AdapterChunk(mSingleChunk, 0));
            notifyItemInserted(0);
        }
    }

    public PayloadAdapter(Context context, ConnectionDescriptor conn, ChunkType mode,  boolean showAsPrintable) {
        this(context, conn, mode, showAsPrintable, null);
    }

    public PayloadAdapter(Context context, HttpLog.HttpRequest req, boolean show_reply) {
        this(context, req.conn, ChunkType.HTTP, true, getChunk(req, show_reply));
    }

    private static PayloadChunk getChunk(HttpLog.HttpRequest req, boolean show_reply) {
        if (show_reply) {
            if (req.reply != null)
                return req.conn.getHttpResponseChunk(req.reply.firstChunkPos);
        } else
            return req.conn.getHttpRequestChunk(req.firstChunkPos);

        return null;
    }

    public void setExportPayloadHandler(ExportPayloadHandler handler) {
        mExportHandler = handler;
    }

    private class AdapterChunk {
        private final PayloadChunk mChunk;
        private String mTheText;
        private boolean mIsExpanded;
        private int mNumPages = 1;
        public final int incrId;

        AdapterChunk(PayloadChunk _chunk, int incr_id) {
            mChunk = _chunk;
            incrId = incr_id;
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

        @CheckResult
        private String makeText(boolean as_printable, boolean expanded) {
            int dump_len = expanded ? mChunk.payload.length : Math.min(mChunk.payload.length, COLLAPSE_CHUNK_SIZE);

            if(!as_printable)
                return Utils.hexdump(mChunk.payload, 0, dump_len);
            else
                return new String(mChunk.payload, 0, dump_len, StandardCharsets.UTF_8);
        }

        @CheckResult
        private String makeText() {
            return makeText(mShowAsPrintable, mIsExpanded);
        }

        void expand() {
            mIsExpanded = true;
            mTheText = makeText();

            // round up div
            mNumPages = (mTheText.length() + VISUAL_PAGE_SIZE - 1) / VISUAL_PAGE_SIZE;
        }

        // collapses the item and returns the old number of pages
        void collapse() {
            mIsExpanded = false;
            mTheText = null;

            mNumPages = 1;
        }

        String getText(int start, int end) {
            if(mTheText == null)
                mTheText = makeText();

            if((start == 0) && (end >= mTheText.length() - 1)) {
                return mTheText;
            }

            return mTheText.substring(start, end);
        }

        String getExpandedText(boolean as_printable) {
            return makeText(as_printable, true);
        }

        Page getPage(int pageIdx) {
            assert(pageIdx < mNumPages);

            if(mTheText == null)
                mTheText = makeText();

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
        View headerLine;
        View dumpBox;
        TextView header;
        TextView dump;
        MaterialButton expandButton;
        MaterialButton copybutton;
        MaterialButton exportbutton;

        public PayloadViewHolder(View view) {
            super(view);

            headerLine = view.findViewById(R.id.header_line);
            header = view.findViewById(R.id.header);
            dump = view.findViewById(R.id.dump);
            dumpBox = view.findViewById(R.id.dump_box);
            expandButton = view.findViewById(R.id.expand_button);
            copybutton = view.findViewById(R.id.copy_button);
            exportbutton = view.findViewById(R.id.export_button);
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
                int firstPagePos = pos - (numPages - 1);
                page.adaptChunk.collapse();
                notifyItemChanged(firstPagePos);
                notifyItemRangeRemoved(firstPagePos + 1, numPages - 1);
            } else {
                page.adaptChunk.expand();
                notifyItemChanged(pos);
                notifyItemRangeInserted(pos + 1, page.adaptChunk.getNumPages() - 1);
            }
        });

        holder.copybutton.setOnClickListener(v -> handleCopyExportButtons(holder, false));
        holder.exportbutton.setOnClickListener(v -> handleCopyExportButtons(holder, true));
        holder.exportbutton.setVisibility(mSupportsFileDialog ? View.VISIBLE : View.GONE);

        return holder;
    }

    private void handleCopyExportButtons(PayloadViewHolder holder, boolean is_export) {
        if(is_export && (mExportHandler == null))
            return;

        int payload_pos = holder.getAbsoluteAdapterPosition();
        int title = is_export ? R.string.export_ellipsis : R.string.copy_action;
        int positive_action = is_export ? R.string.export_action : R.string.copy_to_clipboard;

        AdapterChunk chunk = getItem(payload_pos).adaptChunk;
        if (chunk == null)
            return;

        if(mMode == ChunkType.HTTP) {
            String payload = chunk.getExpandedText(true);
            int crlf_pos = payload.indexOf("\r\n\r\n");
            String content_type = ((chunk.mChunk.httpContentType != null) && (!chunk.mChunk.httpContentType.isEmpty())) ?
                    chunk.mChunk.httpContentType : "text/plain";

            Log.d(TAG, "Export body content type: " + content_type);

            String fname = "";
            if (chunk.mChunk.is_sent && (chunk.mChunk.httpPath != null))
                fname = chunk.mChunk.httpPath;
            else if (payload_pos > 0) {
                // Try to match the HTTP request, to determine the file name
                AdapterChunk req_chunk = getItem(payload_pos - 1).adaptChunk;
                if (req_chunk.mChunk.is_sent && (req_chunk.mChunk.httpPath != null))
                    fname = req_chunk.mChunk.httpPath;
            }

            if (!fname.isEmpty()) {
                int last_slash = fname.lastIndexOf('/');
                if (last_slash >= 0)
                    fname = fname.substring(last_slash + 1);
            }

            if (fname.contains("."))
                Log.d(TAG, "File name: " + fname);
            else
                fname = "";

            String filename = fname;

            boolean has_body = (crlf_pos > 0) && (crlf_pos < (payload.length() - 4));
            if (!has_body) {
                // only HTTP headers
                if (is_export) {
                    if (mExportHandler != null)
                        mExportHandler.exportPayload(payload);
                } else
                    Utils.copyToClipboard(mContext, payload);
                return;
            }

            String[] choices = {
                    mContext.getString(R.string.headers),
                    mContext.getString(R.string.body),
                    mContext.getString(R.string.both),
            };

            AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
            builder.setTitle(title);
            builder.setSingleChoiceItems(choices, 1, (dialogInterface, i) -> {});
            builder.setNeutralButton(R.string.cancel_action, (dialogInterface, i) -> {});
            builder.setPositiveButton(positive_action, (dialogInterface, i) -> {
                int choice = ((AlertDialog)dialogInterface).getListView().getCheckedItemPosition();
                String to_copy = payload;

                if (choice != 2) {
                    if (choice == 0 /* Headers */)
                        to_copy = to_copy.substring(0, crlf_pos);
                    else /* body */
                        to_copy = to_copy.substring(crlf_pos + 4);
                }

                if (is_export) {
                    if (mExportHandler != null) {
                        boolean only_body = (choice == 1);

                        if (only_body) {
                            // export the raw body bytes
                            byte[] payload_bytes = chunk.mChunk.payload;

                            if (crlf_pos < (payload_bytes.length - 4))
                                payload_bytes = Arrays.copyOfRange(payload_bytes, crlf_pos + 4, payload_bytes.length);

                            mExportHandler.exportPayload(payload_bytes, content_type, filename);
                        } else
                            mExportHandler.exportPayload(to_copy);
                    }
                } else
                    Utils.copyToClipboard(mContext, to_copy);
            });
            builder.create().show();
        } else {
            List<String> choices = new ArrayList<>(Arrays.asList(
                    mContext.getString(R.string.printable_text),
                    mContext.getString(R.string.hexdump)
            ));
            if (is_export)
                choices.add(mContext.getString(R.string.raw_bytes));

            AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
            builder.setTitle(title);
            builder.setSingleChoiceItems(choices.toArray(new String[]{}), mShowAsPrintable ? 0 : 1, (dialogInterface, i) -> {});

            builder.setNeutralButton(R.string.cancel_action, (dialogInterface, i) -> {});
            builder.setPositiveButton(positive_action, (dialogInterface, i) -> {
                int choice = ((AlertDialog)dialogInterface).getListView().getCheckedItemPosition();

                if (choice == 2 /* raw bytes */) {
                    assert (is_export);

                    if (mExportHandler != null)
                        mExportHandler.exportPayload(chunk.mChunk.payload, "application/octet-stream", "");
                } else {
                    String payload = getItem(payload_pos).adaptChunk.getExpandedText(choice == 0);

                    if (is_export) {
                        if (mExportHandler != null)
                            mExportHandler.exportPayload(payload);
                    } else
                        Utils.copyToClipboard(mContext, payload);
                }
            });
            builder.create().show();
        }
    }

    private String getHeaderTag(PayloadChunk chunk) {
        if(mMode == ChunkType.HTTP)
            return (chunk.is_sent) ? mContext.getString(R.string.request) : mContext.getString(R.string.response);
        else
            return chunk.is_sent ? mContext.getString(R.string.tx_direction) : mContext.getString(R.string.rx_direction);
    }

    @Override
    public void onBindViewHolder(@NonNull PayloadViewHolder holder, int position) {
        Page page = getItem(position);
        PayloadChunk chunk = page.adaptChunk.getPayloadChunk();

        if(page.isFirst()) {
            holder.headerLine.setVisibility(View.VISIBLE);

            Locale locale = Utils.getPrimaryLocale(mContext);
            String formattedTstamp = (new SimpleDateFormat("HH:mm:ss.SSS", locale)).format(new Date(chunk.timestamp));
            String formattedBytes = Utils.formatBytes(chunk.payload.length);

            if (mSingleChunk == null)
                holder.header.setText(String.format(locale,
                        "#%d [%s] %s — %s", page.adaptChunk.incrId + 1,
                        getHeaderTag(chunk),
                        formattedTstamp, formattedBytes));
            else
                holder.header.setText(String.format(locale,
                        "%s — %s",
                        formattedTstamp, formattedBytes));
        } else
            holder.headerLine.setVisibility(View.GONE);

        if(page.isLast && page.adaptChunk.canBeExpanded()) {
            holder.expandButton.setVisibility(View.VISIBLE);
            holder.expandButton.setRotation(page.adaptChunk.isExpanded() ? 180 : 0);
        } else
            holder.expandButton.setVisibility(View.GONE);

        holder.dump.setText(page.getText());

        if(chunk.is_sent) {
            holder.dumpBox.setBackgroundResource(R.color.sentPayloadBg);
            holder.dump.setTextColor(ContextCompat.getColor(mContext, R.color.sentPayloadFg));
        } else {
            holder.dumpBox.setBackgroundResource(R.color.rcvdPayloadBg);
            holder.dump.setTextColor(ContextCompat.getColor(mContext, R.color.rcvdPayloadFg));
        }
    }

    @Override
    public int getItemCount() {
        // TODO remove loop, as it can generate ANRs on high number of elements
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

    @SuppressLint("NotifyDataSetChanged")
    public void setDisplayAsPrintableText(boolean asText) {
        if(mShowAsPrintable != asText) {
            mShowAsPrintable = asText;

            // Chunk pagination depends on the displayed data length, collapsing everything is simpler
            // than handling individual changes
            for(AdapterChunk chunk: mChunks)
                chunk.collapse(); // resets the chunk text
            notifyDataSetChanged();
        }
    }

    private int getAdapterPosition(AdapterChunk chunk) {
        int i;
        int count = 0;

        for(i=0; i < mChunks.size(); i++) {
            AdapterChunk aChunk = mChunks.get(i);
            if(aChunk == chunk)
                break;

            count += aChunk.getNumPages();
        }

        return count;
    }

    public void handleChunksAdded(int tot_chunks) {
        int items_count = -1;

        for(int i = mHandledChunks; i<tot_chunks; i++) {
            PayloadChunk chunk = mConn.getPayloadChunk(i);
            if(chunk == null)
                continue;

            // Exclude unrelated chunks
            if((mMode != ChunkType.RAW) && (mMode != chunk.type))
                continue;

            if(mMode == ChunkType.HTTP) {
                // will call onChunkReassembled
                if(chunk.is_sent)
                    mHttpReq.handleChunk(chunk);
                else
                    mHttpRes.handleChunk(chunk);
            } else {
                // TODO remove temporary caching due to slow getItemCount
                if(items_count == -1)
                    items_count = getItemCount();
                mChunks.add(new AdapterChunk(chunk, mChunks.size()));
                notifyItemInserted(items_count);
                items_count += 1;
            }
        }

        mHandledChunks = tot_chunks;
    }

    private void setNextUnrepliedRequest(int prevChunkIdx) {
        // Possibly find next un-replied HTTP request
        for(int i=prevChunkIdx + 1; i<mChunks.size(); i++) {
            AdapterChunk cur = mChunks.get(i);

            if(cur.mChunk.is_sent) {
                mUnrepliedHttpReq = cur;
                return;
            }
        }

        // no unreplied found
        mUnrepliedHttpReq = null;
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void onChunkReassembled(PayloadChunk chunk) {
        AdapterChunk adapterChunk = new AdapterChunk(chunk, mChunks.size());
        int adapterPos = getItemCount();
        int insertPos = mChunks.size();

        // Need to determine where to add the chunk. If HTTP request, always add it to the bottom.
        // If HTTP reply, it should be added right after the first un-replied HTTP request
        if(!chunk.is_sent && (mUnrepliedHttpReq != null)) {
            // HTTP reply to a matching request
            int reqPos = mChunks.indexOf(mUnrepliedHttpReq);
            assert(reqPos >= 0);

            insertPos = reqPos + 1;
            adapterPos = getAdapterPosition(mUnrepliedHttpReq) + mUnrepliedHttpReq.getNumPages();
            Log.d(TAG, String.format("chunk #%d reply of #%d at %d", adapterChunk.incrId, mUnrepliedHttpReq.incrId, insertPos));
            setNextUnrepliedRequest(reqPos);
        } else if((chunk.is_sent) && (mUnrepliedHttpReq == null))
            mUnrepliedHttpReq = adapterChunk;

        mChunks.add(insertPos, adapterChunk);
        notifyItemInserted(adapterPos);
    }
}
