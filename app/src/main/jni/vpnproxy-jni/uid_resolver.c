/*
    This file is part of RemoteCapture.

    RemoteCapture is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    RemoteCapture is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with RemoteCapture.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2019 by Emanuele Faranda
*/

//
// Code adapted from NetGuard
// https://github.com/M66B/NetGuard/blob/master/app/src/main/jni/netguard/ip.c
//

#include "vpnproxy.h"

#define VPN_TAG "UID_RESOLVER"
#define UID_MAX_AGE 30000 // milliseconds

static int uid_cache_size = 0;
static struct uid_cache_entry *uid_cache = NULL;

struct uid_cache_entry {
    uint8_t version;
    uint8_t protocol;
    uint8_t saddr[16];
    uint16_t sport;
    uint8_t daddr[16];
    uint16_t dport;
    jint uid;
    long time;
};

typedef  union {
    __be32 ip4; // network notation
    struct in6_addr ip6;
} ng_addr;

/* ******************************************************* */

static uint8_t char2nible(const char c) {
    if (c >= '0' && c <= '9') return (uint8_t) (c - '0');
    if (c >= 'a' && c <= 'f') return (uint8_t) ((c - 'a') + 10);
    if (c >= 'A' && c <= 'F') return (uint8_t) ((c - 'A') + 10);
    return 255;
}

static void hex2bytes(const char *hex, uint8_t *buffer) {
    size_t len = strlen(hex);
    for (int i = 0; i < len; i += 2)
        buffer[i / 2] = (char2nible(hex[i]) << 4) | char2nible(hex[i + 1]);
}

/* ******************************************************* */

static void log_android(int prio, const char *fmt, ...) {
    char line[1024];
    va_list argptr;
    va_start(argptr, fmt);
    vsprintf(line, fmt, argptr);
    __android_log_print(prio, VPN_TAG, "%s", line);
    va_end(argptr);
}

/* ******************************************************* */

