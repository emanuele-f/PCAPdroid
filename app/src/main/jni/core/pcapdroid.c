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
 * Copyright 2020-24 - Emanuele Faranda
 */

#include <inttypes.h>
#include <assert.h> // NOTE: look for "assertion" in logcat
#include <pthread.h>
#include "pcapdroid.h"
#include "pcap_dump.h"
#include "common/utils.h"
#include "pcapd/pcapd.h"
#include "ndpi_protocol_ids.h"

extern int run_vpn(pcapdroid_t *pd);
extern int run_libpcap(pcapdroid_t *pd);
extern void libpcap_iter_connections(pcapdroid_t *pd, conn_cb cb);
extern void vpn_process_ndpi(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, pd_conn_t *data);

/* ******************************************************* */

bool running = false;
uint32_t new_dns_server = 0;
bool block_private_dns = false;
bool has_seen_pcapdroid_trailer = false;

bool dump_capture_stats_now = false;
bool reload_blacklists_now = false;
int bl_num_checked_connections = 0;
int fw_num_checked_connections = 0;

char *pd_appver = (char*) "";
char *pd_device = (char*) "";
char *pd_os = (char*) "";

static ndpi_protocol_bitmask_struct_t masterProtos;
static bool masterProtosInit = false;

/* ******************************************************* */

/* NOTE: these must be reset during each run, as android may reuse the service */
static int netd_resolve_waiting;
static u_int64_t last_connections_dump;
static u_int64_t next_connections_dump;

/* ******************************************************* */

static void conn_free_ndpi(pd_conn_t *data) {
    if(data->ndpi_flow) {
        ndpi_free_flow(data->ndpi_flow);
        data->ndpi_flow = NULL;
    }
}

/* ******************************************************* */

uint16_t pd_ndpi2proto(ndpi_protocol proto) {
    // The nDPI master/app protocol logic is not clear (e.g. the first packet of a DNS flow has
    // master_protocol unknown whereas the second has master_protocol set to DNS). We are not interested
    // in the app protocols, so just take the one that's not unknown.
    uint16_t l7proto = ((proto.master_protocol != NDPI_PROTOCOL_UNKNOWN) ? proto.master_protocol : proto.app_protocol);

    if((l7proto == NDPI_PROTOCOL_HTTP_CONNECT) || (l7proto == NDPI_PROTOCOL_HTTP_PROXY))
        l7proto = NDPI_PROTOCOL_HTTP;

    if(!masterProtosInit) {
        init_ndpi_protocols_bitmask(&masterProtos);
        masterProtosInit = true;
    }

    // nDPI will still return a disabled protocol (via the bitmask) if it matches some
    // metadata for it (e.g. the SNI)
    if(!NDPI_ISSET(&masterProtos, l7proto))
        l7proto = NDPI_PROTOCOL_UNKNOWN;

    //log_d("PROTO: %d/%d -> %d", proto.master_protocol, proto.app_protocol, l7proto);

    return l7proto;
}

/* ******************************************************* */

static bool is_encrypted_l7(struct ndpi_detection_module_struct *ndpi_str, uint16_t l7proto) {
    // The ndpi_is_encrypted_proto API does not work reliably as it mixes master protocols with apps
    if(l7proto >= (NDPI_MAX_SUPPORTED_PROTOCOLS + NDPI_MAX_NUM_CUSTOM_PROTOCOLS))
        return false;

    return(ndpi_str->proto_defaults[l7proto].isClearTextProto == 0);
}

/* ******************************************************* */

void pd_purge_connection(pcapdroid_t *pd, pd_conn_t *data) {
    if(!data)
        return;

    conn_free_ndpi(data);

    if(data->info)
        pd_free(data->info);
    if(data->url)
        pd_free(data->url);

#ifdef ANDROID
    if(data->payload_chunks)
        (*pd->env)->DeleteLocalRef(pd->env, data->payload_chunks);
#endif

    pd_free(data);
}

/* ******************************************************* */

static int notif_connection(pcapdroid_t *pd, conn_array_t *arr, const zdtun_5tuple_t *tuple, pd_conn_t *data) {
    // End the detection when the connection is closed
    // Always check this, even pending_notification are present
    if(data->status >= CONN_STATUS_CLOSED)
        pd_giveup_dpi(pd, data, tuple);

    if(data->pending_notification)
        return 0;

    if(arr->cur_items >= arr->size) {
        /* Extend array */
        arr->size = (arr->size == 0) ? 8 : (arr->size * 2);
        arr->items = pd_realloc(arr->items, arr->size * sizeof(conn_and_tuple_t));

        if(arr->items == NULL) {
            log_e("realloc(conn_array_t) (%d items) failed", arr->size);
            return -1;
        }
    }

    conn_and_tuple_t *slot = &arr->items[arr->cur_items++];
    slot->tuple = *tuple;
    slot->data = data;
    data->pending_notification = true;
    return 0;
}

