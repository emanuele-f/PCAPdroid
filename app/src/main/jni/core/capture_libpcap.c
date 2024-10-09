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
 * Copyright 2021-24 - Emanuele Faranda
 */

#include <sys/un.h>
#include <sys/wait.h>
#include <linux/limits.h>
#include "pcapdroid.h"
#include "errors.h"
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

static int get_pcapd_pid() {
    char pid_s[8];
    FILE *f = fopen(PCAPD_PID, "r");

    if(f == NULL)
        return -1;

    fgets(pid_s, sizeof(pid_s), f);
    fclose(f);

    return atoi(pid_s);
}

/* ******************************************************* */

static void kill_process(int pid, bool as_root, int signum) {
    char args[16];

    snprintf(args, sizeof(args), "-%d %d", signum, pid);
    run_shell_cmd("kill", args, as_root, false);
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

static const char* get_uids_filter(pcapdroid_t *pd, char *buf, size_t buf_size) {
    if (pd->tls_decryption.enabled || (pd->pcap.app_filter_uids_size <= 0))
        return "-1";

    size_t off = 0;

    for (int i = 0; (i < pd->pcap.app_filter_uids_size) && (off < buf_size); i++) {
        const char * fmt = (i == 0) ? "%d" : ",%d";
        off += snprintf(buf + off, (buf_size - off), fmt, pd->pcap.app_filter_uids[i]);
    }

    if (off >= buf_size)
        log_e("The UID filter has been truncated");

    return buf;
}

/* ******************************************************* */

static int connectPcapd(pcapdroid_t *pd) {
    int sock;
    int client = -1;
    char pcapd[PATH_MAX];
    char *bpf = pd->pcap.bpf ? pd->pcap.bpf : "";

    log_d("Starting pcapd...");

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

    int pid = get_pcapd_pid();
    if(pid > 0) {
        log_i("Killing old pcapd with pid %d", pid);
        kill_process(pid, pd->pcap.as_root, SIGTERM);
        pid = -1;
    }

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
            log_f("BPF contains suspicious characters");
            goto cleanup;
        }
        log_d("BPF filter is in use");
    }

    if(pd->pcap_file_capture) {
        // must be a file path
        if(access(pd->pcap.capture_interface, F_OK)) {
            log_f(PD_ERR_PCAP_DOES_NOT_EXIST);
            goto cleanup;
        }
    } else {
        // must be a valid interface name
        if(!valid_ifname(pd->pcap.capture_interface)) {
            log_f("Invalid capture_interface");
            goto cleanup;
        }

        // this is needed to run with root under recent Magisk
        // the drawback is that it's not possible to get the pcapd exit status,
        // which is needed when reading a PCAP file
        pd->pcap.daemonize = true;
    }

    // Start the daemon
    char uids_filter_buf[128];
    char args[256];
    snprintf(args, sizeof(args), "-l pcapd.log -L %u -i '%s' -u %s -t -b '%s'%s",
             getuid(), pd->pcap.capture_interface,
             get_uids_filter(pd, uids_filter_buf, sizeof(uids_filter_buf)),
             bpf, pd->pcap.daemonize ? " -d" : "");

    pid = start_subprocess(pcapd, args, pd->pcap.as_root, NULL);
    if(pid <= 0) {
        log_e("start_subprocess failed");
        goto cleanup;
    }

    if(pd->pcap.daemonize) {
        // when running as a daemon, child exits early
        // note: this will block until user grants/denies root permission
        int rv;
        if((waitpid(pid, &rv, 0) == pid) && (rv != 0)) {
            log_w("pcapd exited with code %d", rv);

            log_f(PD_ERR_PCAPD_START);
            goto cleanup;
        }
    }

    // Wait for pcapd to connect to the socket
    const time_t start_timeout = time(NULL) + 5 /* 5 seconds */;
    bool pcapd_connected = false;

    while(time(NULL) < start_timeout) {
        struct timeval timeout = {.tv_sec = 0, .tv_usec = 500000 /* 500 ms */};
        fd_set selfds;

        FD_ZERO(&selfds);
        FD_SET(sock, &selfds);
        select(sock + 1, &selfds, NULL, NULL, &timeout);

        if(!running) {
            log_w("Connect to pcapd aborted");
            goto cleanup;
        }

        if(FD_ISSET(sock, &selfds)) {
            pcapd_connected = true;
            break;
        }

        // check if the child process terminated incorrectly
        int rv;
        if(!pd->pcap.daemonize && (waitpid(pid, &rv, WNOHANG) == pid)) {
            log_w("pcapd exited with code %d", rv);
            pid = -1;

            log_f(PD_ERR_PCAPD_START);
            goto cleanup;
        }
    }

    if(!pcapd_connected) {
        log_f(PD_ERR_PCAPD_NOT_SPAWNED);
        goto cleanup;
    }

    if((client = accept(sock, NULL, NULL)) < 0) {
        log_f("AF_UNIX accept failed [%d]: %s", errno,
                    strerror(errno));
        goto cleanup;
    }

    log_i("Connected to pcapd (pid=%d)", pid);
    pd->pcap.pcapd_pid = pid;

