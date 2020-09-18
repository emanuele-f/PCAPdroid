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

#include <netinet/udp.h>
#include <netinet/ip.h>
#include <ndpi_typedefs.h>
#include "jni_helpers.c"
#include "utils.c"
#include "vpnproxy.h"
#include "pcap.h"
#include "../../../../../../nDPI/src/include/ndpi_protocol_ids.h"

#define CAPTURE_STATS_UPDATE_FREQUENCY_MS 300
#define CONNECTION_DUMP_UPDATE_FREQUENCY_MS 3000
#define MAX_JAVA_DUMP_DELAY_MS 1000
#define MAX_DPI_PACKETS 12
#define JAVA_PCAP_BUFFER_SIZE (512*1204) // 512K
#define MAX_NUM_CONNECTIONS_DUMPED 64

/* ******************************************************* */

#define DNS_FLAGS_MASK 0x8000
#define DNS_TYPE_REQUEST 0x0000
#define DNS_TYPE_RESPONSE 0x8000

typedef struct dns_packet {
    uint16_t transaction_id;
    uint16_t flags;
    uint16_t questions;
    uint16_t answ_rrs;
    uint16_t auth_rrs;
    uint16_t additional_rrs;
    uint8_t initial_dot; // just skip
    uint8_t queries[];
} __attribute__((packed)) dns_packet_t;

/* ******************************************************* */

typedef struct conn_data {
    jint incr_id; /* an incremental identifier */

    /* nDPI */
    struct ndpi_flow_struct *ndpi_flow;
    struct ndpi_id_struct *src_id, *dst_id;
    ndpi_protocol l7proto;

    jlong first_seen;
    jlong last_seen;
    jlong sent_bytes;
    jlong rcvd_bytes;
    jint sent_pkts;
    jint rcvd_pkts;
    char *info;
    char *url;
    jint uid;
    bool notified;
} conn_data_t;

/* ******************************************************* */

typedef struct jni_methods {
    jmethodID getApplicationByUid;
    jmethodID protect;
    jmethodID dumpPcapData;
    jmethodID sendCaptureStats;
    jmethodID sendConnectionsDump;
    jmethodID connInit;
    jmethodID connSetData;
    jmethodID sendServiceStatus;
} jni_methods_t;

typedef struct jni_classes {
    jclass vpn_service;
    jclass conn;
} jni_classes_t;

static bool jni_resolved = false;
static jni_classes_t cls;
static jni_methods_t mids;

/* ******************************************************* */

/* NOTE: these must be reset during each run, as android may reuse the service */
static int dumper_socket;
static bool send_header;

/* ******************************************************* */

static u_int32_t getIPv4Pref(vpnproxy_data_t *proxy, const char *key) {
    JNIEnv *env = proxy->env;
    struct in_addr addr = {0};

    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()Ljava/lang/String;");
    jstring obj = (*env)->CallObjectMethod(env, proxy->vpn_service, midMethod);

    if(!jniCheckException(env)) {
        const char *value = (*env)->GetStringUTFChars(env, obj, 0);
        log_android(ANDROID_LOG_DEBUG, "getIPv4Pref(%s) = %s", key, value);

        if(inet_aton(value, &addr) == 0)
            log_android(ANDROID_LOG_ERROR, "%s() returned invalid address", key);

        ReleaseStringUTFChars(env, obj, value);
    }

    DeleteLocalRef(env, obj);

    return(addr.s_addr);
}

/* ******************************************************* */

static jint getIntPref(vpnproxy_data_t *proxy, const char *key) {
    JNIEnv *env = proxy->env;
    jint value;
    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()I");

    value = (*env)->CallIntMethod(env, proxy->vpn_service, midMethod);
    jniCheckException(env);

    log_android(ANDROID_LOG_DEBUG, "getIntPref(%s) = %d", key, value);

    return(value);
}

/* ******************************************************* */

static void protectSocket(vpnproxy_data_t *proxy, socket_t sock) {
    JNIEnv *env = proxy->env;

    /* Call VpnService protect */
    jboolean isProtected = (*env)->CallBooleanMethod(
            env, proxy->vpn_service, mids.protect, sock);
    jniCheckException(env);

    if (!isProtected)
        log_android(ANDROID_LOG_ERROR, "socket protect failed");
}

static void protectSocketCallback(zdtun_t *tun, socket_t sock) {
    vpnproxy_data_t *proxy = ((vpnproxy_data_t*)zdtun_userdata(tun));
    protectSocket(proxy, sock);
}

