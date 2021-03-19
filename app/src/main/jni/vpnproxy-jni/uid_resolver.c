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

#include "vpnproxy.h"
#include "jni_helpers.h"

/* ******************************************************* */

struct uid_resolver {
    jint sdk;
    JNIEnv *env;
    jobject vpn_service;
    jmethodID getUidQ;
};

/* ******************************************************* */

// src_port and dst_port are in HBO.
static jint get_uid_proc(int ipver, int ipproto, const char *conn_shex,
                         const char *conn_dhex, u_int16_t src_port, u_int16_t dst_port) {
    char *proc;

    // Get proc file name
    switch(ipproto) {
        case IPPROTO_TCP:
            proc = (ipver == 4) ? "/proc/net/tcp" : "/proc/net/tcp6";
            break;
        case IPPROTO_UDP:
            proc = (ipver == 4) ? "/proc/net/udp" : "/proc/net/udp6";
            break;
        case IPPROTO_ICMP:
        case IPPROTO_ICMPV6:
            proc = (ipver == 4) ? "/proc/net/icmp" : "/proc/net/icmp6";
            break;
        default:
            return UID_UNKNOWN;
    }

    FILE *fd = fopen(proc, "r");

    if (fd == NULL) {
        log_android(ANDROID_LOG_ERROR, "fopen(%s) failed[%d]: %s", proc, errno, strerror(errno));
        return UID_UNKNOWN;
    }

    // Parse proc file
    char line[256];
    int lines = 0;
    jint rv = UID_UNKNOWN;
    int sport, dport, uid;
    char shex[33], dhex[33];
    const char *zero = (ipver == 4 ? "00000000" : "00000000000000000000000000000000");
    const char *fmt = (ipver == 4
                       ? "%*d: %8s:%X %8s:%X %*X %*X:%*X %*X:%*X %*X %d"
                       : "%*d: %32s:%X %32s:%X %*X %*X:%*X %*X:%*X %*X %d");

    while(fgets(line, sizeof(line), fd) != NULL) {
        // skip header
        if(!lines++)
            continue;

        //log_android(ANDROID_LOG_INFO, "[try] %s", line);

        if(sscanf(line, fmt, shex, &sport, dhex, &dport, &uid) == 5) {
            //log_android(ANDROID_LOG_DEBUG, "[try] %s:%d -> %s:%d [%d]", shex, sport, dhex, dport, uid);

            if((sport == src_port) && (dport == dst_port)
               && (!strcmp(conn_dhex, dhex) || !strcmp(dhex, zero))
               && (!strcmp(conn_shex, shex) || !strcmp(shex, zero))) {
                // found
                rv = uid;
                break;
            }
        }
    }

    fclose(fd);

    return(rv);
}

/* ******************************************************* */

#if 0
static char* tohex(const uint8_t *src, int srcsize, char *dst, int dstsize) {
    static const char *hex = "0123456789ABCDEF";
    int j = 0;

    for(int i=0; (i < srcsize) && (j+2 < dstsize); i++) {
        dst[j++] = hex[(src[i] >> 4)];
        dst[j++] = hex[(src[i] & 0x0F)];
    }

    dst[j] = '\0';

    return dst;
}
#endif

/* ******************************************************* */

