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
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.CheckResult;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/* An adapter to show PayloadChunk items.
 * Each item is wrapped into an AdapterChunk. An item can either be collapsed or expanded.
 * Since the text of a chunk can be very long (hundreds of KB) and rendering it would freeze the UI,
 * it is split into pages of VISUAL_PAGE_SIZE.
 * When loading a PCAP file, the RAW chunks are stored on disk (see PayloadIndex), so their text is
 * loaded asynchronously and only kept while visible or expanded. */
public class PayloadAdapter extends RecyclerView.Adapter<PayloadAdapter.PayloadViewHolder> implements HTTPReassembly.ReassemblyListener {
    private static final String TAG = "PayloadAdapter";
    public static final int COLLAPSE_CHUNK_SIZE = 1500;
    public static final int VISUAL_PAGE_SIZE = 4020; // must be a multiple of 67 to avoid splitting the hexdump
    private final LayoutInflater mLayoutInflater;
    private final ConnectionDescriptor mConn;
    private final Context mContext;
    private final ChunkType mMode;
    private int mHandledChunks;
    private final ArrayList<AdapterChunk> mUnrepliedHttpReqs = new ArrayList<>();
    private final ArrayList<AdapterChunk> mChunks = new ArrayList<>();
    private final HTTPReassembly mHttpReq;
    private final HTTPReassembly mHttpRes;
    private final boolean mSupportsFileDialog;
    private final PayloadChunk mSingleChunk;
    private boolean mShowAsPrintable;
    private ExportPayloadHandler mExportHandler;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ExecutorService mExecutor;
    private volatile boolean mDestroyed;
    private int mTotalPages;
    private RecyclerView mRecyclerView;

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

            if (mSingleChunk.payload.length > 0)
                appendChunk(new AdapterChunk(mSingleChunk, 0));
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

