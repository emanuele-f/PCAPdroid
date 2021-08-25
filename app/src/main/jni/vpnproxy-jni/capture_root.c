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

#define ICMP_TIMEOUT_SEC 5
#define UDP_TIMEOUT_SEC 30
#define TCP_CLOSED_TIMEOUT_SEC 60   // some servers keep sending FIN+ACK after close
#define TCP_TIMEOUT_SEC 300         // needs to be large as TCP connections may stay active for a long time

/* ******************************************************* */

typedef struct {
    zdtun_5tuple_t tuple;
    conn_data_t *data;

    UT_hash_handle hh;
} pcap_conn_t;

/* ******************************************************* */

static int su_cmd(const char *prog, const char *args) {
    FILE *fp;

    if((fp = popen("su", "w")) == NULL) {
      log_e("popen(\"su\") failed[%d]: %s", errno, strerror(errno));
      pclose(fp);
      return -1;
    }

    log_d("su_cmd: %s %s", prog, args);
    fprintf(fp, "%s", prog);
    fprintf(fp, " %s\n", args);

    return pclose(fp);
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
        su_cmd("kill", pid_s);
    }

    fclose(f);
}

/* ******************************************************* */

static int connectPcapd(vpnproxy_data_t *proxy) {
    int sock;
    int client = -1;
    char bpf[256];
    char workdir[PATH_MAX], pcapd[PATH_MAX];
    char capture_interface[16];

    getStringPref(proxy, "getPcapDumperBpf", bpf, sizeof(bpf));
    getStringPref(proxy, "getPcapdWorkingDir", workdir, PATH_MAX);
    getStringPref(proxy, "getCaptureInterface", capture_interface, sizeof(capture_interface));
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

    if(bpf[0])
        log_d("Using dumper BPF \"%s\"", bpf);

    // Start the daemon
    char args[256];
    snprintf(args, sizeof(args), "-l pcapd.log -i %s -d -u %d -b \"%s\"", capture_interface, proxy->app_filter, bpf);
    su_cmd(pcapd, args);

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

    cleanup:
    unlink(PCAPD_SOCKET_PATH);
    close(sock);

    return client;
}

/* ******************************************************* */

// Determines when a connection gets closed
static void update_connection_status(pcap_conn_t *conn, zdtun_pkt_t *pkt, uint8_t dir) {
  if((conn->data->status >= CONN_STATUS_CLOSED) || (pkt->flags & ZDTUN_PKT_IS_FRAGMENT))
      return;

  if(conn->tuple.ipproto == IPPROTO_TCP) {
      struct tcphdr *tcp = pkt->tcp;

      conn->data->tcp_flags[dir] |= tcp->th_flags;
      uint8_t seen_flags = conn->data->tcp_flags[0] & conn->data->tcp_flags[1];

      if(tcp->th_flags & TH_RST)
          conn->data->status = CONN_STATUS_RESET;
      else if(seen_flags & TH_FIN) {
          // closed when both the peers have sent FIN and the last FIN was acknowledged
          if(!conn->data->last_ack)
              conn->data->last_ack = true; // wait for the last ACK
          else if(tcp->th_flags & TH_ACK)
              conn->data->status = CONN_STATUS_CLOSED;
      } else if(conn->data->status < CONN_STATUS_CONNECTED) {
          const uint8_t syn_ack_flags = TH_SYN | TH_ACK;

          // the 3-way-handshake is complete when both the peers have sent the SYN+ACK flags
          if((pkt->l7_len > 0) ||
                ((seen_flags & syn_ack_flags) == syn_ack_flags))
              conn->data->status = CONN_STATUS_CONNECTED;
          else
              conn->data->status = CONN_STATUS_CONNECTING;
      }
  } else {
      if(conn->data->status < CONN_STATUS_CONNECTED)
        conn->data->status = CONN_STATUS_CONNECTED;

      if((conn->tuple.ipproto == IPPROTO_UDP) &&
            pkt->l7_len >= sizeof(struct dns_packet) &&
            (conn->tuple.dst_port == ntohs(53))) {
          const dns_packet_t *dns = (dns_packet_t *)pkt->l7;

          if((dns->flags & DNS_FLAGS_MASK) == DNS_TYPE_REQUEST)
              conn->data->pending_dns_queries++;
          else if((dns->flags & DNS_FLAGS_MASK) == DNS_TYPE_RESPONSE) {
              conn->data->pending_dns_queries--;

              // Close the connection as soon as all the responses arrive
              if(conn->data->pending_dns_queries == 0)
                  conn->data->status = CONN_STATUS_CLOSED;
          }
      }
  }
}

/* ******************************************************* */