/* ******************************************************* */

static char* getApplicationByUid(vpnproxy_data_t *proxy, jint uid, char *buf, size_t bufsize) {
    JNIEnv *env = proxy->env;
    const char *value = NULL;

    jstring obj = (*env)->CallObjectMethod(env, proxy->vpn_service, mids.getApplicationByUid, uid);
    jniCheckException(env);

    if(obj)
        value = (*env)->GetStringUTFChars(env, obj, 0);

    if(!value) {
        strncpy(buf, "???", bufsize);
        buf[bufsize-1] = '\0';
    } else {
        strncpy(buf, value, bufsize);
        buf[bufsize - 1] = '\0';
    }

    if(value) ReleaseStringUTFChars(env, obj, value);
    if(obj) DeleteLocalRef(env, obj);

    return(buf);
}

/* ******************************************************* */

struct ndpi_detection_module_struct* init_ndpi() {
    struct ndpi_detection_module_struct *ndpi = ndpi_init_detection_module(ndpi_no_prefs);
    NDPI_PROTOCOL_BITMASK protocols;

    if(!ndpi)
        return(NULL);

    // enable all the protocols
    NDPI_BITMASK_SET_ALL(protocols);

    ndpi_set_protocol_detection_bitmask2(ndpi, &protocols);
    ndpi_finalize_initalization(ndpi);

    return(ndpi);
}

/* ******************************************************* */

void free_ndpi(conn_data_t *data) {
    if(data->ndpi_flow) {
        ndpi_free_flow(data->ndpi_flow);
        data->ndpi_flow = NULL;
    }
    if(data->src_id) {
        ndpi_free(data->src_id);
        data->src_id = NULL;
    }
    if(data->dst_id) {
        ndpi_free(data->dst_id);
        data->dst_id = NULL;
    }
}

/* ******************************************************* */

const char *getL7ProtoName(struct ndpi_detection_module_struct *mod, ndpi_protocol l7proto) {
    return ndpi_get_proto_name(mod, l7proto.master_protocol);
}

/* ******************************************************* */

static void process_ndpi_packet(conn_data_t *data, vpnproxy_data_t *proxy, const char *packet,
        ssize_t size, uint8_t from_tap) {
    bool giveup = ((data->sent_pkts + data->rcvd_pkts) >= MAX_DPI_PACKETS);

    data->l7proto = ndpi_detection_process_packet(proxy->ndpi, data->ndpi_flow, packet, size, data->last_seen,
                                                  from_tap ? data->src_id : data->dst_id,
                                                  from_tap ? data->dst_id : data->src_id);

    if(giveup || ((data->l7proto.app_protocol != NDPI_PROTOCOL_UNKNOWN) &&
            (!ndpi_extra_dissection_possible(proxy->ndpi, data->ndpi_flow)))) {
        if (data->l7proto.app_protocol == NDPI_PROTOCOL_UNKNOWN) {
            uint8_t proto_guessed;

            data->l7proto = ndpi_detection_giveup(proxy->ndpi, data->ndpi_flow, 1 /* Guess */,
                                                  &proto_guessed);
        }

        if(data->l7proto.master_protocol == 0)
            data->l7proto.master_protocol = data->l7proto.app_protocol;

        log_android(ANDROID_LOG_DEBUG, "l7proto: app=%d, master=%d",
                            data->l7proto.app_protocol, data->l7proto.master_protocol);

        switch (data->l7proto.master_protocol) {
            case NDPI_PROTOCOL_DNS:
                if(data->ndpi_flow->host_server_name[0])
                    data->info = strdup(data->ndpi_flow->host_server_name);
                break;
            case NDPI_PROTOCOL_HTTP:
                if(data->ndpi_flow->host_server_name[0])
                    data->info = strdup(data->ndpi_flow->host_server_name);
                if(data->ndpi_flow->http.url)
                    data->url = strdup(data->ndpi_flow->http.url);
                break;
            case NDPI_PROTOCOL_TLS:
                if(data->ndpi_flow->protos.stun_ssl.ssl.client_requested_server_name[0])
                    data->info = strdup(data->ndpi_flow->protos.stun_ssl.ssl.client_requested_server_name);
                break;
        }

        if(data->info)
            log_android(ANDROID_LOG_DEBUG, "info: %s", data->info);

        free_ndpi(data);
    }
}

/* ******************************************************* */