static jint get_uid_slow(const zdtun_5tuple_t *conn_info) {
    char shex[33], dhex[33];
    jint rv;

    //clock_t start = clock();

    u_int16_t sport = ntohs(conn_info->src_port);
    u_int16_t dport = ntohs(conn_info->dst_port);

    if(conn_info->ipver == 4) {
        sprintf(shex, "%08X", conn_info->src_ip.ip4);
        sprintf(dhex, "%08X", conn_info->dst_ip.ip4);

        rv = get_uid_proc(4, conn_info->ipproto, shex, dhex, sport, dport);

        if (rv == UID_UNKNOWN) {
            // Search for IPv4-mapped IPv6 addresses
            // https://tools.ietf.org/html/rfc3493#section-3.7
            sprintf(shex, "0000000000000000FFFF0000%08X", conn_info->src_ip.ip4);
            sprintf(dhex, "0000000000000000FFFF0000%08X", conn_info->dst_ip.ip4);

            rv = get_uid_proc(6, conn_info->ipproto, shex, dhex, sport, dport);
        }
    } else {
        const uint32_t *src = conn_info->src_ip.ip6.in6_u.u6_addr32;
        const uint32_t *dst = conn_info->dst_ip.ip6.in6_u.u6_addr32;

        sprintf(shex, "%08X%08X%08X%08X", src[0], src[1], src[2], src[3]);
        sprintf(dhex, "%08X%08X%08X%08X", dst[0], dst[1], dst[2], dst[3]);

        //log_android(ANDROID_LOG_INFO, "HEX %s %s", shex, dhex);

        rv = get_uid_proc(6, conn_info->ipproto, shex, dhex, sport, dport);
    }

    //double cpu_time_used = ((double) (clock() - start)) / CLOCKS_PER_SEC;
    //log_android(ANDROID_LOG_DEBUG, "cpu_time_used %f", cpu_time_used);

    return rv;
}

/* ******************************************************* */

static jint get_uid_q(uid_resolver_t *resolver,
                      const zdtun_5tuple_t *conn_info) {
    JNIEnv *env = resolver->env;
    jint juid = UID_UNKNOWN;
    int version = conn_info->ipver;
    int family = (version == 4) ? AF_INET : AF_INET6;
    char srcip[INET6_ADDRSTRLEN];
    char dstip[INET6_ADDRSTRLEN];

    // getUidQ only works for TCP/UDP connections
    if((conn_info->ipproto != IPPROTO_TCP) && (conn_info->ipproto != IPPROTO_UDP))
        return UID_UNKNOWN;

    if(resolver->getUidQ == NULL) {
        // Resolve method
        jclass vpn_service_cls = (*env)->GetObjectClass(env, resolver->vpn_service);
        resolver->getUidQ = jniGetMethodID(env, vpn_service_cls, "getUidQ",
                "(IILjava/lang/String;ILjava/lang/String;I)I");

        if(!resolver->getUidQ)
            return UID_UNKNOWN;
    }

    u_int16_t sport = ntohs(conn_info->src_port);
    u_int16_t dport = ntohs(conn_info->dst_port);

    inet_ntop(family, &conn_info->src_ip, srcip, sizeof(srcip));
    inet_ntop(family, &conn_info->dst_ip, dstip, sizeof(dstip));

    jstring jsource = (*env)->NewStringUTF(env, srcip);
    jstring jdest = (*env)->NewStringUTF(env, dstip);

    if((jsource != NULL) && (jdest != NULL)) {
        juid = (*env)->CallIntMethod(
            env, resolver->vpn_service, resolver->getUidQ,
            version, conn_info->ipproto, jsource, sport, jdest, dport);
        jniCheckException(env);
    }

    (*env)->DeleteLocalRef(env, jsource);
    (*env)->DeleteLocalRef(env, jdest);

    return juid;
}

/* ******************************************************* */

uid_resolver_t* init_uid_resolver(jint sdk_version, JNIEnv *env, jobject vpn) {
    uid_resolver_t *rv = calloc(1, sizeof(uid_resolver_t));

    if(!rv) {
        log_android(ANDROID_LOG_ERROR, "calloc uid_resolver_t failed");
        return NULL;
    }

    rv->sdk = sdk_version;
    rv->env = env;
    rv->vpn_service = vpn;

    return rv;
}

/* ******************************************************* */

void destroy_uid_resolver(uid_resolver_t *resolver) {
    free(resolver);
}

/* ******************************************************* */

jint get_uid(uid_resolver_t *resolver, const zdtun_5tuple_t *conn_info) {
    if(resolver->sdk <= 28) // Android 9 Pie
        return(get_uid_slow(conn_info));
    else
        return(get_uid_q(resolver, conn_info));
}