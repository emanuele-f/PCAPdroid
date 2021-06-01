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
#include "pcap_utils.h"
#include "common/utils.h"
#include "ndpi_protocol_ids.h"

/* ******************************************************* */

jni_classes_t cls;
jni_methods_t mids;
bool running = false;
uint32_t new_dns_server = 0;

static bool dump_capture_stats_now = false;
static ndpi_protocol_bitmask_struct_t masterProtos;

/* ******************************************************* */

/* NOTE: these must be reset during each run, as android may reuse the service */
static int dumper_socket;
static bool send_header;
static time_t last_connections_dump;
static vpnproxy_data_t *global_proxy = NULL;

/* ******************************************************* */

static void trim_trailing_newlines(char *request_data, int request_len) {
    while((request_len > 0) && (request_data[request_len - 1] == '\n'))
        request_data[--request_len] = '\0';
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

void free_connection_data(conn_data_t *data) {
    if(!data)
        return;

    free_ndpi(data);

    if(data->info)
        free(data->info);
    if(data->http.url)
        free(data->http.url);
    if(data->http.request_data)
        free(data->http.request_data);

    free(data);
}

/* ******************************************************* */

void conns_add(conn_array_t *arr, const zdtun_5tuple_t *tuple, conn_data_t *data) {
    if(arr->cur_items >= arr->size) {
        /* Extend array */
        arr->size = (arr->size == 0) ? 8 : (arr->size * 2);
        arr->items = realloc(arr->items, arr->size * sizeof(vpn_conn_t));

        if(arr->items == NULL) {
            log_e("realloc(conn_array_t) (%d items) failed", arr->size);
            return;
        }
    }

    vpn_conn_t *slot = &arr->items[arr->cur_items++];
    slot->tuple = *tuple;
    slot->data = data;
    data->pending_notification = true;
}

/* ******************************************************* */

static void conns_clear(conn_array_t *arr, bool free_all) {
    if(arr->items) {
        for(int i=0; i < arr->cur_items; i++) {
            vpn_conn_t *slot = &arr->items[i];

            if(slot->data && ((slot->data->status >= CONN_STATUS_CLOSED) || free_all))
                free_connection_data(slot->data);
        }

        free(arr->items);
        arr->items = NULL;
    }

    arr->size = 0;
    arr->cur_items = 0;
}

/* ******************************************************* */

char* getStringPref(vpnproxy_data_t *proxy, const char *key, char *buf, int bufsize) {
    JNIEnv *env = proxy->env;

    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()Ljava/lang/String;");
    jstring obj = (*env)->CallObjectMethod(env, proxy->vpn_service, midMethod);
    char *rv = NULL;

    if(!jniCheckException(env)) {
        const char *value = (*env)->GetStringUTFChars(env, obj, 0);
        log_d("getStringPref(%s) = %s", key, value);

        strncpy(buf, value, bufsize);
        buf[bufsize - 1] = '\0';
        rv = buf;

        (*env)->ReleaseStringUTFChars(env, obj, value);
    }

    (*env)->DeleteLocalRef(env, obj);

    return(rv);
}

/* ******************************************************* */

static u_int32_t getIPv4Pref(JNIEnv *env, jobject vpn_inst, const char *key) {
    struct in_addr addr = {0};

    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()Ljava/lang/String;");
    jstring obj = (*env)->CallObjectMethod(env, vpn_inst, midMethod);

    if(!jniCheckException(env)) {
        const char *value = (*env)->GetStringUTFChars(env, obj, 0);
        log_d("getIPv4Pref(%s) = %s", key, value);

        if(inet_aton(value, &addr) == 0)
            log_e("%s() returned invalid IPv4 address", key);

        (*env)->ReleaseStringUTFChars(env, obj, value);
    }

    (*env)->DeleteLocalRef(env, obj);

    return(addr.s_addr);
}

/* ******************************************************* */

static struct in6_addr getIPv6Pref(JNIEnv *env, jobject vpn_inst, const char *key) {
    struct in6_addr addr = {0};

    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()Ljava/lang/String;");
    jstring obj = (*env)->CallObjectMethod(env, vpn_inst, midMethod);

    if(!jniCheckException(env)) {
        const char *value = (*env)->GetStringUTFChars(env, obj, 0);
        log_d("getIPv6Pref(%s) = %s", key, value);

        if(inet_pton(AF_INET6, value, &addr) != 1)
            log_e("%s() returned invalid IPv6 address", key);

        (*env)->ReleaseStringUTFChars(env, obj, value);
    }

    (*env)->DeleteLocalRef(env, obj);

    return(addr);
}

/* ******************************************************* */

static jint getIntPref(JNIEnv *env, jobject vpn_inst, const char *key) {
    jint value;
    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()I");

    value = (*env)->CallIntMethod(env, vpn_inst, midMethod);
    jniCheckException(env);

    log_d("getIntPref(%s) = %d", key, value);

    return(value);
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

    if(value) (*env)->ReleaseStringUTFChars(env, obj, value);
    if(obj) (*env)->DeleteLocalRef(env, obj);

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

conn_data_t* new_connection(vpnproxy_data_t *proxy, const zdtun_5tuple_t *tuple, int uid) {
    conn_data_t *data = calloc(1, sizeof(conn_data_t));

    if(!data) {
        log_e("calloc(conn_data_t) failed with code %d/%s",
                    errno, strerror(errno));
        return(NULL);
    }

    /* nDPI */
    if((data->ndpi_flow = calloc(1, SIZEOF_FLOW_STRUCT)) == NULL) {
        log_e("ndpi_flow_malloc failed");
        free_ndpi(data);
    }

    if((data->src_id = calloc(1, SIZEOF_ID_STRUCT)) == NULL) {
        log_e("ndpi_malloc(src_id) failed");
        free_ndpi(data);
    }

    if((data->dst_id = calloc(1, SIZEOF_ID_STRUCT)) == NULL) {
        log_e("ndpi_malloc(dst_id) failed");
        free_ndpi(data);
    }

    data->first_seen = data->last_seen = time(NULL);
    data->uid = uid;

    // Try to resolve host name via the LRU cache
    zdtun_ip_t ip = tuple->dst_ip;
    data->info = ip_lru_find(proxy->ip_to_host, &ip);

    if(data->info) {
        char resip[INET6_ADDRSTRLEN];
        int family = (tuple->ipver == 4) ? AF_INET : AF_INET6;

        resip[0] = '\0';
        inet_ntop(family, &ip, resip, sizeof(resip));

        log_d("Host LRU cache HIT: %s -> %s", resip, data->info);
    }

    return(data);
}

/* ******************************************************* */

void end_ndpi_detection(conn_data_t *data, vpnproxy_data_t *proxy, const zdtun_5tuple_t *tuple) {
    if(!data->ndpi_flow)
        return;

    if(data->l7proto.app_protocol == NDPI_PROTOCOL_UNKNOWN) {
        uint8_t proto_guessed;

        data->l7proto = ndpi_detection_giveup(proxy->ndpi, data->ndpi_flow, 1 /* Guess */,
                                              &proto_guessed);
    }

    if(data->l7proto.master_protocol == 0)
        data->l7proto.master_protocol = data->l7proto.app_protocol;

    log_d("nDPI completed[ipver=%d, proto=%d] -> l7proto: app=%d, master=%d",
                tuple->ipver, tuple->ipproto, data->l7proto.app_protocol, data->l7proto.master_protocol);

    switch (data->l7proto.master_protocol) {
        case NDPI_PROTOCOL_DNS:
            if(data->ndpi_flow->host_server_name[0]) {
                u_int16_t rsp_type = data->ndpi_flow->protos.dns.rsp_type;
                zdtun_ip_t rsp_addr = {0};
                int ipver = 0;

                if(data->info)
                    free(data->info);
                data->info = strndup((char*)data->ndpi_flow->host_server_name, 256);

                if(data->info && strchr(data->info, '.')) { // ignore invalid domain names
                    if((rsp_type == 0x1) && (data->ndpi_flow->protos.dns.rsp_addr.ipv4 != 0)) { /* A */
                        rsp_addr.ip4 = data->ndpi_flow->protos.dns.rsp_addr.ipv4;
                        ipver = 4;
                    } else if((rsp_type == 0x1c)
                              && ((data->ndpi_flow->protos.dns.rsp_addr.ipv6.u6_addr.u6_addr8[0] & 0xE0) == 0x20)) { /* AAAA unicast */
                        memcpy(&rsp_addr.ip6, &data->ndpi_flow->protos.dns.rsp_addr.ipv6.u6_addr, 16);
                        ipver = 6;
                    }

                    if(ipver != 0) {
                        char rspip[INET6_ADDRSTRLEN];
                        int family = (ipver == 4) ? AF_INET : AF_INET6;

                        rspip[0] = '\0';
                        inet_ntop(family, &rsp_addr, rspip, sizeof(rspip));

                        log_d("Host LRU cache ADD [v%d]: %s -> %s", ipver, rspip, data->info);

                        ip_lru_add(proxy->ip_to_host, &rsp_addr, data->info);
                    }
                }
            }
            break;
        case NDPI_PROTOCOL_HTTP:
            if(data->ndpi_flow->host_server_name[0]) {
                if(data->info)
                    free(data->info);
                data->info = strndup((char*) data->ndpi_flow->host_server_name, 256);
            }

            if(data->ndpi_flow->http.url)
                data->http.url = strndup(data->ndpi_flow->http.url, 256);

            if(data->http.request_data && !data->http.parsing_done)
                trim_trailing_newlines(data->http.request_data, strlen(data->http.request_data));
            data->http.parsing_done = true;

            break;
        case NDPI_PROTOCOL_TLS:
            if(data->ndpi_flow->protos.stun_ssl.ssl.client_requested_server_name[0]) {
                if(data->info)
                    free(data->info);

                data->info = strndup(data->ndpi_flow->protos.stun_ssl.ssl.client_requested_server_name, 256);
            }
            break;
    }

    free_ndpi(data);
}

/* ******************************************************* */

static int http_is_printable(char c) {
    return isprint(c) || (c == '\r') || (c == '\n');
}

/* ******************************************************* */

static void process_http_data(conn_data_t *data, const struct zdtun_pkt *pkt, uint8_t from_tun) {
    if(pkt->l7_len > 0) {
        int request_len = data->http.request_data ? (int)strlen(data->http.request_data) : 0;

        if(from_tun) {
            int num_chars = min(MAX_HTTP_REQUEST_LENGTH - request_len, pkt->l7_len);

            if(num_chars <= 0) {
                data->http.parsing_done = true;
                return;
            }

            // +1 to add a NULL terminator
            data->http.request_data = realloc(data->http.request_data,
                                              request_len + num_chars + 1);

            if(!data->http.request_data) {
                log_e("realloc(http.request_data.buffer) failed with code %d/%s",
                      errno, strerror(errno));
                data->http.parsing_done = true;
                return;
            }

            for(int i = 0; i < num_chars; i++) {
                char ch = pkt->l7[i];

                if(!http_is_printable(ch)) {
                    data->http.parsing_done = true;
                    break;
                }

                if(ch != '\r')
                    data->http.request_data[request_len++] = ch;
            }

            data->http.request_data[request_len] = '\0';
        } else {
            trim_trailing_newlines(data->http.request_data, request_len);
            data->http.parsing_done = true;
        }
    }
}

/* ******************************************************* */

static void process_ndpi_packet(conn_data_t *data, vpnproxy_data_t *proxy,
                                const struct zdtun_pkt *pkt, uint8_t from_tun) {
    bool giveup = ((data->sent_pkts + data->rcvd_pkts) >= MAX_DPI_PACKETS);

    data->l7proto = ndpi_detection_process_packet(proxy->ndpi, data->ndpi_flow, (const u_char *)pkt->buf,
            pkt->pkt_len, data->last_seen,
            from_tun ? data->src_id : data->dst_id,
            from_tun ? data->dst_id : data->src_id);

    if((data->l7proto.master_protocol == NDPI_PROTOCOL_HTTP) && (!data->http.parsing_done)
            && !data->ndpi_flow->packet.tcp_retransmission)
        process_http_data(data, pkt, from_tun);

    if(giveup || ((data->l7proto.app_protocol != NDPI_PROTOCOL_UNKNOWN) &&
            (!ndpi_extra_dissection_possible(proxy->ndpi, data->ndpi_flow))))
        end_ndpi_detection(data, proxy, &pkt->tuple);
}

/* ******************************************************* */

static void javaPcapDump(vpnproxy_data_t *proxy) {
    JNIEnv *env = proxy->env;

    log_d("Exporting a %d B PCAP buffer", proxy->java_dump.buffer_idx);

    jbyteArray barray = (*env)->NewByteArray(env, proxy->java_dump.buffer_idx);
    if(jniCheckException(env))
        return;

    (*env)->SetByteArrayRegion(env, barray, 0, proxy->java_dump.buffer_idx, proxy->java_dump.buffer);
    (*env)->CallVoidMethod(env, proxy->vpn_service, mids.dumpPcapData, barray);
    jniCheckException(env);

    proxy->java_dump.buffer_idx = 0;
    proxy->java_dump.last_dump_ms = proxy->now_ms;

    (*env)->DeleteLocalRef(env, barray);
}

/* ******************************************************* */

int resolve_uid(vpnproxy_data_t *proxy, const zdtun_5tuple_t *conn_info) {
    char buf[256];
    jint uid;

    zdtun_5tuple2str(conn_info, buf, sizeof(buf));
    uid = get_uid(proxy->resolver, conn_info);

    if(uid >= 0) {
        char appbuf[128];

        if(uid == 0)
            strncpy(appbuf, "ROOT", sizeof(appbuf));
        else if(uid == 1051)
            strncpy(appbuf, "netd", sizeof(appbuf));
        else
            getApplicationByUid(proxy, uid, appbuf, sizeof(appbuf));

        log_i( "%s [%d/%s]", buf, uid, appbuf);
    } else {
        uid = UID_UNKNOWN;
        log_w("%s => UID not found!", buf);
    }

    return(uid);
}

/* ******************************************************* */

static int dumpConnection(vpnproxy_data_t *proxy, const vpn_conn_t *conn, jobject arr, int idx) {
    char srcip[INET6_ADDRSTRLEN], dstip[INET6_ADDRSTRLEN];
    //struct in_addr addr;
    JNIEnv *env = proxy->env;
    const zdtun_5tuple_t *conn_info = &conn->tuple;
    const conn_data_t *data = conn->data;
    int rv = 0;
    int family = (conn->tuple.ipver == 4) ? AF_INET : AF_INET6;

    if((inet_ntop(family, &conn_info->src_ip, srcip, sizeof(srcip)) == NULL) ||
       (inet_ntop(family, &conn_info->dst_ip, dstip, sizeof(dstip)) == NULL)) {
        log_w("inet_ntop failed: ipver=%d, dstport=%d", conn->tuple.ipver, ntohs(conn_info->dst_port));
        return 0;
    }

#if 0
    log_i( "DUMP: [proto=%d]: %s:%u -> %s:%u [%d]",
                        conn_info->ipproto,
                        srcip, ntohs(conn_info->src_port),
                        dstip, ntohs(conn_info->dst_port),
                        data->uid);
#endif

    jobject info_string = (*env)->NewStringUTF(env, data->info ? data->info : "");
    jobject url_string = (*env)->NewStringUTF(env, data->http.url ? data->http.url : "");
    jobject req_string = (*env)->NewStringUTF(env, data->http.request_data ? data->http.request_data : "");
    jobject proto_string = (*env)->NewStringUTF(env, getProtoName(proxy->ndpi, data->l7proto, conn_info->ipproto));
    jobject src_string = (*env)->NewStringUTF(env, srcip);
    jobject dst_string = (*env)->NewStringUTF(env, dstip);
    jobject conn_descriptor = (*env)->NewObject(env, cls.conn, mids.connInit);

    if((conn_descriptor != NULL) && !jniCheckException(env)) {
        /* NOTE: as an alternative to pass all the params into the constructor, GetFieldID and
         * SetIntField like methods could be used. */
        (*env)->CallVoidMethod(env, conn_descriptor, mids.connSetData,
                               src_string, dst_string, info_string, url_string, req_string, proto_string,
                               data->status, conn_info->ipver, conn_info->ipproto,
                               ntohs(conn_info->src_port), ntohs(conn_info->dst_port),
                               data->first_seen, data->last_seen, data->sent_bytes,
                               data->rcvd_bytes, data->sent_pkts,
                               data->rcvd_pkts, data->uid, data->incr_id);
        if(jniCheckException(env))
            rv = -1;
        else {
            /* Add the connection to the array */
            (*env)->SetObjectArrayElement(env, arr, idx, conn_descriptor);

            if(jniCheckException(env))
                rv = -1;
        }

        (*env)->DeleteLocalRef(env, conn_descriptor);
    } else {
        log_e("NewObject(ConnectionDescriptor) failed");
        rv = -1;
    }

    (*env)->DeleteLocalRef(env, info_string);
    (*env)->DeleteLocalRef(env, req_string);
    (*env)->DeleteLocalRef(env, url_string);
    (*env)->DeleteLocalRef(env, proto_string);
    (*env)->DeleteLocalRef(env, src_string);
    (*env)->DeleteLocalRef(env, dst_string);

    return rv;
}

/* Perform a full dump of the active connections */
static void sendConnectionsDump(vpnproxy_data_t *proxy) {
    if((proxy->new_conns.cur_items == 0) && (proxy->conns_updates.cur_items == 0))
        return;

    log_d("sendConnectionsDump: new=%d, updates=%d", proxy->new_conns.cur_items, proxy->conns_updates.cur_items);

    JNIEnv *env = proxy->env;
    jobject new_conns = (*env)->NewObjectArray(env, proxy->new_conns.cur_items, cls.conn, NULL);
    jobject conns_updates = (*env)->NewObjectArray(env, proxy->conns_updates.cur_items, cls.conn, NULL);

    if((new_conns == NULL) || (conns_updates == NULL) || jniCheckException(env)) {
        log_e("NewObjectArray() failed");
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

    (*env)->DeleteLocalRef(env, new_conns);
    (*env)->DeleteLocalRef(env, conns_updates);
}

/* ******************************************************* */

static void sendStatsDump(const vpnproxy_data_t *proxy, const zdtun_statistics_t *stats) {
    JNIEnv *env = proxy->env;
    const capture_stats_t *capstats = &proxy->capture_stats;

    int active_conns = (int)(stats->num_icmp_conn + stats->num_tcp_conn + stats->num_udp_conn);
    int tot_conns = (int)(stats->num_icmp_opened + stats->num_tcp_opened + stats->num_udp_opened);

    jobject stats_obj = (*env)->NewObject(env, cls.stats, mids.statsInit);

    if((stats_obj == NULL) || jniCheckException(env)) {
        log_e("NewObject(VPNStats) failed");
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

    (*env)->DeleteLocalRef(env, stats_obj);
}

/* ******************************************************* */

static void notifyServiceStatus(vpnproxy_data_t *proxy, const char *status) {
    JNIEnv *env = proxy->env;
    jstring status_str;

    status_str = (*env)->NewStringUTF(env, status);

    (*env)->CallVoidMethod(env, proxy->vpn_service, mids.sendServiceStatus, status_str);
    jniCheckException(env);

    (*env)->DeleteLocalRef(env, status_str);
}

/* ******************************************************* */

void protectSocket(vpnproxy_data_t *proxy, socket_t sock) {
    JNIEnv *env = proxy->env;

    if(proxy->root_capture)
        return;

    /* Call VpnService protect */
    jboolean isProtected = (*env)->CallBooleanMethod(
            env, proxy->vpn_service, mids.protect, sock);
    jniCheckException(env);

    if (!isProtected)
        log_e("socket protect failed");
}

/* ******************************************************* */

static int connect_dumper(vpnproxy_data_t *proxy) {
    if(proxy->pcap_dump.enabled) {
        dumper_socket = socket(AF_INET, proxy->pcap_dump.tcp_socket ? SOCK_STREAM : SOCK_DGRAM, 0);

        if (!dumper_socket) {
            log_f("could not open UDP pcap dump socket [%d]: %s", errno, strerror(errno));
            return(-1);
        }

        protectSocket(proxy, dumper_socket);

        if(proxy->pcap_dump.tcp_socket) {
            struct sockaddr_in servaddr = {0};
            servaddr.sin_family = AF_INET;
            servaddr.sin_port = proxy->pcap_dump.collector_port;
            servaddr.sin_addr.s_addr = proxy->pcap_dump.collector_addr;

            if(connect(dumper_socket, (struct sockaddr*)&servaddr, sizeof(servaddr)) < 0) {
                log_f("connection to the PCAP receiver failed [%d]: %s", errno,
                                    strerror(errno));
                return(-2);
            }
        }
    }

    return(0);
}

/* ******************************************************* */

void run_housekeeping(vpnproxy_data_t *proxy) {
    if(proxy->capture_stats.new_stats
            && ((proxy->now_ms - proxy->capture_stats.last_update_ms) >= CAPTURE_STATS_UPDATE_FREQUENCY_MS) ||
            dump_capture_stats_now) {
        dump_capture_stats_now = false;

        if(proxy->tun)
            zdtun_get_stats(proxy->tun, &proxy->stats);

        sendStatsDump(proxy, &proxy->stats);

        proxy->capture_stats.new_stats = false;
        proxy->capture_stats.last_update_ms = proxy->now_ms;
    } else if ((proxy->now_ms - last_connections_dump) >= CONNECTION_DUMP_UPDATE_FREQUENCY_MS) {
        sendConnectionsDump(proxy);
        last_connections_dump = proxy->now_ms;
    } else if ((proxy->java_dump.buffer_idx > 0)
               && (proxy->now_ms - proxy->java_dump.last_dump_ms) >= MAX_JAVA_DUMP_DELAY_MS) {
        javaPcapDump(proxy);
    }
}

/* ******************************************************* */

void refreshTime(vpnproxy_data_t *proxy) {
    struct timeval now_tv;

    gettimeofday(&now_tv, NULL);
    proxy->now_ms = now_tv.tv_sec * 1000 + now_tv.tv_usec / 1000;
}

/* ******************************************************* */

static void log_callback(int lvl, const char *line) {
    if(lvl >= ANDROID_LOG_FATAL) {
        vpnproxy_data_t *proxy = global_proxy;

        // This is a fatal error, report it to the gui
        jobject info_string = (*proxy->env)->NewStringUTF(proxy->env, line);

        if((jniCheckException(proxy->env) != 0) || (info_string == NULL))
            return;

        (*proxy->env)->CallVoidMethod(proxy->env, proxy->vpn_service, mids.reportError, info_string);
        jniCheckException(proxy->env);

        (*proxy->env)->DeleteLocalRef(proxy->env, info_string);
    }
}

/* ******************************************************* */

void account_packet(vpnproxy_data_t *proxy, const zdtun_pkt_t *pkt, uint8_t from_tun,
                    const zdtun_5tuple_t *conn_tuple, conn_data_t *data) {
#if 0
    if(from_tun)
        log_d("tun2net: %ld B", size);
    else
        log_d("net2tun: %lu B", size);
#endif

    data->last_seen = time(NULL);

    if(from_tun) {
        data->sent_pkts++;
        data->sent_bytes += pkt->pkt_len;
        proxy->capture_stats.sent_pkts++;
        proxy->capture_stats.sent_bytes += pkt->pkt_len;
    } else {
        data->rcvd_pkts++;
        data->rcvd_bytes += pkt->pkt_len;
        proxy->capture_stats.rcvd_pkts++;
        proxy->capture_stats.rcvd_bytes += pkt->pkt_len;
    }

    if(data->ndpi_flow)
        process_ndpi_packet(data, proxy, pkt, from_tun);

    /* New stats to notify */
    proxy->capture_stats.new_stats = true;

    if (!data->pending_notification) {
        conns_add(&proxy->conns_updates, conn_tuple, data);
        data->pending_notification = true;
    }

    if (proxy->java_dump.buffer) {
        int tot_size = pkt->pkt_len + (int) sizeof(pcaprec_hdr_s);

        if ((JAVA_PCAP_BUFFER_SIZE - proxy->java_dump.buffer_idx) <= tot_size) {
// Flush the buffer
            javaPcapDump(proxy);
        }

        if ((JAVA_PCAP_BUFFER_SIZE - proxy->java_dump.buffer_idx) <= tot_size)
            log_e("Invalid buffer size [size=%d, idx=%d, tot_size=%d]",
                        JAVA_PCAP_BUFFER_SIZE, proxy->java_dump.buffer_idx, tot_size);
        else
            proxy->java_dump.buffer_idx += dump_pcap_rec(
                    (u_char *) proxy->java_dump.buffer + proxy->java_dump.buffer_idx,
                    (u_char *) pkt->buf, pkt->pkt_len);
    }

    if (dumper_socket > 0) {
        struct sockaddr_in servaddr = {0};
        servaddr.sin_family = AF_INET;
        servaddr.sin_port = proxy->pcap_dump.collector_port;
        servaddr.sin_addr.s_addr = proxy->pcap_dump.collector_addr;

        if(send_header) {
            write_pcap_hdr(dumper_socket, (struct sockaddr *) &servaddr, sizeof(servaddr));
            send_header = false;
        }

        write_pcap_rec(dumper_socket, (struct sockaddr *) &servaddr, sizeof(servaddr),
                       (u_int8_t *) pkt->buf, pkt->pkt_len);
    }
}

/* ******************************************************* */

static int run_tun(JNIEnv *env, jclass vpn, int tunfd, jint sdk) {
    last_connections_dump = (time(NULL) * 1000) - CONNECTION_DUMP_UPDATE_FREQUENCY_MS + 1000 /* update in a second */;
    jclass vpn_class = (*env)->GetObjectClass(env, vpn);

    /* Classes */
    cls.vpn_service = vpn_class;
    cls.conn = jniFindClass(env, "com/emanuelef/remote_capture/model/ConnectionDescriptor");
    cls.stats = jniFindClass(env, "com/emanuelef/remote_capture/model/VPNStats");

    /* Methods */
    mids.reportError = jniGetMethodID(env, vpn_class, "reportError", "(Ljava/lang/String;)V");
    mids.getApplicationByUid = jniGetMethodID(env, vpn_class, "getApplicationByUid", "(I)Ljava/lang/String;"),
            mids.protect = jniGetMethodID(env, vpn_class, "protect", "(I)Z");
    mids.dumpPcapData = jniGetMethodID(env, vpn_class, "dumpPcapData", "([B)V");
    mids.sendConnectionsDump = jniGetMethodID(env, vpn_class, "sendConnectionsDump", "([Lcom/emanuelef/remote_capture/model/ConnectionDescriptor;[Lcom/emanuelef/remote_capture/model/ConnectionDescriptor;)V");
    mids.sendStatsDump = jniGetMethodID(env, vpn_class, "sendStatsDump", "(Lcom/emanuelef/remote_capture/model/VPNStats;)V");
    mids.sendServiceStatus = jniGetMethodID(env, vpn_class, "sendServiceStatus", "(Ljava/lang/String;)V");
    mids.getLibprogPath = jniGetMethodID(env, vpn_class, "getLibprogPath", "(Ljava/lang/String;)Ljava/lang/String;");
    mids.connInit = jniGetMethodID(env, cls.conn, "<init>", "()V");
    mids.connSetData = jniGetMethodID(env, cls.conn, "setData",
            /* NOTE: must match ConnectionDescriptor::setData */
                                      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IIIIIJJJJIIII)V");
    mids.statsInit = jniGetMethodID(env, cls.stats, "<init>", "()V");
    mids.statsSetData = jniGetMethodID(env, cls.stats, "setData", "(JJIIIIIIII)V");

    vpnproxy_data_t proxy = {
            .tunfd = tunfd,
            .sdk = sdk,
            .env = env,
            .vpn_service = vpn,
            .resolver = init_uid_resolver(sdk, env, vpn),
            .known_dns_servers = ndpi_ptree_create(),
            .ip_to_host = ip_lru_init(MAX_HOST_LRU_SIZE),
            .vpn_ipv4 = getIPv4Pref(env, vpn, "getVpnIPv4"),
            .vpn_dns = getIPv4Pref(env, vpn, "getVpnDns"),
            .dns_server = getIPv4Pref(env, vpn, "getDnsServer"),
            .app_filter = getIntPref(env, vpn, "getAppFilterUid"),
            .root_capture = (bool) getIntPref(env, vpn, "isRootCapture"),
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
            .socks5 = {
                    .enabled = (bool) getIntPref(env, vpn, "getSocks5Enabled"),
                    .proxy_ip = getIPv4Pref(env, vpn, "getSocks5ProxyAddress"),
                    .proxy_port = htons(getIntPref(env, vpn, "getSocks5ProxyPort")),
            },
            .ipv6 = {
                    .enabled = (bool) getIntPref(env, vpn, "getIPv6Enabled"),
                    .dns_server = getIPv6Pref(env, vpn, "getIpv6DnsServer"),
            }
    };

    /* Important: init global state every time. Android may reuse the service. */
    dumper_socket = -1;
    send_header = true;
    running = true;

    logcallback = log_callback;
    global_proxy = &proxy;

    /* nDPI */
    proxy.ndpi = init_ndpi();
    initMasterProtocolsBitmap(&masterProtos);

    if(proxy.ndpi == NULL) {
        log_f("nDPI initialization failed");
        return(-1);
    }

    signal(SIGPIPE, SIG_IGN);

    if(proxy.pcap_dump.enabled) {
        if(connect_dumper(&proxy) < 0)
            running = false;
    }

    if(proxy.java_dump.enabled) {
        proxy.java_dump.buffer = malloc(JAVA_PCAP_BUFFER_SIZE);
        proxy.java_dump.buffer_idx = 0;

        if(!proxy.java_dump.buffer) {
            log_f("malloc(java_dump.buffer) failed with code %d/%s",
                        errno, strerror(errno));
            running = false;
        }
    }

    memset(&proxy.stats, 0, sizeof(proxy.stats));

    notifyServiceStatus(&proxy, "started");

    // Run the capture
    int rv = proxy.root_capture ? run_root(&proxy) : run_proxy(&proxy);

    log_d("Stopped packet loop");

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
    destroy_uid_resolver(proxy.resolver);
    ndpi_ptree_destroy(proxy.known_dns_servers);

    log_d("Host LRU cache size: %d", ip_lru_size(proxy.ip_to_host));
    ip_lru_destroy(proxy.ip_to_host);

    logcallback = NULL;
    global_proxy = NULL;

    return(rv);
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_stopPacketLoop(JNIEnv *env, jclass type) {
    /* NOTE: the select on the packets loop uses a timeout to wake up periodically */
    log_i( "stopPacketLoop called");
    running = false;
}

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_runPacketLoop(JNIEnv *env, jclass type, jint tunfd,
                                                              jobject vpn, jint sdk) {

    run_tun(env, vpn, tunfd, sdk);
}

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_askStatsDump(JNIEnv *env, jclass clazz) {
    if(running)
        dump_capture_stats_now = true;
}

JNIEXPORT jint JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_getFdSetSize(JNIEnv *env, jclass clazz) {
    return FD_SETSIZE;
}

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_setDnsServer(JNIEnv *env, jclass clazz,
                                                               jstring server) {
    struct in_addr addr = {0};
    const char *value = (*env)->GetStringUTFChars(env, server, 0);

    if(inet_aton(value, &addr) != 0)
        new_dns_server = addr.s_addr;
}