/* Call this when the connection data has changed. The connection data will be sent to JAVA during the
 * next sendConnectionsDump. The type of change is determined by the data->update_type.
 * A negative value is returned if the connection update could not be enqueued. */
int pd_notify_connection_update(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, pd_conn_t *data) {
    return notif_connection(pd, &pd->conns_updates, tuple, data);
}

/* ******************************************************* */

static void conns_clear(pcapdroid_t *pd, conn_array_t *arr, bool free_all) {
    if(arr->items) {
        for(int i=0; i < arr->cur_items; i++) {
            conn_and_tuple_t *slot = &arr->items[i];

            if(slot->data && (slot->data->to_purge || free_all))
                pd_purge_connection(pd, slot->data);
        }

        pd_free(arr->items);
        arr->items = NULL;
    }

    arr->size = 0;
    arr->cur_items = 0;
}

/* ******************************************************* */

char* get_appname_by_uid(pcapdroid_t *pd, int uid, char *buf, int bufsize) {
#ifdef ANDROID
    uid_to_app_t *app_entry;

    HASH_FIND_INT(pd->uid2app, &uid, app_entry);
    if(app_entry == NULL) {
        app_entry = (uid_to_app_t*) pd_malloc(sizeof(uid_to_app_t));

        if(app_entry) {
            // Resolve the app name
            getApplicationByUid(pd, uid, app_entry->appname, sizeof(app_entry->appname));

            log_d("uid %d resolved to \"%s\"", uid, app_entry->appname);

            app_entry->uid = uid;
            HASH_ADD_INT(pd->uid2app, uid, app_entry);
        }
    }
#else
    uid_to_app_t *app_entry = NULL;
#endif

    if(app_entry) {
        strncpy(buf, app_entry->appname, bufsize-1);
        buf[bufsize-1] = '\0';
    } else
        buf[0] = '\0';

    return buf;
}

/* ******************************************************* */

struct ndpi_detection_module_struct* init_ndpi() {
#ifdef FUZZING
    // nDPI initialization is very expensive, cache it
    // see also ndpi_exit_detection_module
    static struct ndpi_detection_module_struct *ndpi_cache = NULL;

    if(ndpi_cache != NULL)
      return ndpi_cache;
#endif

    struct ndpi_detection_module_struct *ndpi = ndpi_init_detection_module(ndpi_no_prefs);
    NDPI_PROTOCOL_BITMASK protocols;

    if(!ndpi)
        return(NULL);

    // needed by pd_get_proto_name
    if(!masterProtosInit) {
        init_ndpi_protocols_bitmask(&masterProtos);
        masterProtosInit = true;
    }

#ifndef FUZZING
    // enable all the protocols
    NDPI_BITMASK_SET_ALL(protocols);
#else
    // nDPI has a big performance impact on fuzzing.
    // Only enable some protocols to extract the metadata for use in
    // PCAPdroid, we are not fuzzing nDPI!
    NDPI_BITMASK_RESET(protocols);
    NDPI_BITMASK_ADD(protocols, NDPI_PROTOCOL_DNS);
    NDPI_BITMASK_ADD(protocols, NDPI_PROTOCOL_HTTP);
    //NDPI_BITMASK_ADD(protocols, NDPI_PROTOCOL_TLS);
#endif

    ndpi_set_protocol_detection_bitmask2(ndpi, &protocols);

    ndpi_finalize_initialization(ndpi);

#ifdef FUZZING
    ndpi_cache = ndpi;
#endif

    return(ndpi);
}

/* ******************************************************* */

const char* pd_get_proto_name(pcapdroid_t *pd, uint16_t proto, uint16_t alpn, int ipproto) {
    if(proto == NDPI_PROTOCOL_UNKNOWN) {
        // Return the L3 protocol
        return zdtun_proto2str(ipproto);
    }

    if(proto == NDPI_PROTOCOL_TLS) {
        switch (alpn) {
            case NDPI_PROTOCOL_HTTP:
                return "HTTPS";
            case NDPI_PROTOCOL_MAIL_IMAP:
                return "IMAPS";
            case NDPI_PROTOCOL_MAIL_SMTP:
                return "SMTPS";
            default:
                // go on
                break;
        }
    }

    return ndpi_get_proto_name(pd->ndpi, proto);
}

