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
#include "utils.h"

int loglevel = 0;
const char *logtag = "VPNProxy";
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

        __android_log_print(lvl, logtag, "%s", line);

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
        return -1;

    return 0;
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

int jniCheckException(JNIEnv *env) {
    jthrowable ex = (*env)->ExceptionOccurred(env);
    if (ex) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, ex);
        return 1;
    }
    return 0;
}

/* ******************************************************* */

jclass jniFindClass(JNIEnv *env, const char *name) {
    jclass cls = (*env)->FindClass(env, name);
    if (cls == NULL)
        log_android(ANDROID_LOG_ERROR, "Class %s not found", name);
    else
        jniCheckException(env);
    return cls;
}

/* ******************************************************* */

jmethodID jniGetMethodID(JNIEnv *env, jclass cls, const char *name, const char *signature) {
    jmethodID method = (*env)->GetMethodID(env, cls, name, signature);

    if (method == NULL) {
        log_android(ANDROID_LOG_ERROR, "Method %s %s not found", name, signature);
        jniCheckException(env);
    }

    return method;
}