static void javaPcapDump(zdtun_t *tun, vpnproxy_data_t *proxy) {
    JNIEnv *env = proxy->env;

    log_android(ANDROID_LOG_DEBUG, "Exporting a %dB PCAP buffer", proxy->java_dump.buffer_idx);

    jbyteArray barray = (*env)->NewByteArray(env, proxy->java_dump.buffer_idx);
    if(jniCheckException(env))
        return;

    (*env)->SetByteArrayRegion(env, barray, 0, proxy->java_dump.buffer_idx, proxy->java_dump.buffer);
    (*env)->CallVoidMethod(env, proxy->vpn_service, mids.dumpPcapData, barray);
    jniCheckException(env);

    proxy->java_dump.buffer_idx = 0;
    proxy->java_dump.last_dump_ms = proxy->now_ms;

    DeleteLocalRef(env, barray);
}

/* ******************************************************* */

static void account_packet(zdtun_t *tun, const char *packet, ssize_t size, uint8_t from_tap, const zdtun_conn_t *conn_info) {
    struct sockaddr_in servaddr = {0};
    conn_data_t *data = (conn_data_t*)conn_info->user_data;
    vpnproxy_data_t *proxy;
    bool is_unknown_app;
    int uid;

    if(!data) {
        log_android(ANDROID_LOG_ERROR, "Missing user_data in connection");
        return;
    }

    uid = data->uid;
    is_unknown_app = ((uid == -1) || (uid == 1051 /* netd DNS resolver */));
    proxy = ((vpnproxy_data_t*)zdtun_userdata(tun));

#if 0
    if(from_tap)
        log_android(ANDROID_LOG_DEBUG, "tap2net: %ld B", size);
    else
        log_android(ANDROID_LOG_DEBUG, "net2tap: %lu B", size);
#endif

    /* NOTE: account connection stats also for non-matched connections */
    if(from_tap) {
        data->sent_pkts++;
        data->sent_bytes += size;
    } else {
        data->rcvd_pkts++;
        data->rcvd_bytes += size;
    }

    data->last_seen = time(NULL);

    if(data->ndpi_flow)
        process_ndpi_packet(data, proxy, packet, size, from_tap);

    if(((proxy->uid_filter != -1) && (proxy->uid_filter != uid))
        && (!is_unknown_app || !proxy->capture_unknown_app_traffic)) {
        //log_android(ANDROID_LOG_DEBUG, "Discarding connection: UID=%d [filter=%d]", uid, proxy->uid_filter);
        return;
    }

    if(from_tap) {
        proxy->capture_stats.sent_pkts++;
        proxy->capture_stats.sent_bytes += size;
    } else {
        proxy->capture_stats.rcvd_pkts++;
        proxy->capture_stats.rcvd_bytes += size;
    }

    /* New stats to notify */
    proxy->capture_stats.new_stats = true;

    if(proxy->java_dump.buffer) {
        int rem_size = JAVA_PCAP_BUFFER_SIZE - proxy->java_dump.buffer_idx;

        if((size + sizeof(pcaprec_hdr_s)) > rem_size) {
            // Flush the buffer
            javaPcapDump(tun, proxy);
        }

        proxy->java_dump.buffer_idx += dump_pcap_rec(proxy->java_dump.buffer + proxy->java_dump.buffer_idx, packet, size);
    }

    if(dumper_socket > 0) {
        servaddr.sin_family = AF_INET;
        servaddr.sin_port = proxy->pcap_dump.collector_port;
        servaddr.sin_addr.s_addr = proxy->pcap_dump.collector_addr;

        if (send_header) {
            write_pcap_hdr(dumper_socket, (struct sockaddr *) &servaddr, sizeof(servaddr));
            send_header = false;
        }

        write_pcap_rec(dumper_socket, (struct sockaddr *) &servaddr, sizeof(servaddr),
                       (u_int8_t *) packet, size);
    }
}

/* ******************************************************* */

static int resolve_uid(vpnproxy_data_t *proxy, const zdtun_conn_t *conn_info) {
    jint uid;
    zdtun_conn_t unproxied_info = *conn_info;

    if(proxy->dns_changed) {
        /* The packet was changed by check_dns.
         * Restore the original DNS request to be able to fetch connection UID. */
        unproxied_info.dst_ip = proxy->vpn_dns;
    }

    uid = get_uid(proxy, &unproxied_info);

    if(uid >= 0) {
#if 1
        char appbuf[256];
        char srcip[64], dstip[64];
        struct in_addr addr;

        if(uid == 0)
            strncpy(appbuf, "ROOT", sizeof(appbuf));
        else if(uid == 1051)
            strncpy(appbuf, "netd", sizeof(appbuf));
        else
            getApplicationByUid(proxy, uid, appbuf, sizeof(appbuf));

        addr.s_addr = conn_info->src_ip;
        strncpy(srcip, inet_ntoa(addr), sizeof(srcip));
        addr.s_addr = conn_info->dst_ip;
        strncpy(dstip, inet_ntoa(addr), sizeof(dstip));

        log_android(ANDROID_LOG_INFO, "[proto=%d]: %s:%u -> %s:%u [%d/%s]",
                            conn_info->ipproto,
                            srcip, ntohs(conn_info->src_port),
                            dstip, ntohs(conn_info->dst_port),
                            uid, appbuf);
#endif
    } else
        uid = -1;

    return(uid);
}

