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
 * Copyright 2021 - Emanuele Faranda
 */

#include <sys/un.h>
#include <linux/limits.h>
#include "vpnproxy.h"
#include "pcapd/pcapd.h"
#include "common/utils.h"
#include "third_party/uthash.h"

// Keep in sync with zdtun.c
#define ICMP_TIMEOUT_SEC 5
#define UDP_TIMEOUT_SEC 30
#define TCP_TIMEOUT_SEC 60

/* ******************************************************* */

typedef struct {
    zdtun_5tuple_t tuple;
    conn_data_t *data;

    UT_hash_handle hh;
} pcap_conn_t;

/* ******************************************************* */

static int su_cmd(vpnproxy_data_t *proxy, const char *prog, const char *args) {
    char cmd[1024];
    int rv;
    JNIEnv *env = proxy->env;

    if(snprintf(cmd, sizeof(cmd), "su -c '%s %s'", prog, args) >= sizeof(cmd)) {
        log_e("su cmd buffer too small");
        return -1;
    }

    log_d("su_cmd: %s", cmd);

    rv = system(cmd);

    return rv;
}

/* ******************************************************* */

static void get_libprog_path(vpnproxy_data_t *proxy, const char *prog_name, char *buf, int bufsize) {
    JNIEnv *env = proxy->env;
    jobject prog_str = (*env)->NewStringUTF(env, prog_name);

    buf[0] = '\0';

    if((prog_str == NULL) || jniCheckException(env)) {
        log_e("could not allocate get_libprog_path string");
        return;
    }

    jstring obj = (*env)->CallObjectMethod(env, proxy->vpn_service, mids.getLibprogPath, prog_str);

    if(!jniCheckException(env)) {
        const char *value = (*env)->GetStringUTFChars(env, obj, 0);

        strncpy(buf, value, bufsize);
        buf[bufsize - 1] = '\0';

        (*env)->ReleaseStringUTFChars(env, obj, value);
    }

    (*env)->DeleteLocalRef(env, obj);
}

/* ******************************************************* */

static void kill_pcapd(vpnproxy_data_t *proxy) {
    int pid;
    char pid_s[8];
    FILE *f = fopen(PCAPD_PID, "r");

    if(f == NULL)
        return;

    fgets(pid_s, sizeof(pid_s), f);
    pid = atoi(pid_s);

    if(pid != 0) {
        log_d("Killing old pcapd with pid %d", pid);
        su_cmd(proxy, "kill", pid_s);
    }

    fclose(f);
}

/* ******************************************************* */

