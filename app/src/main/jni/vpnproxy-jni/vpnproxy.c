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

#include <netinet/udp.h>
#include <netinet/ip.h>
#include <ndpi_typedefs.h>
#include "jni_helpers.c"
#include "utils.c"
#include "ndpi_master_protos.c"
#include "vpnproxy.h"
#include "pcap.h"
#include "ndpi_protocol_ids.h"

#define CAPTURE_STATS_UPDATE_FREQUENCY_MS 300
#define CONNECTION_DUMP_UPDATE_FREQUENCY_MS 1000
#define MAX_JAVA_DUMP_DELAY_MS 1000
#define MAX_DPI_PACKETS 12
#define JAVA_PCAP_BUFFER_SIZE (512*1024) // 512K
#define PERIODIC_PURGE_TIMEOUT_MS 5000

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

typedef struct mitm_proxy_hdr {
    uint32_t dst_ip;
    uint16_t dst_port;
} __attribute__((packed)) mitm_proxy_hdr_t;

/* ******************************************************* */

typedef struct jni_methods {
    jmethodID getApplicationByUid;
    jmethodID protect;
    jmethodID dumpPcapData;
    jmethodID sendConnectionsDump;
    jmethodID connInit;
    jmethodID connSetData;
    jmethodID sendServiceStatus;
    jmethodID sendStatsDump;
    jmethodID statsInit;
    jmethodID statsSetData;
} jni_methods_t;

typedef struct jni_classes {
    jclass vpn_service;
    jclass conn;
    jclass stats;
} jni_classes_t;

static jni_classes_t cls;
static jni_methods_t mids;
static bool running = false;
static bool dump_vpn_stats_now = false;
static bool dump_capture_stats_now = false;
static ndpi_protocol_bitmask_struct_t masterProtos;

/* TCP/IP packet to hold the mitmproxy header */
static char mitmproxy_pkt_buffer[] = {
    "\x45\x00" \

    /* Total length: 52 + 6 (sizeof mitm_proxy_hdr_t) */
    "\x00\x3a" \

    "\xb8\x9e\x40\x00\x40\x06\xf7\xe1\x00\x00\x00\x00\x00\x00" \
    "\x00\x00\x00\x00\x00\x00\x65\xbf\xc2\xaf\x08\x93\x36\x09\x80\x18" \
    "\x01\xf5\x00\x00\x00\x00\x01\x01\x08\x0a\x6c\xe2\x4f\x95\x4a\xe0" \
    "\x92\x51" \

    /* TCP payload */
    "\x01\x02\x03\x04\x05\x06"
};
static zdtun_pkt_t mitm_pkt;

/* ******************************************************* */

/* NOTE: these must be reset during each run, as android may reuse the service */
static int dumper_socket;
static bool send_header;

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

static void conns_add(conn_array_t *arr, const zdtun_conn_t *conn) {
    if(arr->cur_items >= arr->size) {
        /* Extend array */
        arr->size = (arr->size == 0) ? 8 : (arr->size * 2);
        arr->items = realloc(arr->items, arr->size * sizeof(vpn_conn_t));

        if(arr->items == NULL) {
            log_android(ANDROID_LOG_FATAL, "realloc(conn_array_t) (%d items) failed", arr->size);
            return;
        }
    }

    vpn_conn_t *slot = &arr->items[arr->cur_items++];
    slot->tuple = *zdtun_conn_get_5tuple(conn);
    slot->data = zdtun_conn_get_userdata(conn);
}

/* ******************************************************* */

static void conns_clear(conn_array_t *arr, bool free_all) {
    if(arr->items) {
        for(int i=0; i < arr->cur_items; i++) {
            vpn_conn_t *slot = &arr->items[i];

            if(slot->data && (slot->data->closed || free_all))
                free_connection_data(slot->data);
        }

        free(arr->items);
        arr->items = NULL;
    }

    arr->size = 0;
    arr->cur_items = 0;
}

/* ******************************************************* */