cleanup:
    if((client < 0) && (pid > 0)) {
        int rv;
        kill_process(pid, pd->pcap.as_root, SIGKILL);

        if(!pd->pcap.daemonize)
            waitpid(pid, &rv, 0);
    }
    unlink(PCAPD_SOCKET_PATH);
    close(sock);

    return client;
}

/* ******************************************************* */

static char* get_mitm_redirection_args(pcapdroid_t *pd, char *buf, int uid, bool add) {
    int off = sprintf(buf, "-t nat -%c OUTPUT -p tcp -m owner ", add ? 'I' : 'D');
    if(uid >= 0)
        off += sprintf(buf + off, "--uid-owner %d", uid);
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

    HASH_DEL(pd->pcap.connections, conn);
    pd_free(conn);
}

/* ******************************************************* */

// Determines when a connection gets closed
static void update_connection_status(pcapdroid_t *pd, pcap_conn_t *conn, zdtun_pkt_t *pkt, uint8_t dir) {
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
                  remove_connection(pd, conn);
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

    if(pd->pcap_file_capture && (hdr->uid == UID_UNKNOWN)) {
        // retrieve the UID from the PCAPdroid trailer, if available
        int non_ip_overhead = (int)hdr->len - (int)pkt.len;

        if(non_ip_overhead >= sizeof(pcapdroid_trailer_t)) {
            const struct pcapdroid_trailer* trailer =
                    (const struct pcapdroid_trailer*) (buffer + hdr->len - sizeof(pcapdroid_trailer_t));

            if(ntohl(trailer->magic) == PCAPDROID_TRAILER_MAGIC) {
                hdr->uid = ntohl(trailer->uid);
                has_seen_pcapdroid_trailer = true;
            }
        }
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

    HASH_FIND(hh, pd->pcap.connections, &pkt.tuple, sizeof(zdtun_5tuple_t), conn);
    if(!conn) {
        // is_tx may be wrong, search in the other direction
        is_tx = !is_tx;
        tupleSwapPeers(&pkt.tuple);

        HASH_FIND(hh, pd->pcap.connections, &pkt.tuple, sizeof(zdtun_5tuple_t), conn);

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
                log_f("malloc(pcap_conn_t) failed with code %d/%s",
                      errno, strerror(errno));
                return false;
            }

            pd_conn_t *data = pd_new_connection(pd, &pkt.tuple, hdr->uid);
            if(!data) {
                pd_free(conn);
                return false;
            }

            if(hdr->linktype == PCAPD_DLT_LINUX_SLL2)
                data->pcap.ifidx = ntohl(*(uint32_t*)(buffer + 4)); // sll2_header->sll2_if_index

            conn->tuple = pkt.tuple;
            conn->data = data;
            HASH_ADD(hh, pd->pcap.connections, tuple, sizeof(zdtun_5tuple_t), conn);

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
    conn->data->pcap.last_update_ms = pd->now_ms;

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

    HASH_ITER(hh, pd->pcap.connections, conn, tmp) {
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

        if(purge_all || (pd->now_ms >= (conn->data->pcap.last_update_ms + timeout))) {
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

void libpcap_iter_connections(pcapdroid_t *pd, conn_cb cb) {
    pcap_conn_t *conn, *tmp;

    HASH_ITER(hh, pd->pcap.connections, conn, tmp) {
        if(cb(pd, &conn->tuple, conn->data) != 0)
            return;
    }
}

/* ******************************************************* */

static void process_pcapd_rv(pcapdroid_t *pd, int rv) {
    pcapd_rv rrv = (pcapd_rv) rv;
    log_i("pcapd exit code: %d", rv);

    switch (rrv) {
        case PCAPD_OK:
            break;
        case PCAPD_INTERFACE_OPEN_FAILED:
            if(pd->pcap_file_capture)
                log_f(PD_ERR_INVALID_PCAP_FILE);
            else
                log_f(PD_ERR_INTERFACE_OPEN_ERROR);
            break;
        case PCAPD_UNSUPPORTED_DATALINK:
            log_f(PD_ERR_UNSUPPORTED_DATALINK);
            break;
        case PCAPD_PCAP_READ_ERROR:
            log_f(PD_ERR_PCAP_READ);
            break;
        case PCAPD_SOCKET_WRITE_ERROR:
            // ignore, as it can be caused by PCAPdroid stopping the capture
            break;
        default:
            log_f("pcapd daemon exited with code %d", rv);
    }
}

/* ******************************************************* */

int run_libpcap(pcapdroid_t *pd) {
    int sock = -1;
    int rv = -1;
    char buffer[PCAPD_SNAPLEN];
    bool iptables_cleanup = false;
    u_int64_t next_purge_ms;
    zdtun_callbacks_t callbacks = {.send_client = (void*)1};

#if ANDROID
    char capture_interface[PATH_MAX] = "@inet";
    char bpf[256];
    bpf[0] = '\0';

    pd->pcap.app_filter_uids_size = getIntArrayPref(pd->env, pd->capture_service, "getAppFilterUids", &pd->pcap.app_filter_uids);
    pd->pcap.as_root = !pd->pcap_file_capture;
    pd->pcap.bpf = getStringPref(pd, "getPcapDumperBpf", bpf, sizeof(bpf));
    pd->pcap.capture_interface = getStringPref(pd, "getCaptureInterface", capture_interface, sizeof(capture_interface));
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

        if(pd->pcap.app_filter_uids_size > 0) {
            for (int i = 0; i < pd->pcap.app_filter_uids_size; i++) {
                int uid = pd->pcap.app_filter_uids[i];

                if (uid >= 0) {
                    if(run_shell_cmd("iptables", get_mitm_redirection_args(pd, args, uid, true), true, true) != 0)
                        goto cleanup;

                    iptables_cleanup = true;
                }
            }
        } else {
            if(run_shell_cmd("iptables", get_mitm_redirection_args(pd, args, -1, true), true, true) != 0)
                goto cleanup;

            iptables_cleanup = true;
        }
    }

    pd_refresh_time(pd);
    next_purge_ms = pd->now_ms + PERIODIC_PURGE_TIMEOUT_MS;

    log_i("Starting packet loop");
    if(pd->cb.notify_service_status && running)
        pd->cb.notify_service_status(pd, "started");

    while(running) {
        pcapd_hdr_t hdr;
        fd_set fdset = {0};

        FD_SET(sock, &fdset);

        struct timeval timeout = {.tv_sec = 0, .tv_usec = SELECT_TIMEOUT_MS * 1000};

        if(select(sock + 1, &fdset, NULL, NULL, &timeout) < 0) {
            log_f("select failed[%d]: %s", errno, strerror(errno));
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
                log_f("read hdr from pcapd failed[%d]: %s", errno, strerror(errno));
            goto cleanup;
        }
        if(hdr.len > sizeof(buffer)) {
            log_f("packet too big (%d B)", hdr.len);
            goto cleanup;
        }
        if(xread(sock, buffer, hdr.len) != hdr.len) {
            log_f("read %d B packet from pcapd failed[%d]: %s", hdr.len, errno, strerror(errno));
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

        if(pd->pcap.app_filter_uids_size > 0) {
            for (int i = 0; i < pd->pcap.app_filter_uids_size; i++) {
                int uid = pd->pcap.app_filter_uids[i];

                if (uid >= 0)
                    run_shell_cmd("iptables", get_mitm_redirection_args(pd, args, uid, false), true, false);
            }
        } else
            run_shell_cmd("iptables", get_mitm_redirection_args(pd, args, -1, false), true, false);
    }

    if((pd->pcap.pcapd_pid > 0) && !pd->pcap.daemonize) {
        int status = PCAPD_ERROR;

        if(waitpid(pd->pcap.pcapd_pid, &status, 0) <= 0)
            log_e("waitpid %d failed[%d]: %s", pd->pcap.pcapd_pid, errno, strerror(errno));

        if(WIFEXITED(status))
            process_pcapd_rv(pd, WEXITSTATUS(status));
    }

#if ANDROID
    if (pd->pcap.app_filter_uids) {
        pd_free(pd->pcap.app_filter_uids);

        pd->pcap.app_filter_uids = NULL;
        pd->pcap.app_filter_uids_size = 0;
    }
#endif

    return rv;
}
