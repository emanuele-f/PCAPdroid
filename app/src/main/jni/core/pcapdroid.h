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
 * Copyright 2020-24 - Emanuele Faranda
 */

#ifndef __PCAPDROID_H__
#define __PCAPDROID_H__

#include <stdbool.h>
#include "zdtun.h"
#include "ip_lru.h"
#include "blacklist.h"
#include "pcap_dump.h"
#include "ndpi_api.h"
#include "common/jni_utils.h"
#include "common/uid_resolver.h"
#include "third_party/uthash.h"

#define CAPTURE_STATS_UPDATE_FREQUENCY_MS 300
#define CONNECTION_DUMP_UPDATE_FREQUENCY_MS 1000
#define NETD_RESOLVE_DELAY_MS 1000
#define SELECT_TIMEOUT_MS 250
#define MAX_DPI_PACKETS 12
#define VPN_BUFFER_SIZE 32768
#define MAX_HOST_LRU_SIZE 256
#define PERIODIC_PURGE_TIMEOUT_MS 5000
#define MINIMAL_PAYLOAD_MAX_DIRECTION_SIZE 512

#define DNS_FLAGS_MASK 0x8000
#define DNS_TYPE_REQUEST 0x0000
#define DNS_TYPE_RESPONSE 0x8000

#define CONN_UPDATE_STATS   0x1
#define CONN_UPDATE_INFO    0x2
#define CONN_UPDATE_PAYLOAD 0x4

typedef struct {
    jlong sent_bytes;
    jlong rcvd_bytes;
    jlong ipv6_sent_bytes;
    jlong ipv6_rcvd_bytes;
    jint sent_pkts;
    jint rcvd_pkts;

    bool new_stats;
    u_int64_t last_update_ms;
} capture_stats_t;

// NOTE: sync with Prefs.PayloadMode
typedef enum {
    PAYLOAD_MODE_NONE = 0,
    PAYLOAD_MODE_MINIMAL,
    PAYLOAD_MODE_FULL
} payload_mode_t;

// NOTE: sync with Prefs.BlockQuicMode
typedef enum {
    BLOCK_QUIC_MODE_NEVER = 0,
    BLOCK_QUIC_MODE_ALWAYS,
    BLOCK_QUIC_MODE_TO_DECRYPT
} block_quic_mode_t;

