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
 * Copyright 2020 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/* Provides an input stream to read data from bytes chunks produced
   asynchronously via produceData(). bytes[] chunks are used instead of a
   single bytes[] in order to avoid excessive data copies.
 */
class ChunkedInputStream extends InputStream {
    private static final byte[] pcapHeader = Utils.hexStringToByteArray("d4c3b2a1020004000000000000000000ffff000065000000");
    final Lock mLock = new ReentrantLock();
    final Condition newData = mLock.newCondition();
    ArrayList<byte[]> mChunks = new ArrayList<byte[]>();
    int mCurChunkIndex = 0;
    boolean hasFinished = false;

    ChunkedInputStream() {
        // Send the PCAP header as the first chunk
        mChunks.add(pcapHeader);
    }

    /* Mark the termination of stream */
    public void stop() {
        mLock.lock();

        try {
            hasFinished = true;
            newData.signal();
        } finally {
            mLock.unlock();
        }
    }

    /* Produce data to be read from the stream */
    public void produceData(byte data[]) {
        mLock.lock();
        try {
            if(hasFinished)
                return;

            mChunks.add(data);
            newData.signal();
        } finally {
            mLock.unlock();
        }
    }

    @Override
    public int read(byte[] buf, int off, int maxlen) {
        int out_size = 0;

        if(maxlen <= 0)
            return(0);

        mLock.lock();
        try {
            /* Possibly wait for new data */
            while((!hasFinished) && (mChunks.size() == 0))
                newData.await();

            if(mChunks.size() > 0) {
                /* At least one byte will be returned here. Do not call await() below,
                   just return the available bytes to provide a more responsive transfer. */

                while((mChunks.size() > 0) && (maxlen > 0)) {
                    byte[] chunk = mChunks.get(0);

                    if(off > 0) {
                        // skip bytes due to the offset
                        int toSkip = Math.min(off, chunk.length - mCurChunkIndex);
                        off -= toSkip;
                        mCurChunkIndex += toSkip;
                    }

                    if (mCurChunkIndex < chunk.length) {
                        int copy_length = Math.min(maxlen, chunk.length - mCurChunkIndex);
                        System.arraycopy(chunk, mCurChunkIndex, buf, out_size, copy_length);
                        out_size += copy_length;
                        mCurChunkIndex += copy_length;
                        maxlen -= copy_length;
                    }

                    if (mCurChunkIndex >= chunk.length) {
                        // next chunk
                        mChunks.remove(0);
                        mCurChunkIndex = 0;
                    }
                }

                return(out_size);
            }

            /* Should be reached when hasFinished is set */
            return(-1);
        } catch (InterruptedException e) {
            return(-1);
        } finally {
            mLock.unlock();
        }
    }

    @Override
    public int read() {
        byte[] buf = new byte[1];
        int rv = read(buf, 0, 1);

        if(rv == -1)
            return(-1);
        else
            return(buf[0]);
    }
}
