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
static JNIEnv *cur_env = NULL;
static jclass vpnclass = 0;
static jclass vpn_inst = 0;
static jmethodID reportError = NULL;

jmethodID jniGetMethodID(JNIEnv *env, jclass cls, const char *name, const char *signature);
int jniCheckException(JNIEnv *env);
void DeleteLocalRef(JNIEnv *env, jobject *jresource);

/* ******************************************************* */

void init_log(int lvl, JNIEnv *env, jclass _vpnclass, jclass _vpn_inst) {
    loglevel = lvl;
    cur_env = env;
    vpnclass = _vpnclass;
    vpn_inst = _vpn_inst;
    reportError = jniGetMethodID(cur_env, vpnclass, "reportError", "(Ljava/lang/String;)V");
}

/* ******************************************************* */

void finish_log() {
    cur_env = NULL;
    vpnclass = 0;
    vpn_inst = 0;
    reportError = 0;
}

/* ******************************************************* */

void log_android(int prio, const char *fmt, ...) {
    if (prio >= loglevel) {
        char line[1024];
        va_list argptr;

        va_start(argptr, fmt);
        vsnprintf(line, sizeof(line), fmt, argptr);
        va_end(argptr);

        __android_log_print(prio, "VPNProxy", "%s", line);

        if((prio >= ANDROID_LOG_FATAL) && (cur_env != NULL) && (reportError != NULL)) {
            // This is a fatal error, report it to the gui
            jobject info_string = (*cur_env)->NewStringUTF(cur_env, line);

            if((jniCheckException(cur_env) != 0) || (info_string == NULL))
                return;

            (*cur_env)->CallVoidMethod(cur_env, vpn_inst, reportError, info_string);
            jniCheckException(cur_env);

            DeleteLocalRef(cur_env, info_string);
        }
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