typedef struct {
    jint incr_id; // an incremental number which identifies a specific connection

    /* nDPI */
    struct ndpi_flow_struct *ndpi_flow;
    struct ndpi_id_struct *src_id, *dst_id;
    uint16_t l7proto;
    uint16_t alpn;

    union {
        struct {
            uint64_t last_update_ms; // like last_seen but monotonic
            u_int ifidx;             // the 1-based interface index
        } pcap;
        struct {
            struct pkt_context *fw_pctx; // context for the forwarded packet
            uint16_t local_port;         // local port, from zdtun to the Internet
        } vpn;
    };

    void* payload_chunks;

    jlong first_seen;
    jlong last_seen;
    jlong payload_length;
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
    bool blacklisted_internal;
    bool blacklisted_ip;
    bool blacklisted_domain;
    bool whitelisted_app;
    bool to_block;
    bool netd_block_missed;
    bool proxied;
    bool decryption_ignored;
    bool port_mapping_applied;
    bool encrypted_l7;
    bool payload_truncated;
    bool has_payload[2]; // [0]: rx, [1] tx
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

/* ******************************************************* */

struct pcapdroid;

// Used to decouple pcapdroid.c from the JNI calls
typedef struct {
    void (*get_libprog_path)(struct pcapdroid *pd, const char *prog_name, char *buf, int bufsize);
     int (*load_blacklists_info)(struct pcapdroid *pd);
    void (*send_stats_dump)(struct pcapdroid *pd);
    void (*send_connections_dump)(struct pcapdroid *pd);
    void (*send_pcap_dump)(struct pcapdroid *pd, const int8_t *buf, int dump_size);
    void (*stop_pcap_dump)(struct pcapdroid *pd);
    void (*notify_service_status)(struct pcapdroid *pd, const char *status);
    void (*notify_blacklists_loaded)(struct pcapdroid *pd, bl_status_arr_t *status_arr);
    bool (*dump_payload_chunk)(struct pcapdroid *pd, const pkt_context_t *pctx, int dump_size);
} pd_callbacks_t;

/* ******************************************************* */

typedef struct pcapdroid {
#ifdef ANDROID
    JNIEnv *env;
    jobject capture_service;
    jint sdk_ver;
#endif
    int new_conn_id;
    uint64_t now_ms;            // Monotonic timestamp, see pd_refresh_time
    struct ndpi_detection_module_struct *ndpi;
    zdtun_t *zdt;
    ip_lru_t *ip_to_host;
    conn_array_t new_conns;
    conn_array_t conns_updates;
    pd_callbacks_t cb;
    uid_to_app_t *uid2app;
    char cachedir[PATH_MAX];
    char filesdir[PATH_MAX];
    int cachedir_len;
    int filesdir_len;

    // config
    jint mitm_addon_uid;
    bool vpn_capture;
    bool pcap_file_capture;
    payload_mode_t payload_mode;

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
            block_quic_mode_t block_quic_mode;
            blacklist_t *known_dns_servers;
            uid_resolver_t *resolver;

            struct {
                bool enabled;
                uint32_t dns_server;
                uint32_t internal_dns;
            } ipv4;
            struct {
                bool enabled;
                struct in6_addr dns_server;
            } ipv6;
        } vpn;
        struct {
            struct pcap_conn_t *connections;
            bool as_root;
            bool daemonize;
            char *bpf;
            char *capture_interface;
            int pcapd_pid;

            int *app_filter_uids;
            int app_filter_uids_size;
        } pcap;
    };

    struct {
        bool enabled;
        bool trailer_enabled;
        bool pcapng_format;
        int snaplen;
        int max_pkts_per_flow;
        int max_dump_size;
        pcap_dumper_t *dumper;
    } pcap_dump;

    struct {
        bool enabled;
        zdtun_ip_t proxy_ip;
        u_int32_t proxy_port;
        int proxy_ipver;
        char proxy_user[32];
        char proxy_pass[32];
    } socks5;

    struct {
        bool enabled;
        blacklist_t *bl; // blacklist
        blacklist_t *whitelist;
        pthread_t reload_worker;
        bool reload_in_progress;
        volatile bool reload_done;
        blacklist_t *new_bl;
        blacklist_t *new_wl;
        bl_status_arr_t *status_arr;
        bl_info_t *bls_info;
        int num_bls;
    } malware_detection;

    struct {
        bool enabled;
        blacklist_t *bl;     // blocklist
        blacklist_t *new_bl;
        bool wl_enabled;
        blacklist_t *wl;     // whitelist
        blacklist_t *new_wl;
    } firewall;

    struct {
        bool enabled;
        blacklist_t *list;
        blacklist_t *new_list;
    } tls_decryption;
} pcapdroid_t;

// return 0 to continue, anything else to break
typedef int (*conn_cb)(pcapdroid_t*, const zdtun_5tuple_t*, pd_conn_t*);

/* ******************************************************* */

typedef struct {
    uint16_t transaction_id;
    uint16_t flags;
    uint16_t questions;
    uint16_t answ_rrs;
    uint16_t auth_rrs;
    uint16_t additional_rrs;
    uint8_t queries[];
} __attribute__((packed)) dns_packet_t;

/* ******************************************************* */

#ifdef ANDROID

typedef struct {
    jmethodID reportError;
    jmethodID getApplicationByUid;
    jmethodID protect;
    jmethodID dumpPcapData;
    jmethodID stopPcapDump;
    jmethodID updateConnections;
    jmethodID connInit;
    jmethodID connProcessUpdate;
    jmethodID connUpdateInit;
    jmethodID connUpdateSetStats;
    jmethodID connUpdateSetInfo;
    jmethodID connUpdateSetPayload;
    jmethodID sendServiceStatus;
    jmethodID sendStatsDump;
    jmethodID statsInit;
    jmethodID statsSetData;
    jmethodID getLibprogPath;
    jmethodID notifyBlacklistsLoaded;
    jmethodID blacklistStatusInit;
    jmethodID getBlacklistsInfo;
    jmethodID listSize;
    jmethodID listGet;
    jmethodID arraylistNew;
    jmethodID arraylistAdd;
    jmethodID payloadChunkInit;
} jni_methods_t;

