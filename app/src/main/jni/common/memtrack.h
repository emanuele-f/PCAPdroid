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
 * Copyright 2021 - Emanuele Faranda
 */

#ifndef PCAPDROID_MEMTRACK_H
#define PCAPDROID_MEMTRACK_H

#include <malloc.h>
#include <stdatomic.h>

// Uncomment to track allocations (with a performance impact)
//#define PCAPDROID_TRACK_ALLOCS

enum memtrack_scope {
    MEMTRACK_PCAPDROID = 0,
    MEMTRACK_NDPI = 1,
    MEMTRACK_BLACKLIST = 2,
    MEMTRACK_UTHASH = 3,
    //MEMTRACK_ZDTUN = 4, // TODO

    MEMTRACK_SCOPE_N
};

typedef struct {
    atomic_size_t scopes[MEMTRACK_SCOPE_N];
} memtrack_t;

extern memtrack_t memtrack;

#ifdef PCAPDROID_TRACK_ALLOCS

static void* _pcapdroid_malloc(size_t size, enum memtrack_scope scope) {
    void *ptr = malloc(size);
    if(ptr)
        memtrack.scopes[scope] += malloc_usable_size(ptr);
    return ptr;
}

static void* _pcapdroid_calloc(size_t nmemb, size_t size, enum memtrack_scope scope) {
    void *ptr = calloc(nmemb, size);
    if(ptr)
        memtrack.scopes[scope] += malloc_usable_size(ptr);
    return ptr;
}

static void _pcapdroid_free(void *ptr, enum memtrack_scope scope) {
    memtrack.scopes[scope] -= malloc_usable_size(ptr);
    free(ptr);
}

static void* _pcapdroid_realloc(void *ptr, size_t size, enum memtrack_scope scope) {
    memtrack.scopes[scope] -= malloc_usable_size(ptr);
    ptr = realloc(ptr, size);
    if(ptr)
        memtrack.scopes[scope] += malloc_usable_size(ptr);
    return ptr;
}

static char* _pcapdroid_strndup(const char *s, size_t n, enum memtrack_scope scope) {
    size_t l = min(strlen(s), n);
    char *c = (char*) _pcapdroid_malloc(l + 1, scope);
    if(!c)
        return NULL;
    memcpy(c, s, l);
    c[l] = 0;
    return c;
}

static inline char* _pcapdroid_strdup(const char *s, enum memtrack_scope scope) {
    return _pcapdroid_strndup(s, (size_t)-1, scope);
}

static inline void* pd_ndpi_malloc(size_t size) {
    return _pcapdroid_malloc(size, MEMTRACK_NDPI);
}

static inline void pd_ndpi_free(void *ptr) {
    return _pcapdroid_free(ptr, MEMTRACK_NDPI);
}

#define pd_malloc(size)         _pcapdroid_malloc(size, MEMTRACK_PCAPDROID)
#define pd_calloc(num, size)    _pcapdroid_calloc(num, size, MEMTRACK_PCAPDROID)
#define pd_free(ptr)            _pcapdroid_free(ptr, MEMTRACK_PCAPDROID)
#define pd_realloc(ptr, size)   _pcapdroid_realloc(ptr, size, MEMTRACK_PCAPDROID)
#define pd_strdup(str)          _pcapdroid_strdup(str, MEMTRACK_PCAPDROID)
#define pd_strndup(str, n)      _pcapdroid_strndup(str, n, MEMTRACK_PCAPDROID)
#define bl_malloc(str)          _pcapdroid_malloc(str, MEMTRACK_BLACKLIST)
#define bl_calloc(num, size)    _pcapdroid_calloc(num, size, MEMTRACK_BLACKLIST)
#define bl_free(ptr)            _pcapdroid_free(ptr, MEMTRACK_BLACKLIST)
#define bl_strdup(str)          _pcapdroid_strdup(str, MEMTRACK_BLACKLIST)

#include "third_party/uthash.h"
#undef uthash_malloc
#define uthash_malloc(sz)       _pcapdroid_malloc(sz, MEMTRACK_UTHASH)
#undef uthash_free
#define uthash_free(ptr, sz)    _pcapdroid_free(ptr, MEMTRACK_UTHASH)

#else // PCAPDROID_TRACK_ALLOCS

#define pd_malloc malloc
#define pd_calloc calloc
#define pd_free free
#define pd_realloc realloc
#define pd_strdup strdup
#define pd_strndup strndup

#define bl_malloc malloc
#define bl_calloc calloc
#define bl_free free
#define bl_strdup strdup

#endif // PCAPDROID_TRACK_ALLOCS

#endif
