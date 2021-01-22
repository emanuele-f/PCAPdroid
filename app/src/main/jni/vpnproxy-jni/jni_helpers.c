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

/* Utilities adapted from
 * https://github.com/M66B/NetGuard/blob/master/app/src/main/jni/netguard/netguard.c
 */

#include <jni.h>
#include <android/log.h>
#include <stdio.h>
#include <malloc.h>

static int loglevel = 0;

/* ******************************************************* */

void log_android(int prio, const char *fmt, ...) {
    if (prio >= loglevel) {
        char line[1024];
        va_list argptr;

        va_start(argptr, fmt);
        vsnprintf(line, sizeof(line), fmt, argptr);
        __android_log_print(prio, "VPNProxy", "%s", line);
        va_end(argptr);
    }
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

/* ******************************************************* */

void DeleteLocalRef(JNIEnv *env , jobject *jresource) {
    (*env)->DeleteLocalRef(env, jresource);
}

/* ******************************************************* */

void ReleaseStringUTFChars(JNIEnv *env , jobject *obj, const char *val) {
    (*env)->ReleaseStringUTFChars(env, obj, val);
}