static void handle_packet(vpnproxy_data_t *proxy, pcap_conn_t **connections, pcapd_hdr_t *hdr, const char *buffer) {
    zdtun_pkt_t pkt;
    pcap_conn_t *conn = NULL;
    uint8_t from_tun = (hdr->flags & PCAPD_FLAG_TX); // NOTE: the direction uses an heuristic so it may be wrong

    if(zdtun_parse_pkt(proxy->tun, buffer, hdr->len, &pkt) != 0) {
        log_d("zdtun_parse_pkt failed");
        return;
    }

    if((pkt.flags & ZDTUN_PKT_IS_FRAGMENT) &&
            (pkt.tuple.src_port == 0) && (pkt.tuple.dst_port == 0)) {
        // This fragment cannot be mapped to the original src/dst ports. This may happen if the first
        // IP fragment is lost or was not captured (e.g. for packets matching the BPF of getPcapDumperBpf).
        // In such a case, we can only ignore the packet as we cannot determine the connection it belongs to.

        //log_d("unmatched IP fragment (ID = 0x%04x)", pkt.ip4->id);
        proxy->num_discarded_fragments++;
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
            if((pkt.flags & ZDTUN_PKT_IS_FRAGMENT) && !(pkt.flags & ZDTUN_PKT_IS_FIRST_FRAGMENT)) {
                log_d("ignoring fragment as it cannot start a connection");
                proxy->num_discarded_fragments++;
                return;
            }

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

            HASH_ADD(hh, *connections, tuple, sizeof(zdtun_5tuple_t), conn);

            data->incr_id = proxy->incr_id++;
            notify_connection(&proxy->new_conns, &pkt.tuple, data);

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

    proxy->last_pkt_ts = hdr->ts;
    conn->data->last_update_ms = proxy->now_ms;

    uint64_t pkt_ms = (uint64_t)hdr->ts.tv_sec * 1000 + hdr->ts.tv_usec / 1000;
    account_packet(proxy, &pkt, from_tun, &conn->tuple, conn->data, pkt_ms);

    update_connection_status(conn, &pkt, !from_tun);
}

/* ******************************************************* */

static void purge_expired_connections(vpnproxy_data_t *proxy, pcap_conn_t **connections, uint8_t purge_all) {
    pcap_conn_t *conn, *tmp;

    HASH_ITER(hh, *connections, conn, tmp) {
        uint64_t timeout = 0;

        switch(conn->tuple.ipproto) {
            case IPPROTO_TCP:
                timeout = (conn->data->status >= CONN_STATUS_CLOSED) ? (TCP_CLOSED_TIMEOUT_SEC * 1000) : (TCP_TIMEOUT_SEC * 1000);
                break;
            case IPPROTO_UDP:
                timeout = UDP_TIMEOUT_SEC * 1000;
                break;
            case IPPROTO_ICMP:
                timeout = ICMP_TIMEOUT_SEC + 1000;
                break;
        }

        if(purge_all || (proxy->now_ms >= (conn->data->last_update_ms + timeout))) {
            //log_d("IDLE (type=%d)", conn->tuple.ipproto);

            // The connection data will be purged
            conn->data->to_purge = true;

            if(conn->data->status < CONN_STATUS_CLOSED) {
                conn->data->status = CONN_STATUS_CLOSED;
                conn->data->update_type |= CONN_UPDATE_STATS;
            }

            if(conn->data->update_type != 0) {
                // Will free the data in sendConnectionsDump
                notify_connection(&proxy->conns_updates, &conn->tuple, conn->data);
            } else
                conn_free_data(conn->data);

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
    int sock = -1;
    int rv = -1;
    char buffer[65535];
    pcap_conn_t *connections = NULL;
    u_int64_t next_purge_ms;
    zdtun_callbacks_t callbacks = {.send_client = (void*)1};

    if((proxy->tun = zdtun_init(&callbacks, NULL)) == NULL)
        return(-1);

    if((sock = connectPcapd(proxy)) < 0) {
        rv = -1;
        goto cleanup;
    }

    refresh_time(proxy);
    next_purge_ms = proxy->now_ms + PERIODIC_PURGE_TIMEOUT_MS;

    log_d("Starting packet loop");

    while(running) {
        pcapd_hdr_t hdr;
        fd_set fdset = {0};

        FD_SET(sock, &fdset);

        struct timeval timeout = {.tv_sec = 0, .tv_usec = SELECT_TIMEOUT_MS * 1000};

        if(select(sock + 1, &fdset, NULL, NULL, &timeout) < 0) {
            log_e("select failed[%d]: %s", errno, strerror(errno));
            goto cleanup;
        }

        refresh_time(proxy);

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

        proxy->num_dropped_pkts = hdr.pkt_drops;
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

    if(proxy->tun) zdtun_finalize(proxy->tun);
    if(sock > 0) close(sock);

    return rv;
}