/* ******************************************************* */

static int handle_new_connection(zdtun_t *tun, const zdtun_conn_t *conn_info, void **conn_data) {
    vpnproxy_data_t *proxy = ((vpnproxy_data_t*)zdtun_userdata(tun));
    conn_data_t *data = calloc(1, sizeof(conn_data_t));

    if(!data) {
        log_android(ANDROID_LOG_ERROR, "calloc(conn_data_t) failed with code %d/%s",
                errno, strerror(errno));
        /* reject connection */
        return(1);
    }

    /* nDPI */
    if((data->ndpi_flow = calloc(1, SIZEOF_FLOW_STRUCT)) == NULL) {
        log_android(ANDROID_LOG_ERROR, "ndpi_flow_malloc failed");
        free_ndpi(data);
    }

    if((data->src_id = calloc(1, SIZEOF_ID_STRUCT)) == NULL) {
        log_android(ANDROID_LOG_ERROR, "ndpi_malloc(src_id) failed");
        free_ndpi(data);
    }

    if((data->dst_id = calloc(1, SIZEOF_ID_STRUCT)) == NULL) {
        log_android(ANDROID_LOG_ERROR, "ndpi_malloc(dst_id) failed");
        free_ndpi(data);
    }

    data->incr_id = proxy->incr_id++;
    data->first_seen = data->last_seen = time(NULL);
    data->uid = resolve_uid(proxy, conn_info);
    *conn_data = data;

    /* accept connection */
    return(0);
}

/* ******************************************************* */

static void free_connection_data(conn_data_t *data) {
    if(!data)
        return;

    free_ndpi(data);

    if(data->info)
        free(data->info);

    if(data->url)
        free(data->url);

    free(data);
}

/* ******************************************************* */

static void destroy_connection(zdtun_t *tun, const zdtun_conn_t *conn_info) {
    conn_data_t *data = (conn_data_t*) conn_info->user_data;

    if(!data) {
        log_android(ANDROID_LOG_ERROR, "Missing user_data in connection");
        return;
    }

    if(!data->notified) {
        /* Connection was not notified to java. Copy it to a special list of pending connections */
        vpnproxy_data_t *proxy = ((vpnproxy_data_t*)zdtun_userdata(tun));

        if(proxy->cur_notif_pending >= proxy->notif_pending_size) {
            /* Extend array */
            if(proxy->notif_pending_size == 0)
                proxy->notif_pending_size = 8;
            else
                proxy->notif_pending_size *= 2;

            proxy->notif_pending = realloc(proxy->notif_pending, proxy->notif_pending_size * sizeof(zdtun_conn_t));

            if(proxy->notif_pending == NULL) {
                log_android(ANDROID_LOG_FATAL, "realloc(notif_pending) failed");
                return;
            }
        }

        memcpy(&proxy->notif_pending[proxy->cur_notif_pending], conn_info, sizeof(zdtun_conn_t));
        proxy->cur_notif_pending++;

        log_android(ANDROID_LOG_DEBUG, "Pending conns: %u/%u", proxy->cur_notif_pending, proxy->notif_pending_size);

        /* Will free the data in sendConnectionsDump */
        return;
    }

    free_connection_data(data);
}

/* ******************************************************* */

/*
 * If the packet contains a DNS request then rewrite server address
 * with public DNS server.
 *
 *
 * Check if the packet contains a DNS response and rewrite destination address to let the packet
 * go into the VPN network stack. This assumes that the DNS request was modified by \_query.
 */
