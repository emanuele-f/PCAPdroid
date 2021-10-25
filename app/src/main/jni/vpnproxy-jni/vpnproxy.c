/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it wsetStatsill be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2020-21 - Emanuele Faranda
 */

#include <inttypes.h>
#include <dirent.h>
#include "vpnproxy.h"
#include "pcap_utils.h"
#include "common/utils.h"
#include "ndpi_protocol_ids.h"

// Minimum length (e.g. of "GET") to avoid reporting non-requests
#define MIN_REQ_PLAINTEXT_CHARS 3

/* ******************************************************* */

jni_classes_t cls;
jni_methods_t mids;
bool running = false;
uint32_t new_dns_server = 0;

static bool dump_capture_stats_now = false;
static bool reload_blacklists_now = false;
static ndpi_protocol_bitmask_struct_t masterProtos;

/* ******************************************************* */

/* NOTE: these must be reset during each run, as android may reuse the service */
static int netd_resolve_waiting;
static u_int64_t last_connections_dump;
static u_int64_t next_connections_dump;
static vpnproxy_data_t *global_proxy = NULL;

/* ******************************************************* */

static void conn_free_ndpi(conn_data_t *data) {
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

void conn_free_data(conn_data_t *data) {
    if(!data)
        return;

    conn_free_ndpi(data);

    if(data->info)
        pd_free(data->info);
    if(data->url)
        pd_free(data->url);
    if(data->request_data)
        pd_free(data->request_data);

    pd_free(data);
}

/* ******************************************************* */

void notify_connection(conn_array_t *arr, const zdtun_5tuple_t *tuple, conn_data_t *data) {
    // End the detection when the connection is closed
    // Always check this, even pending_notification are present
    if(data->status >= CONN_STATUS_CLOSED)
        conn_end_ndpi_detection(data, global_proxy, tuple);

    if(data->pending_notification)
        return;

    if(arr->cur_items >= arr->size) {
        /* Extend array */
        arr->size = (arr->size == 0) ? 8 : (arr->size * 2);
        arr->items = pd_realloc(arr->items, arr->size * sizeof(vpn_conn_t));

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

            if(slot->data && (slot->data->to_purge || free_all))
                conn_free_data(slot->data);
        }

        pd_free(arr->items);
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

u_int32_t getIPv4Pref(JNIEnv *env, jobject vpn_inst, const char *key) {
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

struct in6_addr getIPv6Pref(JNIEnv *env, jobject vpn_inst, const char *key) {
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

int getIntPref(JNIEnv *env, jobject vpn_inst, const char *key) {
    jint value;
    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()I");

    value = (*env)->CallIntMethod(env, vpn_inst, midMethod);
    jniCheckException(env);

    log_d("getIntPref(%s) = %d", key, value);

    return(value);
}

/* ******************************************************* */

static void getApplicationByUidJava(vpnproxy_data_t *proxy, jint uid, char *buf, int bufsize) {
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
}

/* ******************************************************* */

static char* get_appname_by_uid(vpnproxy_data_t *proxy, int uid, char *buf, int bufsize) {
    uid_to_app_t *app_entry;

    HASH_FIND_INT(proxy->uid2app, &uid, app_entry);
    if(app_entry == NULL) {
        app_entry = (uid_to_app_t*) pd_malloc(sizeof(uid_to_app_t));

        if(app_entry) {
            // Resolve the app name
            getApplicationByUidJava(proxy, uid, app_entry->appname, sizeof(app_entry->appname));

            log_d("uid %d resolved to \"%s\"", uid, app_entry->appname);

            app_entry->uid = uid;
            HASH_ADD_INT(proxy->uid2app, uid, app_entry);
        }
    }

    if(app_entry) {
        strncpy(buf, app_entry->appname, bufsize-1);
        buf[bufsize-1] = '\0';
    } else
        buf[0] = '\0';

    return buf;
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
    ndpi_finalize_initialization(ndpi);

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

static void check_blacklisted_domain(vpnproxy_data_t *proxy, conn_data_t *data, const zdtun_5tuple_t *tuple) {
    if(proxy->malware_detection.bl && data->info && data->info[0] && !data->blacklisted_domain) {
        data->blacklisted_domain = blacklist_match_domain(proxy->malware_detection.bl, data->info);
        if(data->blacklisted_domain) {
            char appbuf[64];
            char buf[512];

            get_appname_by_uid(proxy, data->uid, appbuf, sizeof(appbuf));
            log_w("Blacklisted domain [%s]: %s [%s]", data->info, zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);
        }
    }
}

/* ******************************************************* */

conn_data_t* new_connection(vpnproxy_data_t *proxy, const zdtun_5tuple_t *tuple, int uid) {
    conn_data_t *data = pd_calloc(1, sizeof(conn_data_t));

    if(!data) {
        log_e("calloc(conn_data_t) failed with code %d/%s",
                    errno, strerror(errno));
        return(NULL);
    }

    /* nDPI */
    if((data->ndpi_flow = ndpi_calloc(1, SIZEOF_FLOW_STRUCT)) == NULL) {
        log_e("ndpi_flow_malloc failed");
        conn_free_ndpi(data);
    }

    if((data->src_id = ndpi_calloc(1, SIZEOF_ID_STRUCT)) == NULL) {
        log_e("ndpi_malloc(src_id) failed");
        conn_free_ndpi(data);
    }

    if((data->dst_id = ndpi_calloc(1, SIZEOF_ID_STRUCT)) == NULL) {
        log_e("ndpi_malloc(dst_id) failed");
        conn_free_ndpi(data);
    }

    data->uid = uid;

    // Try to resolve host name via the LRU cache
    const zdtun_ip_t dst_ip = tuple->dst_ip;
    data->info = ip_lru_find(proxy->ip_to_host, &dst_ip);

    if(data->info) {
        char resip[INET6_ADDRSTRLEN];
        int family = (tuple->ipver == 4) ? AF_INET : AF_INET6;

        resip[0] = '\0';
        inet_ntop(family, &dst_ip, resip, sizeof(resip));

        log_d("Host LRU cache HIT: %s -> %s", resip, data->info);

        if(data->uid != UID_UNKNOWN) {
            // When a DNS request is followed by a TLS connection or similar, mark the DNS request
            // with the uid of this connection. This allows us to match netd requests to actual apps.
            // Only change the uid of new connections (proxy->new_conns) to avoid possible side effects
            for(int i=0; i<proxy->new_conns.cur_items; i++) {
                vpn_conn_t *conn = &proxy->new_conns.items[i];

                if((conn->data->uid == UID_NETD)
                        && (conn->data->info != NULL)
                        && (strcmp(conn->data->info, data->info) == 0)) {
                    char buf[256];

                    conn->data->uid = data->uid;

                    zdtun_5tuple2str(&conn->tuple, buf, sizeof(buf));
                    log_d("Resolved netd uid: %s : %d", buf, data->uid);

                    if(netd_resolve_waiting > 0) {
                        // If all the netd connections have been resolved, remove the dump delay
                        if((--netd_resolve_waiting) == 0) {
                            log_d("Removing netd resolution delay");
                            next_connections_dump -= NETD_RESOLVE_DELAY_MS;
                        }
                    }
                }
            }
        }

        check_blacklisted_domain(proxy, data, tuple);
    }

    if(proxy->malware_detection.bl && (tuple->ipver == 4)) {
        data->blacklisted_ip = blacklist_match_ip(proxy->malware_detection.bl, tuple->dst_ip.ip4);
        if(data->blacklisted_ip) {
            char appbuf[64];
            char buf[256];

            get_appname_by_uid(proxy, data->uid, appbuf, sizeof(appbuf));
            log_w("Blacklisted dst ip: %s [%s]", zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);
        }
    }

    return(data);
}

/* ******************************************************* */

static bool is_numeric_host(const char *host) {
    if(isdigit(*host))
        return true;

    for(; *host; host++) {
        char ch = *host;

        if(ch == ':') // IPv6
            return true;
        if(ch == '.')
            break;
    }

    return false;
}

/* ******************************************************* */

void conn_end_ndpi_detection(conn_data_t *data, vpnproxy_data_t *proxy, const zdtun_5tuple_t *tuple) {
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
                if(data->info)
                    pd_free(data->info);
                data->info = pd_strndup((char*)data->ndpi_flow->host_server_name, 256);
            }
            break;
        case NDPI_PROTOCOL_HTTP:
            if(data->ndpi_flow->host_server_name[0] &&
                    (!data->info || !is_numeric_host((char*)data->ndpi_flow->host_server_name))) {
                if(data->info)
                    pd_free(data->info);
                data->info = pd_strndup((char*) data->ndpi_flow->host_server_name, 256);
            }

            if(data->ndpi_flow->http.url)
                data->url = pd_strndup(data->ndpi_flow->http.url, 256);

            break;
        case NDPI_PROTOCOL_TLS:
            if(data->ndpi_flow->protos.tls_quic_stun.tls_quic.client_requested_server_name[0]) {
                if(data->info)
                    pd_free(data->info);

                data->info = pd_strndup(data->ndpi_flow->protos.tls_quic_stun.tls_quic.client_requested_server_name, 256);
            }
            break;
    }

    // TODO early match
    check_blacklisted_domain(proxy, data, tuple);

    data->update_type |= CONN_UPDATE_INFO;
    conn_free_ndpi(data);
}

/* ******************************************************* */

static int is_plaintext(char c) {
    return isprint(c) || (c == '\r') || (c == '\n') || (c == '\t');
}

/* ******************************************************* */

static void process_request_data(conn_data_t *data, const struct zdtun_pkt *pkt, uint8_t from_tun) {
    if(pkt->l7_len > 0) {
        if(from_tun && is_plaintext(pkt->l7[0])) {
            int request_len = data->request_data ? (int)strlen(data->request_data) : 0;
            int num_chars = min(MAX_PLAINTEXT_LENGTH - request_len, pkt->l7_len);

            if(num_chars <= 0) {
                data->request_done = true;
                return;
            }

            // +1 to add a NULL terminator
            data->request_data = pd_realloc(data->request_data,request_len + num_chars + 1);

            if(!data->request_data) {
                log_e("realloc(request_data) failed with code %d/%s",
                      errno, strerror(errno));
                data->request_done = true;
                return;
            }

            for(int i = 0; i < num_chars; i++) {
                char ch = pkt->l7[i];

                if(!is_plaintext(ch)) {
                    data->request_done = true;
                    break;
                }

                data->request_data[request_len++] = ch;
            }

            data->request_data[request_len] = '\0';
            data->update_type |= CONN_UPDATE_INFO;
        } else
            data->request_done = true;
    }
}

/* ******************************************************* */

static void process_dns_reply(conn_data_t *data, vpnproxy_data_t *proxy, const struct zdtun_pkt *pkt) {
    const char *query = (const char*) data->ndpi_flow->host_server_name;

    if((!query[0]) || !strchr(query, '.') || (pkt->l7_len < sizeof(dns_packet_t)))
        return;

    dns_packet_t *dns = (dns_packet_t*)pkt->l7;

    if(((dns->flags & 0x8000) == 0x8000) && (dns->questions != 0) && (dns->answ_rrs != 0)) {
        u_char *reply = dns->queries;
        int len = pkt->l7_len - sizeof(dns_packet_t);
        int num_queries = ntohs(dns->questions);
        int num_replies = min(ntohs(dns->answ_rrs), 32);

        // Skip queries
        for(int i=0; (i<num_queries) && (len > 0); i++) {
            while((len > 0) && (*reply != '\0')) {
                reply++;
                len--;
            }

            reply += 5; len -= 5;
        }

        for(int i=0; (i<num_replies) && (len > 0); i++) {
            int ipver = 0;
            zdtun_ip_t rsp_addr = {0};

            // Skip name
            while(len > 0) {
                if(*reply == 0x00) {
                    reply++; len--;
                    break;
                } else if(*reply == 0xc0) {
                    reply+=2; len-=2;
                    break;
                }

                reply++; len--;
            }

            if(len < 10)
                return;

            uint16_t rec_type = ntohs((*(uint16_t*)reply));
            uint16_t addr_len = ntohs((*(uint16_t*)(reply + 8)));
            reply += 10; len -= 10;

            if((rec_type == 0x1) && (addr_len == 4)) { // A record
                ipver = 4;
                rsp_addr.ip4 = *((u_int32_t*)reply);
            } else if((rec_type == 0x1c) && (addr_len == 16)) { // AAAA record
                ipver = 6;
                memcpy(&rsp_addr.ip6, reply, 16);
            }

            if(ipver != 0) {
                char rspip[INET6_ADDRSTRLEN];
                int family = (ipver == 4) ? AF_INET : AF_INET6;

                rspip[0] = '\0';
                inet_ntop(family, &rsp_addr, rspip, sizeof(rspip));

                log_d("Host LRU cache ADD [v%d]: %s -> %s", ipver, rspip, query);
                ip_lru_add(proxy->ip_to_host, &rsp_addr, query);
            }

            reply += addr_len; len -= addr_len;
        }
    }
}

/* ******************************************************* */

static void process_ndpi_packet(conn_data_t *data, vpnproxy_data_t *proxy,
                                const struct zdtun_pkt *pkt, uint8_t from_tun) {
    bool giveup = ((data->sent_pkts + data->rcvd_pkts) >= MAX_DPI_PACKETS);

    u_int16_t old_proto = data->l7proto.master_protocol;
    data->l7proto = ndpi_detection_process_packet(proxy->ndpi, data->ndpi_flow, (const u_char *)pkt->buf,
            pkt->len, data->last_seen,
            from_tun ? data->src_id : data->dst_id,
            from_tun ? data->dst_id : data->src_id);

    if(old_proto != data->l7proto.master_protocol)
        data->update_type |= CONN_UPDATE_INFO;

    if((!data->request_done) && !data->ndpi_flow->packet.tcp_retransmission)
        process_request_data(data, pkt, from_tun);

    bool is_dns = ((data->l7proto.master_protocol == NDPI_PROTOCOL_DNS) ||
            (data->l7proto.app_protocol == NDPI_PROTOCOL_DNS));
    if(!from_tun && is_dns)
        process_dns_reply(data, proxy, pkt);

    if(giveup || ((data->l7proto.app_protocol != NDPI_PROTOCOL_UNKNOWN) &&
            !ndpi_extra_dissection_possible(proxy->ndpi, data->ndpi_flow)))
        conn_end_ndpi_detection(data, proxy, &pkt->tuple);
}

/* ******************************************************* */

static void javaPcapDump(vpnproxy_data_t *proxy) {
    JNIEnv *env = proxy->env;

    log_d("Exporting a %d B PCAP buffer", proxy->pcap_dump.buffer_idx);

    jbyteArray barray = (*env)->NewByteArray(env, proxy->pcap_dump.buffer_idx);
    if(jniCheckException(env))
        return;

    (*env)->SetByteArrayRegion(env, barray, 0, proxy->pcap_dump.buffer_idx, proxy->pcap_dump.buffer);
    (*env)->CallVoidMethod(env, proxy->vpn_service, mids.dumpPcapData, barray);
    jniCheckException(env);

    proxy->pcap_dump.buffer_idx = 0;
    proxy->pcap_dump.last_dump_ms = proxy->now_ms;

    (*env)->DeleteLocalRef(env, barray);
}

/* ******************************************************* */

int resolve_uid(vpnproxy_data_t *proxy, const zdtun_5tuple_t *conn_info) {
    char buf[256];
    jint uid;

    zdtun_5tuple2str(conn_info, buf, sizeof(buf));
    uid = get_uid(proxy->resolver, conn_info);

    if(uid >= 0) {
        char appbuf[64];

        get_appname_by_uid(proxy, uid, appbuf, sizeof(appbuf));
        log_i( "%s [%d/%s]", buf, uid, appbuf);
    } else {
        uid = UID_UNKNOWN;
        log_w("%s => UID not found!", buf);
    }

    return(uid);
}

/* ******************************************************* */

static jobject getConnUpdate(vpnproxy_data_t *proxy, const vpn_conn_t *conn) {
    JNIEnv *env = proxy->env;
    conn_data_t *data = conn->data;

    jobject update = (*env)->NewObject(env, cls.conn_update, mids.connUpdateInit, data->incr_id);

    if((update == NULL) || jniCheckException(env)) {
        log_e("NewObject(ConnectionDescriptor) failed");
        return NULL;
    }

    if(data->update_type & CONN_UPDATE_STATS) {
        (*env)->CallVoidMethod(env, update, mids.connUpdateSetStats, data->last_seen,
                               data->sent_bytes, data->rcvd_bytes, data->sent_pkts, data->rcvd_pkts,
                               (data->tcp_flags[0] << 8) | data->tcp_flags[1],
                               (data->blacklisted_domain << 9) | (data->blacklisted_ip << 8) | (data->status & 0xFF));
    }
    if(data->update_type & CONN_UPDATE_INFO) {
        jobject info = (*env)->NewStringUTF(env, data->info ? data->info : "");
        jobject url = (*env)->NewStringUTF(env, data->url ? data->url : "");
        jobject req = (*env)->NewStringUTF(env, (data->request_data &&
            (strnlen(data->request_data, MIN_REQ_PLAINTEXT_CHARS) == MIN_REQ_PLAINTEXT_CHARS)) ? data->request_data : "");
        jobject l7proto = (*env)->NewStringUTF(env, getProtoName(proxy->ndpi, data->l7proto, conn->tuple.ipproto));

        (*env)->CallVoidMethod(env, update, mids.connUpdateSetInfo, info, url, req, l7proto);

        (*env)->DeleteLocalRef(env, info);
        (*env)->DeleteLocalRef(env, url);
        (*env)->DeleteLocalRef(env, req);
        (*env)->DeleteLocalRef(env, l7proto);
    }

    // reset the update flag
    data->update_type = 0;

    if(jniCheckException(env)) {
        log_e("getConnUpdate() failed");
        (*env)->DeleteLocalRef(env, update);
        return NULL;
    }

    return update;
}

/* ******************************************************* */

static int dumpNewConnection(vpnproxy_data_t *proxy, const vpn_conn_t *conn, jobject arr, int idx) {
    char srcip[INET6_ADDRSTRLEN], dstip[INET6_ADDRSTRLEN];
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

    jobject src_string = (*env)->NewStringUTF(env, srcip);
    jobject dst_string = (*env)->NewStringUTF(env, dstip);
    jobject conn_descriptor = (*env)->NewObject(env, cls.conn, mids.connInit, data->incr_id,
                                                conn_info->ipver, conn_info->ipproto,
                                                src_string, dst_string,
                                                ntohs(conn_info->src_port), ntohs(conn_info->dst_port),
                                                data->uid, data->first_seen);

    if((conn_descriptor != NULL) && !jniCheckException(env)) {
        // This is the first update, send all the data
        conn->data->update_type = CONN_UPDATE_STATS | CONN_UPDATE_INFO;
        jobject update = getConnUpdate(proxy, conn);

        if(update != NULL) {
            (*env)->CallVoidMethod(env, conn_descriptor, mids.connProcessUpdate, update);
            (*env)->DeleteLocalRef(env, update);
        } else
            rv = -1;

        /* Add the connection to the array */
        (*env)->SetObjectArrayElement(env, arr, idx, conn_descriptor);

        if(jniCheckException(env))
            rv = -1;

        (*env)->DeleteLocalRef(env, conn_descriptor);
    } else {
        log_e("NewObject(ConnectionDescriptor) failed");
        rv = -1;
    }

    (*env)->DeleteLocalRef(env, src_string);
    (*env)->DeleteLocalRef(env, dst_string);

    return rv;
}

/* ******************************************************* */

static int dumpConnectionUpdate(vpnproxy_data_t *proxy, const vpn_conn_t *conn, jobject arr, int idx) {
    JNIEnv *env = proxy->env;
    jobject update = getConnUpdate(proxy, conn);

    if(update != NULL) {
        (*env)->SetObjectArrayElement(env, arr, idx, update);
        (*env)->DeleteLocalRef(env, update);
        return 0;
    }

    return -1;
}

/* ******************************************************* */

/* Perform a full dump of the active connections */
static void sendConnectionsDump(vpnproxy_data_t *proxy) {
    if((proxy->new_conns.cur_items == 0) && (proxy->conns_updates.cur_items == 0))
        return;

    log_d("sendConnectionsDump [after %" PRIu64 " ms]: new=%d, updates=%d",
          proxy->now_ms - last_connections_dump,
          proxy->new_conns.cur_items, proxy->conns_updates.cur_items);

    JNIEnv *env = proxy->env;
    jobject new_conns = (*env)->NewObjectArray(env, proxy->new_conns.cur_items, cls.conn, NULL);
    jobject conns_updates = (*env)->NewObjectArray(env, proxy->conns_updates.cur_items, cls.conn_update, NULL);

    if((new_conns == NULL) || (conns_updates == NULL) || jniCheckException(env)) {
        log_e("NewObjectArray() failed");
        goto cleanup;
    }

    // New connections
    for(int i=0; i<proxy->new_conns.cur_items; i++) {
        vpn_conn_t *conn = &proxy->new_conns.items[i];
        conn->data->pending_notification = false;

        if(dumpNewConnection(proxy, conn, new_conns, i) < 0)
            goto cleanup;
    }

    //clock_t start = clock();

    // Updated connections
    for(int i=0; i<proxy->conns_updates.cur_items; i++) {
        vpn_conn_t *conn = &proxy->conns_updates.items[i];
        conn->data->pending_notification = false;

        if(dumpConnectionUpdate(proxy, conn, conns_updates, i) < 0)
            goto cleanup;
    }

    //double cpu_time_used = ((double) (clock() - start)) / CLOCKS_PER_SEC;
    //log_d("avg cpu_time_used per update: %f sec", cpu_time_used / proxy->conns_updates.cur_items);

    /* Send the dump */
    (*env)->CallVoidMethod(env, proxy->vpn_service, mids.updateConnections, new_conns, conns_updates);
    jniCheckException(env);

cleanup:
    conns_clear(&proxy->new_conns, false);
    conns_clear(&proxy->conns_updates, false);

    (*env)->DeleteLocalRef(env, new_conns);
    (*env)->DeleteLocalRef(env, conns_updates);
}

/* ******************************************************* */

#ifdef PCAPDROID_TRACK_ALLOCS

static char allocs_buf[1024];

static char* get_allocs_summary() {
    char b1[16], b2[16], b3[16], b4[16];

    snprintf(allocs_buf, sizeof(allocs_buf),
             "*** Allocs Summary ***\n"
             "  PCAPdroid: %s\n"
             "  nDPI: %s\n"
             "  Blacklist (domains): %s\n"
             "  UTHash: %s\n",
             humanSize(b1, 32, memtrack.scopes[MEMTRACK_PCAPDROID]),
             humanSize(b2, 32, memtrack.scopes[MEMTRACK_NDPI]),
             humanSize(b3, 32, memtrack.scopes[MEMTRACK_BLACKLIST]),
             humanSize(b4, 32, memtrack.scopes[MEMTRACK_UTHASH]));
    return allocs_buf;
}

#endif

/* ******************************************************* */

static void sendStatsDump(const vpnproxy_data_t *proxy) {
    JNIEnv *env = proxy->env;
    const capture_stats_t *capstats = &proxy->capture_stats;
    const zdtun_statistics_t *stats = &proxy->stats;
    jstring allocs_summary =
#ifdef PCAPDROID_TRACK_ALLOCS
            (*proxy->env)->NewStringUTF(proxy->env, get_allocs_summary());
#else
    NULL;
#endif

    int active_conns = (int)(stats->num_icmp_conn + stats->num_tcp_conn + stats->num_udp_conn);
    int tot_conns = (int)(stats->num_icmp_opened + stats->num_tcp_opened + stats->num_udp_opened);

    jobject stats_obj = (*env)->NewObject(env, cls.stats, mids.statsInit);

    if((stats_obj == NULL) || jniCheckException(env)) {
        log_e("NewObject(VPNStats) failed");
        return;
    }

    (*env)->CallVoidMethod(env, stats_obj, mids.statsSetData,
            allocs_summary,
            capstats->sent_bytes, capstats->rcvd_bytes,
            capstats->sent_pkts, capstats->rcvd_pkts,
            min(proxy->num_dropped_pkts, INT_MAX), proxy->num_dropped_connections,
            stats->num_open_sockets, stats->all_max_fd, active_conns, tot_conns,
            proxy->num_dns_requests);

    if(!jniCheckException(env)) {
        (*env)->CallVoidMethod(env, proxy->vpn_service, mids.sendStatsDump, stats_obj);
        jniCheckException(env);
    }

    (*env)->DeleteLocalRef(env, allocs_summary);
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

void vpn_protect_socket(vpnproxy_data_t *proxy, socket_t sock) {
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

const char* get_cache_path(const char *subpath) {
    strncpy(global_proxy->cachedir + global_proxy->cachedir_len, subpath,
            sizeof(global_proxy->cachedir) - global_proxy->cachedir_len - 1);
    global_proxy->cachedir[sizeof(global_proxy->cachedir) - 1] = 0;
    return global_proxy->cachedir;
}

/* ******************************************************* */

const char* get_file_path(const char *subpath) {
    strncpy(global_proxy->filesdir + global_proxy->filesdir_len, subpath,
            sizeof(global_proxy->filesdir) - global_proxy->filesdir_len - 1);
    global_proxy->filesdir[sizeof(global_proxy->filesdir) - 1] = 0;
    return global_proxy->filesdir;
}

/* ******************************************************* */

// called after load_new_blacklists
static void use_new_blacklists(vpnproxy_data_t *proxy) {
    if(!proxy->malware_detection.new_bl)
        return;

    if(proxy->malware_detection.bl)
        blacklist_destroy(proxy->malware_detection.bl);
    proxy->malware_detection.bl = proxy->malware_detection.new_bl;
    proxy->malware_detection.new_bl = NULL;
    blacklist_ready(proxy->malware_detection.bl);

    // Notify
    blacklist_stats_t stats;
    blacklist_get_stats(proxy->malware_detection.bl, &stats);
    (*proxy->env)->CallVoidMethod(proxy->env, proxy->vpn_service, mids.notifyBlacklistsLoaded,
                                  stats.num_lists, stats.num_domains, stats.num_ips);
}

/* ******************************************************* */

// Loads the blacklists data into new_bl and sets reload_done.
// use_new_blacklists needs to be called to use it.
static void* load_new_blacklists(void *data) {
    vpnproxy_data_t *proxy = (vpnproxy_data_t*) data;

    // NOTE: proxy->ndpi is shared
    // Calling ndpi_load_ip_category from another thread is safe
    blacklist_t *bl = blacklist_init(proxy->ndpi);
    if(!bl) {
        proxy->malware_detection.reload_done = true;
        return NULL;
    }

    clock_t start = clock();

    // load all the files in the malware_bl directory
    DIR *dir = opendir(get_file_path("malware_bl"));
    if(dir) {
        struct dirent *dent;
        char subpath[256];

        while((dent = readdir(dir)) != NULL) {
            if(dent->d_name[0] != '.') {
                snprintf(subpath, sizeof(subpath), "malware_bl/%s", dent->d_name);
                blacklist_load_file(bl, get_file_path(subpath));
            }
        }
        closedir(dir);
    }

    // This is a domain to test domain blacklist match
    blacklist_add_domain(bl, "internetbadguys.com");
    log_d("Blacklists loaded in %.3f sec", ((double) (clock() - start)) / CLOCKS_PER_SEC);

    proxy->malware_detection.new_bl = bl;
    proxy->malware_detection.reload_done = true;
    return NULL;
}

/* ******************************************************* */

void run_housekeeping(vpnproxy_data_t *proxy) {
    if(proxy->capture_stats.new_stats
            && ((proxy->now_ms - proxy->capture_stats.last_update_ms) >= CAPTURE_STATS_UPDATE_FREQUENCY_MS) ||
            dump_capture_stats_now) {
        dump_capture_stats_now = false;

        if(!proxy->root_capture)
            zdtun_get_stats(proxy->tun, &proxy->stats);

        sendStatsDump(proxy);

        proxy->capture_stats.new_stats = false;
        proxy->capture_stats.last_update_ms = proxy->now_ms;
    } else if (proxy->now_ms >= next_connections_dump) {
        sendConnectionsDump(proxy);
        last_connections_dump = proxy->now_ms;
        next_connections_dump = proxy->now_ms + CONNECTION_DUMP_UPDATE_FREQUENCY_MS;
        netd_resolve_waiting = 0;
    } else if ((proxy->pcap_dump.buffer_idx > 0)
               && (proxy->now_ms - proxy->pcap_dump.last_dump_ms) >= MAX_JAVA_DUMP_DELAY_MS) {
        javaPcapDump(proxy);
    } else if(proxy->malware_detection.enabled) {
        // Malware detection
        if(proxy->malware_detection.reload_in_progress) {
            if(proxy->malware_detection.reload_done) {
                pthread_join(proxy->malware_detection.reload_worker, NULL);
                proxy->malware_detection.reload_in_progress = false;
                use_new_blacklists(proxy);
            }
        } else if(reload_blacklists_now) {
            reload_blacklists_now = false;
            proxy->malware_detection.reload_done = false;
            proxy->malware_detection.new_bl = NULL;
            pthread_create(&proxy->malware_detection.reload_worker, NULL, load_new_blacklists,
                           proxy);
            proxy->malware_detection.reload_in_progress = true;
        }
    }
}

/* ******************************************************* */

void refresh_time(vpnproxy_data_t *proxy) {
    struct timespec ts;

    if(clock_gettime(CLOCK_MONOTONIC_COARSE, &ts)) {
        log_d("clock_gettime failed[%d]: %s", errno, strerror(errno));
        return;
    }

    proxy->now_ms = (uint64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
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

void fill_custom_data(struct pcapdroid_trailer *cdata, vpnproxy_data_t *proxy, conn_data_t *conn) {
    memset(cdata, 0, sizeof(*cdata));

    cdata->magic = htonl(PCAPDROID_TRAILER_MAGIC);
    cdata->uid = htonl(conn->uid);
    get_appname_by_uid(proxy, conn->uid, cdata->appname, sizeof(cdata->appname));
}

/* ******************************************************* */

void account_packet(vpnproxy_data_t *proxy, const zdtun_pkt_t *pkt, uint8_t from_tun,
                    const zdtun_5tuple_t *conn_tuple, conn_data_t *data, uint64_t pkt_ms) {
#if 0
    if(from_tun)
        log_d("tun2net: %ld B", size);
    else
        log_d("net2tun: %lu B", size);
#endif

    if((data->sent_pkts + data->rcvd_pkts) == 0)
        data->first_seen = pkt_ms;
    data->last_seen = pkt_ms;

    if(from_tun) {
        data->sent_pkts++;
        data->sent_bytes += pkt->len;
        proxy->capture_stats.sent_pkts++;
        proxy->capture_stats.sent_bytes += pkt->len;
    } else {
        data->rcvd_pkts++;
        data->rcvd_bytes += pkt->len;
        proxy->capture_stats.rcvd_pkts++;
        proxy->capture_stats.rcvd_bytes += pkt->len;
    }

    if(data->ndpi_flow &&
            (!(pkt->flags & ZDTUN_PKT_IS_FRAGMENT) || (pkt->flags & ZDTUN_PKT_IS_FIRST_FRAGMENT))) {
        // nDPI cannot handle fragments, since they miss the L4 layer (see ndpi_iph_is_valid_and_not_fragmented)
        process_ndpi_packet(data, proxy, pkt, from_tun);

        if(((data->l7proto.master_protocol == NDPI_PROTOCOL_DNS) || (data->l7proto.app_protocol == NDPI_PROTOCOL_DNS))
                && (data->uid == UID_NETD)
                && (data->sent_pkts + data->rcvd_pkts == 1)
                && ((netd_resolve_waiting > 0) || ((next_connections_dump - NETD_RESOLVE_DELAY_MS) < proxy->now_ms))) {
            if(netd_resolve_waiting == 0) {
                // Wait before sending the dump to possibly resolve netd DNS connections uid.
                // Only delay for the first DNS request, to avoid excessive delay.
                log_d("Adding netd resolution delay");
                next_connections_dump += NETD_RESOLVE_DELAY_MS;
            }
            netd_resolve_waiting++;
        }
    }

    /* New stats to notify */
    proxy->capture_stats.new_stats = true;

    data->update_type |= CONN_UPDATE_STATS;
    notify_connection(&proxy->conns_updates, conn_tuple, data);

    if (proxy->pcap_dump.buffer) {
        int rec_size = pcap_rec_size(pkt->len);

        if ((JAVA_PCAP_BUFFER_SIZE - proxy->pcap_dump.buffer_idx) <= rec_size) {
            // Flush the buffer
            javaPcapDump(proxy);
        }

        if ((JAVA_PCAP_BUFFER_SIZE - proxy->pcap_dump.buffer_idx) <= rec_size)
            log_e("Invalid buffer size [size=%d, idx=%d, tot_size=%d]",
                  JAVA_PCAP_BUFFER_SIZE, proxy->pcap_dump.buffer_idx, rec_size);
        else {
            pcap_dump_rec(pkt, (u_char *) proxy->pcap_dump.buffer + proxy->pcap_dump.buffer_idx,
                    proxy, data);

            proxy->pcap_dump.buffer_idx += rec_size;
        }
    }
}

/* ******************************************************* */

static int run_tun(JNIEnv *env, jclass vpn, int tunfd, jint sdk) {
    netd_resolve_waiting = 0;
    jclass vpn_class = (*env)->GetObjectClass(env, vpn);

#ifdef PCAPDROID_TRACK_ALLOCS
    set_ndpi_malloc(pd_ndpi_malloc);
    set_ndpi_free(pd_ndpi_free);
#endif

    /* Classes */
    cls.vpn_service = vpn_class;
    cls.conn = jniFindClass(env, "com/emanuelef/remote_capture/model/ConnectionDescriptor");
    cls.conn_update = jniFindClass(env, "com/emanuelef/remote_capture/model/ConnectionUpdate");
    cls.stats = jniFindClass(env, "com/emanuelef/remote_capture/model/VPNStats");

    /* Methods */
    mids.reportError = jniGetMethodID(env, vpn_class, "reportError", "(Ljava/lang/String;)V");
    mids.getApplicationByUid = jniGetMethodID(env, vpn_class, "getApplicationByUid", "(I)Ljava/lang/String;"),
            mids.protect = jniGetMethodID(env, vpn_class, "protect", "(I)Z");
    mids.dumpPcapData = jniGetMethodID(env, vpn_class, "dumpPcapData", "([B)V");
    mids.updateConnections = jniGetMethodID(env, vpn_class, "updateConnections", "([Lcom/emanuelef/remote_capture/model/ConnectionDescriptor;[Lcom/emanuelef/remote_capture/model/ConnectionUpdate;)V");
    mids.sendStatsDump = jniGetMethodID(env, vpn_class, "sendStatsDump", "(Lcom/emanuelef/remote_capture/model/VPNStats;)V");
    mids.sendServiceStatus = jniGetMethodID(env, vpn_class, "sendServiceStatus", "(Ljava/lang/String;)V");
    mids.getLibprogPath = jniGetMethodID(env, vpn_class, "getLibprogPath", "(Ljava/lang/String;)Ljava/lang/String;");
    mids.notifyBlacklistsLoaded = jniGetMethodID(env, vpn_class, "notifyBlacklistsLoaded", "(III)V");
    mids.connInit = jniGetMethodID(env, cls.conn, "<init>", "(IIILjava/lang/String;Ljava/lang/String;IIIJ)V");
    mids.connProcessUpdate = jniGetMethodID(env, cls.conn, "processUpdate", "(Lcom/emanuelef/remote_capture/model/ConnectionUpdate;)V");
    mids.connUpdateInit = jniGetMethodID(env, cls.conn_update, "<init>", "(I)V");
    mids.connUpdateSetStats = jniGetMethodID(env, cls.conn_update, "setStats", "(JJJIIII)V");
    mids.connUpdateSetInfo = jniGetMethodID(env, cls.conn_update, "setInfo", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    mids.statsInit = jniGetMethodID(env, cls.stats, "<init>", "()V");
    mids.statsSetData = jniGetMethodID(env, cls.stats, "setData", "(Ljava/lang/String;JJIIIIIIIII)V");

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
            .pcap_dump = {
                    .enabled = (bool) getIntPref(env, vpn, "pcapDumpEnabled"),
            },
            .socks5 = {
                    .enabled = (bool) getIntPref(env, vpn, "getSocks5Enabled"),
                    .proxy_ip = getIPv4Pref(env, vpn, "getSocks5ProxyAddress"),
                    .proxy_port = htons(getIntPref(env, vpn, "getSocks5ProxyPort")),
            },
            .ipv6 = {
                    .enabled = (bool) getIntPref(env, vpn, "getIPv6Enabled"),
                    .dns_server = getIPv6Pref(env, vpn, "getIpv6DnsServer"),
            },
            .malware_detection = {
                    .enabled = (bool) getIntPref(env, vpn, "malwareDetectionEnabled"),
            }
    };

    getStringPref(&proxy, "getWorkingDir", proxy.cachedir, sizeof(proxy.cachedir));
    strcat(proxy.cachedir, "/");
    proxy.cachedir_len = strlen(proxy.cachedir);

    getStringPref(&proxy, "getPersistentDir", proxy.filesdir, sizeof(proxy.filesdir));
    strcat(proxy.filesdir, "/");
    proxy.filesdir_len = strlen(proxy.filesdir);

    // Enable or disable the PCAPdroid trailer
    pcap_set_pcapdroid_trailer((bool)getIntPref(env, vpn, "addPcapdroidTrailer"));

    /* Important: init global state every time. Android may reuse the service. */
    running = true;

    logcallback = log_callback;
    global_proxy = &proxy;

    /* nDPI */
    proxy.ndpi = init_ndpi();
    init_protocols_bitmask(&masterProtos);
    if(proxy.ndpi == NULL) {
        log_f("nDPI initialization failed");
        return(-1);
    }

    // Load the blacklist before starting
    if(proxy.malware_detection.enabled && reload_blacklists_now) {
        reload_blacklists_now = false;
        load_new_blacklists(&proxy);
        use_new_blacklists(&proxy);
    }

    signal(SIGPIPE, SIG_IGN);

    if(proxy.pcap_dump.enabled) {
        proxy.pcap_dump.buffer = pd_malloc(JAVA_PCAP_BUFFER_SIZE);
        proxy.pcap_dump.buffer_idx = 0;

        if(!proxy.pcap_dump.buffer) {
            log_f("malloc(pcap_dump.buffer) failed with code %d/%s",
                        errno, strerror(errno));
            running = false;
        }
    }

    memset(&proxy.stats, 0, sizeof(proxy.stats));

    refresh_time(&proxy);
    last_connections_dump = proxy.now_ms;
    next_connections_dump = last_connections_dump + 500 /* first update after 500 ms */;

    notifyServiceStatus(&proxy, "started");

    // Run the capture
    int rv = proxy.root_capture ? run_root(&proxy) : run_proxy(&proxy);

    log_d("Stopped packet loop");

    conns_clear(&proxy.new_conns, true);
    conns_clear(&proxy.conns_updates, true);

    if(proxy.malware_detection.enabled) {
        if(proxy.malware_detection.reload_in_progress) {
            log_d("Joining blacklists reload_worker");
            pthread_join(proxy.malware_detection.reload_worker, NULL);
        }
        if(proxy.malware_detection.bl)
            blacklist_destroy(proxy.malware_detection.bl);
    }
    ndpi_exit_detection_module(proxy.ndpi);

    if(proxy.pcap_dump.buffer) {
        if(proxy.pcap_dump.buffer_idx > 0)
            javaPcapDump(&proxy);

        pd_free(proxy.pcap_dump.buffer);
        proxy.pcap_dump.buffer = NULL;
    }

    uid_to_app_t *e, *tmp;
    HASH_ITER(hh, proxy.uid2app, e, tmp) {
        HASH_DEL(proxy.uid2app, e);
        pd_free(e);
    }

    notifyServiceStatus(&proxy, "stopped");
    destroy_uid_resolver(proxy.resolver);
    ndpi_ptree_destroy(proxy.known_dns_servers);

    log_d("Host LRU cache size: %d", ip_lru_size(proxy.ip_to_host));
    log_d("Discarded fragments: %ld", proxy.num_discarded_fragments);
    ip_lru_destroy(proxy.ip_to_host);

    logcallback = NULL;
    global_proxy = NULL;

#ifdef PCAPDROID_TRACK_ALLOCS
    log_i(get_allocs_summary());
#endif

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

JNIEXPORT jbyteArray JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_getPcapHeader(JNIEnv *env, jclass clazz) {
    struct pcap_hdr_s pcap_hdr;

    pcap_build_hdr(&pcap_hdr);

    jbyteArray barray = (*env)->NewByteArray(env, sizeof(struct pcap_hdr_s));
    if((barray == NULL) || jniCheckException(env))
        return NULL;

    (*env)->SetByteArrayRegion(env, barray, 0, sizeof(struct pcap_hdr_s), (jbyte*)&pcap_hdr);

    if(jniCheckException(env)) {
        (*env)->DeleteLocalRef(env, barray);
        return NULL;
    }

    return barray;
}

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_reloadBlacklists(JNIEnv *env, jclass clazz) {
    reload_blacklists_now = true;
}