static u_int32_t getIPv4Pref(JNIEnv *env, jobject vpn_inst, const char *key) {
    struct in_addr addr = {0};

    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()Ljava/lang/String;");
    jstring obj = (*env)->CallObjectMethod(env, vpn_inst, midMethod);

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

static jint getIntPref(JNIEnv *env, jobject vpn_inst, const char *key) {
    jint value;
    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()I");

    value = (*env)->CallIntMethod(env, vpn_inst, midMethod);
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

static char* getApplicationByUid(vpnproxy_data_t *proxy, jint uid, char *buf, int bufsize) {
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

const char *getProtoName(struct ndpi_detection_module_struct *mod, ndpi_protocol l7proto, int ipproto) {
    int proto = l7proto.master_protocol;

    if((proto == NDPI_PROTOCOL_UNKNOWN) || !NDPI_ISSET(&masterProtos, proto)) {
        // Return the L3 protocol
        return zdtun_proto2str(ipproto);
    }

    return ndpi_get_proto_name(mod, proto);
}

/* ******************************************************* */

static void process_ndpi_packet(conn_data_t *data, vpnproxy_data_t *proxy, const char *packet,
        int size, uint8_t from_tap) {
    bool giveup = ((data->sent_pkts + data->rcvd_pkts) >= MAX_DPI_PACKETS);

    data->l7proto = ndpi_detection_process_packet(proxy->ndpi, data->ndpi_flow, (const u_char *)packet,
            size, data->last_seen,
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
                    data->info = strdup((char*)data->ndpi_flow->host_server_name);
                break;
            case NDPI_PROTOCOL_HTTP:
                if(data->ndpi_flow->host_server_name[0])
                    data->info = strdup((char*)data->ndpi_flow->host_server_name);
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

static void javaPcapDump(vpnproxy_data_t *proxy) {
    JNIEnv *env = proxy->env;

    log_android(ANDROID_LOG_DEBUG, "Exporting a %d B PCAP buffer", proxy->java_dump.buffer_idx);

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

static bool shouldIgnoreConn(vpnproxy_data_t *proxy, const zdtun_5tuple_t *tuple, const conn_data_t *data) {
#if 0
    int uid = data.uid;
    bool is_unknown_app = ((uid == UID_UNKNOWN) || (uid == 1051 /* netd DNS resolver */));

    if(((proxy->uid_filter != UID_UNKNOWN) && (proxy->uid_filter != uid))
        && (!is_unknown_app || !proxy->capture_unknown_app_traffic))
        return true;
#endif

    // ignore some internal communications, e.g. DNS-over-TLS check on port 853
    if((tuple->dst_ip == proxy->vpn_dns) && (ntohs(tuple->dst_port) != 53))
        return true;

    return false;
}

/* ******************************************************* */

static void account_packet(zdtun_t *tun, const char *packet, int size, uint8_t from_tap, const zdtun_conn_t *conn_info) {
    struct sockaddr_in servaddr = {0};
    conn_data_t *data = zdtun_conn_get_userdata(conn_info);
    vpnproxy_data_t *proxy;

    if(!data) {
        log_android(ANDROID_LOG_ERROR, "Missing user_data in connection");
        return;
    }

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

    if(shouldIgnoreConn(proxy, zdtun_conn_get_5tuple(conn_info), data)) {
        //log_android(ANDROID_LOG_DEBUG, "Ignoring connection: UID=%d [filter=%d]", data->uid, proxy->uid_filter);
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

    if(!data->pending_notification) {
        conns_add(&proxy->conns_updates, conn_info);
        data->pending_notification = true;
    }

    if(proxy->java_dump.buffer) {
        int tot_size = size + (int) sizeof(pcaprec_hdr_s);

        if((JAVA_PCAP_BUFFER_SIZE - proxy->java_dump.buffer_idx) <= tot_size) {
            // Flush the buffer
            javaPcapDump(proxy);
        }

        if((JAVA_PCAP_BUFFER_SIZE - proxy->java_dump.buffer_idx) <= tot_size)
            log_android(ANDROID_LOG_ERROR, "Invalid buffer size [size=%d, idx=%d, tot_size=%d]", JAVA_PCAP_BUFFER_SIZE, proxy->java_dump.buffer_idx, tot_size);
        else
            proxy->java_dump.buffer_idx += dump_pcap_rec((u_char*)proxy->java_dump.buffer + proxy->java_dump.buffer_idx, (u_char*)packet, size);
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

static int resolve_uid(vpnproxy_data_t *proxy, const zdtun_5tuple_t *conn_info) {
    char buf[256];
    jint uid;

    zdtun_5tuple2str(conn_info, buf, sizeof(buf));
    uid = get_uid(proxy, conn_info);

    if(uid >= 0) {
        char appbuf[128];

        if(uid == 0)
            strncpy(appbuf, "ROOT", sizeof(appbuf));
        else if(uid == 1051)
            strncpy(appbuf, "netd", sizeof(appbuf));
        else
            getApplicationByUid(proxy, uid, appbuf, sizeof(appbuf));

        log_android(ANDROID_LOG_INFO, "%s [%d/%s]", buf, uid, appbuf);
    } else {
        uid = UID_UNKNOWN;
        log_android(ANDROID_LOG_WARN, "%s => UID not found!", buf);
    }

    return(uid);
}

/* ******************************************************* */

static int handle_new_connection(zdtun_t *tun, zdtun_conn_t *conn_info) {
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

    data->first_seen = data->last_seen = time(NULL);
    data->uid = resolve_uid(proxy, zdtun_conn_get_5tuple(conn_info));

    zdtun_conn_set_userdata(conn_info, data);

    if(!shouldIgnoreConn(proxy, zdtun_conn_get_5tuple(conn_info), data)) {
        // Important: only set the incr_id on registered connections since
        // ConnectionsRegister::connectionsUpdates does not allow gaps
        data->incr_id = proxy->incr_id++;

        conns_add(&proxy->new_conns, conn_info);
        data->pending_notification = true;
    }

    /* accept connection */
    return(0);
}

/* ******************************************************* */

static void destroy_connection(zdtun_t *tun, const zdtun_conn_t *conn_info) {
    vpnproxy_data_t *proxy = (vpnproxy_data_t*) zdtun_userdata(tun);
    conn_data_t *data = zdtun_conn_get_userdata(conn_info);

    if(!data) {
        log_android(ANDROID_LOG_ERROR, "Missing user_data in connection");
        return;
    }

    /* Will free the other data in sendConnectionsDump */
    free_ndpi(data);
    data->closed = true;

    if(!data->pending_notification && !shouldIgnoreConn(proxy, zdtun_conn_get_5tuple(conn_info), data)) {
        // Send last notification
        conns_add(&proxy->conns_updates, conn_info);
        data->pending_notification = true;
    }
}

/* ******************************************************* */

/*
 * If the packet contains a DNS request then rewrite server address
 * with public DNS server.
 */
static int check_dns_req_dnat(struct vpnproxy_data *proxy, zdtun_pkt_t *pkt, zdtun_conn_t *conn) {
    struct dns_packet *dns_data;
    int dns_length;

    // TODO support TCP
    if(pkt->tuple.ipproto != IPPROTO_UDP)
        return(0);

    dns_length = pkt->l7_len;
    dns_data = (struct dns_packet*) pkt->l7;

    if(dns_length < sizeof(struct dns_packet))
        return(0);

    if((ntohs(pkt->udp->uh_dport) != 53) ||
        (pkt->tuple.dst_ip != proxy->vpn_dns) || (pkt->tuple.src_ip != proxy->vpn_ipv4))
        // Not VPN DNS
        return(0);

    if((dns_data->flags & DNS_FLAGS_MASK) != DNS_TYPE_REQUEST)
        return(0);

    log_android(ANDROID_LOG_DEBUG, "Detected DNS query[%u]", dns_length);

    /*
     * Direct the packet to the public DNS server. Checksum recalculation is not strictly necessary
     * here as zdtun will proxy the connection.
     */
    zdtun_conn_dnat(conn, proxy->public_dns, 0);
    proxy->num_dns_requests++;

    return(1);
}

/* ******************************************************* */

/*
 * Check if the packet should be redirected to the mitmproxy
 */
static void check_tls_mitm(zdtun_t *tun, struct vpnproxy_data *proxy, zdtun_pkt_t *pkt, zdtun_conn_t *conn) {
    conn_data_t *data = zdtun_conn_get_userdata(conn);

    if(shouldIgnoreConn(proxy, zdtun_conn_get_5tuple(conn), data))
        return;

    if(pkt->tuple.ipproto == IPPROTO_TCP) {
        uint32_t mitm_ip = proxy->tls_decryption.proxy_ip;
        uint16_t mitm_port = proxy->tls_decryption.proxy_port;

        bool is_new = ((data->sent_pkts + data->rcvd_pkts) == 0);

        if(is_new) {
            uint16_t port = ntohs(pkt->tuple.dst_port);

            if (port == 443) {
                zdtun_conn_dnat(conn, mitm_ip, mitm_port);
                data->mitm_header_needed = true;
            }
        } else if(data->mitm_header_needed && (pkt->l7_len > 0)) {
            /* First L7 packet, send the mitmproxy header first */
            mitm_proxy_hdr_t *mitm = (mitm_proxy_hdr_t*) mitm_pkt.l7;

            /* Fix the packet with the correct peers */
            mitm_pkt.tuple.src_ip = mitm_pkt.ip->saddr = pkt->ip->saddr;
            mitm_pkt.tuple.dst_ip = mitm_pkt.ip->daddr = mitm_ip;
            mitm_pkt.tuple.src_port = mitm_pkt.tcp->th_sport = pkt->tcp->th_sport;
            mitm_pkt.tuple.dst_port = mitm_pkt.tcp->th_dport = mitm_port;

            /* Send the original (pre-nat) IP and port */
            mitm->dst_ip = pkt->tuple.dst_ip;
            mitm->dst_port = pkt->tuple.dst_port;
            zdtun_send_oob(tun, &mitm_pkt, conn);

            data->mitm_header_needed = false;
        }
    }
}

/* ******************************************************* */

static int net2tap(zdtun_t *tun, char *pkt_buf, int pkt_size, const zdtun_conn_t *conn_info) {
    if(!running)
        return 0;

    vpnproxy_data_t *proxy = (vpnproxy_data_t*) zdtun_userdata(tun);

    int rv = write(proxy->tapfd, pkt_buf, pkt_size);

    if(rv < 0) {
        if(errno == ENOBUFS) {
            char buf[256];

            // Do not abort, the connection will be terminated
            log_android(ANDROID_LOG_ERROR, "Got ENOBUFS %s", zdtun_5tuple2str(zdtun_conn_get_5tuple(conn_info), buf, sizeof(buf)));
        } else if(errno == EIO) {
            log_android(ANDROID_LOG_INFO, "Got I/O error (terminating?)");
            running = false;
        } else {
            log_android(ANDROID_LOG_FATAL,
                        "tap write (%d) failed [%d]: %s", pkt_size, errno, strerror(errno));
            running = false;
        }
    } else if(rv != pkt_size) {
        log_android(ANDROID_LOG_FATAL,
                    "partial tap write (%d / %d)", rv, pkt_size);
        rv = -1;
    } else
        rv = 0;

    return rv;
}

/* ******************************************************* */

static int dumpConnection(vpnproxy_data_t *proxy, const vpn_conn_t *conn, jobject arr, int idx) {
    char srcip[64], dstip[64];
    struct in_addr addr;
    JNIEnv *env = proxy->env;
    const zdtun_5tuple_t *conn_info = &conn->tuple;
    const conn_data_t *data = conn->data;
    int rv = 0;

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

    jobject info_string = (*env)->NewStringUTF(env, data->info ? data->info : "");
    jobject url_string = (*env)->NewStringUTF(env, data->url ? data->url : "");
    jobject proto_string = (*env)->NewStringUTF(env, getProtoName(proxy->ndpi, data->l7proto, conn_info->ipproto));
    jobject src_string = (*env)->NewStringUTF(env, srcip);
    jobject dst_string = (*env)->NewStringUTF(env, dstip);
    jobject conn_descriptor = (*env)->NewObject(env, cls.conn, mids.connInit);

    if((conn_descriptor != NULL) && !jniCheckException(env)) {
        /* NOTE: as an alternative to pass all the params into the constructor, GetFieldID and
         * SetIntField like methods could be used. */
        (*env)->CallVoidMethod(env, conn_descriptor, mids.connSetData,
                               src_string, dst_string, info_string, url_string, proto_string,
                               conn_info->ipproto, ntohs(conn_info->src_port),
                               ntohs(conn_info->dst_port),
                               data->first_seen, data->last_seen, data->sent_bytes,
                               data->rcvd_bytes, data->sent_pkts,
                               data->rcvd_pkts, data->uid, data->incr_id, data->closed);
        if(jniCheckException(env))
            rv = -1;
        else {
            /* Add the connection to the array */
            (*env)->SetObjectArrayElement(env, arr, idx, conn_descriptor);

            if(jniCheckException(env))
                rv = -1;
        }

        DeleteLocalRef(env, conn_descriptor);
    } else {
        log_android(ANDROID_LOG_ERROR, "NewObject(ConnectionDescriptor) failed");
        rv = -1;
    }

    DeleteLocalRef(env, info_string);
    DeleteLocalRef(env, url_string);
    DeleteLocalRef(env, proto_string);
    DeleteLocalRef(env, src_string);
    DeleteLocalRef(env, dst_string);

    return rv;
}

/* Perform a full dump of the active connections */
static void sendConnectionsDump(zdtun_t *tun, vpnproxy_data_t *proxy) {
    if((proxy->new_conns.cur_items == 0) && (proxy->conns_updates.cur_items == 0))
        return;

    log_android(ANDROID_LOG_DEBUG, "sendConnectionsDump: new=%d, updates=%d", proxy->new_conns.cur_items, proxy->conns_updates.cur_items);

    JNIEnv *env = proxy->env;
    jobject new_conns = (*env)->NewObjectArray(env, proxy->new_conns.cur_items, cls.conn, NULL);
    jobject conns_updates = (*env)->NewObjectArray(env, proxy->conns_updates.cur_items, cls.conn, NULL);

    if((new_conns == NULL) || (conns_updates == NULL) || jniCheckException(env)) {
        log_android(ANDROID_LOG_ERROR, "NewObjectArray() failed");
        goto cleanup;
    }

    // New connections
    for(int i=0; i<proxy->new_conns.cur_items; i++) {
        vpn_conn_t *conn = &proxy->new_conns.items[i];
        conn->data->pending_notification = false;

        if(dumpConnection(proxy, conn, new_conns, i) < 0)
            goto cleanup;
    }

    // Updated connections
    for(int i=0; i<proxy->conns_updates.cur_items; i++) {
        vpn_conn_t *conn = &proxy->conns_updates.items[i];
        conn->data->pending_notification = false;

        if(dumpConnection(proxy, conn, conns_updates, i) < 0)
            goto cleanup;
    }

    /* Send the dump */
    (*env)->CallVoidMethod(env, proxy->vpn_service, mids.sendConnectionsDump, new_conns, conns_updates);
    jniCheckException(env);

cleanup:
    conns_clear(&proxy->new_conns, false);
    conns_clear(&proxy->conns_updates, false);

    DeleteLocalRef(env, new_conns);
    DeleteLocalRef(env, conns_updates);
}

/* ******************************************************* */

static void sendVPNStats(const vpnproxy_data_t *proxy, const zdtun_statistics_t *stats) {
    JNIEnv *env = proxy->env;
    const capture_stats_t *capstats = &proxy->capture_stats;

    int active_conns = (int)(stats->num_icmp_conn + stats->num_tcp_conn + stats->num_udp_conn);
    int tot_conns = (int)(stats->num_icmp_opened + stats->num_tcp_opened + stats->num_udp_opened);

    jobject stats_obj = (*env)->NewObject(env, cls.stats, mids.statsInit);

    if((stats_obj == NULL) || jniCheckException(env)) {
        log_android(ANDROID_LOG_ERROR, "NewObject(VPNStats) failed");
        return;
    }

    (*env)->CallVoidMethod(env, stats_obj, mids.statsSetData,
            capstats->sent_bytes, capstats->rcvd_bytes,
            capstats->sent_pkts, capstats->rcvd_pkts,
            proxy->num_dropped_connections,
            stats->num_open_sockets, stats->all_max_fd, active_conns, tot_conns, proxy->num_dns_requests);

    if(!jniCheckException(env)) {
        (*env)->CallVoidMethod(env, proxy->vpn_service, mids.sendStatsDump, stats_obj);
        jniCheckException(env);
    }

    DeleteLocalRef(env, stats_obj);
}

/* ******************************************************* */

static void notifyServiceStatus(vpnproxy_data_t *proxy, const char *status) {
    JNIEnv *env = proxy->env;
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
            log_android(ANDROID_LOG_FATAL,
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
                log_android(ANDROID_LOG_FATAL,
                                    "connection to the PCAP receiver failed [%d]: %s", errno,
                                    strerror(errno));
                return(-2);
            }
        }
    }

    return(0);
}

/* ******************************************************* */

static int run_tun(JNIEnv *env, jclass vpn, int tapfd, jint sdk) {
    zdtun_t *tun;
    char buffer[32767];
    struct timeval now_tv;
    u_int64_t now_ms;
    u_int64_t next_purge_ms;
    time_t last_connections_dump = (time(NULL) * 1000) - CONNECTION_DUMP_UPDATE_FREQUENCY_MS + 1000 /* update in a second */;
    jclass vpn_class = (*env)->GetObjectClass(env, vpn);

    init_log(ANDROID_LOG_DEBUG, env, vpn_class, vpn);

    zdtun_parse_pkt(mitmproxy_pkt_buffer, sizeof(mitmproxy_pkt_buffer)-1, &mitm_pkt);

    /* Classes */
    cls.vpn_service = vpn_class;
    cls.conn = jniFindClass(env, "com/emanuelef/remote_capture/model/ConnectionDescriptor");
    cls.stats = jniFindClass(env, "com/emanuelef/remote_capture/model/VPNStats");

    /* Methods */
    mids.getApplicationByUid = jniGetMethodID(env, vpn_class, "getApplicationByUid", "(I)Ljava/lang/String;"),
    mids.protect = jniGetMethodID(env, vpn_class, "protect", "(I)Z");
    mids.dumpPcapData = jniGetMethodID(env, vpn_class, "dumpPcapData", "([B)V");
    mids.sendConnectionsDump = jniGetMethodID(env, vpn_class, "sendConnectionsDump", "([Lcom/emanuelef/remote_capture/model/ConnectionDescriptor;[Lcom/emanuelef/remote_capture/model/ConnectionDescriptor;)V");
    mids.sendStatsDump = jniGetMethodID(env, vpn_class, "sendStatsDump", "(Lcom/emanuelef/remote_capture/model/VPNStats;)V");
    mids.sendServiceStatus = jniGetMethodID(env, vpn_class, "sendServiceStatus", "(Ljava/lang/String;)V");
    mids.connInit = jniGetMethodID(env, cls.conn, "<init>", "()V");
    mids.connSetData = jniGetMethodID(env, cls.conn, "setData",
            /* NOTE: must match ConnectionDescriptor::setData */
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIJJJJIIIIZ)V");
    mids.statsInit = jniGetMethodID(env, cls.stats, "<init>", "()V");
    mids.statsSetData = jniGetMethodID(env, cls.stats, "setData", "(JJIIIIIIII)V");

    vpnproxy_data_t proxy = {
            .tapfd = tapfd,
            .sdk = sdk,
            .env = env,
            .vpn_service = vpn,
            .vpn_ipv4 = getIPv4Pref(env, vpn, "getVpnIPv4"),
            .vpn_dns = getIPv4Pref(env, vpn, "getVpnDns"),
            .public_dns = getIPv4Pref(env, vpn, "getPublicDns"),
            .incr_id = 0,
            .java_dump = {
                .enabled = (bool) getIntPref(env, vpn, "dumpPcapToJava"),
            },
            .pcap_dump = {
                .collector_addr = getIPv4Pref(env, vpn, "getPcapCollectorAddress"),
                .collector_port = htons(getIntPref(env, vpn, "getPcapCollectorPort")),
                .tcp_socket = false,
                .enabled = (bool) getIntPref(env, vpn, "dumpPcapToUdp"),
            },
            .tls_decryption = {
                .enabled = (bool) getIntPref(env, vpn, "getTlsDecryptionEnabled"),
                .proxy_ip = getIPv4Pref(env, vpn, "getTlsProxyAddress"),
                .proxy_port = htons(getIntPref(env, vpn, "getTlsProxyPort")),
            }
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
    running = true;

    /* nDPI */
    proxy.ndpi = init_ndpi();
    initMasterProtocolsBitmap(&masterProtos);

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

    // Limit the segments size for two reasons:
    // 1. to be able to encapsulate the packets for the UDP export
    // 2. to avoid ENOBUFS while writing to the tapfd (for big packets, e.g. speedtest).
    zdtun_set_max_window_size(tun, 8192);

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
            log_android(ANDROID_LOG_FATAL, "malloc(java_dump.buffer) failed with code %d/%s",
                                errno, strerror(errno));
            running = false;
        }
    }

    gettimeofday(&now_tv, NULL);
    now_ms = now_tv.tv_sec * 1000 + now_tv.tv_usec / 1000;
    next_purge_ms = now_ms + PERIODIC_PURGE_TIMEOUT_MS;

    while(running) {
        int max_fd;
        fd_set fdset;
        fd_set wrfds;
        int size;
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

            if (size > 0) {
                zdtun_pkt_t pkt;
                int rc;

                if (zdtun_parse_pkt(buffer, size, &pkt) != 0) {
                    log_android(ANDROID_LOG_DEBUG, "zdtun_parse_pkt failed");
                    goto housekeeping;
                }

                zdtun_conn_t *conn = zdtun_lookup(tun, &pkt.tuple, 1 /* create if not exists */);

                if (!conn) {
                    proxy.num_dropped_connections++;
                    log_android(ANDROID_LOG_ERROR, "zdtun_lookup failed");
                    goto housekeeping;
                }

                if((check_dns_req_dnat(&proxy, &pkt, conn) == 0)
                        && (pkt.tuple.dst_ip == proxy.vpn_dns)) {
                    log_android(ANDROID_LOG_DEBUG, "ignoring packet directed to the virtual DNS server");
                    goto housekeeping;
                }

                if(proxy.tls_decryption.enabled)
                    check_tls_mitm(tun, &proxy, &pkt, conn);

                if ((rc = zdtun_forward(tun, &pkt, conn)) != 0) {
                    log_android(ANDROID_LOG_ERROR, "zdtun_forward (proto=%d) failed with code %d", pkt.tuple.ipproto, rc);
                    proxy.num_dropped_connections++;

                    zdtun_destroy_conn(tun, conn);
                    goto housekeeping;
                }
            } else if (size < 0)
                log_android(ANDROID_LOG_ERROR, "recv(tapfd) returned error [%d]: %s", errno,
                            strerror(errno));
        } else
            zdtun_handle_fd(tun, &fdset, &wrfds);

housekeeping:

        if(proxy.capture_stats.new_stats
         && ((now_ms - proxy.capture_stats.last_update_ms) >= CAPTURE_STATS_UPDATE_FREQUENCY_MS) || dump_capture_stats_now) {
            zdtun_statistics_t stats;
            dump_capture_stats_now = false;

            zdtun_get_stats(tun, &stats);
            sendVPNStats(&proxy, &stats);
            proxy.capture_stats.new_stats = false;
            proxy.capture_stats.last_update_ms = now_ms;
        } else if((now_ms - last_connections_dump) >= CONNECTION_DUMP_UPDATE_FREQUENCY_MS) {
            sendConnectionsDump(tun, &proxy);
            last_connections_dump = now_ms;
        } else if((proxy.java_dump.buffer_idx > 0)
         && (now_ms - proxy.java_dump.last_dump_ms) >= MAX_JAVA_DUMP_DELAY_MS) {
            javaPcapDump(&proxy);
        } else if((now_ms >= next_purge_ms) || dump_vpn_stats_now) {
            dump_vpn_stats_now = false;

            zdtun_purge_expired(tun, now_ms/1000);
            next_purge_ms = now_ms + PERIODIC_PURGE_TIMEOUT_MS;
        }
    }

    log_android(ANDROID_LOG_DEBUG, "Stopped packet loop");

    ztdun_finalize(tun);
    conns_clear(&proxy.new_conns, true);
    conns_clear(&proxy.conns_updates, true);

    ndpi_exit_detection_module(proxy.ndpi);

    if(dumper_socket > 0) {
        close(dumper_socket);
        dumper_socket = -1;
    }

    if(proxy.java_dump.buffer) {
        if(proxy.java_dump.buffer_idx > 0)
            javaPcapDump(&proxy);

        free(proxy.java_dump.buffer);
        proxy.java_dump.buffer = NULL;
    }

    notifyServiceStatus(&proxy, "stopped");

    finish_log();
    return(0);
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_stopPacketLoop(JNIEnv *env, jclass type) {
    /* NOTE: the select on the packets loop uses a timeout to wake up periodically */
    log_android(ANDROID_LOG_INFO, "stopPacketLoop called");
    running = false;
}

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_runPacketLoop(JNIEnv *env, jclass type, jint tapfd,
                                                              jobject vpn, jint sdk) {

    run_tun(env, vpn, tapfd, sdk);
}

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_askStatsDump(JNIEnv *env, jclass clazz) {
    if(running) {
        dump_vpn_stats_now = true;
        dump_capture_stats_now = true;
    }
}

JNIEXPORT jint JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_getFdSetSize(JNIEnv *env, jclass clazz) {
    return FD_SETSIZE;
}
