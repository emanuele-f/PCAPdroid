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

memtrack_t memtrack = {0};
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
        log_e("Class %s not found", name);
    else
        jniCheckException(env);
    return cls;
}

/* ******************************************************* */

jmethodID jniGetMethodID(JNIEnv *env, jclass cls, const char *name, const char *signature) {
    jmethodID method = (*env)->GetMethodID(env, cls, name, signature);
    if (method == NULL) {
        log_e("Method %s %s not found", name, signature);
        jniCheckException(env);
    }

    return method;
}

/* ******************************************************* */

jfieldID jniFieldID(JNIEnv *env, jclass cls, const char *name, const char *type) {
    jfieldID field = (*env)->GetFieldID(env, cls, name, type);
    if(field == NULL) {
        log_e("Field %s(%s) not found", name, type);
        jniCheckException(env);
    }

    return field;
}

/* ******************************************************* */

jobject jniEnumVal(JNIEnv *env, const char *class_name, const char *enum_key) {
    char buf[512];

    jclass cls = jniFindClass(env, class_name);
    if(cls == NULL)
        return NULL;

    snprintf(buf, sizeof(buf), "L%s;", class_name);
    jfieldID field = (*env)->GetStaticFieldID(env, cls, enum_key, buf);
    if(field == NULL) {
        log_e("Static field %s(%s) not found", enum_key, buf);
        jniCheckException(env);
        return NULL;
    }

    jobject val = (*env)->GetStaticObjectField(env, cls, field);
    if(!val) {
        log_e("Enum value %s not found in \"%s\"", enum_key, class_name);
        jniCheckException(env);
    }

    return val;
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