static int check_dns(struct vpnproxy_data *proxy, char *packet, size_t size, bool query) {
    struct ip *iphdr = (struct ip*) packet;
    struct udphdr *udp;
    struct dns_packet *dns_data;
    int ip_size;
    int udp_length;
    int dns_length;

    if(size <= sizeof(struct ip))
        return(0);

    ip_size = 4 * iphdr->ip_hl;

    // TODO support TCP
    if((size <= ip_size + sizeof(struct udphdr)) || (iphdr->ip_p != IPPROTO_UDP))
        return(0);

    udp = (struct udphdr*)(packet + ip_size);
    udp_length = ntohs(udp->len);

    if(size < ip_size + udp_length)
        return(0);

    dns_length = udp_length - sizeof(struct udphdr);
    dns_data = (struct dns_packet*)(packet + ip_size + sizeof(struct udphdr));

    if(dns_length < sizeof(struct dns_packet))
        return(0);

    if(query) {
        if((ntohs(udp->uh_dport) != 53) ||
            (iphdr->ip_dst.s_addr != proxy->vpn_dns) || (iphdr->ip_src.s_addr != proxy->vpn_ipv4))
            // Not VPN DNS
            return(0);

        if((dns_data->flags & DNS_FLAGS_MASK) != DNS_TYPE_REQUEST)
            return(0);
    } else {
        if((ntohs(udp->uh_sport) != 53) ||
            (iphdr->ip_dst.s_addr != proxy->vpn_ipv4))
            return(0);

        if((dns_data->flags & DNS_FLAGS_MASK) != DNS_TYPE_RESPONSE)
            return(0);
    }

    log_android(ANDROID_LOG_DEBUG, "Detected DNS[%u] query=%u", dns_length, query);

    if(query) {
        /*
         * Direct the packet to the public DNS server. Checksum recalculation is not strictly necessary
         * here as zdtun will proxy the connection, however since traffic will be dumped as PCAP, having a
         * correct checksum is better.
         */
        iphdr->ip_dst.s_addr = proxy->public_dns;
    } else {
        /*
         * Change the origin of the packet back to the virtual DNS server
         */
        iphdr->ip_src.s_addr = proxy->vpn_dns;
    }

    udp->check = 0; // UDP checksum is not mandatory
    iphdr->ip_sum = 0;
    iphdr->ip_sum = ip_checksum(iphdr, ip_size);

    return(1);
}

/* ******************************************************* */

static int net2tap(zdtun_t *tun, char *pkt_buf, ssize_t pkt_size, const zdtun_conn_t *conn_info) {
    vpnproxy_data_t *proxy = (vpnproxy_data_t*) zdtun_userdata(tun);

    check_dns(proxy, pkt_buf, pkt_size, 0 /* reply */);

    // TODO return value check
    write(proxy->tapfd, pkt_buf, pkt_size);
    return 0;
}

/* ******************************************************* */

static void sendCaptureStats(vpnproxy_data_t *proxy) {
    JNIEnv *env = proxy->env;
    capture_stats_t *stats = &proxy->capture_stats;

    (*env)->CallVoidMethod(env, proxy->vpn_service, mids.sendCaptureStats, stats->sent_bytes, stats->rcvd_bytes,
            stats->sent_pkts, stats->rcvd_pkts);
    jniCheckException(env);
}

/* ******************************************************* */

typedef struct dump_data {
    jobjectArray connections;
    jint idx;
    jint num_connections;
} dump_data_t;

