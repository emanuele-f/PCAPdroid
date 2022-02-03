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

#include <stdio.h>
#include <errno.h>
#include <unistd.h>
#include <stdarg.h>
#include "utils.h"

memtrack_t memtrack = {0};
int loglevel = 0;
const char *logtag = "pcapdroid-native";
void (*logcallback)(int lvl, const char *msg) = NULL;

/* ******************************************************* */

void set_log_level(int lvl) {
    loglevel = lvl;
}

/* ******************************************************* */

void log_android(int lvl, const char *fmt, ...) {
    if(lvl >= loglevel) {
        char line[1024];
        va_list argptr;

        va_start(argptr, fmt);
        vsnprintf(line, sizeof(line), fmt, argptr);
        va_end(argptr);

#ifdef ANDROID
        __android_log_print(lvl, logtag, "%s", line);
#else
        {
            char ch = '?';

            switch(lvl) {
                case ANDROID_LOG_DEBUG: ch = 'D'; break;
                case ANDROID_LOG_INFO:  ch = 'I'; break;
                case ANDROID_LOG_WARN:  ch = 'W'; break;
                case ANDROID_LOG_ERROR: ch = 'E'; break;
                case ANDROID_LOG_FATAL: ch = 'F'; break;
            }

            fprintf(lvl >= ANDROID_LOG_WARN ? stderr : stdout, "[%c] %s\n", ch, line);
        }
#endif

        if(logcallback != NULL)
            logcallback(lvl, line);
    }
}

/* ******************************************************* */

char loglvl2char(int lvl) {
    switch (lvl) {
        case ANDROID_LOG_DEBUG: return 'D';
        case ANDROID_LOG_INFO:  return 'I';
        case ANDROID_LOG_WARN:  return 'W';
        case ANDROID_LOG_ERROR: return 'E';
        case ANDROID_LOG_FATAL: return 'F';
        default:                return '?';
    }
}

/* ******************************************************* */

ssize_t xwrite(int fd, const void *buf, size_t count) {
    size_t sofar = 0;
    ssize_t ret;

    do {
        ret = write(fd, (u_char*)buf + sofar, count - sofar);

        if(ret < 0) {
            if(errno == EINTR)
                continue;

            return ret;
        }

        sofar += ret;
    } while((sofar != count) && (ret != 0));

    if(sofar != count)
        return -1;

    return 0;
}

/* ******************************************************* */

// returns < 0 on error, 0 if fd is closed
ssize_t xread(int fd, void *buf, size_t count) {
    size_t sofar = 0;
    ssize_t rv;

    do {
        rv = read(fd, (char*)buf + sofar, count - sofar);

        if(rv < 0) {
            if(errno == EINTR)
                continue;
            return rv;
        }

        sofar += rv;
    } while((sofar != count) && (rv != 0));

    if(sofar != count)
        return (rv == 0) ? 0 : -1;

    return count;
}

/* ******************************************************* */

void tupleSwapPeers(zdtun_5tuple_t *tuple) {
    uint16_t tmp = tuple->dst_port;
    tuple->dst_port = tuple->src_port;
    tuple->src_port = tmp;

    zdtun_ip_t tmp1 = tuple->dst_ip;
    tuple->dst_ip = tuple->src_ip;
    tuple->src_ip = tmp1;
}

/* ******************************************************* */

char* humanSize(char *buf, int bufsize, double bytes) {
    static char *suffix[] = {"B", "KB", "MB", "GB", "TB"};
    int num_suffix = sizeof(suffix) / sizeof(suffix[0]);
    int i;

    for(i = 0; (bytes >= 1024) && (i < num_suffix); i++)
        bytes /= 1024;

    snprintf(buf, bufsize, "%.02f %s", bytes, suffix[i]);
    return buf;
}

/* ******************************************************* */

/* Dumps packets in the hex format of "od -A x -t x1", which makes it compatible with
 * text2pcap. */
void hexdump(const char *buf, size_t bufsize) {
    size_t off = 0;
    char out[64];
    int idx = 0;
    static const char hex[] = "0123456789abcdef";

    while(off < bufsize) {
        if((off % 16) == 0) {
            if(off > 0) {
                out[idx] = '\0';
                log_d("%s", out);
            }
            idx = sprintf(out, "%06zx", off);
        }

        out[idx++] = ' ';
        out[idx++] = hex[(buf[off] & 0xF0) >> 4];
        out[idx++] = hex[buf[off] & 0x0F];
        off++;
    }

    if((off % 16) != 0) {
        out[idx] = '\0';
        log_d("%s", out);
        idx = sprintf(out, "%06zx", off);
    }

    out[idx] = '\0';
    log_d("%s", out);
}