/* ******************************************************* */

static void check_blacklisted_domain(pcapdroid_t *pd, pd_conn_t *data, const zdtun_5tuple_t *tuple) {
    if(data->info && data->info[0]) {
        if(pd->malware_detection.bl && !data->blacklisted_domain && !data->whitelisted_app) {
            bool blacklisted = blacklist_match_domain(pd->malware_detection.bl, data->info);
            if(blacklisted) {
                char appbuf[64];
                char buf[512];
                get_appname_by_uid(pd, data->uid, appbuf, sizeof(appbuf));

                // Check if whitelisted
                if(pd->malware_detection.whitelist && blacklist_match_domain(pd->malware_detection.whitelist, data->info))
                    log_d("Whitelisted domain [%s]: %s [%s]", data->info,
                          zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);
                else {
                    log_w("Blacklisted domain [%s]: %s [%s]", data->info,
                          zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);
                    data->blacklisted_domain = true;
                    data->to_block = true;
                }
            }
        }

        if(pd->firewall.enabled && pd->firewall.bl && !data->to_block) {
            // Check if the domain is explicitly blocked by the firewall
            data->to_block |= blacklist_match_domain(pd->firewall.bl, data->info);
            if(data->to_block) {
                char appbuf[64];
                char buf[512];

                get_appname_by_uid(pd, data->uid, appbuf, sizeof(appbuf));
                log_d("Blocked domain [%s]: %s [%s]", data->info, zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);
            }
        }
    }
}

/* ******************************************************* */

static void check_whitelist_mode_block(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, pd_conn_t *data) {
    // whitelist mode: block any app unless it's explicitly whitelisted.
    // The blocklist still has priority to determine if a connection should be blocked.

    // NOTE: data->l7proto is not computed yet
    bool is_dns = (tuple->ipproto == IPPROTO_UDP) && (ntohs(tuple->dst_port) == 53);

    if(pd->firewall.enabled && pd->firewall.wl_enabled && pd->firewall.wl && !data->to_block &&
            // always allow DNS traffic from unspecified apps
            (!is_dns || ((data->uid != UID_NETD) && (data->uid != UID_PHONE) && (data->uid != UID_UNKNOWN))))
        data->to_block = !blacklist_match_uid(pd->firewall.wl, data->uid);
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
        pd_purge_connection(pd, data);
        return(NULL);
    }

    if(notif_connection(pd, &pd->new_conns, tuple, data) < 0) {
        pd_purge_connection(pd, data);
        return(NULL);
    }

    data->uid = uid;
    data->incr_id = pd->new_conn_id++;

    if(pd->malware_detection.whitelist) {
        // NOTE: if app is whitelisted, no need to check for blacklisted IP/domains
        data->whitelisted_app = blacklist_match_uid(pd->malware_detection.whitelist, uid);

        if(data->whitelisted_app) {
            char appbuf[64];
            char buf[256];
            get_appname_by_uid(pd, data->uid, appbuf, sizeof(appbuf));

            log_d("Whitelisted app: %s [%s]", zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);
        }
    }

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

                    if(!conn->data->to_block && pd->firewall.enabled && pd->firewall.bl && (
                            blacklist_match_uid(pd->firewall.bl, conn->data->uid) ||
                            (pd->firewall.wl_enabled && pd->firewall.wl && !blacklist_match_uid(pd->firewall.wl, conn->data->uid))))
                        conn->data->netd_block_missed = true;

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
        if(!data->whitelisted_app) {
            bool blacklisted = blacklist_match_ip(pd->malware_detection.bl, &dst_ip, tuple->ipver);
            if (blacklisted) {
                char appbuf[64];
                char buf[256];
                get_appname_by_uid(pd, data->uid, appbuf, sizeof(appbuf));

                if(pd->malware_detection.whitelist && blacklist_match_ip(pd->malware_detection.whitelist, &dst_ip, tuple->ipver))
                    log_d("Whitelisted dst ip: %s [%s]", zdtun_5tuple2str(tuple, buf, sizeof(buf)),
                          appbuf);
                else {
                    log_w("Blacklisted dst ip: %s [%s]", zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);
                    data->blacklisted_ip = true;
                    data->to_block = true;
                }
            }
        }

        bl_num_checked_connections++;
    }

    if(pd->firewall.enabled && pd->firewall.bl && !data->to_block) {
        data->to_block |= blacklist_match_ip(pd->firewall.bl, &dst_ip, tuple->ipver);
        if(data->to_block) {
            char appbuf[64];
            char buf[256];

            get_appname_by_uid(pd, data->uid, appbuf, sizeof(appbuf));
            log_d("Blocked ip: %s [%s]", zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);
        } else {
            data->to_block |= blacklist_match_uid(pd->firewall.bl, data->uid);
            if(data->to_block) {
                char appbuf[64];
                char buf[256];

                get_appname_by_uid(pd, data->uid, appbuf, sizeof(appbuf));
                log_d("Blocked app: %s [%s]", zdtun_5tuple2str(tuple, buf, sizeof(buf)), appbuf);
            }
        }

        fw_num_checked_connections++;
    }

    check_whitelist_mode_block(pd, tuple, data);

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
        case NDPI_PROTOCOL_TLS:
            // ALPN extension in client hello (https://datatracker.ietf.org/doc/html/rfc7301)
            if(!data->alpn && data->ndpi_flow->protos.tls_quic.alpn) {
                if(strstr(data->ndpi_flow->protos.tls_quic.alpn, "http/")) {
                    data->alpn = NDPI_PROTOCOL_HTTP;
                    data->update_type |= CONN_UPDATE_INFO;
                } else if(strstr(data->ndpi_flow->protos.tls_quic.alpn, "imap")) {
                    data->alpn = NDPI_PROTOCOL_MAIL_IMAP;
                    data->update_type |= CONN_UPDATE_INFO;
                } else if(strstr(data->ndpi_flow->protos.tls_quic.alpn, "stmp")) {
                    data->alpn = NDPI_PROTOCOL_MAIL_SMTP;
                    data->update_type |= CONN_UPDATE_INFO;
                } else {
                    log_d("Unknown ALPN: %s", data->ndpi_flow->protos.tls_quic.alpn);
                    data->alpn = NDPI_PROTOCOL_TLS; // mark to avoid port-based guessing
                }
            }
            /* fallthrough */
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
    }

    if(found_info && (!data->info || data->info_from_lru)) {
        if(data->info)
            pd_free(data->info);
        data->info = pd_strndup(found_info, 256);
        data->info_from_lru = false;

        check_blacklisted_domain(pd, data, tuple);
        data->update_type |= CONN_UPDATE_INFO;
    }

    if(pd->vpn_capture)
        vpn_process_ndpi(pd, tuple, data);
}