static int connectPcapd(vpnproxy_data_t *proxy) {
    int sock;
    int client = -1;
    char workdir[PATH_MAX], pcapd[PATH_MAX];

    getStringPref(proxy, "getPcapdWorkingDir", workdir, PATH_MAX);
    get_libprog_path(proxy, "pcapd", pcapd, sizeof(pcapd));

    if(!pcapd[0])
        return(-1);

    if(chdir(workdir) < 0) {
        log_f("chdir to %s failed [%d]: %s", workdir,
                    errno, strerror(errno));
        return (-1);
    }

    sock = socket(AF_UNIX, SOCK_STREAM, 0);

    if(sock < 0) {
        log_f("AF_UNIX socket creation failed [%d]: %s", errno,
                    strerror(errno));
        return (-1);
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strcpy(addr.sun_path, PCAPD_SOCKET_PATH);

    kill_pcapd(proxy);
    unlink(PCAPD_PID);
    unlink(PCAPD_SOCKET_PATH);

    if(bind(sock, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        log_f("AF_UNIX bind failed [%d]: %s", errno,
                    strerror(errno));
        goto cleanup;
    }

    listen(sock, 1);

    log_d("AF_UNIX socket listening at '%s'", addr.sun_path);

    // Start the daemon
    char args[32];
    snprintf(args, sizeof(args), "-d %d", proxy->app_filter);
    su_cmd(proxy, pcapd, args);

    // Wait for pcapd to start
    struct timeval timeout = {.tv_sec = 1, .tv_usec = 0};
    fd_set selfds = {0};

    FD_SET(sock, &selfds);
    select(sock + 1, &selfds, NULL, NULL, &timeout);

    if(!FD_ISSET(sock, &selfds)) {
        log_f("pcapd daemon did not spawn");
        goto cleanup;
    }

    if((client = accept(sock, NULL, NULL)) < 0) {
        log_f("AF_UNIX accept failed [%d]: %s", errno,
                    strerror(errno));
        goto cleanup;
    }

    log_d("Connected to pcapd");

    unlink(PCAPD_SOCKET_PATH);

    cleanup:
    close(sock);

    return client;
}

/* ******************************************************* */

static void handle_packet(vpnproxy_data_t *proxy, pcap_conn_t **connections, pcapd_hdr_t *hdr, const char *buffer) {
    zdtun_pkt_t pkt;
    pcap_conn_t *conn = NULL;
    uint8_t from_tun = (hdr->flags & PCAPD_FLAG_TX); // NOTE: the direction uses an heuristic so it may be wrong

    // NOTE: only IP packets supported
    if(zdtun_parse_pkt(buffer, hdr->len, &pkt) != 0) {
        log_d("zdtun_parse_pkt failed");
        return;
    }

    if(!from_tun) {
        // Packet from the internet, swap src and dst
        tupleSwapPeers(&pkt.tuple);
    }

    HASH_FIND(hh, *connections, &pkt.tuple, sizeof(zdtun_5tuple_t), conn);

    if(!conn) {
        // from_tun may be wrong, search in the other direction
        from_tun = !from_tun;
        tupleSwapPeers(&pkt.tuple);

        HASH_FIND(hh, *connections, &pkt.tuple, sizeof(zdtun_5tuple_t), conn);

        if(!conn) {
            // assume from_tun was correct
            from_tun = !from_tun;
            tupleSwapPeers(&pkt.tuple);

            conn_data_t *data = new_connection(proxy, &pkt.tuple, hdr->uid);

            if (!data)
                return;

            conn = malloc(sizeof(pcap_conn_t));

            if (!conn) {
                log_e("malloc(pcap_conn_t) failed with code %d/%s",
                      errno, strerror(errno));
                return;
            }

            conn->tuple = pkt.tuple;
            conn->data = data;

            // TODO read from linux?
            data->status = CONN_STATUS_CONNECTED;

            HASH_ADD(hh, *connections, tuple, sizeof(zdtun_5tuple_t), conn);

            data->incr_id = proxy->incr_id++;
            conns_add(&proxy->new_conns, &pkt.tuple, data);

            switch (conn->tuple.ipproto) {
                case IPPROTO_TCP:
                    proxy->stats.num_tcp_conn++;
                    proxy->stats.num_tcp_opened++;
                    break;
                case IPPROTO_UDP:
                    proxy->stats.num_udp_conn++;
                    proxy->stats.num_udp_opened++;
                    break;
                case IPPROTO_ICMP:
                    proxy->stats.num_icmp_conn++;
                    proxy->stats.num_icmp_opened++;
                    break;
            }
        }
    }

    account_packet(proxy, buffer, hdr->len, from_tun, &conn->tuple, conn->data);
}

/* ******************************************************* */

static void purge_expired_connections(vpnproxy_data_t *proxy, pcap_conn_t **connections, uint8_t purge_all) {
    pcap_conn_t *conn, *tmp;
    time_t now = proxy->now_ms / 1000;

    HASH_ITER(hh, *connections, conn, tmp) {
        time_t timeout = 0;

        // TODO: sync with linux?
        switch(conn->tuple.ipproto) {
            case IPPROTO_TCP:
                timeout = TCP_TIMEOUT_SEC;
                break;
            case IPPROTO_UDP:
                timeout = UDP_TIMEOUT_SEC;
                break;
            case IPPROTO_ICMP:
                timeout = ICMP_TIMEOUT_SEC;
                break;
        }

        if(purge_all || (conn->data->status >= CONN_STATUS_CLOSED) || (now >= (timeout + conn->data->last_seen))) {
            log_d("IDLE (type=%d)", conn->tuple.ipproto);

            // Will free the data in sendConnectionsDump
            if(!conn->data->pending_notification)
                conns_add(&proxy->conns_updates, &conn->tuple, conn->data);

            conn->data->status = CONN_STATUS_CLOSED;

            switch (conn->tuple.ipproto) {
                case IPPROTO_TCP:
                    proxy->stats.num_tcp_conn--;
                    break;
                case IPPROTO_UDP:
                    proxy->stats.num_udp_conn--;
                    break;
                case IPPROTO_ICMP:
                    proxy->stats.num_icmp_conn--;
                    break;
            }

            HASH_DELETE(hh, *connections, conn);
            free(conn);
        }
    }
}

/* ******************************************************* */

int run_root(vpnproxy_data_t *proxy) {
    int sock = connectPcapd(proxy);
    int rv = -1;
    char buffer[16384];
    pcap_conn_t *connections = NULL;
    u_int64_t next_purge_ms;

    if(sock < 0)
        return(-1);

    refreshTime(proxy);
    next_purge_ms = proxy->now_ms + PERIODIC_PURGE_TIMEOUT_MS;

    log_d("Starting packet loop");

    while(running) {
        pcapd_hdr_t hdr;
        fd_set fdset = {0};

        FD_SET(sock, &fdset);

        struct timeval timeout = {.tv_sec = 0, .tv_usec = 500*1000}; // wake every 500 ms

        if(select(sock + 1, &fdset, NULL, NULL, &timeout) < 0) {
            log_e("select failed[%d]: %s", errno, strerror(errno));
            goto cleanup;
        }

        refreshTime(proxy);

        if(!running)
            break;

        if(!FD_ISSET(sock, &fdset))
            goto housekeeping;

        if(xread(sock, &hdr, sizeof(hdr)) < 0) {
            log_e("read hdr from pcapd failed[%d]: %s", errno, strerror(errno));
            goto cleanup;
        }
        if(hdr.len > sizeof(buffer)) {
            log_e("packet too big (%d B)", hdr.len);
            goto cleanup;
        }
        if(xread(sock, buffer, hdr.len) < 0) {
            log_e("read %d B packet from pcapd failed[%d]: %s", hdr.len, errno, strerror(errno));
            goto cleanup;
        }

        handle_packet(proxy, &connections, &hdr, buffer);

    housekeeping:
        run_housekeeping(proxy);

        if(proxy->now_ms >= next_purge_ms) {
            purge_expired_connections(proxy, &connections, 0);
            next_purge_ms = proxy->now_ms + PERIODIC_PURGE_TIMEOUT_MS;
        }
    }

    rv = 0;

cleanup:
    purge_expired_connections(proxy, &connections, 1 /* purge_all */);

    close(sock);
    return rv;
}