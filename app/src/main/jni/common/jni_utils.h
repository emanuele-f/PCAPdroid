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
 * Copyright 2022 - Emanuele Faranda
 */

#ifndef __JNI_UTILS_H__
#define __JNI_UTILS_H__

#ifdef ANDROID

#include <jni.h>

jclass jniFindClass(JNIEnv *env, const char *name);
jmethodID jniGetMethodID(JNIEnv *env, jclass cls, const char *name, const char *signature);
jmethodID jniGetStaticMethodID(JNIEnv *env, jclass cls, const char *name, const char *signature);
jfieldID jniFieldID(JNIEnv *env, jclass cls, const char *name, const char *type);
jobject jniEnumVal(JNIEnv *env, const char *class_name, const char *enum_key);
int jniCheckException(JNIEnv *env);
void jniDumpReferences(JNIEnv *env);

#else // if ANDROID

#include <stdint.h>

// https://docs.oracle.com/javase/7/docs/technotes/guides/jni/spec/types.html
typedef uint8_t  jboolean;
typedef int8_t   jbyte;
typedef int32_t  jint;
typedef uint64_t jlong;

#endif // ANDROID

#endif // __JNI_UTILS_H__
