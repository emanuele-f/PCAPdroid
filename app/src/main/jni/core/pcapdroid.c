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
#include <assert.h> // NOTE: look for "assertion" in logcat
#include "pcapdroid.h"
#include "pcap_utils.h"
#include "common/utils.h"
#include "ndpi_protocol_ids.h"

// Minimum length (e.g. of "GET") to avoid reporting non-requests
#define MIN_REQ_PLAINTEXT_CHARS 3

extern int run_vpn(pcapdroid_t *pd, int tunfd);
extern int run_root(pcapdroid_t *pd);

/* ******************************************************* */

jni_classes_t cls;
jni_methods_t mids;
jni_fields_t fields;
bool running = false;
uint32_t new_dns_server = 0;
bool block_private_dns = false;

static bool dump_capture_stats_now = false;
static bool reload_blacklists_now = false;
static ndpi_protocol_bitmask_struct_t masterProtos;
static int bl_num_checked_connections = 0;

/* ******************************************************* */

/* NOTE: these must be reset during each run, as android may reuse the service */
static int netd_resolve_waiting;
static u_int64_t last_connections_dump;
static u_int64_t next_connections_dump;
static pcapdroid_t *global_pd = NULL;

/* ******************************************************* */

static void conn_free_ndpi(pd_conn_t *data) {
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

static uint16_t ndpi2proto(ndpi_protocol proto) {
    // The nDPI master/app protocol logic is not clear (e.g. the first packet of a DNS flow has
    // master_protocol unknown whereas the second has master_protocol set to DNS). We are not interested
    // in the app protocols, so just take the one that's not unknown.
    return((proto.master_protocol != NDPI_PROTOCOL_UNKNOWN) ? proto.master_protocol : proto.app_protocol);
}

/* ******************************************************* */

void pd_purge_connection(pd_conn_t *data) {
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

static void notif_connection(pcapdroid_t *pd, conn_array_t *arr, const zdtun_5tuple_t *tuple, pd_conn_t *data) {
    // End the detection when the connection is closed
    // Always check this, even pending_notification are present
    if(data->status >= CONN_STATUS_CLOSED)
        pd_giveup_dpi(pd, data, tuple);

    if(data->pending_notification)
        return;

    if(arr->cur_items >= arr->size) {
        /* Extend array */
        arr->size = (arr->size == 0) ? 8 : (arr->size * 2);
        arr->items = pd_realloc(arr->items, arr->size * sizeof(conn_and_tuple_t));

        if(arr->items == NULL) {
            log_e("realloc(conn_array_t) (%d items) failed", arr->size);
            return;
        }
    }

    conn_and_tuple_t *slot = &arr->items[arr->cur_items++];
    slot->tuple = *tuple;
    slot->data = data;
    data->pending_notification = true;
}

/* Call this when the connection data has changed. The connection data will sent to JAVA during the
 * next sendConnectionsDump. The type of change is determined by the data->update_type. */
void pd_notify_connection_update(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, pd_conn_t *data) {
    notif_connection(pd, &pd->conns_updates, tuple, data);
}

/* ******************************************************* */

static void conns_clear(conn_array_t *arr, bool free_all) {
    if(arr->items) {
        for(int i=0; i < arr->cur_items; i++) {
            conn_and_tuple_t *slot = &arr->items[i];

            if(slot->data && (slot->data->to_purge || free_all))
                pd_purge_connection(slot->data);
        }

        pd_free(arr->items);
        arr->items = NULL;
    }

    arr->size = 0;
    arr->cur_items = 0;
}

/* ******************************************************* */

char* getStringPref(pcapdroid_t *pd, const char *key, char *buf, int bufsize) {
    JNIEnv *env = pd->env;

    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()Ljava/lang/String;");
    jstring obj = (*env)->CallObjectMethod(env, pd->capture_service, midMethod);
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

static void getApplicationByUidJava(pcapdroid_t *pd, jint uid, char *buf, int bufsize) {
    JNIEnv *env = pd->env;
    const char *value = NULL;

    jstring obj = (*env)->CallObjectMethod(env, pd->capture_service, mids.getApplicationByUid, uid);
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

char* get_appname_by_uid(pcapdroid_t *pd, int uid, char *buf, int bufsize) {
    uid_to_app_t *app_entry;

    HASH_FIND_INT(pd->uid2app, &uid, app_entry);
    if(app_entry == NULL) {
        app_entry = (uid_to_app_t*) pd_malloc(sizeof(uid_to_app_t));

        if(app_entry) {
            // Resolve the app name
            getApplicationByUidJava(pd, uid, app_entry->appname, sizeof(app_entry->appname));

            log_d("uid %d resolved to \"%s\"", uid, app_entry->appname);

            app_entry->uid = uid;
            HASH_ADD_INT(pd->uid2app, uid, app_entry);
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

const char *getProtoName(struct ndpi_detection_module_struct *mod, uint16_t proto, int ipproto) {
    if((proto == NDPI_PROTOCOL_UNKNOWN) || !NDPI_ISSET(&masterProtos, proto)) {
        // Return the L3 protocol
        return zdtun_proto2str(ipproto);
    }

    return ndpi_get_proto_name(mod, proto);
}

/* ******************************************************* */

static void check_blacklisted_domain(pcapdroid_t *pd, pd_conn_t *data, const zdtun_5tuple_t *tuple) {
    if(data->info && data->info[0]) {
        if(pd->malware_detection.bl && !data->blacklisted_domain) {
            data->blacklisted_domain = blacklist_match_domain(pd->malware_detection.bl,
                                                              data->info);
            if (data->blacklisted_domain) {
                char appbuf[64];
                char buf[512];

                get_appname_by_uid(pd, data->uid, appbuf, sizeof(appbuf));
                log_w("Blacklisted domain [%s]: %s [%s]", data->info,
                      zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);

                // Block all the blacklisted domains
                data->to_block |= (pd->firewall.bl != NULL);
            }
        }
        if(pd->firewall.bl && !data->to_block) {
            // Check if the domain is explicitly blocked
            data->to_block |= blacklist_match_domain(pd->firewall.bl, data->info);
            if(data->to_block) {
                char appbuf[64];
                char buf[512];

                get_appname_by_uid(pd, data->uid, appbuf, sizeof(appbuf));
                log_w("Blocked domain [%s]: %s [%s]", data->info, zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);
            }

            log_d("Domain check: %s -> %d", data->info, data->to_block);
        }
    }
}

/* ******************************************************* */

pd_conn_t* pd_new_connection(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, int uid) {
    pd_conn_t *data = pd_calloc(1, sizeof(pd_conn_t));
    if(!data) {
        log_e("calloc(pd_conn_t) failed with code %d/%s",
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
    data->incr_id = pd->new_conn_id++;

    // Try to resolve host name via the LRU cache
    const zdtun_ip_t dst_ip = tuple->dst_ip;
    data->info = ip_lru_find(pd->ip_to_host, &dst_ip);

    if(data->info) {
        char resip[INET6_ADDRSTRLEN];
        int family = (tuple->ipver == 4) ? AF_INET : AF_INET6;

        resip[0] = '\0';
        inet_ntop(family, &dst_ip, resip, sizeof(resip));

        log_d("Host LRU cache HIT: %s -> %s", resip, data->info);
        data->info_from_lru = true;

        if(data->uid != UID_UNKNOWN) {
            // When a DNS request is followed by a TLS connection or similar, mark the DNS request
            // with the uid of this connection. This allows us to match netd requests to actual apps.
            // Only change the uid of new connections (pd->new_conns) to avoid possible side effects
            for(int i=0; i < pd->new_conns.cur_items; i++) {
                conn_and_tuple_t *conn = &pd->new_conns.items[i];

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

        check_blacklisted_domain(pd, data, tuple);
    }

    if(pd->malware_detection.bl) {
        data->blacklisted_ip = blacklist_match_ip(pd->malware_detection.bl, &dst_ip, tuple->ipver);
        if(data->blacklisted_ip) {
            char appbuf[64];
            char buf[256];

            get_appname_by_uid(pd, data->uid, appbuf, sizeof(appbuf));
            log_w("Blacklisted dst ip: %s [%s]", zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);

            data->to_block |= (pd->firewall.bl != NULL);
        }

        bl_num_checked_connections++;
    }
    if(pd->firewall.bl && !data->to_block) {
        data->to_block |= blacklist_match_ip(pd->firewall.bl, &dst_ip, tuple->ipver);
        if(data->to_block) {
            char appbuf[64];
            char buf[256];

            get_appname_by_uid(pd, data->uid, appbuf, sizeof(appbuf));
            log_w("Blocked ip: %s [%s]", zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);
        } else {
            data->to_block |= blacklist_match_uid(pd->firewall.bl, data->uid);
            if(data->to_block) {
                char appbuf[64];
                char buf[256];

                get_appname_by_uid(pd, data->uid, appbuf, sizeof(appbuf));
                log_w("Blocked app: %s [%s]", zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);
            }
        }
    }

    notif_connection(pd, &pd->new_conns, tuple, data);

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

static void process_ndpi_data(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, pd_conn_t *data) {
    char *found_info = NULL;

    switch(data->l7proto) {
        case NDPI_PROTOCOL_DNS:
            if(data->ndpi_flow->host_server_name[0])
                found_info = (char*)data->ndpi_flow->host_server_name;
            break;
        case NDPI_PROTOCOL_HTTP:
            if(data->ndpi_flow->host_server_name[0] &&
               !is_numeric_host((char*)data->ndpi_flow->host_server_name))
                found_info = (char*)data->ndpi_flow->host_server_name;

            if(!data->url && data->ndpi_flow->http.url) {
                data->url = pd_strndup(data->ndpi_flow->http.url, 256);
                data->update_type |= CONN_UPDATE_INFO;
            }

            break;
        case NDPI_PROTOCOL_TLS:
            if(data->ndpi_flow->protos.tls_quic_stun.tls_quic.client_requested_server_name[0])
                found_info = (char*)data->ndpi_flow->protos.tls_quic_stun.tls_quic.client_requested_server_name;
            break;
    }

    if(found_info && (!data->info || data->info_from_lru)) {
        if(data->info)
            pd_free(data->info);
        data->info = pd_strndup(found_info, 256);
        data->info_from_lru = false;

        check_blacklisted_domain(pd, data, tuple);
        data->update_type |= CONN_UPDATE_INFO;
    }
}

/* ******************************************************* */

/* Stop the DPI detection and determine the l7proto of the connection. */
void pd_giveup_dpi(pcapdroid_t *pd, pd_conn_t *data, const zdtun_5tuple_t *tuple) {
    if(!data->ndpi_flow)
        return;

    if(data->l7proto == NDPI_PROTOCOL_UNKNOWN) {
        uint8_t proto_guessed;
        data->l7proto = ndpi2proto(ndpi_detection_giveup(pd->ndpi, data->ndpi_flow, 1 /* Guess */,
                                              &proto_guessed));
    }

    log_d("nDPI completed[ipver=%d, proto=%d] -> l7proto: %d",
                tuple->ipver, tuple->ipproto, data->l7proto);

    process_ndpi_data(pd, tuple, data);
    conn_free_ndpi(data);
}

/* ******************************************************* */

static int is_plaintext(char c) {
    return isprint(c) || (c == '\r') || (c == '\n') || (c == '\t');
}

/* ******************************************************* */

static void process_request_data(pcapdroid_t *pd, pkt_context_t *pctx) {
    const zdtun_pkt_t *pkt = pctx->pkt;
    pd_conn_t *data = pctx->data;

    if(pkt->l7_len > 0) {
        if(pctx->is_tx && is_plaintext(pkt->l7[0])) {
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

static void process_dns_reply(pd_conn_t *data, pcapdroid_t *pd, const struct zdtun_pkt *pkt) {
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
                ip_lru_add(pd->ip_to_host, &rsp_addr, query);
            }

            reply += addr_len; len -= addr_len;
        }
    }
}

/* ******************************************************* */

static void perform_dpi(pcapdroid_t *pd, pkt_context_t *pctx) {
    pd_conn_t *data = pctx->data;
    bool giveup = ((data->sent_pkts + data->rcvd_pkts + 1) >= MAX_DPI_PACKETS);
    zdtun_pkt_t *pkt = pctx->pkt;
    bool is_tx = pctx->is_tx;

    uint16_t old_proto = data->l7proto;
    data->l7proto = ndpi2proto(ndpi_detection_process_packet(pd->ndpi, data->ndpi_flow, (const u_char *)pkt->buf,
            pkt->len, data->last_seen,
            is_tx ? data->src_id : data->dst_id,
            is_tx ? data->dst_id : data->src_id));

    if(old_proto != data->l7proto)
        data->update_type |= CONN_UPDATE_INFO;

    if((!data->request_done) && !data->ndpi_flow->packet.tcp_retransmission)
        process_request_data(pd, pctx);

    if(!is_tx && (data->l7proto == NDPI_PROTOCOL_DNS))
        process_dns_reply(data, pd, pkt);

    if(giveup || ((data->l7proto != NDPI_PROTOCOL_UNKNOWN) &&
            !ndpi_extra_dissection_possible(pd->ndpi, data->ndpi_flow)))
        pd_giveup_dpi(pd, data, &pkt->tuple); // calls process_ndpi_data
    else
        process_ndpi_data(pd, &pkt->tuple, data);

    if((data->l7proto == NDPI_PROTOCOL_DNS)
       && (data->uid == UID_NETD)
       && (data->sent_pkts + data->rcvd_pkts == 0)
       && ((netd_resolve_waiting > 0) || ((next_connections_dump - NETD_RESOLVE_DELAY_MS) < pd->now_ms))) {
        if(netd_resolve_waiting == 0) {
            // Wait before sending the dump to possibly resolve netd DNS connections uid.
            // Only delay for the first DNS request, to avoid excessive delay.
            log_d("Adding netd resolution delay");
            next_connections_dump += NETD_RESOLVE_DELAY_MS;
        }
        netd_resolve_waiting++;
    }
}

/* ******************************************************* */

static void javaPcapDump(pcapdroid_t *pd) {
    JNIEnv *env = pd->env;

    log_d("Exporting a %d B PCAP buffer", pd->pcap_dump.buffer_idx);

    jbyteArray barray = (*env)->NewByteArray(env, pd->pcap_dump.buffer_idx);
    if(jniCheckException(env))
        return;

    (*env)->SetByteArrayRegion(env, barray, 0, pd->pcap_dump.buffer_idx, pd->pcap_dump.buffer);
    (*env)->CallVoidMethod(env, pd->capture_service, mids.dumpPcapData, barray);
    jniCheckException(env);

    pd->pcap_dump.buffer_idx = 0;
    pd->pcap_dump.last_dump_ms = pd->now_ms;

    (*env)->DeleteLocalRef(env, barray);
}

/* ******************************************************* */

static jobject getConnUpdate(pcapdroid_t *pd, const conn_and_tuple_t *conn) {
    JNIEnv *env = pd->env;
    pd_conn_t *data = conn->data;

    jobject update = (*env)->NewObject(env, cls.conn_update, mids.connUpdateInit, data->incr_id);

    if((update == NULL) || jniCheckException(env)) {
        log_e("NewObject(ConnectionDescriptor) failed");
        return NULL;
    }

    if(data->update_type & CONN_UPDATE_STATS) {
        (*env)->CallVoidMethod(env, update, mids.connUpdateSetStats, data->last_seen,
                               data->sent_bytes, data->rcvd_bytes, data->sent_pkts, data->rcvd_pkts, data->blocked_pkts,
                               (data->tcp_flags[0] << 8) | data->tcp_flags[1],
                               (data->to_block << 10) | (data->blacklisted_domain << 9) |
                                    (data->blacklisted_ip << 8) | (data->status & 0xFF));
    }
    if(data->update_type & CONN_UPDATE_INFO) {
        jobject info = (*env)->NewStringUTF(env, data->info ? data->info : "");
        jobject url = (*env)->NewStringUTF(env, data->url ? data->url : "");
        jobject req = (*env)->NewStringUTF(env, (data->request_data &&
            (strnlen(data->request_data, MIN_REQ_PLAINTEXT_CHARS) == MIN_REQ_PLAINTEXT_CHARS)) ? data->request_data : "");
        jobject l7proto = (*env)->NewStringUTF(env, getProtoName(pd->ndpi, data->l7proto, conn->tuple.ipproto));

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

static int dumpNewConnection(pcapdroid_t *pd, const conn_and_tuple_t *conn, jobject arr, int idx) {
    char srcip[INET6_ADDRSTRLEN], dstip[INET6_ADDRSTRLEN];
    JNIEnv *env = pd->env;
    const zdtun_5tuple_t *conn_info = &conn->tuple;
    const pd_conn_t *data = conn->data;
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
    u_int ifidx = (pd->root_capture ? data->root.ifidx : 0);
    jobject conn_descriptor = (*env)->NewObject(env, cls.conn, mids.connInit, data->incr_id,
                                                conn_info->ipver, conn_info->ipproto,
                                                src_string, dst_string,
                                                ntohs(conn_info->src_port), ntohs(conn_info->dst_port),
                                                data->uid, ifidx, data->first_seen);

    if((conn_descriptor != NULL) && !jniCheckException(env)) {
        // This is the first update, send all the data
        conn->data->update_type = CONN_UPDATE_STATS | CONN_UPDATE_INFO;
        jobject update = getConnUpdate(pd, conn);

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

static int dumpConnectionUpdate(pcapdroid_t *pd, const conn_and_tuple_t *conn, jobject arr, int idx) {
    JNIEnv *env = pd->env;
    jobject update = getConnUpdate(pd, conn);

    if(update != NULL) {
        (*env)->SetObjectArrayElement(env, arr, idx, update);
        (*env)->DeleteLocalRef(env, update);
        return 0;
    }

    return -1;
}

/* ******************************************************* */

/* Perform a full dump of the active connections */
static void sendConnectionsDump(pcapdroid_t *pd) {
    if((pd->new_conns.cur_items == 0) && (pd->conns_updates.cur_items == 0))
        return;

    log_d("sendConnectionsDump [after %" PRIu64 " ms]: new=%d, updates=%d",
          pd->now_ms - last_connections_dump,
          pd->new_conns.cur_items, pd->conns_updates.cur_items);

    JNIEnv *env = pd->env;
    jobject new_conns = (*env)->NewObjectArray(env, pd->new_conns.cur_items, cls.conn, NULL);
    jobject conns_updates = (*env)->NewObjectArray(env, pd->conns_updates.cur_items, cls.conn_update, NULL);

    if((new_conns == NULL) || (conns_updates == NULL) || jniCheckException(env)) {
        log_e("NewObjectArray() failed");
        goto cleanup;
    }

    // New connections
    for(int i=0; i < pd->new_conns.cur_items; i++) {
        conn_and_tuple_t *conn = &pd->new_conns.items[i];
        conn->data->pending_notification = false;

        if(dumpNewConnection(pd, conn, new_conns, i) < 0)
            goto cleanup;
    }

    //clock_t start = clock();

    // Updated connections
    for(int i=0; i < pd->conns_updates.cur_items; i++) {
        conn_and_tuple_t *conn = &pd->conns_updates.items[i];
        conn->data->pending_notification = false;

        if(dumpConnectionUpdate(pd, conn, conns_updates, i) < 0)
            goto cleanup;
    }

    //double cpu_time_used = ((double) (clock() - start)) / CLOCKS_PER_SEC;
    //log_d("avg cpu_time_used per update: %f sec", cpu_time_used / pd->conns_updates.cur_items);

    /* Send the dump */
    (*env)->CallVoidMethod(env, pd->capture_service, mids.updateConnections, new_conns, conns_updates);
    jniCheckException(env);

cleanup:
    conns_clear(&pd->new_conns, false);
    conns_clear(&pd->conns_updates, false);

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

static void sendStatsDump(const pcapdroid_t *pd) {
    JNIEnv *env = pd->env;
    const capture_stats_t *capstats = &pd->capture_stats;
    const zdtun_statistics_t *stats = &pd->stats;
    jstring allocs_summary =
#ifdef PCAPDROID_TRACK_ALLOCS
            (*pd->env)->NewStringUTF(pd->env, get_allocs_summary());
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
                           min(pd->num_dropped_pkts, INT_MAX), pd->num_dropped_connections,
                           stats->num_open_sockets, stats->all_max_fd, active_conns, tot_conns,
                           pd->num_dns_requests);

    if(!jniCheckException(env)) {
        (*env)->CallVoidMethod(env, pd->capture_service, mids.sendStatsDump, stats_obj);
        jniCheckException(env);
    }

    (*env)->DeleteLocalRef(env, allocs_summary);
    (*env)->DeleteLocalRef(env, stats_obj);
}

/* ******************************************************* */

static void notifyServiceStatus(pcapdroid_t *pd, const char *status) {
    JNIEnv *env = pd->env;
    jstring status_str;

    status_str = (*env)->NewStringUTF(env, status);

    (*env)->CallVoidMethod(env, pd->capture_service, mids.sendServiceStatus, status_str);
    jniCheckException(env);

    (*env)->DeleteLocalRef(env, status_str);
}

/* ******************************************************* */

const char* get_cache_path(const char *subpath) {
    strncpy(global_pd->cachedir + global_pd->cachedir_len, subpath,
            sizeof(global_pd->cachedir) - global_pd->cachedir_len - 1);
    global_pd->cachedir[sizeof(global_pd->cachedir) - 1] = 0;
    return global_pd->cachedir;
}

/* ******************************************************* */

const char* get_file_path(const char *subpath) {
    strncpy(global_pd->filesdir + global_pd->filesdir_len, subpath,
            sizeof(global_pd->filesdir) - global_pd->filesdir_len - 1);
    global_pd->filesdir[sizeof(global_pd->filesdir) - 1] = 0;
    return global_pd->filesdir;
}

/* ******************************************************* */

// called after load_new_blacklists
static void use_new_blacklists(pcapdroid_t *pd) {
    JNIEnv *env = pd->env;

    if(!pd->malware_detection.new_bl)
        return;

    if(pd->malware_detection.bl)
        blacklist_destroy(pd->malware_detection.bl);
    pd->malware_detection.bl = pd->malware_detection.new_bl;
    pd->malware_detection.new_bl = NULL;

    bl_status_arr_t *status_arr = pd->malware_detection.status_arr;
    pd->malware_detection.status_arr = NULL;

    jobject status_obj = (*env)->NewObjectArray(env, status_arr ? status_arr->cur_items : 0, cls.blacklist_status, NULL);
    if((status_obj == NULL) || jniCheckException(env)) {
        log_e("NewObjectArray() failed");
        goto cleanup;
    }

    // Notify
    if(status_arr != NULL) {
        for(int i=0; i<status_arr->cur_items; i++) {
            bl_status_t *st = &status_arr->items[i];
            jstring fname = (*env)->NewStringUTF(env, st->fname);
            if((fname == NULL) || jniCheckException(env))
                break;

            jobject stats = (*env)->NewObject(env, cls.blacklist_status, mids.blacklistStatusInit,
                                                  fname, st->num_rules);
            if((stats == NULL) || jniCheckException(env)) {
                (*env)->DeleteLocalRef(env, fname);
                break;
            }

            (*env)->SetObjectArrayElement(env, status_obj, i, stats);
            if(jniCheckException(env)) {
                (*env)->DeleteLocalRef(env, stats);
                break;
            }
        }
    }
    (*pd->env)->CallVoidMethod(pd->env, pd->capture_service, mids.notifyBlacklistsLoaded, status_obj);

cleanup:
    if(status_arr != NULL) {
        for(int i = 0; i < status_arr->cur_items; i++) {
            bl_status_t *st = &status_arr->items[i];
            pd_free(st->fname);
        }
        pd_free(status_arr->items);
        pd_free(status_arr);
    }
}

/* ******************************************************* */

// Load information about the blacklists to use (pd->malware_detection.bls_info)
int load_blacklists_info(pcapdroid_t *pd) {
    int rv = 0;
    JNIEnv *env = pd->env;
    jobjectArray *arr = (*env)->CallObjectMethod(env, pd->capture_service, mids.getBlacklistsInfo);
    pd->malware_detection.bls_info = NULL;
    pd->malware_detection.num_bls = 0;

    if((jniCheckException(pd->env) != 0) || (arr == NULL))
        return -1;

    pd->malware_detection.num_bls = (*env)->GetArrayLength(env, arr);
    if(pd->malware_detection.num_bls == 0)
        goto cleanup;

    pd->malware_detection.bls_info = (bl_info_t*) pd_calloc(pd->malware_detection.num_bls, sizeof(bl_info_t));
    if(pd->malware_detection.bls_info == NULL) {
        pd->malware_detection.num_bls = 0;
        rv = -1;
        goto cleanup;
    }

    jobject type_ip = jniEnumVal(env, "com/emanuelef/remote_capture/model/BlacklistDescriptor$Type", "IP_BLACKLIST");

    for(int i = 0; i < pd->malware_detection.num_bls; i++) {
        jobject *bl_descr = (*env)->GetObjectArrayElement(env, arr, i);
        if(bl_descr != NULL) {
            bl_info_t *blinfo = &pd->malware_detection.bls_info[i];

            jstring fname_obj = (*env)->GetObjectField(env, bl_descr, fields.bldescr_fname);
            const char *fname = (*env)->GetStringUTFChars(env, fname_obj, 0);
            blinfo->fname = pd_strdup(fname);
            (*env)->ReleaseStringUTFChars(env, fname_obj, fname);
            (*pd->env)->DeleteLocalRef(pd->env, fname_obj);

            jobject bl_type = (*env)->GetObjectField(env, bl_descr, fields.bldescr_type);
            blinfo->type = (*env)->IsSameObject(env, bl_type, type_ip) ? IP_BLACKLIST : DOMAIN_BLACKLIST;
            (*pd->env)->DeleteLocalRef(pd->env, bl_type);

            //log_d("[+] Blacklist: %s (%s)", blinfo->fname, (blinfo->type == IP_BLACKLIST) ? "IP" : "domain");
        }
    }

cleanup:
    (*pd->env)->DeleteLocalRef(pd->env, arr);
    return rv;
}

/* ******************************************************* */

// Loads the blacklists data into new_bl and sets reload_done.
// use_new_blacklists needs to be called to use it.
static void* load_new_blacklists(void *data) {
    pcapdroid_t *pd = (pcapdroid_t*) data;
    bl_status_arr_t *status_arr = pd_calloc(1, sizeof(bl_status_arr_t));
    if(!status_arr) {
        pd->malware_detection.reload_done = true;
        return NULL;
    }

    blacklist_t *bl = blacklist_init();
    if(!bl) {
        pd_free(status_arr);
        pd->malware_detection.reload_done = true;
        return NULL;
    }

    clock_t start = clock();

    // load files in the malware_bl directory
    for(int i = 0; i < pd->malware_detection.num_bls; i++) {
        bl_info_t *blinfo = &pd->malware_detection.bls_info[i];
        char subpath[256];
        blacklist_stats_t stats;

        snprintf(subpath, sizeof(subpath), "malware_bl/%s", blinfo->fname);

        if(blacklist_load_file(bl, get_file_path(subpath), blinfo->type, &stats) == 0) {
            // NOTE: cannot invoke JNI from this thread, must use an intermediate storage
            if(status_arr->size >= status_arr->cur_items) {
                /* Extend array */
                status_arr->size = (status_arr->size == 0) ? 8 : (status_arr->size * 2);
                status_arr->items = pd_realloc(status_arr->items, status_arr->size * sizeof(bl_status_t));
                if(!status_arr->items) {
                    log_e("realloc(bl_status_arr_t) (%d items) failed", status_arr->size);
                    status_arr->size = 0;
                    continue;
                }
            }

            char *fname = pd_strdup(blinfo->fname);
            if(!fname)
                continue;

            bl_status_t *status = &status_arr->items[status_arr->cur_items++];
            status->fname = fname;
            status->num_rules = stats.num_rules;
        }
    }

    // Test domain/IP to test blacklist match
    blacklist_add_domain(bl, "internetbadguys.com");
    blacklist_add_ipstr(bl, "0.0.0.1");

    log_d("Blacklists loaded in %.3f sec", ((double) (clock() - start)) / CLOCKS_PER_SEC);

    pd->malware_detection.new_bl = bl;
    pd->malware_detection.status_arr = status_arr;
    pd->malware_detection.reload_done = true;
    return NULL;
}

/* ******************************************************* */

static int check_blocked_conn_cb(zdtun_t *zdt, const zdtun_conn_t *conn_info, void *userdata) {
    pcapdroid_t *pd = (pcapdroid_t*) userdata;
    pd_conn_t *data = zdtun_conn_get_userdata(conn_info);
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn_info);
    zdtun_ip_t dst_ip = tuple->dst_ip;
    blacklist_t *bl = pd->firewall.bl;
    bool old_block = data->to_block;

    data->to_block = (data->blacklisted_internal || data->blacklisted_ip || data->blacklisted_domain) ||
            blacklist_match_uid(bl, data->uid) ||
            blacklist_match_ip(bl, &dst_ip, tuple->ipver) ||
            (data->info && data->info[0] && blacklist_match_domain(bl, data->info));

    if(old_block != data->to_block) {
        data->update_type |= CONN_UPDATE_STATS;
        pd_notify_connection_update(pd, tuple, data);
    }

    // continue
    return 0;
}

/* ******************************************************* */

/* Perfom periodic tasks. This should be called after processing a packet or after some time has
 * passed (e.g. after a select with no packet). */
void pd_housekeeping(pcapdroid_t *pd) {
    if(pd->capture_stats.new_stats
       && ((pd->now_ms - pd->capture_stats.last_update_ms) >= CAPTURE_STATS_UPDATE_FREQUENCY_MS) ||
       dump_capture_stats_now) {
        dump_capture_stats_now = false;

        if(!pd->root_capture)
            zdtun_get_stats(pd->zdt, &pd->stats);

        sendStatsDump(pd);

        pd->capture_stats.new_stats = false;
        pd->capture_stats.last_update_ms = pd->now_ms;
    } else if (pd->now_ms >= next_connections_dump) {
        sendConnectionsDump(pd);
        last_connections_dump = pd->now_ms;
        next_connections_dump = pd->now_ms + CONNECTION_DUMP_UPDATE_FREQUENCY_MS;
        netd_resolve_waiting = 0;
    } else if ((pd->pcap_dump.buffer_idx > 0)
               && (pd->now_ms - pd->pcap_dump.last_dump_ms) >= MAX_JAVA_DUMP_DELAY_MS) {
        javaPcapDump(pd);
    } else if(pd->malware_detection.enabled) {
        // Malware detection
        if(pd->malware_detection.reload_in_progress) {
            if(pd->malware_detection.reload_done) {
                pthread_join(pd->malware_detection.reload_worker, NULL);
                pd->malware_detection.reload_in_progress = false;
                use_new_blacklists(pd);
            }
        } else if(reload_blacklists_now) {
            reload_blacklists_now = false;
            pd->malware_detection.reload_done = false;
            pd->malware_detection.new_bl = NULL;
            pd->malware_detection.status_arr = NULL;
            pthread_create(&pd->malware_detection.reload_worker, NULL, load_new_blacklists,
                           pd);
            pd->malware_detection.reload_in_progress = true;
        }
    }

    if(pd->firewall.new_bl) {
        // Load new bl
        if(pd->firewall.bl)
            blacklist_destroy(pd->firewall.bl);
        pd->firewall.bl = pd->firewall.new_bl;
        pd->firewall.new_bl = NULL;

        if(pd->zdt)
            zdtun_iter_connections(pd->zdt, check_blocked_conn_cb, pd);
    }
}

/* ******************************************************* */

/* Refresh the monotonic time. This must be called before any call to pd_housekeeping. */
void pd_refresh_time(pcapdroid_t *pd) {
    struct timespec ts;

    if(clock_gettime(CLOCK_MONOTONIC_COARSE, &ts)) {
        log_d("clock_gettime failed[%d]: %s", errno, strerror(errno));
        return;
    }

    pd->now_ms = (uint64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ******************************************************* */

static void log_callback(int lvl, const char *line) {
    if(lvl >= ANDROID_LOG_FATAL) {
        pcapdroid_t *pd = global_pd;

        // This is a fatal error, report it to the gui
        jobject info_string = (*pd->env)->NewStringUTF(pd->env, line);

        if((jniCheckException(pd->env) != 0) || (info_string == NULL))
            return;

        (*pd->env)->CallVoidMethod(pd->env, pd->capture_service, mids.reportError, info_string);
        jniCheckException(pd->env);

        (*pd->env)->DeleteLocalRef(pd->env, info_string);
    }
}

/* ******************************************************* */

void fill_custom_data(struct pcapdroid_trailer *cdata, pcapdroid_t *pd, pd_conn_t *conn) {
    memset(cdata, 0, sizeof(*cdata));

    cdata->magic = htonl(PCAPDROID_TRAILER_MAGIC);
    cdata->uid = htonl(conn->uid);
    get_appname_by_uid(pd, conn->uid, cdata->appname, sizeof(cdata->appname));
}

/* ******************************************************* */

/* Process the packet (e.g. perform DPI) and fill the packet context. */
void pd_process_packet(pcapdroid_t *pd, zdtun_pkt_t *pkt, bool is_tx, const zdtun_5tuple_t *tuple,
                       pd_conn_t *data, struct timeval *tv, pkt_context_t *pctx) {
    pctx->pkt = pkt;
    pctx->tv = *tv;
    pctx->ms = (uint64_t)tv->tv_sec * 1000 + tv->tv_usec / 1000;
    pctx->is_tx = is_tx;
    pctx->tuple = tuple;
    pctx->data = data;

    // NOTE: pd_account_stats will not be called for blocked connections
    data->last_seen = pctx->ms;
    if(!data->first_seen)
        data->first_seen = pctx->ms;

    if(data->ndpi_flow &&
       (!(pkt->flags & ZDTUN_PKT_IS_FRAGMENT) || (pkt->flags & ZDTUN_PKT_IS_FIRST_FRAGMENT))) {
        // nDPI cannot handle fragments, since they miss the L4 layer (see ndpi_iph_is_valid_and_not_fragmented)
        perform_dpi(pd, pctx);
    }
}

/* ******************************************************* */

/* Update the stats for the current packet and dump it if requested. */
void pd_account_stats(pcapdroid_t *pd, pkt_context_t *pctx) {
    zdtun_pkt_t *pkt = pctx->pkt;
    pd_conn_t *data = pctx->data;

    if(pctx->is_tx) {
        data->sent_pkts++;
        data->sent_bytes += pkt->len;
        pd->capture_stats.sent_pkts++;
        pd->capture_stats.sent_bytes += pkt->len;
    } else {
        data->rcvd_pkts++;
        data->rcvd_bytes += pkt->len;
        pd->capture_stats.rcvd_pkts++;
        pd->capture_stats.rcvd_bytes += pkt->len;
    }

    /* New stats to notify */
    pd->capture_stats.new_stats = true;

    data->update_type |= CONN_UPDATE_STATS;
    pd_notify_connection_update(pd, pctx->tuple, pctx->data);

    if(pd->pcap_dump.buffer) {
        int rec_size = pcap_rec_size(pkt->len);

        if ((JAVA_PCAP_BUFFER_SIZE - pd->pcap_dump.buffer_idx) <= rec_size) {
            // Flush the buffer
            javaPcapDump(pd);
        }

        if ((JAVA_PCAP_BUFFER_SIZE - pd->pcap_dump.buffer_idx) <= rec_size)
            log_e("Invalid buffer size [size=%d, idx=%d, tot_size=%d]",
                  JAVA_PCAP_BUFFER_SIZE, pd->pcap_dump.buffer_idx, rec_size);
        else {
            pcap_dump_rec(pd, (u_char *) pd->pcap_dump.buffer + pd->pcap_dump.buffer_idx,
                          pctx);

            pd->pcap_dump.buffer_idx += rec_size;
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
    cls.blacklist_status = jniFindClass(env, "com/emanuelef/remote_capture/model/Blacklists$NativeBlacklistStatus");
    cls.blacklist_descriptor = jniFindClass(env, "com/emanuelef/remote_capture/model/BlacklistDescriptor");
    cls.matchlist_descriptor = jniFindClass(env, "com/emanuelef/remote_capture/model/MatchList$ListDescriptor");
    cls.list = jniFindClass(env, "java/util/List");

    /* Methods */
    mids.reportError = jniGetMethodID(env, vpn_class, "reportError", "(Ljava/lang/String;)V");
    mids.getApplicationByUid = jniGetMethodID(env, vpn_class, "getApplicationByUid", "(I)Ljava/lang/String;"),
            mids.protect = jniGetMethodID(env, vpn_class, "protect", "(I)Z");
    mids.dumpPcapData = jniGetMethodID(env, vpn_class, "dumpPcapData", "([B)V");
    mids.updateConnections = jniGetMethodID(env, vpn_class, "updateConnections", "([Lcom/emanuelef/remote_capture/model/ConnectionDescriptor;[Lcom/emanuelef/remote_capture/model/ConnectionUpdate;)V");
    mids.sendStatsDump = jniGetMethodID(env, vpn_class, "sendStatsDump", "(Lcom/emanuelef/remote_capture/model/VPNStats;)V");
    mids.sendServiceStatus = jniGetMethodID(env, vpn_class, "sendServiceStatus", "(Ljava/lang/String;)V");
    mids.getLibprogPath = jniGetMethodID(env, vpn_class, "getLibprogPath", "(Ljava/lang/String;)Ljava/lang/String;");
    mids.notifyBlacklistsLoaded = jniGetMethodID(env, vpn_class, "notifyBlacklistsLoaded", "([Lcom/emanuelef/remote_capture/model/Blacklists$NativeBlacklistStatus;)V");
    mids.getBlacklistsInfo = jniGetMethodID(env, vpn_class, "getBlacklistsInfo", "()[Lcom/emanuelef/remote_capture/model/BlacklistDescriptor;");
    mids.connInit = jniGetMethodID(env, cls.conn, "<init>", "(IIILjava/lang/String;Ljava/lang/String;IIIIJ)V");
    mids.connProcessUpdate = jniGetMethodID(env, cls.conn, "processUpdate", "(Lcom/emanuelef/remote_capture/model/ConnectionUpdate;)V");
    mids.connUpdateInit = jniGetMethodID(env, cls.conn_update, "<init>", "(I)V");
    mids.connUpdateSetStats = jniGetMethodID(env, cls.conn_update, "setStats", "(JJJIIIII)V");
    mids.connUpdateSetInfo = jniGetMethodID(env, cls.conn_update, "setInfo", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    mids.statsInit = jniGetMethodID(env, cls.stats, "<init>", "()V");
    mids.statsSetData = jniGetMethodID(env, cls.stats, "setData", "(Ljava/lang/String;JJIIIIIIIII)V");
    mids.blacklistStatusInit = jniGetMethodID(env, cls.blacklist_status, "<init>", "(Ljava/lang/String;I)V");
    mids.listSize = jniGetMethodID(env, cls.list, "size", "()I");
    mids.listGet = jniGetMethodID(env, cls.list, "get", "(I)Ljava/lang/Object;");

    /* Fields */
    fields.bldescr_fname = jniFieldID(env, cls.blacklist_descriptor, "fname", "Ljava/lang/String;");
    fields.bldescr_type = jniFieldID(env, cls.blacklist_descriptor, "type", "Lcom/emanuelef/remote_capture/model/BlacklistDescriptor$Type;");
    fields.ld_apps = jniFieldID(env, cls.matchlist_descriptor, "apps", "Ljava/util/List;");
    fields.ld_hosts = jniFieldID(env, cls.matchlist_descriptor, "hosts", "Ljava/util/List;");
    fields.ld_ips = jniFieldID(env, cls.matchlist_descriptor, "ips", "Ljava/util/List;");

    pcapdroid_t pd = {
            .sdk_ver = sdk,
            .env = env,
            .capture_service = vpn,
            .ip_to_host = ip_lru_init(MAX_HOST_LRU_SIZE),
            .app_filter = getIntPref(env, vpn, "getAppFilterUid"),
            .root_capture = (bool) getIntPref(env, vpn, "isRootCapture"),
            .new_conn_id = 0,
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

    getStringPref(&pd, "getWorkingDir", pd.cachedir, sizeof(pd.cachedir));
    strcat(pd.cachedir, "/");
    pd.cachedir_len = strlen(pd.cachedir);

    getStringPref(&pd, "getPersistentDir", pd.filesdir, sizeof(pd.filesdir));
    strcat(pd.filesdir, "/");
    pd.filesdir_len = strlen(pd.filesdir);

    // Enable or disable the PCAPdroid trailer
    pcap_set_pcapdroid_trailer((bool)getIntPref(env, vpn, "addPcapdroidTrailer"));

    /* Important: init global state every time. Android may reuse the service. */
    running = true;

    logcallback = log_callback;
    global_pd = &pd;

    /* nDPI */
    pd.ndpi = init_ndpi();
    init_protocols_bitmask(&masterProtos);
    if(pd.ndpi == NULL) {
        log_f("nDPI initialization failed");
        return(-1);
    }

    if(pd.malware_detection.enabled)
        load_blacklists_info(&pd);

    // Load the blacklist before starting
    if(pd.malware_detection.enabled && reload_blacklists_now) {
        reload_blacklists_now = false;
        load_new_blacklists(&pd);
        use_new_blacklists(&pd);
    }

    signal(SIGPIPE, SIG_IGN);

    if(pd.pcap_dump.enabled) {
        pd.pcap_dump.buffer = pd_malloc(JAVA_PCAP_BUFFER_SIZE);
        pd.pcap_dump.buffer_idx = 0;

        if(!pd.pcap_dump.buffer) {
            log_f("malloc(pcap_dump.buffer) failed with code %d/%s",
                        errno, strerror(errno));
            running = false;
        }
    }

    memset(&pd.stats, 0, sizeof(pd.stats));

    pd_refresh_time(&pd);
    last_connections_dump = pd.now_ms;
    next_connections_dump = last_connections_dump + 500 /* first update after 500 ms */;
    bl_num_checked_connections = 0;

    notifyServiceStatus(&pd, "started");

    // Run the capture
    int rv = pd.root_capture ? run_root(&pd) : run_vpn(&pd, tunfd);

    log_d("Stopped packet loop");

    conns_clear(&pd.new_conns, true);
    conns_clear(&pd.conns_updates, true);

    if(pd.firewall.bl)
        blacklist_destroy(pd.firewall.bl);
    if(pd.firewall.new_bl)
        blacklist_destroy(pd.firewall.new_bl);

    if(pd.malware_detection.enabled) {
        if(pd.malware_detection.reload_in_progress) {
            log_d("Joining blacklists reload_worker");
            pthread_join(pd.malware_detection.reload_worker, NULL);
        }
        if(pd.malware_detection.bl)
            blacklist_destroy(pd.malware_detection.bl);
        if(pd.malware_detection.bls_info) {
            for(int i=0; i < pd.malware_detection.num_bls; i++)
                pd_free(pd.malware_detection.bls_info[i].fname);
            pd_free(pd.malware_detection.bls_info);
        }
    }
    ndpi_exit_detection_module(pd.ndpi);

    if(pd.pcap_dump.buffer) {
        if(pd.pcap_dump.buffer_idx > 0)
            javaPcapDump(&pd);

        pd_free(pd.pcap_dump.buffer);
        pd.pcap_dump.buffer = NULL;
    }

    uid_to_app_t *e, *tmp;
    HASH_ITER(hh, pd.uid2app, e, tmp) {
        HASH_DEL(pd.uid2app, e);
        pd_free(e);
    }

    notifyServiceStatus(&pd, "stopped");

    log_d("Host LRU cache size: %d", ip_lru_size(pd.ip_to_host));
    log_d("Discarded fragments: %ld", pd.num_discarded_fragments);
    ip_lru_destroy(pd.ip_to_host);

    logcallback = NULL;
    global_pd = NULL;

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

JNIEXPORT jint JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_getNumCheckedConnections(JNIEnv *env, jclass clazz) {
    return bl_num_checked_connections;
}

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_setPrivateDnsBlocked(JNIEnv *env, jclass clazz, jboolean to_block) {
    block_private_dns = to_block;
}

JNIEXPORT jboolean JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_reloadBlocklist(JNIEnv *env, jclass clazz,
        jobject ld) {
    pcapdroid_t *pd = global_pd;
    if(!pd) {
        log_e("NULL pd instance");
        return false;
    }

    if(pd->root_capture) {
        log_e("firewall in root mode not implemented");
        return false;
    }

    if(pd->firewall.new_bl != NULL) {
        log_e("previous bl not loaded yet");
        return false;
    }

    blacklist_t *bl = blacklist_init();
    if(!bl) {
        log_e("blacklist_init failed");
        return false;
    }

    if(blacklist_load_list_descriptor(bl, env, ld) < 0) {
        blacklist_destroy(bl);
        return false;
    }

    blacklists_stats_t stats;
    blacklist_get_stats(bl, &stats);
    log_d("reloadBlocklist: %d apps, %d domains, %d IPs", stats.num_apps, stats.num_domains, stats.num_ips);

    pd->firewall.new_bl = bl;
    return true;
}