        // return an empty chunk instead of null to activate the single-chunk mode
        return new PayloadChunk(new byte[0], ChunkType.HTTP, true, 0, 0);
    }

    public void setExportPayloadHandler(ExportPayloadHandler handler) {
        mExportHandler = handler;
    }

    static final int MAX_JSON_FORMAT_SIZE = 1024 * 1024;

    private static final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();

    static String formatHttpPayload(String text, String contentType) {
        if ((contentType == null) || !contentType.equals("application/json"))
            return text;
        if (text.length() > MAX_JSON_FORMAT_SIZE)
            return text;

        int sep = text.indexOf("\r\n\r\n");
        if ((sep < 0) || (sep + 4 >= text.length()))
            return text;

        String headers = text.substring(0, sep + 4);
        String body = text.substring(sep + 4);

        try {
            String pretty = prettyGson.toJson(JsonParser.parseString(body));
            return headers + pretty;
        } catch (JsonSyntaxException e) {
            return text;
        }
    }

    private class AdapterChunk {
        private final PayloadChunk mChunk; // null if the chunk is on disk
        private final int mChunkPos;       // position in the connection, for the chunks on disk
        private final boolean mIsSent;
        private final long mTimestamp;
        private final int mLength;
        private String mTheText;
        private boolean mIsExpanded;
        private int mNumPages = 1;
        private int mFirstPage;            // adapter position of the first page
        private boolean mLoading;
        private int mGeneration;           // incremented to discard the text being loaded
        private volatile int mNumBound;    // number of view holders showing this chunk
        public final int incrId;

        AdapterChunk(PayloadChunk _chunk, int incr_id) {
            mChunk = _chunk;
            mChunkPos = -1;
            mIsSent = _chunk.is_sent;
            mTimestamp = _chunk.timestamp;
            mLength = _chunk.payload.length;
            incrId = incr_id;
        }

        AdapterChunk(int chunk_pos, boolean is_sent, long timestamp, int length, int incr_id) {
            mChunk = null;
            mChunkPos = chunk_pos;
            mIsSent = is_sent;
            mTimestamp = timestamp;
            mLength = length;
            incrId = incr_id;
        }

        boolean isOnDisk() {
            return (mChunk == null);
        }

        boolean canBeExpanded() {
            return mLength > COLLAPSE_CHUNK_SIZE;
        }

        boolean isExpanded() {
            return mIsExpanded;
        }

        boolean isBound() {
            return mNumBound > 0;
        }

        int getNumPages() {
            return mNumPages;
        }

        @CheckResult
        private String makeText(boolean as_printable, boolean expanded) {
            return makePayloadText(mChunk.payload, as_printable, expanded);
        }

        @CheckResult
        private String makeText() {
            String text = makeText(mShowAsPrintable, mIsExpanded);
            if (mShowAsPrintable && (mMode == ChunkType.HTTP))
                text = formatHttpPayload(text, mChunk.httpContentType);
            return text;
        }

        void expand() {
            setExpandedText(makeText(mShowAsPrintable, true));
        }

        void setExpandedText(String text) {
            mIsExpanded = true;
            mTheText = text;

            if (mShowAsPrintable && (mMode == ChunkType.HTTP))
                mTheText = formatHttpPayload(mTheText, mChunk.httpContentType);

            // round up div
            mNumPages = (mTheText.length() + VISUAL_PAGE_SIZE - 1) / VISUAL_PAGE_SIZE;
        }

        // collapses the item and returns the old number of pages
        void collapse() {
            mIsExpanded = false;
            mTheText = null;
            mGeneration++;

            mNumPages = 1;
        }

        // returns null if the text must be loaded from disk
        @Nullable String getPageText(int pageIdx) {
            if(mTheText == null) {
                if(isOnDisk())
                    return null;

                mTheText = makeText();
            }

            int len = mTheText.length();
            int start = 0;
            int end = len;

            if(mIsExpanded) {
                start = pageIdx * VISUAL_PAGE_SIZE;
                end = Math.min((pageIdx + 1) * VISUAL_PAGE_SIZE, len);
            }

            if((start == 0) && (end == len))
                return mTheText;

            // the page break already acts as a line break, avoid an empty line (e.g. in the hexdump)
            if((end < len) && (mTheText.charAt(end - 1) == '\n'))
                end--;

            return mTheText.substring(start, end);
        }

        String getExpandedText(boolean as_printable) {
            return makeText(as_printable, true);
        }

        Page getPage(int pageIdx) {
            assert(pageIdx < mNumPages);
            return new Page(this, pageIdx, !mIsExpanded || (pageIdx == (mNumPages - 1)));
        }
    }

    @CheckResult
    private static String makePayloadText(byte[] payload, boolean as_printable, boolean expanded) {
        int dump_len = expanded ? payload.length : Math.min(payload.length, COLLAPSE_CHUNK_SIZE);

        if(!as_printable)
            return Utils.hexdump(payload, 0, dump_len);
        else
            return new String(payload, 0, dump_len, StandardCharsets.UTF_8);
    }

    private static class Page {
        AdapterChunk adaptChunk;
        int pageIdx;
        boolean isLast;

        Page(AdapterChunk _adaptChunk, int _pageIdx, boolean _isLast) {
            adaptChunk = _adaptChunk;
            pageIdx = _pageIdx;
            isLast = _isLast;
        }

        boolean isFirst() {
            return (pageIdx == 0);
        }

        @Nullable String getText() {
            return adaptChunk.getPageText(pageIdx);
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
        AdapterChunk boundChunk;

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
            if(page == null)
                return;

            AdapterChunk aChunk = page.adaptChunk;

            if(aChunk.isExpanded()) {
                int numPages = aChunk.getNumPages();
                int firstPagePos = aChunk.mFirstPage;
                aChunk.collapse();
                updatePages(aChunk);
                notifyItemChanged(firstPagePos);
                notifyItemRangeRemoved(firstPagePos + 1, numPages - 1);
            } else if(aChunk.isOnDisk()) {
                // will be expanded when loaded
                loadChunkText(aChunk, true);
                notifyItemChanged(pos);
            } else {
                aChunk.expand();
                onChunkExpanded(aChunk);
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

        Page page = getItem(payload_pos);
        if (page == null)
            return;

        AdapterChunk chunk = page.adaptChunk;

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
        } else if(chunk.isOnDisk()) {
            runInBackground(() -> {
                if(mDestroyed)
                    return;

                PayloadChunk read_chunk = mConn.readPayloadChunk(chunk.mChunkPos);

                mHandler.post(() -> {
                    if(mDestroyed)
                        return;

                    if(read_chunk != null)
                        showRawCopyExportDialog(read_chunk.payload, is_export, title, positive_action);
                    else
                        Utils.showToast(mContext, R.string.error);
                });
            }, null);
        } else
            showRawCopyExportDialog(chunk.mChunk.payload, is_export, title, positive_action);
    }

    private void showRawCopyExportDialog(byte[] payload, boolean is_export, int title, int positive_action) {
        List<String> choices = new ArrayList<>(Arrays.asList(
                mContext.getString(R.string.text),
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
                    mExportHandler.exportPayload(payload, "application/octet-stream", "");
            } else {
                String text = makePayloadText(payload, choice == 0, true);

                if (is_export) {
                    if (mExportHandler != null)
                        mExportHandler.exportPayload(text);
                } else
                    Utils.copyToClipboard(mContext, text);
            }
        });
        builder.create().show();
    }

    private String getHeaderTag(boolean is_sent) {
        if(mMode == ChunkType.HTTP)
            return is_sent ? mContext.getString(R.string.request) : mContext.getString(R.string.response);
        else
            return is_sent ? mContext.getString(R.string.tx_direction) : mContext.getString(R.string.rx_direction);
    }

    @Override
    public void onBindViewHolder(@NonNull PayloadViewHolder holder, int position) {
        Page page = getItem(position);
        AdapterChunk aChunk = page.adaptChunk;

        if(holder.boundChunk != aChunk) {
            if(holder.boundChunk != null)
                holder.boundChunk.mNumBound--;

            aChunk.mNumBound++;
            holder.boundChunk = aChunk;
        }

        if(page.isFirst()) {
            holder.headerLine.setVisibility(View.VISIBLE);

            Locale locale = Utils.getPrimaryLocale(mContext);
            String formattedTstamp = (new SimpleDateFormat("HH:mm:ss.SSS", locale)).format(new Date(aChunk.mTimestamp));

            String formattedBytes;
            if (mMode == ChunkType.HTTP)
                formattedBytes = Utils.formatBytes(aChunk.mChunk.httpBodyLength);
            else
                formattedBytes = Utils.formatBytes(aChunk.mLength);

            if (mSingleChunk == null)
                holder.header.setText(String.format(locale,
                        "#%d [%s] %s — %s", aChunk.incrId + 1,
                        getHeaderTag(aChunk.mIsSent),
                        formattedTstamp, formattedBytes));
            else
                holder.header.setText(String.format(locale,
                        "%s — %s",
                        formattedTstamp, formattedBytes));
        } else
            holder.headerLine.setVisibility(View.GONE);

        String text = page.getText();
        if(text == null) {
            holder.dump.setText(R.string.loading);
            loadChunkText(aChunk, false);
        } else
            holder.dump.setText(text);

        if(page.isLast && aChunk.canBeExpanded()) {
            holder.expandButton.setVisibility(View.VISIBLE);
            holder.expandButton.setRotation(aChunk.isExpanded() ? 180 : 0);
            holder.expandButton.setEnabled(!aChunk.mLoading);
        } else
            holder.expandButton.setVisibility(View.GONE);

        if(aChunk.mIsSent) {
            holder.dumpBox.setBackgroundResource(R.color.sentPayloadBg);
            holder.dump.setTextColor(ContextCompat.getColor(mContext, R.color.sentPayloadFg));
        } else {
            holder.dumpBox.setBackgroundResource(R.color.rcvdPayloadBg);
            holder.dump.setTextColor(ContextCompat.getColor(mContext, R.color.rcvdPayloadFg));
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        mRecyclerView = recyclerView;
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        mRecyclerView = null;
    }

    @Override
    public void onViewRecycled(@NonNull PayloadViewHolder holder) {
        unbindChunk(holder);
    }

    @Override
    public boolean onFailedToRecycleView(@NonNull PayloadViewHolder holder) {
        unbindChunk(holder);
        return false;
    }

    private void unbindChunk(PayloadViewHolder holder) {
        AdapterChunk aChunk = holder.boundChunk;
        if(aChunk == null)
            return;

        aChunk.mNumBound--;
        holder.boundChunk = null;

        // the text of the chunks on disk is only kept while visible, to bound the memory usage
        if(!aChunk.isBound() && aChunk.isOnDisk() && !aChunk.isExpanded())
            aChunk.mTheText = null;
    }

    // Loads the text of a chunk on disk. If expand is true, the chunk is expanded when loaded
    private void loadChunkText(AdapterChunk aChunk, boolean expand) {
        if(aChunk.mLoading || mDestroyed)
            return;

        aChunk.mLoading = true;
        int generation = aChunk.mGeneration;
        boolean as_printable = mShowAsPrintable;

        runInBackground(() -> {
            String text = null;
            boolean read_failed = false;

            // the chunk may have been scrolled away in the meantime
            if(!mDestroyed && (expand || aChunk.isBound())) {
                PayloadChunk chunk = mConn.readPayloadChunk(aChunk.mChunkPos,
                        expand ? Integer.MAX_VALUE : COLLAPSE_CHUNK_SIZE);

                if(chunk != null)
                    text = makePayloadText(chunk.payload, as_printable, expand);
                else if(expand)
                    read_failed = true;
                else
                    text = mContext.getString(R.string.error);
            }

            String loadedText = text;
            boolean expandFailed = read_failed;

            mHandler.post(() -> {
                // keep the chunk collapsed, rather than expanding the error text
                if(expandFailed && !mDestroyed)
                    Utils.showToast(mContext, R.string.error);

                onChunkTextLoaded(aChunk, generation, expand, loadedText);
            });
        }, () -> aChunk.mLoading = false);
    }

    private void onChunkTextLoaded(AdapterChunk aChunk, int generation, boolean expand, @Nullable String text) {
        aChunk.mLoading = false;

        if(mDestroyed)
            return;

        if(generation != aChunk.mGeneration) {
            // the text is outdated (e.g. display mode changed), reload it if still visible
            if(aChunk.isBound())
                rebindChunk(aChunk);
            return;
        }

        if(text == null) {
            // not loaded, re-enable the expand button or load the text if bound again in the meantime
            if(aChunk.isBound())
                rebindChunk(aChunk);
            return;
        }

        if(expand) {
            aChunk.setExpandedText(text);
            onChunkExpanded(aChunk);
        } else if(aChunk.isBound()) {
            aChunk.mTheText = text;
            rebindChunk(aChunk);
        }
    }

    // Rebind the visible holder directly to prevent a weird item animation on notifyItemChanged
    private void rebindChunk(AdapterChunk aChunk) {
        int pos = aChunk.mFirstPage;
        RecyclerView.ViewHolder holder = (mRecyclerView != null) ? mRecyclerView.findViewHolderForAdapterPosition(pos) : null;

        if(holder instanceof PayloadViewHolder)
            onBindViewHolder((PayloadViewHolder) holder, pos);
        else
            notifyItemChanged(pos);
    }

    private void runInBackground(Runnable task, @Nullable Runnable onRejected) {
        if(mExecutor == null)
            mExecutor = Executors.newSingleThreadExecutor();

        try {
            mExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Task rejected: " + e);

            if(onRejected != null)
                onRejected.run();
        }
    }

    // Stops the background loading, to be called when the adapter is no longer used
    public void destroy() {
        mDestroyed = true;
        mHandler.removeCallbacksAndMessages(null);

        // shutdownNow would interrupt a running read, which closes the FileChannel shared by all the connections
        if(mExecutor != null)
            mExecutor.shutdown();
    }

    private void onChunkExpanded(AdapterChunk aChunk) {
        int pos = aChunk.mFirstPage;

        updatePages(aChunk);
        notifyItemChanged(pos);
        notifyItemRangeInserted(pos + 1, aChunk.getNumPages() - 1);
    }

    @Override
    public int getItemCount() {
        return mTotalPages;
    }

    public @Nullable Page getItem(int pos) {
        if((pos < 0) || (pos >= mTotalPages))
            return null;

        int idx = findChunkIndex(pos);
        AdapterChunk aChunk = mChunks.get(idx);

        return aChunk.getPage(pos - aChunk.mFirstPage);
    }

    // Returns the index in mChunks of the chunk containing the given page pos
    private int findChunkIndex(int pos) {
        int low = 0;
        int high = mChunks.size() - 1;

        while(low < high) {
            int mid = (low + high + 1) / 2;

            if(mChunks.get(mid).mFirstPage <= pos)
                low = mid;
            else
                high = mid - 1;
        }

        return low;
    }

    private void appendChunk(AdapterChunk aChunk) {
        aChunk.mFirstPage = mTotalPages;
        mChunks.add(aChunk);
        mTotalPages += aChunk.getNumPages();

        notifyItemInserted(aChunk.mFirstPage);
    }

    // Updates the first page of the chunks after the given one, after its pages changed
    private void updatePages(AdapterChunk aChunk) {
        recomputePages(findChunkIndex(aChunk.mFirstPage));
    }

    private void recomputePages(int fromIdx) {
        int page = 0;

        if(fromIdx > 0) {
            AdapterChunk prev = mChunks.get(fromIdx - 1);
            page = prev.mFirstPage + prev.getNumPages();
        }

        for(int i = fromIdx; i < mChunks.size(); i++) {
            AdapterChunk aChunk = mChunks.get(i);

            aChunk.mFirstPage = page;
            page += aChunk.getNumPages();
        }

        mTotalPages = page;
    }

    @SuppressLint("NotifyDataSetChanged")
    public void setDisplayAsPrintableText(boolean asText) {
        if(mShowAsPrintable != asText) {
            mShowAsPrintable = asText;

            // Chunk pagination depends on the displayed data length, collapsing everything is simpler
            // than handling individual changes
            for(AdapterChunk chunk: mChunks)
                chunk.collapse(); // resets the chunk text
            recomputePages(0);
            notifyDataSetChanged();
        }
    }

    public void handleChunksAdded(int tot_chunks) {
        boolean readingFromPcap = CaptureService.isReadingFromPcapFile();

        for(int i = mHandledChunks; i < tot_chunks; i++) {
            ChunkType type = mConn.getChunkType(i);

            // when reading from pcap, websocket data must be extracted from HTTP chunks
            boolean websocketFromHttp = readingFromPcap &&
                    (mMode == ChunkType.WEBSOCKET) &&
                    (type != ChunkType.RAW);

            // Exclude unrelated chunks
            if((mMode != ChunkType.RAW) && (mMode != type) && !websocketFromHttp)
                continue;

            if(mConn.isChunkOnDisk(i)) {
                appendChunk(new AdapterChunk(i, mConn.isChunkSent(i), mConn.getChunkTimestamp(i),
                        mConn.getChunkLength(i), mChunks.size()));
                continue;
            }

            PayloadChunk chunk = mConn.getPayloadChunk(i);
            if(chunk == null)
                continue;

            if((mMode == ChunkType.HTTP) || websocketFromHttp) {
                // will call onChunkReassembled
                if(chunk.is_sent)
                    mHttpReq.handleChunk(chunk);
                else
                    mHttpRes.handleChunk(chunk);
            } else
                appendChunk(new AdapterChunk(chunk, mChunks.size()));
        }

        mHandledChunks = tot_chunks;
    }

    private AdapterChunk findMatchingRequest(PayloadChunk chunk) {
        if (mUnrepliedHttpReqs.isEmpty())
            return null;

        if (chunk.stream_id == 0) {
            // HTTP/1: FIFO matching
            return mUnrepliedHttpReqs.get(0);
        } else {
            // HTTP/2: match by stream ID
            for (AdapterChunk req : mUnrepliedHttpReqs) {
                if (req.mChunk.stream_id == chunk.stream_id)
                    return req;
            }
        }

        return null;
    }

    @SuppressLint("DefaultLocale")
    @Override
    public void onChunkReassembled(PayloadChunk chunk) {
        if((mMode != ChunkType.RAW) && (mMode != chunk.type))
            // unrelated chunk (mainly for HTTP data before Websocket)
            return;

        AdapterChunk adapterChunk = new AdapterChunk(chunk, mChunks.size());
        int insertPos = mChunks.size();
        boolean is_http2_rst = chunk.isHttp2Rst();

        // Need to determine where to add the chunk. If HTTP request, always add it to the bottom.
        // If HTTP reply/reset, it should be added right after the matching un-replied HTTP request
        if(!chunk.is_sent || is_http2_rst) {
            AdapterChunk matchedReq = findMatchingRequest(chunk);

            if (matchedReq != null) {
                int reqPos = mChunks.indexOf(matchedReq);
                assert(reqPos >= 0);

                if (!is_http2_rst) {
                    insertPos = reqPos + 1;
                    Log.d(TAG, String.format("chunk #%d reply of #%d at %d", adapterChunk.incrId, matchedReq.incrId, insertPos));
                } else
                    Log.d(TAG, String.format("chunk #%d reset of #%d", adapterChunk.incrId, matchedReq.incrId));

                mUnrepliedHttpReqs.remove(matchedReq);
            }
        } else if(!is_http2_rst)
            mUnrepliedHttpReqs.add(adapterChunk);

        if (!is_http2_rst) {
            if (insertPos == mChunks.size())
                appendChunk(adapterChunk);
            else {
                mChunks.add(insertPos, adapterChunk);
                recomputePages(insertPos);
                notifyItemInserted(adapterChunk.mFirstPage);
            }
        }
    }
}
