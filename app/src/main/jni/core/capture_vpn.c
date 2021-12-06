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

#include "pcapdroid.h"
#include "common/utils.h"

static void vpn_protect_socket(pcapdroid_t *pd, socket_t sock) {
    JNIEnv *env = pd->env;

    if(pd->root_capture)
        return;

    /* Call VpnService protect */
    jboolean isProtected = (*env)->CallBooleanMethod(
            env, pd->capture_service, mids.protect, sock);
    jniCheckException(env);

    if (!isProtected)
        log_e("socket protect failed");
}

/* ******************************************************* */

static int resolve_uid(pcapdroid_t *pd, const zdtun_5tuple_t *conn_info) {
    char buf[256];
    jint uid;

    zdtun_5tuple2str(conn_info, buf, sizeof(buf));
    uid = get_uid(pd->vpn.resolver, conn_info);

    if(uid >= 0) {
        char appbuf[64];

        get_appname_by_uid(pd, uid, appbuf, sizeof(appbuf));
        log_i( "%s [%d/%s]", buf, uid, appbuf);
    } else {
        uid = UID_UNKNOWN;
        log_w("%s => UID not found!", buf);
    }

    return(uid);
}

static void protectSocketCallback(zdtun_t *zdt, socket_t sock) {
    pcapdroid_t *pd = ((pcapdroid_t*)zdtun_userdata(zdt));
    vpn_protect_socket(pd, sock);
}

/* ******************************************************* */

static void add_known_dns_server(pcapdroid_t *pd, const char *ip) {
    ndpi_ip_addr_t parsed;

    if(ndpi_parse_ip_string(ip, &parsed) < 0) {
        log_e("ndpi_parse_ip_string(%s) failed", ip);
        return;
    }

    ndpi_ptree_insert(pd->vpn.known_dns_servers, &parsed, ndpi_is_ipv6(&parsed) ? 128 : 32, 1);
}

/* ******************************************************* */

static struct timeval* get_pkt_timestamp(pcapdroid_t *pd, struct timeval *tv) {
    struct timespec ts;

    if(!clock_gettime(CLOCK_REALTIME, &ts)) {
        tv->tv_sec = ts.tv_sec;
        tv->tv_usec = ts.tv_nsec / 1000;
        return tv;
    }

    // use the last pkt timestamp
    log_w("clock_gettime failed[%d]: %s", errno, strerror(errno));
    return &pd->cur_pkt.tv;
}

/* ******************************************************* */

static int net2tun(zdtun_t *zdt, zdtun_pkt_t *pkt, const zdtun_conn_t *conn_info) {
    if(!running)
        // e.g. during zdtun_finalize
        return 0;

    pcapdroid_t *pd = (pcapdroid_t*) zdtun_userdata(zdt);
    pd_conn_t *data = zdtun_conn_get_userdata(conn_info);

    struct timeval tv;
    pd_refresh_time(pd);
    pd_set_current_packet(pd, pkt, false, get_pkt_timestamp(pd, &tv));

    if(data->to_block) // NOTE: blocked_pkts accounted in pd_account_stats
        return 0;

    int rv = write(pd->vpn.tunfd, pkt->buf, pkt->len);

    if(rv < 0) {
        if(errno == ENOBUFS) {
            char buf[256];

            // Do not abort, the connection will be terminated
            log_e("Got ENOBUFS %s", zdtun_5tuple2str(zdtun_conn_get_5tuple(conn_info), buf, sizeof(buf)));
        } else if(errno == EIO) {
            log_i("Got I/O error (terminating?)");
            running = false;
        } else {
            log_f("zdt write (%d) failed [%d]: %s", pkt->len, errno, strerror(errno));
            running = false;
        }
    } else if(rv != pkt->len) {
        log_f("partial zdt write (%d / %d)", rv, pkt->len);
        rv = -1;
    } else
        rv = 0;

    return rv;
}

/* ******************************************************* */