/* ******************************************************* */

/* Stop the DPI detection and determine the l7proto of the connection. */
void pd_giveup_dpi(pcapdroid_t *pd, pd_conn_t *data, const zdtun_5tuple_t *tuple) {
    if(!data->ndpi_flow)
        return;

    if(data->l7proto == NDPI_PROTOCOL_UNKNOWN) {
        uint8_t proto_guessed;
        struct ndpi_proto n_proto = ndpi_detection_giveup(pd->ndpi, data->ndpi_flow, 1 /* Guess */,
                              &proto_guessed);
        data->l7proto = pd_ndpi2proto(n_proto);
        data->encrypted_l7 = is_encrypted_l7(pd->ndpi, data->l7proto);
    }

    log_d("nDPI completed[pkts=%d, ipver=%d, proto=%d] -> l7proto: %d",
                data->sent_pkts + data->rcvd_pkts,
                tuple->ipver, tuple->ipproto, data->l7proto);

    process_ndpi_data(pd, tuple, data);
    conn_free_ndpi(data);
}

/* ******************************************************* */

static void process_payload(pcapdroid_t *pd, pkt_context_t *pctx) {
    const zdtun_pkt_t *pkt = pctx->pkt;
    pd_conn_t *data = pctx->data;
    bool truncated = data->payload_truncated;
    bool updated = false;

    if((pd->payload_mode == PAYLOAD_MODE_NONE) ||
       (pd->cb.dump_payload_chunk == NULL) ||
       (pkt->l7_len <= 0) ||
       (pd->tls_decryption.enabled && data->proxied)) // NOTE: when performing TLS decryption, TCP connections data is handled by the MitmReceiver
        return;

    if((pd->payload_mode != PAYLOAD_MODE_MINIMAL) || !data->has_payload[pctx->is_tx]) {
        int to_dump = pkt->l7_len;

        if((pd->payload_mode == PAYLOAD_MODE_MINIMAL) && (pkt->l7_len > MINIMAL_PAYLOAD_MAX_DIRECTION_SIZE)) {
            to_dump = MINIMAL_PAYLOAD_MAX_DIRECTION_SIZE;
            truncated = true;
        }

        if(pd->cb.dump_payload_chunk(pd, pctx, to_dump)) {
            data->has_payload[pctx->is_tx] = true;
            updated = true;
        } else
            truncated = true;
    } else
        truncated = true;

    if((updated && data->payload_chunks) || (truncated != data->payload_truncated)) {
        data->payload_truncated |= truncated;
        data->update_type |= CONN_UPDATE_PAYLOAD;
        pd_notify_connection_update(pd, pctx->tuple, data);
    }
}

