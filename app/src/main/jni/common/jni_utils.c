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

#ifdef ANDROID

#include <jni.h>
#include "common/utils.h"

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

#endif
