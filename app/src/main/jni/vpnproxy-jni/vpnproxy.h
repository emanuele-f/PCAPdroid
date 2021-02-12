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

#include <jni.h>
#include <android/log.h>
#include "zdtun.h"
#include "ndpi_api.h"

#ifndef REMOTE_CAPTURE_VPNPROXY_H
#define REMOTE_CAPTURE_VPNPROXY_H

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
    char *info;
    char *url;
    jint uid;
    bool terminated;
    bool pending_notification;
    bool mitm_header_needed;
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
    int tapfd;
    int incr_id;
    jint sdk;
    JNIEnv *env;
    jobject vpn_service;
    u_int32_t vpn_dns;
    u_int32_t public_dns;
    u_int32_t vpn_ipv4;
    struct ndpi_detection_module_struct *ndpi;
    uint64_t now_ms;
    u_int32_t num_dropped_connections;
    u_int32_t num_dns_requests;
    conn_array_t new_conns;
    conn_array_t conns_updates;

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
    } tls_decryption;

    capture_stats_t capture_stats;
} vpnproxy_data_t;

/* ******************************************************* */

jint get_uid(struct vpnproxy_data *proxy, const zdtun_5tuple_t *conn_info);

#endif //REMOTE_CAPTURE_H
