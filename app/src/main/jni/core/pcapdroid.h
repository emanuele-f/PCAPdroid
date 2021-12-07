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
#include "blacklist.h"
#include "ndpi_api.h"
#include "common/uid_resolver.h"
#include "third_party/uthash.h"

#define CAPTURE_STATS_UPDATE_FREQUENCY_MS 300
#define CONNECTION_DUMP_UPDATE_FREQUENCY_MS 1000
#define MAX_JAVA_DUMP_DELAY_MS 1000
#define NETD_RESOLVE_DELAY_MS 1000
#define SELECT_TIMEOUT_MS 250
#define MAX_DPI_PACKETS 12
#define MAX_HOST_LRU_SIZE 256
#define JAVA_PCAP_BUFFER_SIZE (512*1024) // 512K
#define PERIODIC_PURGE_TIMEOUT_MS 5000
#define MAX_PLAINTEXT_LENGTH 1024

#define DNS_FLAGS_MASK 0x8000
#define DNS_TYPE_REQUEST 0x0000
#define DNS_TYPE_RESPONSE 0x8000

#define CONN_UPDATE_STATS 1
#define CONN_UPDATE_INFO 2

typedef struct {
    jlong sent_bytes;
    jlong rcvd_bytes;
    jint sent_pkts;
    jint rcvd_pkts;

    bool new_stats;
    u_int64_t last_update_ms;
} capture_stats_t;

typedef struct {
    jint incr_id; // an incremental number which identifies a specific connection

    /* nDPI */
    struct ndpi_flow_struct *ndpi_flow;
    struct ndpi_id_struct *src_id, *dst_id;
    uint16_t l7proto;

    union {
        struct {
            uint64_t last_update_ms; // like last_seen but monotonic
        } root;
        struct {
            struct pkt_context *fw_pctx; // context for the forwarded packet
        } vpn;
    };

    jlong first_seen;
    jlong last_seen;
    jlong sent_bytes;
    jlong rcvd_bytes;
    jint sent_pkts;
    jint rcvd_pkts;
    jint blocked_pkts;
    zdtun_conn_status_t status;
    char *info;
    jint uid;
    uint8_t tcp_flags[2]; // cli2srv, srv2cli
    union {
        uint8_t last_ack;
        uint8_t pending_dns_queries;
    };
    bool pending_notification;
    bool to_purge; // if true, free this pd_conn_t during the next sendConnectionsDump
    bool info_from_lru;
    bool request_done;
    bool blacklisted_ip;
    bool blacklisted_domain;
    bool to_block;
    char *request_data;
    char *url;
    uint8_t update_type;
} pd_conn_t;

typedef struct {
    zdtun_5tuple_t tuple;
    pd_conn_t *data;
} conn_and_tuple_t;

typedef struct {
    conn_and_tuple_t *items;
    int size;
    int cur_items;
} conn_array_t;

typedef struct {
    int uid;
    char appname[64];
    UT_hash_handle hh;
} uid_to_app_t;

typedef struct pkt_context {
    zdtun_pkt_t *pkt;
    struct timeval tv; // Packet timestamp, need by pcap_dump_rec
    uint64_t ms;       // Packet timestamp in ms
    bool is_tx;
    const zdtun_5tuple_t *tuple;
    pd_conn_t *data;
} pkt_context_t;

typedef struct pcap_conn pcap_conn_t;

