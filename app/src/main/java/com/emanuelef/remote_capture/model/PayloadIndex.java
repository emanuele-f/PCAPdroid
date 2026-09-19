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

package com.emanuelef.remote_capture.model;

import android.util.SparseArray;

import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.PayloadChunk.ChunkType;

import java.util.Arrays;

/* An ordered index of the payload chunks of a connection.
 *
 * When loading a PCAP file, the RAW chunks are not kept in memory, to prevent OOM;
 * only their position in the file is stored, and their data is read on demand.
 *
 * The other chunks (and all the chunks of a live capture) are kept in memory.
 * This class is not thread safe, access must be synchronized externally. */
public class PayloadIndex {
    private static final int INITIAL_CAPACITY = 4;
    private static final int PRINTABLE_CHECK_LEN = 16; // keep in sync with jni_impl.c
    private static final byte FLAG_SENT = (byte) 0x80;
    private static final int LEN_BITS = 24;
    private static final long LEN_MASK = (1L << LEN_BITS) - 1;
    private static final byte TYPE_MASK = 0x0F;
    private static final ChunkType[] CHUNK_TYPES = ChunkType.values();

    // no Java object is allocated for the chunks on disk, since there can be millions of them.
    // packs (file offset << LEN_BITS) | len; -1 for the in-memory chunks.
    // LEN_BITS is enough, as the len of the chunks on disk is bounded by the snaplen
    private long[] mOffsetLens;
    private int[] mTimestamps; // relative to mBaseTimestamp
    private byte[] mFlags;
    private final SparseArray<PayloadChunk> mInMemory = new SparseArray<>(0);
    private long mBaseTimestamp;
    private int mSize;
    private int mNumOnDisk;

    // precomputed, to avoid reading the chunks from the UI thread
    private int mFirstSentPos = -1;
    private int mFirstRcvdPos = -1;
    private boolean mHasHttp;
    private boolean mFirstChunkPrintable;

    public int size() {
        return mSize;
    }

    public void add(PayloadChunk chunk) {
        int pos = mSize;
        boolean on_disk = (chunk.payload == null) && (chunk.file_offset >= 0);

        if (pos == 0)
            mBaseTimestamp = chunk.timestamp;

        if (on_disk && (mOffsetLens == null))
            allocArrays();

        if (mOffsetLens != null) {
            if (pos == mOffsetLens.length)
                growArrays();

            setArrays(pos, chunk, on_disk);
        }

        mSize++;

        if (on_disk)
            mNumOnDisk++;
        else
            mInMemory.append(pos, chunk);

        if (chunk.type == ChunkType.HTTP)
            mHasHttp = true;

        if (chunk.is_sent && (mFirstSentPos < 0))
            mFirstSentPos = pos;
        else if (!chunk.is_sent && (mFirstRcvdPos < 0))
            mFirstRcvdPos = pos;

        if (pos == 0)
            mFirstChunkPrintable = on_disk ? chunk.file_printable : isPrintablePrefix(chunk.payload);
    }

    private void setArrays(int pos, PayloadChunk chunk, boolean on_disk) {
        mOffsetLens[pos] = on_disk ? ((chunk.file_offset << LEN_BITS) | (chunk.file_len & LEN_MASK)) : -1;
        mTimestamps[pos] = (int) Math.max(Integer.MIN_VALUE,
                Math.min(Integer.MAX_VALUE, chunk.timestamp - mBaseTimestamp));

        byte flags = (byte) chunk.type.ordinal();
        if (chunk.is_sent)
            flags |= FLAG_SENT;
        mFlags[pos] = flags;
    }

    // all the chunks added so far are in memory
    private void allocArrays() {
        int capacity = Math.max(INITIAL_CAPACITY, mSize + (mSize >> 1));

        mOffsetLens = new long[capacity];
        mTimestamps = new int[capacity];
        mFlags = new byte[capacity];

        for (int i = 0; i < mSize; i++)
            setArrays(i, mInMemory.get(i), false);
    }

    private void growArrays() {
        int capacity = mOffsetLens.length + (mOffsetLens.length >> 1);

        mOffsetLens = Arrays.copyOf(mOffsetLens, capacity);
        mTimestamps = Arrays.copyOf(mTimestamps, capacity);
        mFlags = Arrays.copyOf(mFlags, capacity);
    }

    private static boolean isPrintablePrefix(byte[] payload) {
        int max_len = Math.min(payload.length, PRINTABLE_CHECK_LEN);

        for (int i = 0; i < max_len; i++) {
            if (!Utils.isPrintable(payload[i]))
                return false;
        }

        return true;
    }

    // Drops the in-memory chunks. The chunks positions are preserved, unless all the chunks are
    // in memory, in which case the index is cleared as well
    public void dropInMemory() {
        // only the in-memory chunks can be HTTP/WS, mark them RAW to avoid showing empty HTTP data
        if (mFlags != null) {
            for (int i = 0; i < mInMemory.size(); i++) {
                int pos = mInMemory.keyAt(i);
                mFlags[pos] = (byte) ((mFlags[pos] & FLAG_SENT) | ChunkType.RAW.ordinal());
            }
        }

        mInMemory.clear();
        mHasHttp = false;

        if (mNumOnDisk == 0) {
            mSize = 0;
            mFirstSentPos = -1;
            mFirstRcvdPos = -1;
            mFirstChunkPrintable = false;
        }
    }

    public boolean isOnDisk(int pos) {
        return (mOffsetLens != null) && (mOffsetLens[pos] >= 0);
    }

    public ChunkType getType(int pos) {
        // the type of the in-memory chunks can be changed by the HTTP reassembly
        PayloadChunk chunk = mInMemory.get(pos);
        if (chunk != null)
            return chunk.type;

        return CHUNK_TYPES[mFlags[pos] & TYPE_MASK];
    }

    public boolean isSent(int pos) {
        if (mFlags == null)
            return mInMemory.get(pos).is_sent;

        return (mFlags[pos] & FLAG_SENT) != 0;
    }

    public int getLength(int pos) {
        if (isOnDisk(pos))
            return (int) (mOffsetLens[pos] & LEN_MASK);

        PayloadChunk chunk = mInMemory.get(pos);
        return (chunk != null) ? chunk.payload.length : 0;
    }

    public long getTimestamp(int pos) {
        if (mTimestamps == null)
            return mInMemory.get(pos).timestamp;

        return mBaseTimestamp + mTimestamps[pos];
    }

    public boolean hasHttp() {
        return mHasHttp;
    }

    public @Nullable ChunkType getFirstChunkType(boolean is_sent) {
        int pos = is_sent ? mFirstSentPos : mFirstRcvdPos;
        if ((pos < 0) || (pos >= mSize))
            return null;

        return getType(pos);
    }

    public boolean isFirstChunkPrintable() {
        return mFirstChunkPrintable;
    }

    // returns null for the chunks on disk
    public @Nullable PayloadChunk getInMemory(int pos) {
        return mInMemory.get(pos);
    }

    public long getFileOffset(int pos) {
        return isOnDisk(pos) ? (mOffsetLens[pos] >>> LEN_BITS) : -1;
    }
}