jint get_uid_sub(const int version, const int protocol,
                 const void *saddr, const uint16_t sport,
                 const void *daddr, const uint16_t dport,
                 const char *source, const char *dest,
                 long now) {
    // NETLINK is not available on Android due to SELinux policies :-(
    // http://stackoverflow.com/questions/27148536/netlink-implementation-for-the-android-ndk
    // https://android.googlesource.com/platform/system/sepolicy/+/master/private/app.te (netlink_tcpdiag_socket)

    static uint8_t zero[16] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    int ws = (version == 4 ? 1 : 4);

    // Check cache
    for (int i = 0; i < uid_cache_size; i++)
        if (now - uid_cache[i].time <= UID_MAX_AGE &&
            uid_cache[i].version == version &&
            uid_cache[i].protocol == protocol &&
            uid_cache[i].sport == sport &&
            (uid_cache[i].dport == dport || uid_cache[i].dport == 0) &&
            (memcmp(uid_cache[i].saddr, saddr, (size_t) (ws * 4)) == 0 ||
             memcmp(uid_cache[i].saddr, zero, (size_t) (ws * 4)) == 0) &&
            (memcmp(uid_cache[i].daddr, daddr, (size_t) (ws * 4)) == 0 ||
             memcmp(uid_cache[i].daddr, zero, (size_t) (ws * 4)) == 0)) {

            //log_android(ANDROID_LOG_INFO, "uid v%d p%d %s/%u > %s/%u => %d (from cache)",
                        //version, protocol, source, sport, dest, dport, uid_cache[i].uid);

            return uid_cache[i].uid;
        }

    // Get proc file name
    char *fn = NULL;
    if (protocol == IPPROTO_ICMP && version == 4)
        fn = "/proc/net/icmp";
    else if (protocol == IPPROTO_ICMPV6 && version == 6)
        fn = "/proc/net/icmp6";
    else if (protocol == IPPROTO_TCP)
        fn = (version == 4 ? "/proc/net/tcp" : "/proc/net/tcp6");
    else if (protocol == IPPROTO_UDP)
        fn = (version == 4 ? "/proc/net/udp" : "/proc/net/udp6");
    else
        return -1;

    // Open proc file
    FILE *fd = fopen(fn, "r");
    if (fd == NULL) {
        log_android(ANDROID_LOG_ERROR, "fopen %s error %d: %s", fn, errno, strerror(errno));
        return -2;
    }

    jint uid = -1;

    char line[250];
    int fields;

    char shex[16 * 2 + 1];
    uint8_t _saddr[16];
    int _sport;

    char dhex[16 * 2 + 1];
    uint8_t _daddr[16];
    int _dport;

    jint _uid;

    // Scan proc file
    int l = 0;
    *line = 0;
    int c = 0;
    const char *fmt = (version == 4
                       ? "%*d: %8s:%X %8s:%X %*X %*lX:%*lX %*X:%*X %*X %d %*d %*ld"
                       : "%*d: %32s:%X %32s:%X %*X %*lX:%*lX %*X:%*X %*X %d %*d %*ld");
    while (fgets(line, sizeof(line), fd) != NULL) {
        if (!l++)
            continue;

        fields = sscanf(line, fmt, shex, &_sport, dhex, &_dport, &_uid);
        if (fields == 5 && strlen(shex) == ws * 8 && strlen(dhex) == ws * 8) {
            hex2bytes(shex, _saddr);
            hex2bytes(dhex, _daddr);

            for (int w = 0; w < ws; w++)
                ((uint32_t *) _saddr)[w] = htonl(((uint32_t *) _saddr)[w]);

            for (int w = 0; w < ws; w++)
                ((uint32_t *) _daddr)[w] = htonl(((uint32_t *) _daddr)[w]);

            if (_sport == sport &&
                (_dport == dport || _dport == 0) &&
                (memcmp(_saddr, saddr, (size_t) (ws * 4)) == 0 ||
                 memcmp(_saddr, zero, (size_t) (ws * 4)) == 0) &&
                (memcmp(_daddr, daddr, (size_t) (ws * 4)) == 0 ||
                 memcmp(_daddr, zero, (size_t) (ws * 4)) == 0))
                uid = _uid;

            for (; c < uid_cache_size; c++)
                if (now - uid_cache[c].time > UID_MAX_AGE)
                    break;

            if (c >= uid_cache_size) {
                if (uid_cache_size == 0)
                    uid_cache = calloc(1, sizeof(struct uid_cache_entry));
                else
                    uid_cache = realloc(uid_cache,
                                           sizeof(struct uid_cache_entry) *
                                           (uid_cache_size + 1));
                c = uid_cache_size;
                uid_cache_size++;
            }

            uid_cache[c].version = (uint8_t) version;
            uid_cache[c].protocol = (uint8_t) protocol;
            memcpy(uid_cache[c].saddr, _saddr, (size_t) (ws * 4));
            uid_cache[c].sport = (uint16_t) _sport;
            memcpy(uid_cache[c].daddr, _daddr, (size_t) (ws * 4));
            uid_cache[c].dport = (uint16_t) _dport;
            uid_cache[c].uid = _uid;
            uid_cache[c].time = now;
        } else {
            log_android(ANDROID_LOG_ERROR, "Invalid field #%d: %s", fields, line);
            return -2;
        }
    }

    if (fclose(fd))
        log_android(ANDROID_LOG_ERROR, "fclose %s error %d: %s", fn, errno, strerror(errno));

    return uid;
}

/* ******************************************************* */

