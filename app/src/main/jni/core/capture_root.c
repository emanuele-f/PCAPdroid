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
#include "pcapdroid.h"
#include "pcapd/pcapd.h"
#include "common/utils.h"
#include "third_party/uthash.h"

#ifdef FUZZING
extern int openPcap(pcapdroid_t *pd);
extern int nextPacket(pcapdroid_t *pd, pcapd_hdr_t *hdr, char *buf, size_t bufsize);
#endif

#define ICMP_TIMEOUT_SEC 5
#define UDP_TIMEOUT_SEC 30
#define TCP_CLOSED_TIMEOUT_SEC 60   // some servers keep sending FIN+ACK after close
#define TCP_TIMEOUT_SEC 300         // needs to be large as TCP connections may stay active for a long time

/* ******************************************************* */

typedef struct pcap_conn_t {
    zdtun_5tuple_t tuple;
    pd_conn_t *data;

    UT_hash_handle hh;
} pcap_conn_t;

/* ******************************************************* */

static void kill_pcapd(pcapdroid_t *nc) {
    int pid;
    char pid_s[8];
    FILE *f = fopen(PCAPD_PID, "r");

    if(f == NULL)
        return;

    fgets(pid_s, sizeof(pid_s), f);
    pid = atoi(pid_s);

    if(pid != 0) {
        log_i("Killing old pcapd with pid %d", pid);
        run_shell_cmd("kill", pid_s, true, false);
    }

    fclose(f);
}

/* ******************************************************* */

static bool valid_ifname(const char *name) {
    if(*name == '\0')
        return false;

    if(strlen(name) >= 16)
        return false;

    while(*name) {
        if((*name != '.') && (*name != '_') && (*name != '@') && !isalnum(*name))
            return false;
        name++;
    }

    return true;
}

/* ******************************************************* */

static bool valid_bpf(const char *bpf) {
    static const char disallowed_chars[] = "$'\"`\n\r";

    while(*bpf) {
        if(strchr(disallowed_chars, *bpf))
            return false;
        bpf++;
    }

    return true;
}

/* ******************************************************* */

