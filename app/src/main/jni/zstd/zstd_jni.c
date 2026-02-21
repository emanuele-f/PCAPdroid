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

#include <jni.h>
#include <stdlib.h>

#define ZSTD_STATIC_LINKING_ONLY
#include "common/memtrack.h"
#include "zstd.h"

static const size_t MAX_DECOMPRESSED_SIZE = 64 * 1024 * 1024;

static jbyteArray decompress_streaming(JNIEnv *env, jclass io_exc,
                                       const jbyte *src_buf, jsize src_len) {
    const char *exception_msg = NULL;
    ZSTD_DStream *dstream = NULL;
    unsigned char *out_buf = NULL;
    jbyteArray result = NULL;

    dstream = ZSTD_createDStream();
    if (!dstream) {
        exception_msg = "Failed to create ZSTD_DStream";
        goto cleanup;
    }

    ZSTD_initDStream(dstream);

    size_t out_capacity = ZSTD_DStreamOutSize();
    if (out_capacity < (size_t)src_len * 4)
        out_capacity = (size_t)src_len * 4;
    if (out_capacity > MAX_DECOMPRESSED_SIZE)
        out_capacity = MAX_DECOMPRESSED_SIZE;

    size_t out_size = 0;
    out_buf = pd_malloc(out_capacity);
    if (!out_buf) {
        exception_msg = "Out of memory";
        goto cleanup;
    }

    ZSTD_inBuffer input = { src_buf, (size_t)src_len, 0 };
    size_t ret = 1;

    while ((ret > 0) && (input.pos < input.size)) {
        ZSTD_outBuffer output = { out_buf + out_size, out_capacity - out_size, 0 };
        ret = ZSTD_decompressStream(dstream, &output, &input);

        if (ZSTD_isError(ret)) {
            exception_msg = ZSTD_getErrorName(ret);
            goto cleanup;
        }

        out_size += output.pos;

        if ((out_size == out_capacity) && (ret > 0)) {
            size_t new_capacity = out_capacity * 2;
            if (new_capacity > MAX_DECOMPRESSED_SIZE)
                new_capacity = MAX_DECOMPRESSED_SIZE;
            if (new_capacity == out_capacity) {
                exception_msg = "Decompressed size too large";
                goto cleanup;
            }

            unsigned char *new_buf = pd_realloc(out_buf, new_capacity);
            if (!new_buf) {
                exception_msg = "Out of memory";
                goto cleanup;
            }
            out_buf = new_buf;
            out_capacity = new_capacity;
        }
    }

    result = (*env)->NewByteArray(env, (jsize)out_size);
    if (result)
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)out_size, (jbyte *)out_buf);

cleanup:
    pd_free(out_buf);

    if (dstream)
        ZSTD_freeDStream(dstream);

    if (exception_msg)
        (*env)->ThrowNew(env, io_exc, exception_msg);

    return result;
}

static jbyteArray decompress_single_shot(JNIEnv *env, jclass io_exc,
                                         const jbyte *src_buf, jsize src_len,
                                         unsigned long long decompressed_size) {
    if (decompressed_size > MAX_DECOMPRESSED_SIZE) {
        (*env)->ThrowNew(env, io_exc, "Decompressed size too large");
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize)decompressed_size);
    if (!result)
        return NULL;

    jbyte *dst_buf = (*env)->GetByteArrayElements(env, result, NULL);
    if (!dst_buf)
        return NULL;

    size_t actual = ZSTD_decompress(dst_buf, (size_t)decompressed_size,
                                    src_buf, (size_t)src_len);

    (*env)->ReleaseByteArrayElements(env, result, dst_buf, 0);

    if (ZSTD_isError(actual)) {
        (*env)->ThrowNew(env, io_exc, ZSTD_getErrorName(actual));
        return NULL;
    }

    return result;
}

/*
 * Decompress a zstd-compressed byte array.
 * Returns the decompressed data or null on error (possibly with an IOException thrown).
 */
JNIEXPORT jbyteArray JNICALL
Java_com_emanuelef_remote_1capture_ZstdDecoder_decompress(JNIEnv *env, jclass cls, jbyteArray src) {
    jsize src_len = (*env)->GetArrayLength(env, src);
    jbyte *src_buf = (*env)->GetByteArrayElements(env, src, NULL);
    if (!src_buf)
        return NULL;

    const jclass io_exc = (*env)->FindClass(env, "java/io/IOException");
    unsigned long long decompressed_size = ZSTD_getFrameContentSize(src_buf, src_len);
    jbyteArray result;

    if ((decompressed_size == ZSTD_CONTENTSIZE_ERROR) ||
        (decompressed_size == ZSTD_CONTENTSIZE_UNKNOWN))
        result = decompress_streaming(env, io_exc, src_buf, src_len);
    else
        result = decompress_single_shot(env, io_exc, src_buf, src_len, decompressed_size);

    (*env)->ReleaseByteArrayElements(env, src, src_buf, JNI_ABORT);

    return result;
}