jint get_uid_slow(struct vpnproxy_data *proxy,
                  const zdtun_conn_t *conn_info) {
    jint uid = -1;

    // TODO IPv6 support
    /* This snippet is only needed to put together netguard and vpnproxy */
    int version = 4;
    ng_addr saddr;
    ng_addr daddr;
    memset(&saddr, 0, sizeof(saddr));
    memset(&daddr, 0, sizeof(daddr));
    saddr.ip4 = conn_info->src_ip;
    daddr.ip4 = conn_info->dst_ip;
    u_int16_t sport = ntohs(conn_info->src_port);
    u_int16_t dport = ntohs(conn_info->dst_port);
    /* end snippet */

    char source[INET6_ADDRSTRLEN + 1];
    char dest[INET6_ADDRSTRLEN + 1];
    inet_ntop(version == 4 ? AF_INET : AF_INET6, &saddr, source, sizeof(source));
    inet_ntop(version == 4 ? AF_INET : AF_INET6, &daddr, dest, sizeof(dest));

    struct timeval time;
    gettimeofday(&time, NULL);
    long now = (time.tv_sec * 1000) + (time.tv_usec / 1000);

    // Check IPv6 table first
    if (version == 4) {
        int8_t saddr128[16];
        memset(saddr128, 0, 10);
        saddr128[10] = (uint8_t) 0xFF;
        saddr128[11] = (uint8_t) 0xFF;
        memcpy(saddr128 + 12, &saddr, 4);

        int8_t daddr128[16];
        memset(daddr128, 0, 10);
        daddr128[10] = (uint8_t) 0xFF;
        daddr128[11] = (uint8_t) 0xFF;
        memcpy(daddr128 + 12, &daddr, 4);

        uid = get_uid_sub(6, conn_info->ipproto, saddr128, sport, daddr128, dport, source, dest, now);
        //log_android(ANDROID_LOG_DEBUG, "uid v%d p%d %s/%u > %s/%u => %d as inet6",
        //            version, conn_info->ipproto, source, sport, dest, dport, uid);
    }

    if (uid == -1) {
        uid = get_uid_sub(version, conn_info->ipproto, &saddr, sport, &daddr, dport, source, dest, now);
        //log_android(ANDROID_LOG_DEBUG, "uid v%d p%d %s/%u > %s/%u => %d fallback",
                    //version, conn_info->ipproto, source, sport, dest, dport, uid);
    }

    if (uid == -1)
        log_android(ANDROID_LOG_WARN, "uid [ipv%d][proto=%d] %s:%u -> %s:%u => not found",
                    version, conn_info->ipproto, source, sport, dest, dport);
    else if (uid >= 0) {
     //   log_android(ANDROID_LOG_INFO, "uid v%d p%d %s/%u > %s/%u => %d",
       //             version, conn_info->ipproto, source, sport, dest, dport, uid);
    }

    return uid;
}

/* ******************************************************* */

static jint get_uid_q(struct vpnproxy_data *proxy,
                      const zdtun_conn_t *conn_info) {
    JNIEnv *env = proxy->env;
    jclass vpn_service_cls = (*env)->GetObjectClass(env, proxy->vpn_service);
    struct in_addr addr;
    int version = 4; // TODO IPv6 support

    // TODO cache
    jmethodID midGetUidQ = (*env)->GetMethodID(env, vpn_service_cls, "getUidQ", "(IILjava/lang/String;ILjava/lang/String;I)I");
    if(!midGetUidQ)
        __android_log_print(ANDROID_LOG_FATAL, VPN_TAG, "GetMethodID(getUidQ) failed");

    addr.s_addr = conn_info->src_ip;
    jstring jsource = (*env)->NewStringUTF(env, inet_ntoa(addr));
    addr.s_addr = conn_info->dst_ip;
    jstring jdest = (*env)->NewStringUTF(env, inet_ntoa(addr));

    jint juid = (*env)->CallIntMethod(
            env, proxy->vpn_service, midGetUidQ,
            version, conn_info->ipproto, jsource, ntohs(conn_info->src_port), jdest, ntohs(conn_info->dst_port));

    (*env)->DeleteLocalRef(env, jsource);
    (*env)->DeleteLocalRef(env, jdest);

    return juid;
}

/* ******************************************************* */

jint get_uid(struct vpnproxy_data *proxy, const zdtun_conn_t *conn_info) {
    // TODO test the get_uid_q
#if 0
    if (proxy->sdk <= 28) // Android 9 Pie
        return(get_uid_slow(proxy, conn_info));
    else
        return(get_uid_q(proxy, conn_info));
#endif
    return(get_uid_slow(proxy, conn_info));
}