static int connectPcapd(pcapdroid_t *pd) {
    int sock;
    int client = -1;
    char pcapd[PATH_MAX];
    char *bpf = pd->root.bpf ? pd->root.bpf : "";

    if(pd->cb.get_libprog_path)
        pd->cb.get_libprog_path(pd, "pcapd", pcapd, sizeof(pcapd));

    if(!pcapd[0])
        return(-1);

    if(chdir(get_cache_dir(pd)) < 0) {
        log_f("chdir to %s failed [%d]: %s", get_cache_dir(pd),
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

    kill_pcapd(pd);
    unlink(PCAPD_PID);
    unlink(PCAPD_SOCKET_PATH);

    if(bind(sock, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        log_f("AF_UNIX bind failed [%d]: %s", errno,
                    strerror(errno));
        goto cleanup;
    }

    listen(sock, 1);

    log_d("AF_UNIX socket listening at '%s'", addr.sun_path);

    // Validate parameters to prevent command injection
    if(bpf[0]) {
        if(!valid_bpf(bpf)) {
            log_e("BPF contains suspicious characters");
            goto cleanup;
        }
        log_d("BPF filter is in use");
    }

#ifdef ANDROID
    // File paths are currently disallowed
    // NOTE: interface validation is currently skipped when running local tests (files are used)
    if(!valid_ifname(pd->root.capture_interface)) {
        log_e("Invalid capture_interface");
        goto cleanup;
    }
#endif

    // Start the daemon
    char args[256];
    snprintf(args, sizeof(args), "-l pcapd.log -i '%s' -d -u %d -t -b '%s'", pd->root.capture_interface, pd->tls_decryption.enabled ? -1 : pd->app_filter, bpf);
    if(run_shell_cmd(pcapd, args, pd->root.as_root, true) != 0)
        goto cleanup;

    // Wait for pcapd to start
    struct timeval timeout = {.tv_sec = 3, .tv_usec = 0};
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

    log_i("Connected to pcapd");

cleanup:
    unlink(PCAPD_SOCKET_PATH);
    close(sock);

    return client;
}

/* ******************************************************* */

static char* get_mitm_redirection_args(pcapdroid_t *pd, char *buf, bool add) {
    int off = sprintf(buf, "-t nat -%c OUTPUT -p tcp -m owner ", add ? 'I' : 'D');
    if(pd->app_filter >= 0)
        off += sprintf(buf + off, "--uid-owner %d", pd->app_filter);
    else
        off += sprintf(buf + off, "! --uid-owner %d", pd->mitm_addon_uid);
    sprintf(buf + off, " -j REDIRECT --to 7780");

    return buf;
}

/* ******************************************************* */

static void remove_connection(pcapdroid_t *pd, pcap_conn_t *conn) {
    switch (conn->tuple.ipproto) {
        case IPPROTO_TCP:
            pd->stats.num_tcp_conn--;
            break;
        case IPPROTO_UDP:
            pd->stats.num_udp_conn--;
            break;
        case IPPROTO_ICMP:
            pd->stats.num_icmp_conn--;
            break;
    }

    HASH_DEL(pd->root.connections, conn);
    pd_free(conn);
}

/* ******************************************************* */

// Determines when a connection gets closed
static void update_connection_status(pcapdroid_t *nc, pcap_conn_t *conn, zdtun_pkt_t *pkt, uint8_t dir) {
  // NOTE: pcap_conn_t needed below in remove_connection
  if((conn->data->status >= CONN_STATUS_CLOSED) || (pkt->flags & ZDTUN_PKT_IS_FRAGMENT))
      return;

  zdtun_5tuple_t *tuple = &conn->tuple;
  pd_conn_t *data = conn->data;

  if(tuple->ipproto == IPPROTO_TCP) {
      struct tcphdr *tcp = pkt->tcp;

      data->tcp_flags[dir] |= tcp->th_flags;
      uint8_t seen_flags = data->tcp_flags[0] & data->tcp_flags[1];

      if(tcp->th_flags & TH_RST)
          data->status = CONN_STATUS_RESET;
      else if(seen_flags & TH_FIN) {
          // closed when both the peers have sent FIN and the last FIN was acknowledged
          if(!data->last_ack)
              data->last_ack = true; // wait for the last ACK
          else if(tcp->th_flags & TH_ACK)
              data->status = CONN_STATUS_CLOSED;
      } else if(data->status < CONN_STATUS_CONNECTED) {
          const uint8_t syn_ack_flags = TH_SYN | TH_ACK;

          // the 3-way-handshake is complete when both the peers have sent the SYN+ACK flags
          if((pkt->l7_len > 0) ||
                ((seen_flags & syn_ack_flags) == syn_ack_flags))
              data->status = CONN_STATUS_CONNECTED;
          else
              data->status = CONN_STATUS_CONNECTING;
      }
  } else {
      if(data->status < CONN_STATUS_CONNECTED)
        data->status = CONN_STATUS_CONNECTED;

      if((tuple->ipproto == IPPROTO_UDP) &&
            pkt->l7_len >= sizeof(dns_packet_t) &&
            (tuple->dst_port == ntohs(53))) {
          const dns_packet_t *dns = (dns_packet_t *)pkt->l7;

          if((dns->flags & DNS_FLAGS_MASK) == DNS_TYPE_REQUEST)
              data->pending_dns_queries++;
          else if((dns->flags & DNS_FLAGS_MASK) == DNS_TYPE_RESPONSE) {
              data->pending_dns_queries--;

              // Close the connection as soon as all the responses arrive
              if(data->pending_dns_queries == 0) {
                  data->status = CONN_STATUS_CLOSED;

                  // Remove the connection from the hash table to ensure that if the DNS connection is
                  // reused for a new query, it will generated a new connection, to properly
                  // extract and handle the new DNS query. This also happens for AAAA + A queries.
                  data->to_purge = true;
                  remove_connection(nc, conn);
              }
          }
      }
  }

  // no need to call pd_notify_connection_update, it will be called as part of pd_account_stats
}

/* ******************************************************* */

static int get_ip_offset(int linktype) {
    switch(linktype) {
        case PCAPD_DLT_RAW:
            return 0;
        case PCAPD_DLT_ETHERNET:
            return 14;
        case PCAPD_DLT_LINUX_SLL:
            return 16;
        case PCAPD_DLT_LINUX_SLL2:
            return 20;
        default:
            return -1;
    }
}

/* ******************************************************* */

/* Returns true if packet is valid. If false is returned, the pkt must still be dumped, so a call to
 * pd_dump_packet is required. */
static bool handle_packet(pcapdroid_t *pd, pcapd_hdr_t *hdr, const char *buffer, int ipoffset) {
    zdtun_pkt_t pkt;
    pcap_conn_t *conn = NULL;
    uint8_t is_tx = (hdr->flags & PCAPD_FLAG_TX); // NOTE: the direction uses an heuristic so it may be wrong

    if(zdtun_parse_pkt(pd->zdt, buffer + ipoffset, hdr->len - ipoffset, &pkt) != 0) {
        log_d("zdtun_parse_pkt failed");
        return false;
    }

    if((pkt.flags & ZDTUN_PKT_IS_FRAGMENT) &&
            (pkt.tuple.src_port == 0) && (pkt.tuple.dst_port == 0)) {
        // This fragment cannot be mapped to the original src/dst ports. This may happen if the first
        // IP fragment is lost or was not captured (e.g. for packets matching the BPF of getPcapDumperBpf).
        // In such a case, we can only ignore the packet as we cannot determine the connection it belongs to.

        //log_d("unmatched IP fragment (ID = 0x%04x)", pkt.ip4->id);
        pd->num_discarded_fragments++;
        return false;
    }

    if(!is_tx) {
        // Packet from the internet, swap src and dst
        tupleSwapPeers(&pkt.tuple);
    }

    HASH_FIND(hh, pd->root.connections, &pkt.tuple, sizeof(zdtun_5tuple_t), conn);
    if(!conn) {
        // is_tx may be wrong, search in the other direction
        is_tx = !is_tx;
        tupleSwapPeers(&pkt.tuple);

        HASH_FIND(hh, pd->root.connections, &pkt.tuple, sizeof(zdtun_5tuple_t), conn);

        if(!conn) {
            if((pkt.flags & ZDTUN_PKT_IS_FRAGMENT) && !(pkt.flags & ZDTUN_PKT_IS_FIRST_FRAGMENT)) {
                log_d("ignoring fragment as it cannot start a connection");
                pd->num_discarded_fragments++;
                return false;
            }

            // assume is_tx was correct
            is_tx = !is_tx;
            tupleSwapPeers(&pkt.tuple);

            conn = pd_malloc(sizeof(pcap_conn_t));
            if(!conn) {
                log_e("malloc(pcap_conn_t) failed with code %d/%s",
                      errno, strerror(errno));
                return false;
            }

            pd_conn_t *data = pd_new_connection(pd, &pkt.tuple, hdr->uid);
            if(!data) {
                pd_free(conn);
                return false;
            }

            if(hdr->linktype == PCAPD_DLT_LINUX_SLL2)
                data->root.ifidx = ntohl(*(uint32_t*)(buffer + 4)); // sll2_header->sll2_if_index

            conn->tuple = pkt.tuple;
            conn->data = data;
            HASH_ADD(hh, pd->root.connections, tuple, sizeof(zdtun_5tuple_t), conn);

            switch (conn->tuple.ipproto) {
                case IPPROTO_TCP:
                    pd->stats.num_tcp_conn++;
                    pd->stats.num_tcp_opened++;
                    break;
                case IPPROTO_UDP:
                    pd->stats.num_udp_conn++;
                    pd->stats.num_udp_opened++;
                    break;
                case IPPROTO_ICMP:
                    pd->stats.num_icmp_conn++;
                    pd->stats.num_icmp_opened++;
                    break;
            }

            // assume connection proxy via iptables
            data->proxied = pd->tls_decryption.enabled && (conn->tuple.ipproto == IPPROTO_TCP);
        }
    }

    // like last_seen but monotonic
    conn->data->root.last_update_ms = pd->now_ms;

    // make a copy before passing it to pd_process_packet since conn may
    // be freed in update_connection_status, while the pkt_context_t is still
    // used in pd_account_stats
    zdtun_5tuple_t conn_tuple = conn->tuple;

    struct timeval tv = hdr->ts;
    pkt_context_t pinfo;
    pd_process_packet(pd, &pkt, is_tx, &conn_tuple, conn->data, &tv, &pinfo);

    // NOTE: this may free the conn
    update_connection_status(pd, conn, &pkt, !is_tx);

    pd_account_stats(pd, &pinfo);
    return true;
}

/* ******************************************************* */

static void purge_expired_connections(pcapdroid_t *pd, uint8_t purge_all) {
    pcap_conn_t *conn, *tmp;

    HASH_ITER(hh, pd->root.connections, conn, tmp) {
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

        if(purge_all || (pd->now_ms >= (conn->data->root.last_update_ms + timeout))) {
            //log_d("IDLE (type=%d)", conn->tuple.ipproto);

            conn->data->to_purge = true;

            if(conn->data->status < CONN_STATUS_CLOSED) {
                conn->data->status = CONN_STATUS_CLOSED;
                conn->data->update_type |= CONN_UPDATE_STATS;
            }

            // If there is a pending notification, the connection data cannot be free now as it is enqueued in a conn_array_t
            if((conn->data->update_type == 0) || (pd_notify_connection_update(pd, &conn->tuple, conn->data) < 0)) {
                // no pending notification/pd_notify_connection_update failed, free now
                pd_purge_connection(pd, conn->data);
                conn->data = NULL;
            }

            remove_connection(pd, conn);
        }
    }
}

/* ******************************************************* */

void root_iter_connections(pcapdroid_t *pd, conn_cb cb) {
    pcap_conn_t *conn, *tmp;

    HASH_ITER(hh, pd->root.connections, conn, tmp) {
        if(cb(pd, &conn->tuple, conn->data) != 0)
            return;
    }
}

/* ******************************************************* */

int run_root(pcapdroid_t *pd) {
    int sock = -1;
    int rv = -1;
    char buffer[PCAPD_SNAPLEN];
    bool iptables_cleanup = false;
    u_int64_t next_purge_ms;
    zdtun_callbacks_t callbacks = {.send_client = (void*)1};

#if ANDROID
    char capture_interface[16] = "@inet";
    char bpf[256];
    bpf[0] = '\0';

    pd->root.as_root = true; // TODO support read from PCAP file
    pd->root.bpf = getStringPref(pd, "getPcapDumperBpf", bpf, sizeof(bpf));
    pd->root.capture_interface = getStringPref(pd, "getCaptureInterface", capture_interface, sizeof(capture_interface));
#endif

    if((pd->zdt = zdtun_init(&callbacks, NULL)) == NULL)
        return(-1);

#ifndef FUZZING
    if((sock = connectPcapd(pd)) < 0) {
        rv = -1;
        goto cleanup;
    }
#else
    // spawning a daemon is too expensive for fuzzing
    if((sock = openPcap(pd)) < 0) {
        rv = -1;
        goto cleanup;
    }
#endif

    if(pd->tls_decryption.enabled) {
        char args[128];

        if(run_shell_cmd("iptables", get_mitm_redirection_args(pd, args, true), true, true) != 0)
            goto cleanup;

        iptables_cleanup = true;
    }

    pd_refresh_time(pd);
    next_purge_ms = pd->now_ms + PERIODIC_PURGE_TIMEOUT_MS;

    log_i("Starting packet loop");

    while(running) {
        pcapd_hdr_t hdr;
        fd_set fdset = {0};

        FD_SET(sock, &fdset);

        struct timeval timeout = {.tv_sec = 0, .tv_usec = SELECT_TIMEOUT_MS * 1000};

        if(select(sock + 1, &fdset, NULL, NULL, &timeout) < 0) {
            log_e("select failed[%d]: %s", errno, strerror(errno));
            goto cleanup;
        }

        pd_refresh_time(pd);

        if(!FD_ISSET(sock, &fdset))
            goto housekeeping;

        if(!running)
            break;

#ifndef FUZZING
        ssize_t xrv = xread(sock, &hdr, sizeof(hdr));
        if(xrv != sizeof(hdr)) {
            if(xrv < 0)
                log_e("read hdr from pcapd failed[%d]: %s", errno, strerror(errno));
            goto cleanup;
        }
        if(hdr.len > sizeof(buffer)) {
            log_e("packet too big (%d B)", hdr.len);
            goto cleanup;
        }
        if(xread(sock, buffer, hdr.len) != hdr.len) {
            log_e("read %d B packet from pcapd failed[%d]: %s", hdr.len, errno, strerror(errno));
            goto cleanup;
        }
#else
        int xrv = nextPacket(pd, &hdr, buffer, sizeof(buffer));
        if(xrv < 0)
          goto cleanup;
        else if(xrv == 0)
          goto housekeeping;
#endif

        pd->num_dropped_pkts = hdr.pkt_drops;

        int ipoffset = get_ip_offset(hdr.linktype);
        if(ipoffset < 0) {
            log_e("invalid datalink: %d", hdr.linktype);
            continue;
        }
        if(hdr.len < ipoffset) {
            log_e("invalid length: %d, expected at least %d", hdr.len, ipoffset);
            continue;
        }

        if(!handle_packet(pd, &hdr, buffer, ipoffset)) {
            // packet was rejected (unsupported/corrupted), dump to PCAP file anyway
            struct timeval tv = hdr.ts;
            pd_dump_packet(pd, buffer + ipoffset, hdr.len - ipoffset, &tv, hdr.uid);
        }

    housekeeping:
        pd_housekeeping(pd);

        if(pd->now_ms >= next_purge_ms) {
            purge_expired_connections(pd, 0);
            next_purge_ms = pd->now_ms + PERIODIC_PURGE_TIMEOUT_MS;
        }
    }

    rv = 0;

cleanup:
    purge_expired_connections(pd, 1 /* purge_all */);

    if(pd->zdt) zdtun_finalize(pd->zdt);
    if(sock > 0) close(sock);

    if(iptables_cleanup) {
        char args[128];
        run_shell_cmd("iptables", get_mitm_redirection_args(pd, args, false), true, false);
    }

    return rv;
}