typedef struct {
    jclass vpn_service;
    jclass conn;
    jclass conn_update;
    jclass stats;
    jclass blacklist_status;
    jclass blacklist_descriptor;
    jclass matchlist_descriptor;
    jclass list;
    jclass arraylist;
    jclass payload_chunk;
} jni_classes_t;

typedef struct {
    jfieldID bldescr_fname;
    jfieldID bldescr_type;
    jfieldID ld_apps;
    jfieldID ld_hosts;
    jfieldID ld_ips;
} jni_fields_t;

typedef struct {
    jobject bltype_ip;
    jobject chunktype_raw;
    jobject chunktype_http;
} jni_enum_t;

extern jni_methods_t mids;
extern jni_classes_t cls;
extern jni_fields_t fields;
extern jni_enum_t enums;

#endif // ANDROID

/* ******************************************************* */

extern bool running;
extern uint32_t new_dns_server;
extern bool block_private_dns;
extern bool dump_capture_stats_now;
extern bool reload_blacklists_now;
extern bool has_seen_pcapdroid_trailer;
extern int bl_num_checked_connections;
extern int fw_num_checked_connections;
extern char *pd_appver;
extern char *pd_device;
extern char *pd_os;

// capture API
int pd_run(pcapdroid_t *pd);
void pd_refresh_time(pcapdroid_t *pd);
void pd_process_packet(pcapdroid_t *pd, zdtun_pkt_t *pkt, bool is_tx, const zdtun_5tuple_t *tuple,
                       pd_conn_t *data, struct timeval *tv, pkt_context_t *pctx);
void pd_account_stats(pcapdroid_t *pd, pkt_context_t *pctx);
void pd_dump_packet(pcapdroid_t *pd, const char *pktbuf, int pktlen, const struct timeval *tv, int uid);
void pd_housekeeping(pcapdroid_t *pd);
pd_conn_t* pd_new_connection(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, int uid);
void pd_purge_connection(pcapdroid_t *pd, pd_conn_t *data);
int pd_notify_connection_update(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, pd_conn_t *data);
void pd_giveup_dpi(pcapdroid_t *pd, pd_conn_t *data, const zdtun_5tuple_t *tuple);
const char* pd_get_proto_name(pcapdroid_t *pd, uint16_t proto, uint16_t alpn, int ipproto);

// Utility
const char* get_cache_path(pcapdroid_t *pd, const char *subpath);
const char* get_file_path(pcapdroid_t *pd, const char *subpath);
static inline const char* get_cache_dir(pcapdroid_t *pd) { return get_cache_path(pd, ""); }
static inline const char* get_files_dir(pcapdroid_t *pd) { return get_file_path(pd, ""); }
char* get_appname_by_uid(pcapdroid_t *pd, int uid, char *buf, int bufsize);
uint16_t pd_ndpi2proto(ndpi_protocol proto);

#ifdef ANDROID

char* getStringPref(pcapdroid_t *pd, const char *key, char *buf, int bufsize);
int getIntPref(JNIEnv *env, jobject vpn_inst, const char *key);
int getIntArrayPref(JNIEnv *env, jobject vpn_inst, const char *key, int **out);
zdtun_ip_t getIPPref(JNIEnv *env, jobject vpn_inst, const char *key, int *ip_ver);
uint32_t getIPv4Pref(JNIEnv *env, jobject vpn_inst, const char *key);
struct in6_addr getIPv6Pref(JNIEnv *env, jobject vpn_inst, const char *key);
void getApplicationByUid(pcapdroid_t *pd, jint uid, char *buf, int bufsize);

#endif // ANDROID

// Internals
void init_ndpi_protocols_bitmask(ndpi_protocol_bitmask_struct_t *b);
void load_ndpi_hosts(struct ndpi_detection_module_struct *ndpi);
uint32_t crc32(u_char *buf, size_t len, uint32_t crc);

#endif //__PCAPDROID_H__
