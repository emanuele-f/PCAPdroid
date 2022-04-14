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
 * Copyright 2022 - Emanuele Faranda
 */

#if ANDROID

#include "pcapdroid.h"
#include "pcap_utils.h"
#include "common/utils.h"

// This files contains functions to make the capture core communicate
// with the Android system.
// Exported functions are defined in pcapdroid.h

static pcapdroid_t *global_pd = NULL;

jni_classes_t cls;
jni_methods_t mids;
jni_fields_t fields;
jni_enum_t enums;

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

static void sendPcapDump(pcapdroid_t *pd) {
    JNIEnv *env = pd->env;

    //log_d("Exporting a %d B PCAP buffer", pd->pcap_dump.buffer_idx);

    jbyteArray barray = (*env)->NewByteArray(env, pd->pcap_dump.buffer_idx);
    if(jniCheckException(env))
        return;

    (*env)->SetByteArrayRegion(env, barray, 0, pd->pcap_dump.buffer_idx, pd->pcap_dump.buffer);
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
        bool blocked = data->to_block && !pd->root_capture; // currently can only block connections in non-root mode

        (*env)->CallVoidMethod(env, update, mids.connUpdateSetStats, data->last_seen,
                               data->payload_length, data->sent_bytes, data->rcvd_bytes, data->sent_pkts, data->rcvd_pkts, data->blocked_pkts,
                               (data->tcp_flags[0] << 8) | data->tcp_flags[1],
                               (blocked << 10) | (data->blacklisted_domain << 9) |
                                    (data->blacklisted_ip << 8) | (data->status & 0xFF));
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
    if(data->update_type & CONN_UPDATE_PAYLOAD)
        (*env)->CallVoidMethod(env, update, mids.connUpdateSetPayload, data->payload_chunks,
                               data->payload_truncated);

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
    u_int local_port = (!pd->root_capture ? data->vpn.local_port : 0);
    bool mitm_decrypt = (pd->tls_decryption_enabled && data->proxied);
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
}

/* ******************************************************* */

// Load information about the blacklists to use (pd->malware_detection.bls_info)
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

    (*pd->env)->CallVoidMethod(pd->env, pd->capture_service, mids.notifyBlacklistsLoaded, status_obj);
}

/* ******************************************************* */