static void check_socks5_redirection(pcapdroid_t *pd, zdtun_pkt_t *pkt, zdtun_conn_t *conn) {
    pd_conn_t *data = zdtun_conn_get_userdata(conn);

    if((pkt->tuple.ipproto == IPPROTO_TCP) && (((data->sent_pkts + data->rcvd_pkts) == 0)))
        zdtun_conn_proxy(conn);
}

/* ******************************************************* */

/*
 * If the packet contains a DNS request directed to the IP address used internally by PCAPdroid,
 * then rewrite the server address with the actual DNS server.
 * Moreover, if a private DNS connection is detected in opportunistic mode (block_private_dns true),
 * then block this connection to force the fallback to non-private DNS mode.
 */
static bool check_dns_req_allowed(pcapdroid_t *pd, zdtun_conn_t *conn) {
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn);

    if(new_dns_server != 0) {
        // Reload DNS server
        pd->vpn.dns_server = new_dns_server;
        new_dns_server = 0;

        zdtun_ip_t ip = {0};
        ip.ip4 = pd->vpn.dns_server;
        zdtun_set_dnat_info(pd->zdt, &ip, htons(53), 4);

        log_d("Using new DNS server");
    }

    if(zdtun_conn_get_5tuple(conn)->ipproto == IPPROTO_ICMP)
        return true;

    bool is_internal_dns = (tuple->ipver == 4) && (tuple->dst_ip.ip4 == pd->vpn.internal_dns);
    bool is_dns_server = is_internal_dns
                         || ((tuple->ipver == 6) && (memcmp(&tuple->dst_ip.ip6, &pd->ipv6.dns_server, 16) == 0));

    if(!is_dns_server) {
        // try with known DNS servers
        u_int64_t matched = 0;
        ndpi_ip_addr_t addr = {0};

        if(tuple->ipver == 4)
            addr.ipv4 = tuple->dst_ip.ip4;
        else
            memcpy(&addr.ipv6, &tuple->dst_ip.ip6, 16);

        ndpi_ptree_match_addr(pd->vpn.known_dns_servers, &addr, &matched);

        if(matched) {
            char ip[INET6_ADDRSTRLEN];
            int family = (tuple->ipver == 4) ? AF_INET : AF_INET6;

            is_dns_server = true;
            ip[0] = '\0';
            inet_ntop(family, &tuple->dst_ip, (char *)&ip, sizeof(ip));

            log_d("Matched known DNS server: %s", ip);
        }
    }

    if(!is_dns_server)
        return(true);

    if((tuple->ipproto == IPPROTO_UDP) && (ntohs(tuple->dst_port) == 53)) {
        zdtun_pkt_t *pkt = pd->cur_pkt.pkt;
        int dns_length = pkt->l7_len;

        if(dns_length >= sizeof(dns_packet_t)) {
            dns_packet_t *dns_data = (dns_packet_t*) pkt->l7;

            if((dns_data->flags & DNS_FLAGS_MASK) != DNS_TYPE_REQUEST)
                return(true);

            log_d("Detected DNS query[%u]", dns_length);
            pd->num_dns_requests++;

            if(is_internal_dns) {
                /*
                 * Direct the packet to the public DNS server. Checksum recalculation is not strictly necessary
                 * here as zdtun will pd the connection.
                 */
                zdtun_conn_dnat(conn);
            }

            return(true);
        }
    }

    if(block_private_dns) {
        log_i("blocking packet directed to the DNS server");
        return(false);
    }

    // allow
    return(true);
}

/* ******************************************************* */

static int handle_new_connection(zdtun_t *zdt, zdtun_conn_t *conn_info) {
    pcapdroid_t *pd = ((pcapdroid_t *) zdtun_userdata(zdt));
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn_info);

    pd_conn_t *data = pd_new_connection(pd, tuple, resolve_uid(pd, tuple));
    if(!data) {
        /* reject connection */
        return (1);
    }

    zdtun_conn_set_userdata(conn_info, data);
    data->to_block = !check_dns_req_allowed(pd, conn_info);

    /* accept connection */
    return(0);
}

/* ******************************************************* */

