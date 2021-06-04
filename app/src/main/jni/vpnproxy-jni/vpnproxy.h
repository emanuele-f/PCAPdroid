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

#ifndef __PCAPDROID_H__
#define __PCAPDROID_H__

#include <jni.h>
#include <stdbool.h>
#include "zdtun.h"
#include "ip_lru.h"
#include "ndpi_api.h"
#include "common/uid_resolver.h"

#define CAPTURE_STATS_UPDATE_FREQUENCY_MS 300
#define CONNECTION_DUMP_UPDATE_FREQUENCY_MS 1000
#define MAX_JAVA_DUMP_DELAY_MS 1000
#define MAX_DPI_PACKETS 12
#define MAX_HOST_LRU_SIZE 128
#define JAVA_PCAP_BUFFER_SIZE (512*1024) // 512K
#define PERIODIC_PURGE_TIMEOUT_MS 5000
#define MAX_PLAINTEXT_LENGTH 1024

#define DNS_FLAGS_MASK 0x8000
#define DNS_TYPE_REQUEST 0x0000
#define DNS_TYPE_RESPONSE 0x8000

typedef struct capture_stats {
    jlong sent_bytes;
    jlong rcvd_bytes;
    jint sent_pkts;
    jint rcvd_pkts;

    bool new_stats;
    u_int64_t last_update_ms;
} capture_stats_t;

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
    zdtun_conn_status_t status;
    char *info;
    jint uid;
    bool pending_notification;
    bool request_done;
    char *request_data;
    char *url;
} conn_data_t;

typedef struct vpn_conn {
    zdtun_5tuple_t tuple;
    conn_data_t *data;
} vpn_conn_t;

typedef struct conn_array {
    vpn_conn_t *items;
    int size;
    int cur_items;
} conn_array_t;

typedef struct vpnproxy_data {
    int tunfd;
    int incr_id;
    jint sdk;
    JNIEnv *env;
    jobject vpn_service;
    jint app_filter;
    u_int32_t vpn_dns;
    u_int32_t dns_server;
    u_int32_t vpn_ipv4;
    struct ndpi_detection_module_struct *ndpi;
    ndpi_ptree_t *known_dns_servers;
    uid_resolver_t *resolver;
    ip_lru_t *ip_to_host;
    uint64_t now_ms;
    u_int32_t num_dropped_connections;
    u_int32_t num_dns_requests;
    conn_array_t new_conns;
    conn_array_t conns_updates;
    zdtun_pkt_t *last_pkt;
    zdtun_t *tun;
    bool last_conn_blocked;
    bool root_capture;
    zdtun_statistics_t stats;

    struct {
        u_int32_t collector_addr;
        u_int16_t collector_port;
        bool tcp_socket;
        bool enabled;
    } pcap_dump;

    struct {
        bool enabled;
        jbyte *buffer;
        int buffer_idx;
        u_int64_t last_dump_ms;
    } java_dump;

    struct {
        bool enabled;
        u_int32_t proxy_ip;
        u_int32_t proxy_port;
    } socks5;

    struct {
        bool enabled;
        struct in6_addr dns_server;
    } ipv6;

    capture_stats_t capture_stats;
} vpnproxy_data_t;

/* ******************************************************* */

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

typedef struct jni_methods {
    jmethodID reportError;
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
    jmethodID getLibprogPath;
} jni_methods_t;

typedef struct jni_classes {
    jclass vpn_service;
    jclass conn;
    jclass stats;
} jni_classes_t;

/* ******************************************************* */

extern jni_methods_t mids;
extern jni_classes_t cls;
extern bool running;
extern uint32_t new_dns_server;
extern bool dump_vpn_stats_now;

conn_data_t* new_connection(vpnproxy_data_t *proxy, const zdtun_5tuple_t *tuple, int uid);
void conn_free_data(conn_data_t *data);
void notify_connection(conn_array_t *arr, const zdtun_5tuple_t *tuple, conn_data_t *data);
void conn_end_ndpi_detection(conn_data_t *data, vpnproxy_data_t *proxy, const zdtun_5tuple_t *tuple);
void run_housekeeping(vpnproxy_data_t *proxy);
void account_packet(vpnproxy_data_t *proxy, const zdtun_pkt_t *pkt, uint8_t from_tun, const zdtun_5tuple_t *conn_tuple, conn_data_t *data);
int resolve_uid(vpnproxy_data_t *proxy, const zdtun_5tuple_t *conn_info);
void refresh_time(vpnproxy_data_t *proxy);
void init_protocols_bitmask(ndpi_protocol_bitmask_struct_t *b);
void vpn_protect_socket(vpnproxy_data_t *proxy, socket_t sock);

char* getStringPref(vpnproxy_data_t *proxy, const char *key, char *buf, int bufsize);
int getIntPref(JNIEnv *env, jobject vpn_inst, const char *key);
uint32_t getIPv4Pref(JNIEnv *env, jobject vpn_inst, const char *key);
struct in6_addr getIPv6Pref(JNIEnv *env, jobject vpn_inst, const char *key);

int run_proxy(vpnproxy_data_t *proxy);
int run_root(vpnproxy_data_t *proxy);

#endif //__PCAPDROID_H__