typedef struct {
    JNIEnv *env;
    jobject capture_service;
    jint sdk_ver;
    int new_conn_id;
    uint64_t now_ms;            // Monotonic timestamp, see pd_refresh_time
    struct ndpi_detection_module_struct *ndpi;
    zdtun_t *zdt;
    ip_lru_t *ip_to_host;
    conn_array_t new_conns;
    conn_array_t conns_updates;
    uid_to_app_t *uid2app;
    char cachedir[PATH_MAX];
    char filesdir[PATH_MAX];
    int cachedir_len;
    int filesdir_len;

    // config
    jint app_filter;
    bool root_capture;

    // stats
    u_int num_dropped_pkts;
    long num_discarded_fragments;
    uint32_t num_dropped_connections;
    uint32_t num_dns_requests;
    zdtun_statistics_t stats;
    capture_stats_t capture_stats;

    union {
        struct {
            int tunfd;
            uint32_t dns_server;
            uint32_t internal_dns;
            uint32_t internal_ipv4;
            ndpi_ptree_t *known_dns_servers;
            uid_resolver_t *resolver;
        } vpn;
        struct {
            pcap_conn_t *connections;
        } root;
    };

    struct {
        bool enabled;
        // the crc32 implementation requires 4-bytes aligned accesses.
        // frames are padded to honor the 4-bytes alignment.
        jbyte *buffer  __attribute__((aligned (4)));
        int buffer_idx;
        u_int64_t last_dump_ms;
    } pcap_dump;

    struct {
        bool enabled;
        u_int32_t proxy_ip;
        u_int32_t proxy_port;
    } socks5;

    struct {
        bool enabled;
        struct in6_addr dns_server;
    } ipv6;

    struct {
        bool enabled;
        blacklist_t *bl;
        pthread_t reload_worker;
        bool reload_in_progress;
        volatile bool reload_done;
        blacklist_t *new_bl;
        bl_status_arr_t *status_arr;
        bl_info_t *bls_info;
        int num_bls;
    } malware_detection;

    struct {
        blacklist_t *bl;
        blacklist_t *new_bl;
    } firewall;
} pcapdroid_t;

/* ******************************************************* */

typedef struct {
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

typedef struct {
    jmethodID reportError;
    jmethodID getApplicationByUid;
    jmethodID protect;
    jmethodID dumpPcapData;
    jmethodID updateConnections;
    jmethodID connInit;
    jmethodID connProcessUpdate;
    jmethodID connUpdateInit;
    jmethodID connUpdateSetStats;
    jmethodID connUpdateSetInfo;
    jmethodID sendServiceStatus;
    jmethodID sendStatsDump;
    jmethodID statsInit;
    jmethodID statsSetData;
    jmethodID getLibprogPath;
    jmethodID notifyBlacklistsLoaded;
    jmethodID blacklistStatusInit;
    jmethodID getBlacklistsInfo;
} jni_methods_t;

typedef struct {
    jclass vpn_service;
    jclass conn;
    jclass conn_update;
    jclass stats;
    jclass blacklist_status;
    jclass blacklist_descriptor;
} jni_classes_t;

typedef struct {
    jfieldID bldescr_fname;
    jfieldID bldescr_type;
} jni_fields_t;

/* ******************************************************* */

extern jni_methods_t mids;
extern jni_classes_t cls;
extern bool running;
extern uint32_t new_dns_server;
extern bool block_private_dns;

// capture API
void pd_refresh_time(pcapdroid_t *pd);
void pd_process_packet(pcapdroid_t *pd, zdtun_pkt_t *pkt, bool is_tx, const zdtun_5tuple_t *tuple,
                       pd_conn_t *data, struct timeval *tv, pkt_context_t *pctx);
void pd_account_stats(pcapdroid_t *pd, pkt_context_t *pctx);
void pd_housekeeping(pcapdroid_t *pd);
pd_conn_t* pd_new_connection(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, int uid);
void pd_purge_connection(pd_conn_t *data);
void pd_notify_connection_update(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, pd_conn_t *data);
void pd_giveup_dpi(pcapdroid_t *pd, pd_conn_t *data, const zdtun_5tuple_t *tuple);

// Utility
const char* get_cache_path(const char *subpath);
const char* get_file_path(const char *subpath);
static inline const char* get_cache_dir() { return get_cache_path(""); }
static inline const char* get_files_dir() { return get_file_path(""); }
char* get_appname_by_uid(pcapdroid_t *pd, int uid, char *buf, int bufsize);
char* getStringPref(pcapdroid_t *pd, const char *key, char *buf, int bufsize);
int getIntPref(JNIEnv *env, jobject vpn_inst, const char *key);
uint32_t getIPv4Pref(JNIEnv *env, jobject vpn_inst, const char *key);
struct in6_addr getIPv6Pref(JNIEnv *env, jobject vpn_inst, const char *key);

// Internals
struct pcapdroid_trailer;
void fill_custom_data(struct pcapdroid_trailer *cdata, pcapdroid_t *pd, pd_conn_t *conn);
void init_protocols_bitmask(ndpi_protocol_bitmask_struct_t *b);
uint32_t crc32(u_char *buf, size_t len, uint32_t crc);

#endif //__PCAPDROID_H__