/* ******************************************************* */

static void process_dns_reply(pd_conn_t *data, pcapdroid_t *pd, const struct zdtun_pkt *pkt) {
    const char *query = (const char*) data->ndpi_flow->host_server_name;

    if((!query[0]) || !strchr(query, '.') || (pkt->l7_len < sizeof(dns_packet_t)))
        return;

    dns_packet_t *dns = (dns_packet_t*)pkt->l7;

    if(((ntohs(dns->flags) & 0x8000) == 0x8000) && (dns->questions != 0) && (dns->answ_rrs != 0)) {
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
    struct ndpi_proto n_proto = ndpi_detection_process_packet(pd->ndpi, data->ndpi_flow, (const u_char *)pkt->buf,
                                  pkt->len, data->last_seen);
    data->l7proto = pd_ndpi2proto(n_proto);

    if(old_proto != data->l7proto) {
        data->update_type |= CONN_UPDATE_INFO;
        data->encrypted_l7 = is_encrypted_l7(pd->ndpi, data->l7proto);
    }

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

    if(!data->ndpi_flow) {
        // nDPI detection complete
        if((data->l7proto == NDPI_PROTOCOL_TLS) && (!data->alpn)) {
            if(ntohs(pctx->tuple->dst_port) == 443)
                data->alpn = NDPI_PROTOCOL_HTTP; // assume HTTPS
            else if(data->info && !strncmp(data->info, "imap.", 5))
                data->alpn = NDPI_PROTOCOL_MAIL_IMAP; // assume IMAPS
            else if(data->info && !strncmp(data->info, "smtp.", 5))
                data->alpn = NDPI_PROTOCOL_MAIL_SMTP; // assume SMTPS

            if(data->alpn) {
                data->update_type |= CONN_UPDATE_INFO;
                pd_notify_connection_update(pd, pctx->tuple, data);
            }
        }
    }
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

const char* get_cache_path(pcapdroid_t *pd, const char *subpath) {
    strncpy(pd->cachedir + pd->cachedir_len, subpath,
            sizeof(pd->cachedir) - pd->cachedir_len - 1);
    pd->cachedir[sizeof(pd->cachedir) - 1] = 0;
    return pd->cachedir;
}

/* ******************************************************* */

const char* get_file_path(pcapdroid_t *pd, const char *subpath) {
    strncpy(pd->filesdir + pd->filesdir_len, subpath,
            sizeof(pd->filesdir) - pd->filesdir_len - 1);
    pd->filesdir[sizeof(pd->filesdir) - 1] = 0;
    return pd->filesdir;
}

/* ******************************************************* */

// called after load_new_blacklists
static void use_new_blacklists(pcapdroid_t *pd) {
    if(!pd->malware_detection.new_bl)
        return;

    if(pd->malware_detection.bl)
        blacklist_destroy(pd->malware_detection.bl);
    pd->malware_detection.bl = pd->malware_detection.new_bl;
    pd->malware_detection.new_bl = NULL;

    bl_status_arr_t *status_arr = pd->malware_detection.status_arr;
    pd->malware_detection.status_arr = NULL;

    if(status_arr == NULL) {
        // NOTE: must notify even if status_arr is NULL
        status_arr = pd_calloc(0, sizeof(bl_status_arr_t));

        if(!status_arr) // this should never happen
            return;
    }

    if(pd->cb.notify_blacklists_loaded)
        pd->cb.notify_blacklists_loaded(pd, status_arr);

    for(int i = 0; i < status_arr->cur_items; i++) {
        bl_status_t *st = &status_arr->items[i];
        pd_free(st->fname);
    }
    pd_free(status_arr->items);
    pd_free(status_arr);
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

        if(blacklist_load_file(bl, get_file_path(pd, subpath), blinfo->type, &stats) == 0) {
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

struct iter_conn_data {
    pcapdroid_t *pd;
    conn_cb cb;
};

static int zdtun_iter_adapter(zdtun_t *zdt, const zdtun_conn_t *conn_info, void *data) {
    struct iter_conn_data *idata = (struct iter_conn_data*) data;
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn_info);
    pd_conn_t *conn = zdtun_conn_get_userdata(conn_info);

    return idata->cb(idata->pd, tuple, conn);
}

static void iter_active_connections(pcapdroid_t *pd, conn_cb cb) {
    if(!pd->vpn_capture)
        libpcap_iter_connections(pd, cb);
    else {
        struct iter_conn_data idata = {
                .pd = pd,
                .cb = cb,
        };
        zdtun_iter_connections(pd->zdt, zdtun_iter_adapter, &idata);
    }
}

/* ******************************************************* */

static int check_blocked_conn_cb(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, pd_conn_t *data) {
    zdtun_ip_t dst_ip = tuple->dst_ip;
    blacklist_t *fw_bl = pd->firewall.bl;
    bool old_block = data->to_block;

    data->to_block = (data->blacklisted_internal || data->blacklisted_ip || data->blacklisted_domain);
    if(!data->to_block && pd->firewall.enabled && fw_bl) {
        data->to_block = blacklist_match_uid(fw_bl, data->uid) ||
                         blacklist_match_ip(fw_bl, &dst_ip, tuple->ipver) ||
                         (data->info && data->info[0] && blacklist_match_domain(fw_bl, data->info));
    }

    check_whitelist_mode_block(pd, tuple, data);

    if(old_block != data->to_block) {
        data->update_type |= CONN_UPDATE_STATS;
        pd_notify_connection_update(pd, tuple, data);
    }

    // continue
    return 0;
}

/* ******************************************************* */

// Check if a previously blacklisted connection is now whitelisted
static int check_blacklisted_conn_cb(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, pd_conn_t *data) {
    blacklist_t *whitelist = pd->malware_detection.whitelist;
    bool changed = false;

    data->whitelisted_app = blacklist_match_uid(whitelist, data->uid);

    if(data->blacklisted_ip) {
        const zdtun_ip_t dst_ip = tuple->dst_ip;
        if(data->whitelisted_app || blacklist_match_ip(whitelist, &dst_ip, tuple->ipver)) {
            data->blacklisted_ip = false;
            changed = true;
        }
    }

    if(data->blacklisted_domain &&
            (data->whitelisted_app || blacklist_match_domain(whitelist, data->info))) {
        data->blacklisted_domain = false;
        changed = true;
    }

    if(changed) {
        // Possibly unblock the connection
        if(pd->firewall.bl)
            check_blocked_conn_cb(pd, tuple, data);

        data->update_type |= CONN_UPDATE_STATS;
        pd_notify_connection_update(pd, tuple, data);
    }

    // continue
    return 0;
}

/* ******************************************************* */

static void stop_pcap_dump(pcapdroid_t *pd) {
    pcap_destroy_dumper(pd->pcap_dump.dumper);
    pd->pcap_dump.dumper = NULL;

    if(pd->cb.stop_pcap_dump)
        pd->cb.stop_pcap_dump(pd);
}

/* ******************************************************* */

/* Perfom periodic tasks. This should be called after processing a packet or after some time has
 * passed (e.g. after a select with no packet). */
void pd_housekeeping(pcapdroid_t *pd) {
    if(dump_capture_stats_now ||
            (pd->capture_stats.new_stats && ((pd->now_ms - pd->capture_stats.last_update_ms) >= CAPTURE_STATS_UPDATE_FREQUENCY_MS))) {
        dump_capture_stats_now = false;
        //log_d("Send stats");

        if(pd->vpn_capture)
            zdtun_get_stats(pd->zdt, &pd->stats);

        if(pd->cb.send_stats_dump)
            pd->cb.send_stats_dump(pd);

        pd->capture_stats.new_stats = false;
        pd->capture_stats.last_update_ms = pd->now_ms;
    } else if (pd->now_ms >= next_connections_dump) {
        /*log_d("sendConnectionsDump [after %" PRIu64 " ms]: new=%d, updates=%d",
              pd->now_ms - last_connections_dump,
              pd->new_conns.cur_items, pd->conns_updates.cur_items);*/

        if ((pd->new_conns.cur_items != 0) || (pd->conns_updates.cur_items != 0)) {
            if (pd->cb.send_connections_dump)
                pd->cb.send_connections_dump(pd);
            conns_clear(pd, &pd->new_conns, false);
            conns_clear(pd, &pd->conns_updates, false);
        }

        last_connections_dump = pd->now_ms;
        next_connections_dump = pd->now_ms + CONNECTION_DUMP_UPDATE_FREQUENCY_MS;
        netd_resolve_waiting = 0;
    } else if(pd->pcap_dump.dumper && pcap_check_export(pd->pcap_dump.dumper))
        ;
    else if(pd->malware_detection.enabled) {
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

    if(pd->malware_detection.new_wl) {
        // Load new whitelist
        if(pd->malware_detection.whitelist)
            blacklist_destroy(pd->malware_detection.whitelist);
        pd->malware_detection.whitelist = pd->malware_detection.new_wl;
        pd->malware_detection.new_wl = NULL;

        // Check the active (blacklisted) connections to possibly whitelist (and unblock) them
        iter_active_connections(pd, check_blacklisted_conn_cb);
    }

    if(pd->firewall.new_bl) {
        // Load new blocklist
        if(pd->firewall.bl)
            blacklist_destroy(pd->firewall.bl);
        pd->firewall.bl = pd->firewall.new_bl;
        pd->firewall.new_bl = NULL;
        iter_active_connections(pd, check_blocked_conn_cb);
    } else if(pd->firewall.new_wl) {
        // Load new whitelist
        if(pd->firewall.wl)
            blacklist_destroy(pd->firewall.wl);
        pd->firewall.wl = pd->firewall.new_wl;
        pd->firewall.new_wl = NULL;
        iter_active_connections(pd, check_blocked_conn_cb);
    }

    if(pd->tls_decryption.new_list) {
        // Load new whitelist
        if(pd->tls_decryption.list)
            blacklist_destroy(pd->tls_decryption.list);
        pd->tls_decryption.list = pd->tls_decryption.new_list;
        pd->tls_decryption.new_list = NULL;
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

    process_payload(pd, pctx);
}

/* ******************************************************* */

void pd_dump_packet(pcapdroid_t *pd, const char *pktbuf, int pktlen, const struct timeval *tv, int uid) {
    if(!pd->pcap_dump.dumper)
        return;

    if(!pcap_dump_packet(pd->pcap_dump.dumper, pktbuf, pktlen, tv, uid))
        stop_pcap_dump(pd);
}

/* ******************************************************* */

/* Update the stats for the current packet and dump it if requested. */
void pd_account_stats(pcapdroid_t *pd, pkt_context_t *pctx) {
    zdtun_pkt_t *pkt = pctx->pkt;
    pd_conn_t *data = pctx->data;

    data->payload_length += pkt->l7_len;

    if(pctx->is_tx) {
        data->sent_pkts++;
        data->sent_bytes += pkt->len;
        pd->capture_stats.sent_pkts++;
        pd->capture_stats.sent_bytes += pkt->len;
        if(pkt->tuple.ipver == 6) {
            pd->capture_stats.ipv6_sent_bytes += pkt->len;
        }
    } else {
        data->rcvd_pkts++;
        data->rcvd_bytes += pkt->len;
        pd->capture_stats.rcvd_pkts++;
        pd->capture_stats.rcvd_bytes += pkt->len;
        if(pkt->tuple.ipver == 6) {
            pd->capture_stats.ipv6_rcvd_bytes += pkt->len;
        }
    }

    /* New stats to notify */
    pd->capture_stats.new_stats = true;
    data->update_type |= CONN_UPDATE_STATS;
    pd_notify_connection_update(pd, pctx->tuple, pctx->data);

    if((pd->pcap_dump.dumper) &&
            ((pd->pcap_dump.max_pkts_per_flow <= 0) ||
                ((data->sent_pkts + data->rcvd_pkts) <= pd->pcap_dump.max_pkts_per_flow)))
        pd_dump_packet(pd, pkt->buf, pkt->len, &pctx->tv, pctx->data->uid);
}

/* ******************************************************* */

int pd_run(pcapdroid_t *pd) {
    /* Important: init global state every time. Android may reuse the service. */
    running = true;
    has_seen_pcapdroid_trailer = false;
    netd_resolve_waiting = 0;

    /* nDPI */
    pd->ndpi = init_ndpi();
    if(pd->ndpi == NULL) {
        log_f("nDPI initialization failed");
        return(-1);
    }

    pd->ip_to_host = ip_lru_init(MAX_HOST_LRU_SIZE);

    if(pd->malware_detection.enabled && pd->cb.load_blacklists_info)
        pd->cb.load_blacklists_info(pd);

    // Load the blacklist before starting
    if(pd->malware_detection.enabled && reload_blacklists_now) {
        reload_blacklists_now = false;
        load_new_blacklists(pd);
        use_new_blacklists(pd);
    }

    if(pd->pcap_dump.enabled) {
        int max_snaplen = !pd->vpn_capture ? PCAPD_SNAPLEN : VPN_BUFFER_SIZE;

        // use the snaplen provided by the API
        if((pd->pcap_dump.snaplen <= 0) || (pd->pcap_dump.snaplen > max_snaplen))
            pd->pcap_dump.snaplen = max_snaplen;

        pcap_dump_mode_t dump_mode;
        if(pd->pcap_dump.pcapng_format)
            dump_mode = PCAPNG_DUMP;
        else if(pd->pcap_dump.trailer_enabled)
            dump_mode = PCAP_DUMP_WITH_TRAILER;
        else
            dump_mode = PCAP_DUMP;

        log_d("dump_mode: %d", dump_mode);
        pd->pcap_dump.dumper = pcap_new_dumper(dump_mode,pd->pcap_dump.snaplen,
                                               pd->pcap_dump.max_dump_size,
                                               pd->cb.send_pcap_dump, pd);
        if(!pd->pcap_dump.dumper) {
            log_f("Could not initialize the PCAP dumper");
            running = false;
        }
    }

    memset(&pd->stats, 0, sizeof(pd->stats));

    pd_refresh_time(pd);
    last_connections_dump = pd->now_ms;
    next_connections_dump = last_connections_dump + 500 /* first update after 500 ms */;
    bl_num_checked_connections = 0;
    fw_num_checked_connections = 0;

    // Run the capture
    int rv = pd->vpn_capture ? run_vpn(pd) : run_libpcap(pd);

    log_i("Stopped packet loop");

    // send last dump
    if(pd->cb.send_stats_dump)
        pd->cb.send_stats_dump(pd);
    if(pd->cb.send_connections_dump)
        pd->cb.send_connections_dump(pd);

    conns_clear(pd, &pd->new_conns, true);
    conns_clear(pd, &pd->conns_updates, true);

    if(pd->firewall.bl)
        blacklist_destroy(pd->firewall.bl);
    if(pd->firewall.new_bl)
        blacklist_destroy(pd->firewall.new_bl);
    if(pd->firewall.wl)
        blacklist_destroy(pd->firewall.wl);
    if(pd->firewall.new_wl)
        blacklist_destroy(pd->firewall.new_wl);
    if(pd->tls_decryption.list)
        blacklist_destroy(pd->tls_decryption.list);
    if(pd->tls_decryption.new_list)
        blacklist_destroy(pd->tls_decryption.new_list);

    if(pd->malware_detection.enabled) {
        if(pd->malware_detection.reload_in_progress) {
            log_i("Joining blacklists reload_worker");
            pthread_join(pd->malware_detection.reload_worker, NULL);
        }
        if(pd->malware_detection.bl)
            blacklist_destroy(pd->malware_detection.bl);
        if(pd->malware_detection.whitelist)
            blacklist_destroy(pd->malware_detection.whitelist);
        if(pd->malware_detection.new_wl)
            blacklist_destroy(pd->malware_detection.new_wl);
        if(pd->malware_detection.bls_info) {
            for(int i=0; i < pd->malware_detection.num_bls; i++)
                pd_free(pd->malware_detection.bls_info[i].fname);
            pd_free(pd->malware_detection.bls_info);
        }
    }

#ifndef FUZZING
    ndpi_exit_detection_module(pd->ndpi);
#endif

    if(pd->pcap_dump.dumper)
        stop_pcap_dump(pd);

    uid_to_app_t *e, *tmp;
    HASH_ITER(hh, pd->uid2app, e, tmp) {
        HASH_DEL(pd->uid2app, e);
        pd_free(e);
    }

    log_i("Host LRU cache size: %d", ip_lru_size(pd->ip_to_host));
    log_i("Discarded fragments: %ld", pd->num_discarded_fragments);
    ip_lru_destroy(pd->ip_to_host);

    return(rv);
}