static bool dumpPayloadChunk(struct pcapdroid *pd, const pkt_context_t *pctx, int dump_size) {
    JNIEnv *env = pd->env;
    bool rv = false;

    if(pctx->data->payload_chunks == NULL) {
        pctx->data->payload_chunks = (*pd->env)->NewObject(pd->env, cls.arraylist, mids.arraylistNew);
        if((pctx->data->payload_chunks == NULL) || jniCheckException(env))
            return false;
    }

    jbyteArray barray = (*env)->NewByteArray(env, dump_size);
    if(jniCheckException(env))
        return false;

    jobject chunk_type = (pctx->data->l7proto == NDPI_PROTOCOL_HTTP) ? enums.chunktype_http : enums.chunktype_raw;

    jobject chunk = (*pd->env)->NewObject(pd->env, cls.payload_chunk, mids.payloadChunkInit, barray, chunk_type, pctx->is_tx, pctx->ms);
    if(chunk && !jniCheckException(env)) {
        (*env)->SetByteArrayRegion(env, barray, 0, dump_size, (jbyte*)pctx->pkt->l7);
        rv = (*pd->env)->CallBooleanMethod(pd->env, pctx->data->payload_chunks, mids.arraylistAdd, chunk);
    }

    //log_d("Dump chunk [size=%d]: %d", rv, dump_size);

    (*env)->DeleteLocalRef(env, barray);
    (*env)->DeleteLocalRef(env, chunk);
    return rv;
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

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_runPacketLoop(JNIEnv *env, jclass type, jint tunfd,
                                                              jobject vpn, jint sdk) {

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
    cls.arraylist = jniFindClass(env, "java/util/ArrayList");
    cls.payload_chunk = jniFindClass(env, "com/emanuelef/remote_capture/model/PayloadChunk");

    /* Methods */
    mids.reportError = jniGetMethodID(env, vpn_class, "reportError", "(Ljava/lang/String;)V");
    mids.getApplicationByUid = jniGetMethodID(env, vpn_class, "getApplicationByUid", "(I)Ljava/lang/String;"),
            mids.protect = jniGetMethodID(env, vpn_class, "protect", "(I)Z");
    mids.dumpPcapData = jniGetMethodID(env, vpn_class, "dumpPcapData", "([B)V");
    mids.stopPcapDump = jniGetMethodID(env, vpn_class, "stopPcapDump", "()V");
    mids.updateConnections = jniGetMethodID(env, vpn_class, "updateConnections", "([Lcom/emanuelef/remote_capture/model/ConnectionDescriptor;[Lcom/emanuelef/remote_capture/model/ConnectionUpdate;)V");
    mids.sendStatsDump = jniGetMethodID(env, vpn_class, "sendStatsDump", "(Lcom/emanuelef/remote_capture/model/VPNStats;)V");
    mids.sendServiceStatus = jniGetMethodID(env, vpn_class, "sendServiceStatus", "(Ljava/lang/String;)V");
    mids.getLibprogPath = jniGetMethodID(env, vpn_class, "getLibprogPath", "(Ljava/lang/String;)Ljava/lang/String;");
    mids.notifyBlacklistsLoaded = jniGetMethodID(env, vpn_class, "notifyBlacklistsLoaded", "([Lcom/emanuelef/remote_capture/model/Blacklists$NativeBlacklistStatus;)V");
    mids.getBlacklistsInfo = jniGetMethodID(env, vpn_class, "getBlacklistsInfo", "()[Lcom/emanuelef/remote_capture/model/BlacklistDescriptor;");
    mids.connInit = jniGetMethodID(env, cls.conn, "<init>", "(IIILjava/lang/String;Ljava/lang/String;IIIIIZJ)V");
    mids.connProcessUpdate = jniGetMethodID(env, cls.conn, "processUpdate", "(Lcom/emanuelef/remote_capture/model/ConnectionUpdate;)V");
    mids.connUpdateInit = jniGetMethodID(env, cls.conn_update, "<init>", "(I)V");
    mids.connUpdateSetStats = jniGetMethodID(env, cls.conn_update, "setStats", "(JJJJIIIII)V");
    mids.connUpdateSetInfo = jniGetMethodID(env, cls.conn_update, "setInfo", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
    mids.connUpdateSetPayload = jniGetMethodID(env, cls.conn_update, "setPayload", "(Ljava/util/ArrayList;Z)V");
    mids.statsInit = jniGetMethodID(env, cls.stats, "<init>", "()V");
    mids.statsSetData = jniGetMethodID(env, cls.stats, "setData", "(Ljava/lang/String;JJIIIIIIIII)V");
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
            },
            .app_filter = getIntPref(env, vpn, "getAppFilterUid"),
            .root_capture = (bool) getIntPref(env, vpn, "isRootCapture"),
            .tls_decryption_enabled = (bool) getIntPref(env, vpn, "isTlsDecryptionEnabled"),
            .payload_mode = (payload_mode_t) getIntPref(env, vpn, "getPayloadMode"),
            .pcap_dump = {
                    .enabled = (bool) getIntPref(env, vpn, "pcapDumpEnabled"),
                    .snaplen = getIntPref(env, vpn, "getSnaplen"),
                    .max_pkts_per_flow = getIntPref(env, vpn, "getMaxPktsPerFlow"),
                    .max_dump_size = getIntPref(env, vpn, "getMaxDumpSize"),
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

    if(pd.socks5.enabled)
        getSocks5ProxyAuth(&pd);

    // Enable or disable the PCAPdroid trailer
    pcap_set_pcapdroid_trailer((bool)getIntPref(env, vpn, "addPcapdroidTrailer"));

    if(!pd.root_capture)
        pd.vpn.tunfd = tunfd;

    getStringPref(&pd, "getWorkingDir", pd.cachedir, sizeof(pd.cachedir));
    strcat(pd.cachedir, "/");
    pd.cachedir_len = strlen(pd.cachedir);

    getStringPref(&pd, "getPersistentDir", pd.filesdir, sizeof(pd.filesdir));
    strcat(pd.filesdir, "/");
    pd.filesdir_len = strlen(pd.filesdir);

    global_pd = &pd;
    logcallback = log_callback;
    signal(SIGPIPE, SIG_IGN);

    // Run the capture
    pd_run(&pd);

    global_pd = NULL;
    logcallback = NULL;

#ifdef PCAPDROID_TRACK_ALLOCS
    log_i(get_allocs_summary());
#endif
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_stopPacketLoop(JNIEnv *env, jclass type) {
    /* NOTE: the select on the packets loop uses a timeout to wake up periodically */
    log_i( "stopPacketLoop called");
    running = false;
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
}

/* ******************************************************* */

JNIEXPORT jbyteArray JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_getPcapHeader(JNIEnv *env, jclass clazz) {
    struct pcap_hdr_s pcap_hdr;

    int snaplen = global_pd ? global_pd->pcap_dump.snaplen : 65535;
    pcap_build_hdr(snaplen, &pcap_hdr);

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

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_reloadBlacklists(JNIEnv *env, jclass clazz) {
    reload_blacklists_now = true;
}

/* ******************************************************* */

JNIEXPORT jint JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_getNumCheckedConnections(JNIEnv *env, jclass clazz) {
    return bl_num_checked_connections;
}

/* ******************************************************* */

JNIEXPORT void JNICALL
Java_com_emanuelef_remote_1capture_CaptureService_setPrivateDnsBlocked(JNIEnv *env, jclass clazz, jboolean to_block) {
    block_private_dns = to_block;
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

    if(pd->root_capture) {
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
        log_e("previous whitelist not loaded yet");
        return false;
    }

    blacklist_t *wl = blacklist_init();
    if(!wl) {
        log_e("blacklist_init failed");
        return false;
    }

    if(blacklist_load_list_descriptor(wl, env, whitelist) < 0) {
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

#endif // ANDROID
