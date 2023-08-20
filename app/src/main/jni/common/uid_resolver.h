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

#ifndef __UID_RESOLVER_H__
#define __UID_RESOLVER_H__

#include "jni_utils.h"
#include "zdtun.h"

#define UID_UNKNOWN -1
#define UID_ROOT 0
#define UID_PHONE 1001
#define UID_NETD 1051

typedef struct uid_resolver uid_resolver_t;

#ifdef ANDROID
uid_resolver_t* init_uid_resolver(jint sdk_version, JNIEnv *env, jobject vpn);
#endif

uid_resolver_t* init_uid_resolver_from_proc();
void destroy_uid_resolver(uid_resolver_t *resolver);
int get_uid(uid_resolver_t *resolver, const zdtun_5tuple_t *conn_info);

#endif // __UID_RESOLVER_H__
