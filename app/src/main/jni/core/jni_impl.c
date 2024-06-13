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
 * Copyright 2022-24 - Emanuele Faranda
 */

#if ANDROID

#include <pthread.h>
#include "pcapdroid.h"
#include "common/utils.h"
#include "log_writer.h"
#include "port_map.h"
#include "pcap_dump.h"

// This files contains functions to make the capture core communicate
// with the Android system.
// Exported functions are defined in pcapdroid.h

static pcapdroid_t *global_pd = NULL;
static pthread_t jni_thread;

jni_classes_t cls;
jni_methods_t mids;
jni_fields_t fields;
jni_enum_t enums;

/* ******************************************************* */

static void log_callback(int lvl, const char *line) {
    pcapdroid_t *pd = global_pd;

    // quick path for debug logs
    if(lvl < PD_DEFAULT_LOGGER_LEVEL)
        return;

    pd_log_write(PD_DEFAULT_LOGGER, lvl, line);

    // ensure that we are invoking jni from the attached thread
    if(!pd || !(pthread_equal(jni_thread, pthread_self())))
        return;

    if(lvl >= ANDROID_LOG_FATAL) {
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

static void sendStatsDump(pcapdroid_t *pd) {
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
        log_e("NewObject(CaptureStats) failed");
        return;
    }

    (*env)->CallVoidMethod(env, stats_obj, mids.statsSetData,
                           allocs_summary,
                           capstats->sent_bytes, capstats->rcvd_bytes,
                           capstats->ipv6_sent_bytes, capstats->ipv6_rcvd_bytes,
                           (jlong)(pd->pcap_dump.dumper ? pcap_get_dump_size(pd->pcap_dump.dumper) : 0),
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

static void sendPcapDump(struct pcapdroid *pd, const int8_t *buf, int dump_size) {
    JNIEnv *env = pd->env;

    //log_d("Exporting a %d B PCAP buffer", pd->pcap_dump.buffer_idx);

    jbyteArray barray = (*env)->NewByteArray(env, dump_size);
    if(jniCheckException(env))
        return;

    (*env)->SetByteArrayRegion(env, barray, 0, dump_size, buf);
    (*env)->CallVoidMethod(env, pd->capture_service, mids.dumpPcapData, barray);
    jniCheckException(env);

    (*env)->DeleteLocalRef(env, barray);
}

/* ******************************************************* */

static void stopPcapDump(pcapdroid_t *pd) {
    JNIEnv *env = pd->env;

    (*env)->CallVoidMethod(env, pd->capture_service, mids.stopPcapDump);
    jniCheckException(env);
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

static jobject getConnUpdate(pcapdroid_t *pd, const conn_and_tuple_t *conn) {
    JNIEnv *env = pd->env;
    pd_conn_t *data = conn->data;

    jobject update = (*env)->NewObject(env, cls.conn_update, mids.connUpdateInit, data->incr_id);

    if((update == NULL) || jniCheckException(env)) {
        log_e("NewObject(ConnectionDescriptor) failed");
        return NULL;
    }

    if(data->update_type & CONN_UPDATE_STATS) {
        bool blocked = data->to_block && pd->vpn_capture; // currently can only block connections in non-root mode

        (*env)->CallVoidMethod(env, update, mids.connUpdateSetStats, data->last_seen,
                               data->payload_length, data->sent_bytes, data->rcvd_bytes, data->sent_pkts, data->rcvd_pkts, data->blocked_pkts,
                               (data->tcp_flags[0] << 8) | data->tcp_flags[1],
                               (data->port_mapping_applied << 13) |
                                    (data->decryption_ignored << 12) |
                                    (data->netd_block_missed << 11) |
                                    (blocked << 10) |
                                    (data->blacklisted_domain << 9) |
                                    (data->blacklisted_ip << 8) |
                                    (data->status & 0xFF) /* 8 bits */);
    }
    if(data->update_type & CONN_UPDATE_INFO) {
        jobject info = (*env)->NewStringUTF(env, data->info ? data->info : "");
        jobject url = (*env)->NewStringUTF(env, data->url ? data->url : "");
        jobject l7proto = (*env)->NewStringUTF(env, pd_get_proto_name(pd, data->l7proto, data->alpn,
                                                                      conn->tuple.ipproto));
        int flags = data->encrypted_l7;

        (*env)->CallVoidMethod(env, update, mids.connUpdateSetInfo, info, url, l7proto, flags);

        (*env)->DeleteLocalRef(env, info);
        (*env)->DeleteLocalRef(env, url);
        (*env)->DeleteLocalRef(env, l7proto);
    }
    if(data->update_type & CONN_UPDATE_PAYLOAD) {
        (*env)->CallVoidMethod(env, update, mids.connUpdateSetPayload, data->payload_chunks,
                               data->payload_truncated |
                               (data->has_decrypted_data << 1));
        (*pd->env)->DeleteLocalRef(pd->env, data->payload_chunks);
        data->payload_chunks = NULL;
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
    u_int ifidx = (pd->vpn_capture ? 0 : data->pcap.ifidx);
    u_int local_port = (pd->vpn_capture ? data->vpn.local_port : conn_info->src_port);
    bool mitm_decrypt = (pd->tls_decryption.enabled && data->proxied);
    jobject conn_descriptor = (*env)->NewObject(env, cls.conn, mids.connInit, data->incr_id,
                                                conn_info->ipver, conn_info->ipproto,
                                                src_string, dst_string,
                                                ntohs(conn_info->src_port), ntohs(conn_info->dst_port),
                                                ntohs(local_port),
                                                data->uid, ifidx, mitm_decrypt, data->first_seen);

    if((conn_descriptor != NULL) && !jniCheckException(env)) {
        // This is the first update, send all the data
        conn->data->update_type |= CONN_UPDATE_STATS | CONN_UPDATE_INFO;
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
    JNIEnv *env = pd->env;
    //jniDumpReferences(env);

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
    (*env)->DeleteLocalRef(env, new_conns);
    (*env)->DeleteLocalRef(env, conns_updates);
    //jniDumpReferences(env);
}

/* ******************************************************* */

// Load information about the blacklists to use (into pd->malware_detection.bls_info)
static int loadBlacklistsInfo(pcapdroid_t *pd) {
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
            blinfo->type = (*env)->IsSameObject(env, bl_type, enums.bltype_ip) ? IP_BLACKLIST : DOMAIN_BLACKLIST;
            (*pd->env)->DeleteLocalRef(pd->env, bl_type);

            //log_d("[+] Blacklist: %s (%s)", blinfo->fname, (blinfo->type == IP_BLACKLIST) ? "IP" : "domain");
            (*pd->env)->DeleteLocalRef(pd->env, bl_descr);
        }
    }

cleanup:
    (*pd->env)->DeleteLocalRef(pd->env, arr);
    return rv;
}

/* ******************************************************* */

static void notifyBlacklistsLoaded(pcapdroid_t *pd, bl_status_arr_t *status_arr) {
    JNIEnv *env = pd->env;
    jobject status_obj = (*env)->NewObjectArray(env, status_arr->cur_items, cls.blacklist_status, NULL);

    if((status_obj == NULL) || jniCheckException(env)) {
        log_e("NewObjectArray() failed");
        return;
    }

    for(int i=0; i<status_arr->cur_items; i++) {
        bl_status_t *st = &status_arr->items[i];
        jstring fname = (*env)->NewStringUTF(env, st->fname);
        if((fname == NULL) || jniCheckException(env))
            break;

        jobject stats = (*env)->NewObject(env, cls.blacklist_status, mids.blacklistStatusInit,
                                              fname, st->num_rules);
        (*env)->DeleteLocalRef(env, fname);

        if((stats == NULL) || jniCheckException(env))
            break;

        (*env)->SetObjectArrayElement(env, status_obj, i, stats);
        (*env)->DeleteLocalRef(env, stats);

        if(jniCheckException(env)) {
            break;
        }
    }

    (*env)->CallVoidMethod(env, pd->capture_service, mids.notifyBlacklistsLoaded, status_obj);
    (*env)->DeleteLocalRef(env, status_obj);
}

/* ******************************************************* */

static bool dumpPayloadChunk(struct pcapdroid *pd, const pkt_context_t *pctx, const char *dump_data, int dump_size) {
    JNIEnv *env = pd->env;
    bool rv = false;

    if(pctx->data->payload_chunks == NULL) {
        // Directly allocating an ArrayList<bytes> rather than creating it afterwards saves us from a data copy.
        // However, this creates a local reference, which is retained until sendConnectionsDump is called.
        // NOTE: Android only allows up to 512 local references.
        pctx->data->payload_chunks = (*env)->NewObject(env, cls.arraylist, mids.arraylistNew);
        if((pctx->data->payload_chunks == NULL) || jniCheckException(env))
            return false;
    }

    jbyteArray barray = (*env)->NewByteArray(env, dump_size);
    if(jniCheckException(env))
        return false;

    jobject chunk_type = (pctx->data->l7proto == NDPI_PROTOCOL_HTTP) ? enums.chunktype_http : enums.chunktype_raw;

    jobject chunk = (*env)->NewObject(env, cls.payload_chunk, mids.payloadChunkInit, barray, chunk_type, pctx->is_tx, pctx->ms);
    if(chunk && !jniCheckException(env)) {
        (*env)->SetByteArrayRegion(env, barray, 0, dump_size, (jbyte*) dump_data);
        rv = (*env)->CallBooleanMethod(env, pctx->data->payload_chunks, mids.arraylistAdd, chunk);
    }

    //log_d("Dump chunk [size=%d]: %d", rv, dump_size);

    (*env)->DeleteLocalRef(env, barray);
    (*env)->DeleteLocalRef(env, chunk);
    return rv;
}

/* ******************************************************* */

static void clearPayloadChunks(struct pcapdroid *pd, const pkt_context_t *pctx) {
    JNIEnv *env = pd->env;

    if (pctx->data->payload_chunks) {
        (*env)->DeleteLocalRef(env, pctx->data->payload_chunks);
        pctx->data->payload_chunks = NULL;
    }
}

/* ******************************************************* */

// TODO rename
static void getLibprogPath(pcapdroid_t *pd, const char *prog_name, char *buf, int bufsize) {
    JNIEnv *env = pd->env;
    jobject prog_str = (*env)->NewStringUTF(env, prog_name);

    buf[0] = '\0';

    if((prog_str == NULL) || jniCheckException(env)) {
        log_e("could not allocate get_libprog_path string");
        return;
    }

    jstring obj = (*env)->CallObjectMethod(env, pd->capture_service, mids.getLibprogPath, prog_str);
    (*env)->DeleteLocalRef(env, prog_str);

    if(!jniCheckException(env)) {
        const char *value = (*env)->GetStringUTFChars(env, obj, 0);

        strncpy(buf, value, bufsize);
        buf[bufsize - 1] = '\0';

        (*env)->ReleaseStringUTFChars(env, obj, value);
    }

    (*env)->DeleteLocalRef(env, obj);
}

/* ******************************************************* */

static void getSocks5ProxyAuth(pcapdroid_t *pd) {
    char buf[64];
    buf[0] = '\0';

    getStringPref(pd, "getSocks5ProxyAuth", buf, sizeof(buf));
    char *sep = strchr(buf, ':');

    if(!sep)
        return;

    *sep = '\0';
    strncpy(pd->socks5.proxy_user, buf, sizeof(pd->socks5.proxy_user));
    strncpy(pd->socks5.proxy_pass, sep + 1, sizeof(pd->socks5.proxy_pass));

    //log_d("SOCKS5: user=%s pass=%s", pd->socks5.proxy_user, pd->socks5.proxy_pass);
}

static void init_jni(JNIEnv *env) {
    // NOTE: these are bound to this specific env

    /* Classes */
    cls.vpn_service = jniFindClass(env, "com/emanuelef/remote_capture/CaptureService");
    cls.conn = jniFindClass(env, "com/emanuelef/remote_capture/model/ConnectionDescriptor");
    cls.conn_update = jniFindClass(env, "com/emanuelef/remote_capture/model/ConnectionUpdate");
    cls.stats = jniFindClass(env, "com/emanuelef/remote_capture/model/CaptureStats");
    cls.blacklist_status = jniFindClass(env, "com/emanuelef/remote_capture/Blacklists$NativeBlacklistStatus");
    cls.blacklist_descriptor = jniFindClass(env, "com/emanuelef/remote_capture/model/BlacklistDescriptor");
    cls.matchlist_descriptor = jniFindClass(env, "com/emanuelef/remote_capture/model/MatchList$ListDescriptor");
    cls.list = jniFindClass(env, "java/util/List");
    cls.arraylist = jniFindClass(env, "java/util/ArrayList");
    cls.payload_chunk = jniFindClass(env, "com/emanuelef/remote_capture/model/PayloadChunk");

    /* Methods */
    mids.reportError = jniGetMethodID(env, cls.vpn_service, "reportError", "(Ljava/lang/String;)V");
    mids.getApplicationByUid = jniGetMethodID(env, cls.vpn_service, "getApplicationByUid", "(I)Ljava/lang/String;"),
            mids.protect = jniGetMethodID(env, cls.vpn_service, "protect", "(I)Z");
    mids.dumpPcapData = jniGetMethodID(env, cls.vpn_service, "dumpPcapData", "([B)V");
    mids.stopPcapDump = jniGetMethodID(env, cls.vpn_service, "stopPcapDump", "()V");
    mids.updateConnections = jniGetMethodID(env, cls.vpn_service, "updateConnections", "([Lcom/emanuelef/remote_capture/model/ConnectionDescriptor;[Lcom/emanuelef/remote_capture/model/ConnectionUpdate;)V");
    mids.sendStatsDump = jniGetMethodID(env, cls.vpn_service, "sendStatsDump", "(Lcom/emanuelef/remote_capture/model/CaptureStats;)V");
    mids.sendServiceStatus = jniGetMethodID(env, cls.vpn_service, "sendServiceStatus", "(Ljava/lang/String;)V");
    mids.getLibprogPath = jniGetMethodID(env, cls.vpn_service, "getLibprogPath", "(Ljava/lang/String;)Ljava/lang/String;");
    mids.notifyBlacklistsLoaded = jniGetMethodID(env, cls.vpn_service, "notifyBlacklistsLoaded", "([Lcom/emanuelef/remote_capture/Blacklists$NativeBlacklistStatus;)V");
    mids.getBlacklistsInfo = jniGetMethodID(env, cls.vpn_service, "getBlacklistsInfo", "()[Lcom/emanuelef/remote_capture/model/BlacklistDescriptor;");
    mids.connInit = jniGetMethodID(env, cls.conn, "<init>", "(IIILjava/lang/String;Ljava/lang/String;IIIIIZJ)V");
    mids.connProcessUpdate = jniGetMethodID(env, cls.conn, "processUpdate", "(Lcom/emanuelef/remote_capture/model/ConnectionUpdate;)V");
    mids.connUpdateInit = jniGetMethodID(env, cls.conn_update, "<init>", "(I)V");
    mids.connUpdateSetStats = jniGetMethodID(env, cls.conn_update, "setStats", "(JJJJIIIII)V");
    mids.connUpdateSetInfo = jniGetMethodID(env, cls.conn_update, "setInfo", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
    mids.connUpdateSetPayload = jniGetMethodID(env, cls.conn_update, "setPayload", "(Ljava/util/ArrayList;I)V");
    mids.statsInit = jniGetMethodID(env, cls.stats, "<init>", "()V");
    mids.statsSetData = jniGetMethodID(env, cls.stats, "setData", "(Ljava/lang/String;JJJJJIIIIIIIII)V");
    mids.blacklistStatusInit = jniGetMethodID(env, cls.blacklist_status, "<init>", "(Ljava/lang/String;I)V");
    mids.listSize = jniGetMethodID(env, cls.list, "size", "()I");
    mids.listGet = jniGetMethodID(env, cls.list, "get", "(I)Ljava/lang/Object;");
    mids.arraylistNew = jniGetMethodID(env, cls.arraylist, "<init>", "()V");
    mids.arraylistAdd = jniGetMethodID(env, cls.arraylist, "add", "(Ljava/lang/Object;)Z");
    mids.payloadChunkInit = jniGetMethodID(env, cls.payload_chunk, "<init>", "([BLcom/emanuelef/remote_capture/model/PayloadChunk$ChunkType;ZJ)V");

    /* Fields */
    fields.bldescr_fname = jniFieldID(env, cls.blacklist_descriptor, "fname", "Ljava/lang/String;");
    fields.bldescr_type = jniFieldID(env, cls.blacklist_descriptor, "type", "Lcom/emanuelef/remote_capture/model/BlacklistDescriptor$Type;");
    fields.ld_apps = jniFieldID(env, cls.matchlist_descriptor, "apps", "Ljava/util/List;");
    fields.ld_hosts = jniFieldID(env, cls.matchlist_descriptor, "hosts", "Ljava/util/List;");
    fields.ld_ips = jniFieldID(env, cls.matchlist_descriptor, "ips", "Ljava/util/List;");

    /* Enums */
    enums.bltype_ip = jniEnumVal(env, "com/emanuelef/remote_capture/model/BlacklistDescriptor$Type", "IP_BLACKLIST");
    enums.chunktype_raw = jniEnumVal(env, "com/emanuelef/remote_capture/model/PayloadChunk$ChunkType", "RAW");
    enums.chunktype_http = jniEnumVal(env, "com/emanuelef/remote_capture/model/PayloadChunk$ChunkType", "HTTP");
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_runPacketLoop(JNIEnv *env, jclass type, jint tunfd,
                                                              jobject vpn, jint sdk) {

#ifdef PCAPDROID_TRACK_ALLOCS
    set_ndpi_malloc(pd_ndpi_malloc);
    set_ndpi_free(pd_ndpi_free);
#endif

    init_jni(env);

    pcapdroid_t pd = {
            .sdk_ver = sdk,
            .env = env,
            .capture_service = vpn,
            .cb = {
                    .get_libprog_path = getLibprogPath,
                    .load_blacklists_info = loadBlacklistsInfo,
                    .send_stats_dump = sendStatsDump,
                    .send_connections_dump = sendConnectionsDump,
                    .send_pcap_dump = sendPcapDump,
                    .stop_pcap_dump = stopPcapDump,
                    .notify_service_status = notifyServiceStatus,
                    .notify_blacklists_loaded = notifyBlacklistsLoaded,
                    .dump_payload_chunk = dumpPayloadChunk,
                    .clear_payload_chunks = clearPayloadChunks,
            },
            .mitm_addon_uid = getIntPref(env, vpn, "getMitmAddonUid"),
            .vpn_capture = (bool) getIntPref(env, vpn, "isVpnCapture"),
            .pcap_file_capture = (bool) getIntPref(env, vpn, "isPcapFileCapture"),
            .payload_mode = (payload_mode_t) getIntPref(env, vpn, "getPayloadMode"),
            .pcap_dump = {
                    .enabled = (bool) getIntPref(env, vpn, "pcapDumpEnabled"),
                    .trailer_enabled = (bool)getIntPref(env, vpn, "addPcapdroidTrailer"),
                    .pcapng_format = (bool)getIntPref(env, vpn, "isPcapngEnabled"),
                    .snaplen = getIntPref(env, vpn, "getSnaplen"),
                    .max_pkts_per_flow = getIntPref(env, vpn, "getMaxPktsPerFlow"),
                    .max_dump_size = getIntPref(env, vpn, "getMaxDumpSize"),
            },
            .socks5 = {
                    .enabled = (bool) getIntPref(env, vpn, "getSocks5Enabled"),
                    .proxy_ip = getIPPref(env, vpn, "getSocks5ProxyAddress", &pd.socks5.proxy_ipver),
                    .proxy_port = htons(getIntPref(env, vpn, "getSocks5ProxyPort")),
            },
            .malware_detection = {
                    .enabled = (bool) getIntPref(env, vpn, "malwareDetectionEnabled"),
            },
            .firewall = {
                    .enabled = (bool) getIntPref(env, vpn, "firewallEnabled"),
            },
            .tls_decryption = {
                    .enabled = (bool) getIntPref(env, vpn, "isTlsDecryptionEnabled"),
            }
    };

    if(pd.socks5.enabled)
        getSocks5ProxyAuth(&pd);

    if(pd.vpn_capture)
        pd.vpn.tunfd = tunfd;

    getStringPref(&pd, "getWorkingDir", pd.cachedir, sizeof(pd.cachedir));
    strcat(pd.cachedir, "/");
    pd.cachedir_len = strlen(pd.cachedir);

    getStringPref(&pd, "getPersistentDir", pd.filesdir, sizeof(pd.filesdir));
    strcat(pd.filesdir, "/");
    pd.filesdir_len = strlen(pd.filesdir);

    global_pd = &pd;
    jni_thread = pthread_self();
    logcallback = log_callback;
    signal(SIGPIPE, SIG_IGN);

    // Run the capture
    pd_run(&pd);

    global_pd = NULL;
    logcallback = NULL;

#if 0
    // free JNI local objects to ease references leak detection
    for(int i=0; i<sizeof(cls)/sizeof(jclass); i++) {
        jclass cur = ((jclass*)&cls)[i];
        (*env)->DeleteLocalRef(env, cur);
    }
    for(int i=0; i<sizeof(enums)/sizeof(jobject); i++) {
        jobject cur = ((jobject*)&enums)[i];
        (*env)->DeleteLocalRef(env, cur);
    }

    // at this point the local reference table should only contain 2 entries (VMDebug + Thread)
    jniDumpReferences(env);
#endif

#ifdef PCAPDROID_TRACK_ALLOCS
    log_i(get_allocs_summary());
#endif
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_stopPacketLoop(JNIEnv *env, jclass type) {
    /* NOTE: the select on the packets loop uses a timeout to wake up periodically */
    log_i("stopPacketLoop called");
    running = false;
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_initPlatformInfo(JNIEnv *env, jclass clazz,
                                                                   jstring appver, jstring device,
                                                                   jstring os) {
    const char *appver_s = (*env)->GetStringUTFChars(env, appver, 0);
    const char *device_s = (*env)->GetStringUTFChars(env, device, 0);
    const char *os_s = (*env)->GetStringUTFChars(env, os, 0);
    pd_appver = strdup(appver_s);
    pd_device = strdup(device_s);
    pd_os = strdup(os_s);
    (*env)->ReleaseStringUTFChars(env, appver, appver_s);
    (*env)->ReleaseStringUTFChars(env, device, device_s);
    (*env)->ReleaseStringUTFChars(env, os, os_s);
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_askStatsDump(JNIEnv *env, jclass clazz) {
    if(running)
        dump_capture_stats_now = true;
}

/* ******************************************************* */

JNIEXPORT jint JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_getFdSetSize(JNIEnv *env, jclass clazz) {
    return FD_SETSIZE;
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_setDnsServer(JNIEnv *env, jclass clazz,
                                                               jstring server) {
    struct in_addr addr = {0};
    const char *value = (*env)->GetStringUTFChars(env, server, 0);

    if(inet_aton(value, &addr) != 0)
        new_dns_server = addr.s_addr;

    (*env)->ReleaseStringUTFChars(env, server, value);
}

/* ******************************************************* */

JNIEXPORT jbyteArray JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_getPcapHeader(JNIEnv *env, jclass clazz) {
    pcapdroid_t *pd = global_pd;
    if(!pd || !pd->pcap_dump.dumper) {
        log_e("NULL pd/dumper instance");
        return false;
    }

    char *pcap_hdr = NULL;
    int hdr_size = pcap_get_preamble(pd->pcap_dump.dumper, &pcap_hdr);
    if((hdr_size < 0) || !pcap_hdr)
        return NULL;

    jbyteArray barray = (*env)->NewByteArray(env, hdr_size);
    if((barray == NULL) || jniCheckException(env)) {
        free(pcap_hdr);
        return NULL;
    }

    (*env)->SetByteArrayRegion(env, barray, 0, hdr_size, (jbyte*)pcap_hdr);
    pd_free(pcap_hdr);

    if(jniCheckException(env)) {
        (*env)->DeleteLocalRef(env, barray);
        return NULL;
    }

    return barray;
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_reloadBlacklists(JNIEnv *env, jclass clazz) {
    reload_blacklists_now = true;
}

/* ******************************************************* */

JNIEXPORT jint JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_getNumCheckedMalwareConnections(JNIEnv *env, jclass clazz) {
    return bl_num_checked_connections;
}

/* ******************************************************* */

JNIEXPORT jint JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_getNumCheckedFirewallConnections(JNIEnv *env, jclass clazz) {
    return fw_num_checked_connections;
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_setPrivateDnsBlocked(JNIEnv *env, jclass clazz, jboolean to_block) {
    block_private_dns = to_block;
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_addPortMapping(JNIEnv *env, jclass clazz, jint ipproto,
                                                                 jint orig_port, jint redirect_port, jstring redirect_ip) {
    zdtun_ip_t ip;

    const char *ip_s = (*env)->GetStringUTFChars(env, redirect_ip, 0);
    int ipver = zdtun_parse_ip(ip_s, &ip);
    (*env)->ReleaseStringUTFChars(env, redirect_ip, ip_s);

    if(ipver < 0) {
        log_e("addPortMapping invalid IP");
        return;
    }

    if(!pd_add_port_map(ipver, ipproto, orig_port, redirect_port, &ip)) {
        log_e("addPortMapping failed");
        return;
    }
}

/* ******************************************************* */

JNIEXPORT jboolean JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_reloadBlocklist(JNIEnv *env, jclass clazz,
        jobject ld) {
    pcapdroid_t *pd = global_pd;
    if(!pd) {
        log_e("NULL pd instance");
        return false;
    }

    if(!pd->vpn_capture) {
        log_e("firewall in root mode not implemented");
        return false;
    }

    if(pd->firewall.new_bl != NULL) {
        log_e("previous blocklist not loaded yet");
        return false;
    }

    blacklist_t *bl = blacklist_init();
    if(!bl) {
        log_e("blacklist_init failed");
        return false;
    }

    if(blacklist_load_list_descriptor(bl, env, ld) < 0) {
        log_f("Could not load firewall rules. Check the log for more details");
        blacklist_destroy(bl);
        return false;
    }

    blacklists_stats_t stats;
    blacklist_get_stats(bl, &stats);
    log_d("reloadBlocklist: %d apps, %d domains, %d IPs", stats.num_apps, stats.num_domains, stats.num_ips);

    pd->firewall.new_bl = bl;
    return true;
}

/* ******************************************************* */

JNIEXPORT jboolean JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_reloadFirewallWhitelist(JNIEnv *env, jclass clazz,
         jobject whitelist) {
    pcapdroid_t *pd = global_pd;
    if(!pd) {
        log_e("NULL pd instance");
        return false;
    }

    if(!pd->vpn_capture) {
        log_e("firewall in root mode not implemented");
        return false;
    }

    if(pd->firewall.new_wl != NULL) {
        log_e("previous firewall whitelist not loaded yet");
        return false;
    }

    if(whitelist == NULL) {
        pd->firewall.wl_enabled = false;
        log_d("firewall whitelist is disabled");
        return true;
    }

    blacklist_t *wl = blacklist_init();
    if(!wl) {
        log_e("blacklist_init failed");
        return false;
    }

    if(blacklist_load_list_descriptor(wl, env, whitelist) < 0) {
        log_f("Could not load firewall whitelist rules. Check the log for more details");
        blacklist_destroy(wl);
        return false;
    }

    blacklists_stats_t stats;
    blacklist_get_stats(wl, &stats);
    log_d("reloadFirewallWhitelist: %d apps, %d domains, %d IPs", stats.num_apps, stats.num_domains, stats.num_ips);

    pd->firewall.new_wl = wl;
    pd->firewall.wl_enabled = true;
    return true;
}

/* ******************************************************* */

JNIEXPORT jboolean JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_reloadMalwareWhitelist(JNIEnv *env, jclass clazz,
        jobject whitelist) {
    pcapdroid_t *pd = global_pd;
    if(!pd) {
        log_e("NULL pd instance");
        return false;
    }

    if(!pd->malware_detection.enabled) {
        log_e("malware detection not enabled");
        return false;
    }

    if(pd->malware_detection.new_wl != NULL) {
        log_e("previous malware whitelist not loaded yet");
        return false;
    }

    blacklist_t *wl = blacklist_init();
    if(!wl) {
        log_e("blacklist_init failed");
        return false;
    }

    if(blacklist_load_list_descriptor(wl, env, whitelist) < 0) {
        log_f("Could not load malware whitelist rules. Check the log for more details");
        blacklist_destroy(wl);
        return false;
    }

    blacklists_stats_t stats;
    blacklist_get_stats(wl, &stats);
    log_d("reloadMalwareWhitelist: %d apps, %d domains, %d IPs", stats.num_apps, stats.num_domains, stats.num_ips);

    pd->malware_detection.new_wl = wl;
    return true;
}

/* ******************************************************* */

JNIEXPORT jboolean JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_reloadDecryptionList(JNIEnv *env,
                                                                       jclass clazz, jobject listobj) {
    pcapdroid_t *pd = global_pd;
    if(!pd) {
        log_e("NULL pd instance");
        return false;
    }

    if(!pd->tls_decryption.enabled) {
        log_e("TLS decryption not enabled");
        return false;
    }

    if(pd->tls_decryption.new_list != NULL) {
        log_e("previous decryption list not loaded yet");
        return false;
    }

    blacklist_t *list = blacklist_init();
    if(!list) {
        log_e("blacklist_init failed");
        return false;
    }

    if(blacklist_load_list_descriptor(list, env, listobj) < 0) {
        log_f("Could not load decryption list. Check the log for more details");
        blacklist_destroy(list);
        return false;
    }

    blacklists_stats_t stats;
    blacklist_get_stats(list, &stats);
    log_d("reloadDecryptionList: %d apps, %d domains, %d IPs", stats.num_apps, stats.num_domains, stats.num_ips);

    pd->tls_decryption.new_list = list;
    return true;
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_nativeSetFirewallEnabled(JNIEnv *env, jclass clazz, jboolean enabled) {
    pcapdroid_t *pd = global_pd;
    if(!pd) {
        log_e("NULL pd instance");
        return;
    }

    pd->firewall.enabled = enabled;
}

/* ******************************************************* */

JNIEXPORT int JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_rootCmd(JNIEnv *env, jclass clazz, jstring prog,
                                                          jstring args) {
    const char *prog_s = (*env)->GetStringUTFChars(env, prog, 0);
    const char *args_s = (*env)->GetStringUTFChars(env, args, 0);
    int rv = run_shell_cmd(prog_s, args_s, true, true);

    (*env)->ReleaseStringUTFChars(env, prog, prog_s);
    (*env)->ReleaseStringUTFChars(env, args, args_s);

    return rv;
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_setPayloadMode(JNIEnv *env, jclass clazz, jint mode) {
    pcapdroid_t *pd = global_pd;
    if(!pd) {
        log_e("NULL pd instance");
        return;
    }

    pd->payload_mode = mode;
}

/* ******************************************************* */

JNIEXPORT jint JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_initLogger(JNIEnv *env, jclass clazz,
                                                             jstring path, jint level) {
    const char *path_s = (*env)->GetStringUTFChars(env, path, 0);
    int rv = pd_init_logger(path_s, level);
    (*env)->ReleaseStringUTFChars(env, path, path_s);
    return rv;
}

/* ******************************************************* */

JNIEXPORT jint JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_writeLog(JNIEnv *env, jclass clazz,
                                                      jint logger, jint lvl, jstring message) {
    const char *message_s = (*env)->GetStringUTFChars(env, message, 0);
    int rv = pd_log_write(logger, lvl, message_s);
    (*env)->ReleaseStringUTFChars(env, message, message_s);
    return rv;
}

/* ******************************************************* */

static bool arraylist_add_string(JNIEnv *env, jmethodID arrayListAdd, jobject arr, const char *s) {
    jobject s_obj = (*env)->NewStringUTF(env, s);
    if(!s_obj || jniCheckException(env))
        return false;

    bool rv = (*env)->CallBooleanMethod(env, arr, arrayListAdd, s_obj);
    (*env)->DeleteLocalRef(env, s_obj);
    return rv;
}

JNIEXPORT jobject JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_getL7Protocols(JNIEnv *env, jclass clazz) {
    jclass arrayListClass = jniFindClass(env, "java/util/ArrayList");
    jmethodID arrayListNew = jniGetMethodID(env, arrayListClass, "<init>", "()V");
    jmethodID arrayListAdd = jniGetMethodID(env, arrayListClass, "add", "(Ljava/lang/Object;)Z");

    struct ndpi_detection_module_struct *ndpi = ndpi_init_detection_module(ndpi_no_prefs);
    if(!ndpi)
        return(NULL);

    jobject plist = (*env)->NewObject(env, arrayListClass, arrayListNew);
    if((plist == NULL) || jniCheckException(env))
        return false;

    bool success = true;
    int num_protos = (int)ndpi_get_ndpi_num_supported_protocols(ndpi);
    ndpi_proto_defaults_t* proto_defaults = ndpi_get_proto_defaults(ndpi);

    ndpi_protocol_bitmask_struct_t unique_protos;
    NDPI_BITMASK_RESET(unique_protos);

    // NOTE: this does not currently exist as a protocol (see pd_get_proto_name)
    if(!arraylist_add_string(env, arrayListAdd, plist, "HTTPS")) {
        success = false;
        goto out;
    }

    for(int i=0; i<num_protos; i++) {
        ndpi_protocol n_proto = {proto_defaults[i].protoId, NDPI_PROTOCOL_UNKNOWN, NDPI_PROTOCOL_CATEGORY_UNSPECIFIED};
        uint16_t proto = pd_ndpi2proto(n_proto);
        //log_d("protos: %d -> %d -> %d", i, proto_defaults[i].protoId, proto);

        if(!NDPI_ISSET(&unique_protos, proto)) {
            NDPI_SET(&unique_protos, proto);
            const char *name = ndpi_get_proto_name(ndpi, proto);
            //log_d("proto: %d %s", proto, name);

            if(!arraylist_add_string(env, arrayListAdd, plist, name)) {
                success = false;
                goto out;
            }
        }
    }

out:
    if(!success) {
        (*env)->DeleteLocalRef(env, plist);
        plist = NULL;
    }
    ndpi_exit_detection_module(ndpi);

    return(plist);
}

/* ******************************************************* */

char* getStringPref(pcapdroid_t *pd, const char *key, char *buf, int bufsize) {
    JNIEnv *env = pd->env;

    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()Ljava/lang/String;");
    jstring obj = (*env)->CallObjectMethod(env, pd->capture_service, midMethod);
    char *rv = NULL;

    if(!jniCheckException(env)) {
        // Null string
        if(obj == NULL)
            return NULL;

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

        if(*value && (inet_aton(value, &addr) == 0))
            log_e("%s() returned invalid IPv4 address: %s", key, value);

        (*env)->ReleaseStringUTFChars(env, obj, value);
    }

    (*env)->DeleteLocalRef(env, obj);

    return(addr.s_addr);
}

/* ******************************************************* */

zdtun_ip_t getIPPref(JNIEnv *env, jobject vpn_inst, const char *key, int *ip_ver) {
    zdtun_ip_t rv = {};

    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()Ljava/lang/String;");
    jstring obj = (*env)->CallObjectMethod(env, vpn_inst, midMethod);

    if(!jniCheckException(env)) {
        const char *value = (*env)->GetStringUTFChars(env, obj, 0);
        log_d("getIPPref(%s) = %s", key, value);

        if(*value) {
            *ip_ver = zdtun_parse_ip(value, &rv);

            if(*ip_ver < 0)
                log_e("%s() returned invalid IP address: %s", key, value);
        }

        (*env)->ReleaseStringUTFChars(env, obj, value);
    }

    (*env)->DeleteLocalRef(env, obj);
    return(rv);
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

// Retrieve a int[] pref.
// If rv is >0, out points to the allocated array. It's up to the caller to free it with pd_free
int getIntArrayPref(JNIEnv *env, jobject vpn_inst, const char *key, int **out) {
    int rv = -1;
    jmethodID midMethod = jniGetMethodID(env, cls.vpn_service, key, "()[I");
    jintArray jarr = (jintArray) (*env)->CallObjectMethod(env, vpn_inst, midMethod);

    if (!jniCheckException(env)) {
        int size = (*env)->GetArrayLength(env, jarr);
        log_d("getIntArrayPref(%s) = #%d", key, size);

        if (size > 0) {
            jint *array = (*env)->GetIntArrayElements(env, jarr, NULL);
            if (array) {
                size_t arr_size = size * sizeof(int);

                *out = (int*) pd_malloc(arr_size);
                if (*out) {
                    // success
                    memcpy(*out, array, arr_size);
                    rv = size;
                }

                (*env)->ReleaseIntArrayElements(env, jarr, array, 0);
            }
        } else
            rv = size;
    }

    (*env)->DeleteLocalRef(env, jarr);
    return rv;
}

/* ******************************************************* */

void getApplicationByUid(pcapdroid_t *pd, jint uid, char *buf, int bufsize) {
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

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_dumpMasterSecret(JNIEnv *env, jclass clazz,
                                                                   jbyteArray secret) {
    jsize sec_len = (*env)->GetArrayLength(env, secret);
    jbyte* sec_data = (*env)->GetByteArrayElements(env, secret, 0);

    if(global_pd && global_pd->pcap_dump.dumper)
        pcap_dump_secret(global_pd->pcap_dump.dumper, sec_data, sec_len);

    (*env)->ReleaseByteArrayElements(env, secret, sec_data, 0);
}

/* ******************************************************* */

JNIEXPORT jboolean JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_hasSeenPcapdroidTrailer(JNIEnv *env, jclass clazz) {
    return has_seen_pcapdroid_trailer;
}

JNIEXPORT jboolean JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_extractKeylogFromPcapng(JNIEnv *env, jclass clazz,
                    jstring pcapng_path, jstring out_path
) {
    const char *pcapng_s = (*env)->GetStringUTFChars(env, pcapng_path, 0);
    const char *out_s = (*env)->GetStringUTFChars(env, out_path, 0);

    bool rv = pcapng_to_keylog(pcapng_s, out_s);

    (*env)->ReleaseStringUTFChars(env, out_path, out_s);
    (*env)->ReleaseStringUTFChars(env, pcapng_path, pcapng_s);
    return rv;
}

#endif // ANDROID