static void destroy_connection(zdtun_t *zdt, const zdtun_conn_t *conn_info) {
    pcapdroid_t *pd = (pcapdroid_t*) zdtun_userdata(zdt);
    pd_conn_t *data = zdtun_conn_get_userdata(conn_info);

    if(!data) {
        log_e("Missing data in connection");
        return;
    }

    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn_info);

    // Send last notification
    // Will free the data in sendConnectionsDump
    data->update_type |= CONN_UPDATE_STATS;
    pd_notify_connection_update(pd, tuple, data);

    pd_giveup_dpi(pd, data, tuple);
    data->status = zdtun_conn_get_status(conn_info);
    data->to_purge = true;
}

/* ******************************************************* */

static void on_packet(zdtun_t *zdt, const zdtun_pkt_t *pkt, uint8_t from_tun, const zdtun_conn_t *conn_info) {
    if(!running)
        // e.g. during zdtun_finalize
        return;

    pcapdroid_t *pd = ((pcapdroid_t*)zdtun_userdata(zdt));
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn_info);

    pd_conn_t *data = zdtun_conn_get_userdata(conn_info);
    if(!data) {
        log_e("Missing data in connection");
        return;
    }

    data->status = zdtun_conn_get_status(conn_info);

    if(data->to_block) {
        data->blocked_pkts++;
        data->last_seen = pd->cur_pkt.ms;
        return;
    }

    pd_account_stats(pd, tuple, data);

    if(data->status >= CONN_STATUS_CLOSED)
        data->to_purge = true;
}

/* ******************************************************* */

