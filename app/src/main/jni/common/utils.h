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

#ifndef __LOG_UTILS_H__
#define __LOG_UTILS_H__

#include <jni.h>
#include <sys/types.h>
#include <android/log.h>
#include "zdtun.h"
#include "memtrack.h"

extern int loglevel;
extern const char* logtag;
extern void (*logcallback)(int lvl, const char *msg);

#define log_d(...) log_android(ANDROID_LOG_DEBUG, __VA_ARGS__)
#define log_i(...) log_android(ANDROID_LOG_INFO, __VA_ARGS__)
#define log_w(...) log_android(ANDROID_LOG_WARN, __VA_ARGS__)
#define log_e(...) log_android(ANDROID_LOG_ERROR, __VA_ARGS__)
#define log_f(...) log_android(ANDROID_LOG_FATAL, __VA_ARGS__)

void log_android(int lvl, const char *fmt, ...);
ssize_t xwrite(int fd, const void *buf, size_t count);
ssize_t xread(int fd, void *buf, size_t count);
uint64_t timeval2ms(struct timeval *tv);
void tupleSwapPeers(zdtun_5tuple_t *tuple);
char loglvl2char(int lvl);

jclass jniFindClass(JNIEnv *env, const char *name);
jmethodID jniGetMethodID(JNIEnv *env, jclass cls, const char *name, const char *signature);
int jniCheckException(JNIEnv *env);
char* humanSize(char *buf, int bufsize, double bytes);

#endif // __LOG_UTILS_H__
