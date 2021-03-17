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
 * Copyright 2020-21 - Emanuele Faranda
 */

#ifndef __JNI_HELPERS_H__
#define __JNI_HELPERS_H__

#include <jni.h>
#include <android/log.h>
#include <stdio.h>

void init_log(int lvl, JNIEnv *env, jclass _vpnclass, jclass _vpn_inst);
void finish_log();
void log_android(int prio, const char *fmt, ...);

jclass jniFindClass(JNIEnv *env, const char *name);
jmethodID jniGetMethodID(JNIEnv *env, jclass cls, const char *name, const char *signature);
int jniCheckException(JNIEnv *env);

#endif // __JNI_HELPERS_H__