int run_vpn(pcapdroid_t *pd, int tunfd) {
    zdtun_t *zdt;
    char buffer[32768];
    u_int64_t next_purge_ms;

    int flags = fcntl(pd->vpn.tunfd, F_GETFL, 0);
    if (flags < 0 || fcntl(pd->vpn.tunfd, F_SETFL, flags & ~O_NONBLOCK) < 0) {
        log_f("fcntl ~O_NONBLOCK error [%d]: %s", errno,
                    strerror(errno));
        return (-1);
    }

    pd->vpn.tunfd = tunfd;
    pd->vpn.internal_ipv4 = getIPv4Pref(pd->env, pd->capture_service, "getVpnIPv4");
    pd->vpn.internal_dns = getIPv4Pref(pd->env, pd->capture_service, "getVpnDns");
    pd->vpn.dns_server = getIPv4Pref(pd->env, pd->capture_service, "getDnsServer");
    pd->vpn.resolver = init_uid_resolver(pd->sdk_ver, pd->env, pd->capture_service);
    pd->vpn.known_dns_servers = ndpi_ptree_create();

    zdtun_callbacks_t callbacks = {
        .send_client = net2tun,
        .account_packet = on_packet,
        .on_socket_open = protectSocketCallback,
        .on_connection_open = handle_new_connection,
        .on_connection_close = destroy_connection,
    };

    // List of known DNS servers
    add_known_dns_server(pd, "8.8.8.8");
    add_known_dns_server(pd, "8.8.4.4");
    add_known_dns_server(pd, "1.1.1.1");
    add_known_dns_server(pd, "1.0.0.1");
    add_known_dns_server(pd, "2001:4860:4860::8888");
    add_known_dns_server(pd, "2001:4860:4860::8844");
    add_known_dns_server(pd, "2606:4700:4700::64");
    add_known_dns_server(pd, "2606:4700:4700::6400");

    zdt = zdtun_init(&callbacks, pd);

    if(zdt == NULL) {
        log_f("zdtun_init failed");
        return(-2);
    }

    pd->zdt = zdt;
    new_dns_server = 0;

    if(pd->socks5.enabled) {
        zdtun_ip_t dnatip = {0};
        dnatip.ip4 = pd->socks5.proxy_ip;
        zdtun_set_socks5_proxy(zdt, &dnatip, pd->socks5.proxy_port, 4);
    }

    zdtun_ip_t ip = {0};
    ip.ip4 = pd->vpn.dns_server;
    zdtun_set_dnat_info(zdt, &ip, ntohs(53), 4);

    pd_refresh_time(pd);
    next_purge_ms = pd->now_ms + PERIODIC_PURGE_TIMEOUT_MS;

    log_d("Starting packet loop [tunfd=%d]", pd->vpn.tunfd);

    while(running) {
        int max_fd;
        fd_set fdset;
        fd_set wrfds;
        int size;
        struct timeval timeout = {.tv_sec = 0, .tv_usec = SELECT_TIMEOUT_MS * 1000};

        zdtun_fds(zdt, &max_fd, &fdset, &wrfds);

        FD_SET(pd->vpn.tunfd, &fdset);
        max_fd = max(max_fd, pd->vpn.tunfd);

        if(select(max_fd + 1, &fdset, &wrfds, NULL, &timeout) < 0) {
            log_e("select failed[%d]: %s", errno, strerror(errno));
            break;
        }

        if(!running)
            break;

        if(FD_ISSET(pd->vpn.tunfd, &fdset)) {
            /* Packet from VPN */
            size = read(pd->vpn.tunfd, buffer, sizeof(buffer));
            if(size > 0) {
                zdtun_pkt_t pkt;
                pd_refresh_time(pd);

                if(zdtun_parse_pkt(zdt, buffer, size, &pkt) != 0) {
                    log_d("zdtun_parse_pkt failed");
                    goto housekeeping;
                }

                if(pkt.flags & ZDTUN_PKT_IS_FRAGMENT) {
                    log_d("discarding IP fragment");
                    pd->num_discarded_fragments++;
                    goto housekeeping;
                }

                struct timeval tv;
                pd_set_current_packet(pd, &pkt, true, get_pkt_timestamp(pd, &tv));

                if((pkt.tuple.ipver == 6) && (!pd->ipv6.enabled)) {
                    char buf[512];

                    log_d("ignoring IPv6 packet: %s",
                                zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));
                    goto housekeeping;
                }

                // Skip established TCP connections
                uint8_t is_tcp_established = ((pkt.tuple.ipproto == IPPROTO_TCP) &&
                                              (!(pkt.tcp->th_flags & TH_SYN) || (pkt.tcp->th_flags & TH_ACK)));

                zdtun_conn_t *conn = zdtun_lookup(zdt, &pkt.tuple, !is_tcp_established);
                if (!conn) {
                    if(!is_tcp_established) {
                        char buf[512];

                        pd->num_dropped_connections++;
                        log_e("zdtun_lookup failed: %s",
                                    zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));
                    } else {
                        char buf[512];

                        log_d("skipping established TCP: %s",
                                    zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));
                    }
                    goto housekeeping;
                }

                pd_conn_t *data = zdtun_conn_get_userdata(conn);
                if(data->to_block) {
                    data->blocked_pkts++;
                    data->last_seen = pd->cur_pkt.ms;
                    if(!data->first_seen)
                        data->first_seen = data->last_seen;
                    goto housekeeping;
                }

                if(pd->socks5.enabled)
                    check_socks5_redirection(pd, &pkt, conn);

                if(zdtun_forward(zdt, &pkt, conn) != 0) {
                    char buf[512];

                    log_e("zdtun_forward failed: %s",
                                zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));

                    pd->num_dropped_connections++;
                    zdtun_destroy_conn(zdt, conn);
                    goto housekeeping;
                }
            } else {
                pd_refresh_time(pd);
                if(size < 0)
                    log_e("recv(tunfd) returned error [%d]: %s", errno,
                          strerror(errno));
            }
        } else {
            pd_refresh_time(pd);
            zdtun_handle_fd(zdt, &fdset, &wrfds);
        }

        housekeeping:
        pd_housekeeping(pd);

        if(pd->now_ms >= next_purge_ms) {
            zdtun_purge_expired(zdt);
            next_purge_ms = pd->now_ms + PERIODIC_PURGE_TIMEOUT_MS;
        }
    }

    zdtun_finalize(zdt);
    destroy_uid_resolver(pd->vpn.resolver);
    ndpi_ptree_destroy(pd->vpn.known_dns_servers);

    return(0);
}