static int connection_dumper(zdtun_t *tun, const zdtun_conn_t *conn_info, void *user_data) {
    char srcip[64], dstip[64];
    struct in_addr addr;
    dump_data_t *dump_data = (dump_data_t *)user_data;
    conn_data_t *data = (conn_data_t *)conn_info->user_data;
    vpnproxy_data_t *proxy = (vpnproxy_data_t*) zdtun_userdata(tun);
    JNIEnv *env = proxy->env;

    addr.s_addr = conn_info->src_ip;
    strncpy(srcip, inet_ntoa(addr), sizeof(srcip));
    addr.s_addr = conn_info->dst_ip;
    strncpy(dstip, inet_ntoa(addr), sizeof(dstip));

#if 0
    log_android(ANDROID_LOG_INFO, "DUMP: [proto=%d]: %s:%u -> %s:%u [%d]",
                        conn_info->ipproto,
                        srcip, ntohs(conn_info->src_port),
                        dstip, ntohs(conn_info->dst_port),
                        data->uid);
#endif

    if(dump_data->idx >= MAX_NUM_CONNECTIONS_DUMPED) {
        log_android(ANDROID_LOG_INFO, "Max connections reached, %d skipped.", (dump_data->num_connections - dump_data->idx));

        /* Abort */
        return(1);
    } else if(dump_data->idx >= dump_data->num_connections) {
        /* Cannot proceed as dump_data->connections would overflow */
        log_android(ANDROID_LOG_ERROR, "Connections count is inconsistent! num_connections=%d",
                dump_data->num_connections);

        /* Abort */
        return(1);
    }

    jobject info_string = (*env)->NewStringUTF(env, data->info ? data->info : "");
    jobject url_string = (*env)->NewStringUTF(env, data->url ? data->url : "");
    jobject proto_string = (*env)->NewStringUTF(env, getL7ProtoName(proxy->ndpi, data->l7proto));
    jobject src_string = (*env)->NewStringUTF(env, srcip);
    jobject dst_string = (*env)->NewStringUTF(env, dstip);
    jobject conn_descriptor = (*env)->NewObject(env, cls.conn, mids.connInit);

    /* NOTE: as an alternative to pass all the params into the constructor, GetFieldID and
     * SetIntField like methods could be used. */
    (*env)->CallVoidMethod(env, conn_descriptor, mids.connSetData,
            src_string, dst_string, info_string, url_string, proto_string,
            conn_info->ipproto, ntohs(conn_info->src_port), ntohs(conn_info->dst_port),
            data->first_seen, data->last_seen, data->sent_bytes, data->rcvd_bytes,
            data->sent_pkts, data->rcvd_pkts, data->uid, data->incr_id);
    jniCheckException(env);

    /* Add the connection to the array */
    (*env)->SetObjectArrayElement(env, dump_data->connections, dump_data->idx++, conn_descriptor);
    jniCheckException(env);
    data->notified = true;

    DeleteLocalRef(env, info_string);
    DeleteLocalRef(env, url_string);
    DeleteLocalRef(env, proto_string);
    DeleteLocalRef(env, src_string);
    DeleteLocalRef(env, dst_string);
    DeleteLocalRef(env, conn_descriptor);

    /* Continue */
    return(0);
}

/* Perform a full dump of the active connections */
static void sendConnectionsDump(zdtun_t *tun, vpnproxy_data_t *proxy) {
    JNIEnv *env = proxy->env;
    dump_data_t dump_data = {0};

    dump_data.num_connections = zdtun_get_num_connections(tun) + proxy->cur_notif_pending;

    dump_data.connections = (*env)->NewObjectArray(env, dump_data.num_connections, cls.conn, NULL);
    if(jniCheckException(env))
        return;

    /* Add connections to the array */
    zdtun_iter_connections(tun, connection_dumper, &dump_data);

    /* Handle possibly pending data */
    if(proxy->cur_notif_pending > 0) {
        log_android(ANDROID_LOG_DEBUG, "Processing %u pending conns", proxy->cur_notif_pending);

        for(int i = 0; i < proxy->cur_notif_pending; i++) {
            connection_dumper(tun, &proxy->notif_pending[i], &dump_data);
            free_connection_data(proxy->notif_pending[i].user_data);
        }

        /* Empty the pending notifications list */
        free(proxy->notif_pending);
        proxy->notif_pending = NULL;
        proxy->cur_notif_pending = proxy->notif_pending_size = 0;
    }

    /* Send the dump */
    (*env)->CallVoidMethod(env, proxy->vpn_service, mids.sendConnectionsDump, dump_data.connections);
    jniCheckException(env);

    DeleteLocalRef(env, dump_data.connections);
}

/* ******************************************************* */

static void notifyServiceStatus(vpnproxy_data_t *proxy, const char *status) {
    JNIEnv *env = proxy->env;
    capture_stats_t *stats = &proxy->capture_stats;
    const char *value = NULL;
    jstring status_str;

    status_str = (*env)->NewStringUTF(env, status);

    (*env)->CallVoidMethod(env, proxy->vpn_service, mids.sendServiceStatus, status_str);
    jniCheckException(env);

    DeleteLocalRef(env, status_str);
}

/* ******************************************************* */

static int connect_dumper(vpnproxy_data_t *proxy) {
    if(proxy->pcap_dump.enabled) {
        dumper_socket = socket(AF_INET, proxy->pcap_dump.tcp_socket ? SOCK_STREAM : SOCK_DGRAM, 0);

        if (!dumper_socket) {
            log_android(ANDROID_LOG_ERROR,
                                "could not open UDP pcap dump socket [%d]: %s", errno,
                                strerror(errno));
            return(-1);
        }

        protectSocket(proxy, dumper_socket);

        if(proxy->pcap_dump.tcp_socket) {
            struct sockaddr_in servaddr = {0};
            servaddr.sin_family = AF_INET;
            servaddr.sin_port = proxy->pcap_dump.collector_port;
            servaddr.sin_addr.s_addr = proxy->pcap_dump.collector_addr;

            if(connect(dumper_socket, (struct sockaddr*)&servaddr, sizeof(servaddr)) < 0) {
                log_android(ANDROID_LOG_ERROR,
                                    "connection to the PCAP receiver failed [%d]: %s", errno,
                                    strerror(errno));
                return(-2);
            }
        }
    }

    return(0);
}

/* ******************************************************* */

static int running = 0;

static int run_tun(JNIEnv *env, jclass vpn, int tapfd, jint sdk) {
    zdtun_t *tun;
    char buffer[32767];
    time_t last_connections_dump = (time(NULL) * 1000) - CONNECTION_DUMP_UPDATE_FREQUENCY_MS + 1000 /* update in a second */;

    if(!jni_resolved) {
        jclass vpn_class = (*env)->GetObjectClass(env, vpn);

        /* Classes */
        cls.vpn_service = vpn_class;
        cls.conn = jniFindClass(env, "com/emanuelef/remote_capture/ConnDescriptor");

        /* Methods */
        mids.getApplicationByUid = jniGetMethodID(env, vpn_class, "getApplicationByUid", "(I)Ljava/lang/String;"),
        mids.protect = jniGetMethodID(env, vpn_class, "protect", "(I)Z");
        mids.dumpPcapData = jniGetMethodID(env, vpn_class, "dumpPcapData", "([B)V");
        mids.sendCaptureStats = jniGetMethodID(env, vpn_class, "sendCaptureStats", "(JJII)V");
        mids.sendConnectionsDump = jniGetMethodID(env, vpn_class, "sendConnectionsDump", "([Lcom/emanuelef/remote_capture/ConnDescriptor;)V");
        mids.sendServiceStatus = jniGetMethodID(env, vpn_class, "sendServiceStatus", "(Ljava/lang/String;)V");
        mids.connInit = jniGetMethodID(env, vpn_class, "<init>", "()V");
        mids.connSetData = jniGetMethodID(env, cls.conn, "setData",
                /* NOTE: must match ConnDescriptor::setData */
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIJJJJIIII)V");

        jni_resolved = true;
    }

    vpnproxy_data_t proxy = {
            .tapfd = tapfd,
            .sdk = sdk,
            .env = env,
            .vpn_service = vpn,
            .vpn_ipv4 = getIPv4Pref(&proxy, "getVpnIPv4"),
            .vpn_dns = getIPv4Pref(&proxy, "getVpnDns"),
            .public_dns = getIPv4Pref(&proxy, "getPublicDns"),
            .incr_id = 0,
            .uid_filter = getIntPref(&proxy, "getPcapUidFilter"),
            .capture_unknown_app_traffic = getIntPref(&proxy, "getCaptureUnknownTraffic"),
            .java_dump = {
                .enabled = getIntPref(&proxy, "dumpPcapToJava"),
            },
            .pcap_dump = {
                .collector_addr = getIPv4Pref(&proxy, "getPcapCollectorAddress"),
                .collector_port = htons(getIntPref(&proxy, "getPcapCollectorPort")),
                .tcp_socket = false,
                .enabled = getIntPref(&proxy, "dumpPcapToUdp"),
            },
    };

    zdtun_callbacks_t callbacks = {
            .send_client = net2tap,
            .account_packet = account_packet,
            .on_socket_open = protectSocketCallback,
            .on_connection_open = handle_new_connection,
            .on_connection_close = destroy_connection,
    };

    /* Important: init global state every time. Android may reuse the service. */
    dumper_socket = -1;
    send_header = true;
    running = 1;

    /* nDPI */
    proxy.ndpi = init_ndpi();

    if(proxy.ndpi == NULL) {
        log_android(ANDROID_LOG_FATAL, "nDPI initialization failed");
        return(-1);
    }

    signal(SIGPIPE, SIG_IGN);

    // Set blocking
    int flags = fcntl(tapfd, F_GETFL, 0);
    if (flags < 0 || fcntl(tapfd, F_SETFL, flags & ~O_NONBLOCK) < 0) {
        log_android(ANDROID_LOG_FATAL, "fcntl ~O_NONBLOCK error [%d]: %s", errno,
                            strerror(errno));
        return(-1);
    }

    // TODO use EPOLL?
    tun = zdtun_init(&callbacks, &proxy);

    if(tun == NULL) {
        log_android(ANDROID_LOG_FATAL, "zdtun_init failed");
        return(-2);
    }

    log_android(ANDROID_LOG_DEBUG, "Starting packet loop [tapfd=%d]", tapfd);

    notifyServiceStatus(&proxy, "started");

    if(proxy.pcap_dump.enabled) {
        if(connect_dumper(&proxy) < 0)
            running = false;
    }

    if(proxy.java_dump.enabled) {
        proxy.java_dump.buffer = malloc(JAVA_PCAP_BUFFER_SIZE);
        proxy.java_dump.buffer_idx = 0;

        if(!proxy.java_dump.buffer) {
            log_android(ANDROID_LOG_ERROR, "malloc(java_dump.buffer) failed with code %d/%s",
                                errno, strerror(errno));
            running = false;
        }
    }

    while(running) {
        int max_fd;
        fd_set fdset;
        fd_set wrfds;
        ssize_t size;
        u_int64_t now_ms;
        struct timeval now_tv;
        struct timeval timeout = {.tv_sec = 0, .tv_usec = 500*1000}; // wake every 500 ms

        zdtun_fds(tun, &max_fd, &fdset, &wrfds);

        FD_SET(tapfd, &fdset);
        max_fd = max(max_fd, tapfd);

        select(max_fd + 1, &fdset, &wrfds, NULL, &timeout);

        if(!running)
            break;

        gettimeofday(&now_tv, NULL);
        now_ms = now_tv.tv_sec * 1000 + now_tv.tv_usec / 1000;
        proxy.now_ms = now_ms;

        if(FD_ISSET(tapfd, &fdset)) {
            /* Packet from VPN */
            size = read(tapfd, buffer, sizeof(buffer));

            if(size > 0) {
                zdtun_conn_t conn_info;
                int rc;

                proxy.dns_changed = check_dns(&proxy, buffer, size, 1 /* query */) != 0;

                /* Forward the packet and retrieve connection information
                 * The uid userdata will be set in on_connection_open. */
                if((rc = zdtun_forward(tun, buffer, size, &conn_info)) != 0)
                    /* NOTE: rc -1 is currently returned for unhandled non-IPv4 flows */
                    log_android(ANDROID_LOG_DEBUG, "zdtun_forward failed with code %d", rc);
            } else if (size < 0)
                log_android(ANDROID_LOG_ERROR, "recv(tapfd) returned error [%d]: %s", errno, strerror(errno));
        } else
            zdtun_handle_fd(tun, &fdset, &wrfds);

        if(proxy.capture_stats.new_stats
         && ((now_ms - proxy.capture_stats.last_update_ms) >= CAPTURE_STATS_UPDATE_FREQUENCY_MS)) {
            sendCaptureStats(&proxy);
            proxy.capture_stats.new_stats = false;
            proxy.capture_stats.last_update_ms = now_ms;
        } else if((now_ms - last_connections_dump) >= CONNECTION_DUMP_UPDATE_FREQUENCY_MS) {
            sendConnectionsDump(tun, &proxy);
            last_connections_dump = now_ms;
        } else if((proxy.java_dump.buffer_idx > 0)
         && (now_ms - proxy.java_dump.last_dump_ms) >= MAX_JAVA_DUMP_DELAY_MS) {
            javaPcapDump(tun, &proxy);
        }
    }

    log_android(ANDROID_LOG_DEBUG, "Stopped packet loop");

    ztdun_finalize(tun);
    ndpi_exit_detection_module(proxy.ndpi);

    /* Free possible pending data */
    if(proxy.cur_notif_pending > 0) {
        for(int i = 0; i < proxy.cur_notif_pending; i++) {
            free_connection_data(proxy.notif_pending[i].user_data);
        }

        /* Empty the pending notifications list */
        free(proxy.notif_pending);
    }

    if(dumper_socket > 0) {
        close(dumper_socket);
        dumper_socket = -1;
    }

    if(proxy.java_dump.buffer) {
        free(proxy.java_dump.buffer);
        proxy.java_dump.buffer = NULL;
    }

    notifyServiceStatus(&proxy, "stopped");
    return(0);
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_stopPacketLoop(JNIEnv *env, jclass type) {
    /* NOTE: the select on the packets loop uses a timeout to wake up periodically */
    running = 0;
}

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_runPacketLoop(JNIEnv *env, jclass type, jint tapfd,
                                                              jobject vpn, jint sdk) {

    run_tun(env, vpn, tapfd, sdk);
    close(tapfd);
}
