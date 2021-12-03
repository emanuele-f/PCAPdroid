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

#include "vpnproxy.h"
#include "common/utils.h"

static void protectSocketCallback(zdtun_t *tun, socket_t sock) {
    vpnproxy_data_t *proxy = ((vpnproxy_data_t*)zdtun_userdata(tun));
    vpn_protect_socket(proxy, sock);
}

/* ******************************************************* */

static void add_known_dns_server(vpnproxy_data_t *proxy, const char *ip) {
    ndpi_ip_addr_t parsed;

    if(ndpi_parse_ip_string(ip, &parsed) < 0) {
        log_e("ndpi_parse_ip_string(%s) failed", ip);
        return;
    }

    ndpi_ptree_insert(proxy->known_dns_servers, &parsed, ndpi_is_ipv6(&parsed) ? 128 : 32, 1);
}

/* ******************************************************* */

static int net2tun(zdtun_t *tun, char *pkt_buf, int pkt_size, const zdtun_conn_t *conn_info) {
    if(!running)
        return 0;

    vpnproxy_data_t *proxy = (vpnproxy_data_t*) zdtun_userdata(tun);
    conn_data_t *data = zdtun_conn_get_userdata(conn_info);

    if(data->to_block) // NOTE: blocked_pkts accounted in account_packet
        return 0;

    int rv = write(proxy->tunfd, pkt_buf, pkt_size);

    if(rv < 0) {
        if(errno == ENOBUFS) {
            char buf[256];

            // Do not abort, the connection will be terminated
            log_e("Got ENOBUFS %s", zdtun_5tuple2str(zdtun_conn_get_5tuple(conn_info), buf, sizeof(buf)));
        } else if(errno == EIO) {
            log_i("Got I/O error (terminating?)");
            running = false;
        } else {
            log_f("tun write (%d) failed [%d]: %s", pkt_size, errno, strerror(errno));
            running = false;
        }
    } else if(rv != pkt_size) {
        log_f("partial tun write (%d / %d)", rv, pkt_size);
        rv = -1;
    } else
        rv = 0;

    return rv;
}

/* ******************************************************* */

static void check_socks5_redirection(vpnproxy_data_t *proxy, zdtun_pkt_t *pkt, zdtun_conn_t *conn) {
    conn_data_t *data = zdtun_conn_get_userdata(conn);

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
static bool check_dns_req_allowed(vpnproxy_data_t *proxy, zdtun_conn_t *conn) {
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn);

    if(new_dns_server != 0) {
        // Reload DNS server
        proxy->dns_server = new_dns_server;
        new_dns_server = 0;

        zdtun_ip_t ip = {0};
        ip.ip4 = proxy->dns_server;
        zdtun_set_dnat_info(proxy->tun, &ip, htons(53), 4);

        log_d("Using new DNS server");
    }

    if(zdtun_conn_get_5tuple(conn)->ipproto == IPPROTO_ICMP)
        return true;

    bool is_internal_dns = (tuple->ipver == 4) && (tuple->dst_ip.ip4 == proxy->vpn_dns);
    bool is_dns_server = is_internal_dns
                         || ((tuple->ipver == 6) && (memcmp(&tuple->dst_ip.ip6, &proxy->ipv6.dns_server, 16) == 0));

    if(!is_dns_server) {
        // try with known DNS servers
        u_int64_t matched = 0;
        ndpi_ip_addr_t addr = {0};

        if(tuple->ipver == 4)
            addr.ipv4 = tuple->dst_ip.ip4;
        else
            memcpy(&addr.ipv6, &tuple->dst_ip.ip6, 16);

        ndpi_ptree_match_addr(proxy->known_dns_servers, &addr, &matched);

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

    if((tuple->ipproto == IPPROTO_UDP) && (ntohs(tuple->dst_port) == 53) && (proxy->last_pkt != NULL)) {
        zdtun_pkt_t *pkt = proxy->last_pkt;
        int dns_length = pkt->l7_len;

        if(dns_length >= sizeof(dns_packet_t)) {
            dns_packet_t *dns_data = (dns_packet_t*) pkt->l7;

            if((dns_data->flags & DNS_FLAGS_MASK) != DNS_TYPE_REQUEST)
                return(true);

            log_d("Detected DNS query[%u]", dns_length);
            proxy->num_dns_requests++;

            if(is_internal_dns) {
                /*
                 * Direct the packet to the public DNS server. Checksum recalculation is not strictly necessary
                 * here as zdtun will proxy the connection.
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

static int handle_new_connection(zdtun_t *tun, zdtun_conn_t *conn_info) {
    vpnproxy_data_t *proxy = ((vpnproxy_data_t *) zdtun_userdata(tun));
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn_info);

    conn_data_t *data = new_connection(proxy, tuple, resolve_uid(proxy, tuple));
    if(!data) {
        /* reject connection */
        return (1);
    }

    zdtun_conn_set_userdata(conn_info, data);
    data->to_block = !check_dns_req_allowed(proxy, conn_info);

    data->incr_id = proxy->incr_id++;
    notify_connection(&proxy->new_conns, tuple, data);

    /* accept connection */
    return(0);
}

/* ******************************************************* */

static void destroy_connection(zdtun_t *tun, const zdtun_conn_t *conn_info) {
    vpnproxy_data_t *proxy = (vpnproxy_data_t*) zdtun_userdata(tun);
    conn_data_t *data = zdtun_conn_get_userdata(conn_info);

    if(!data) {
        log_e("Missing data in connection");
        return;
    }

    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn_info);

    // Send last notification
    // Will free the data in sendConnectionsDump
    data->update_type |= CONN_UPDATE_STATS;
    notify_connection(&proxy->conns_updates, tuple, data);

    conn_end_ndpi_detection(data, proxy, tuple);
    data->status = zdtun_conn_get_status(conn_info);
    data->to_purge = true;
}

/* ******************************************************* */

static void refresh_pkt_timestamp(vpnproxy_data_t *proxy) {
    struct timespec ts = {0};

    if(!clock_gettime(CLOCK_REALTIME, &ts)) {
        proxy->last_pkt_ts.tv_sec = ts.tv_sec;
        proxy->last_pkt_ts.tv_usec = ts.tv_nsec / 1000;
    } else
        log_d("clock_gettime failed[%d]: %s", errno, strerror(errno));
}

/* ******************************************************* */

static void on_packet(zdtun_t *tun, const zdtun_pkt_t *pkt, uint8_t from_tun, const zdtun_conn_t *conn_info) {
    conn_data_t *data = zdtun_conn_get_userdata(conn_info);

    if(!data) {
        log_e("Missing data in connection");
        return;
    }

    vpnproxy_data_t *proxy = ((vpnproxy_data_t*)zdtun_userdata(tun));
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn_info);

    refresh_pkt_timestamp(proxy);
    uint64_t pkt_ms = timeval2ms(&proxy->last_pkt_ts);
    data->status = zdtun_conn_get_status(conn_info);

    if(data->to_block) {
        data->blocked_pkts++;
        data->last_seen = pkt_ms;
        return;
    }

    account_packet(proxy, pkt, from_tun, tuple, data, pkt_ms);

    if(data->status >= CONN_STATUS_CLOSED)
        data->to_purge = true;
}

/* ******************************************************* */

int run_proxy(vpnproxy_data_t *proxy) {
    zdtun_t *tun;
    char buffer[32768];
    u_int64_t next_purge_ms;

    int flags = fcntl(proxy->tunfd, F_GETFL, 0);
    if (flags < 0 || fcntl(proxy->tunfd, F_SETFL, flags & ~O_NONBLOCK) < 0) {
        log_f("fcntl ~O_NONBLOCK error [%d]: %s", errno,
                    strerror(errno));
        return (-1);
    }

    zdtun_callbacks_t callbacks = {
        .send_client = net2tun,
        .account_packet = on_packet,
        .on_socket_open = protectSocketCallback,
        .on_connection_open = handle_new_connection,
        .on_connection_close = destroy_connection,
    };

    // List of known DNS servers
    add_known_dns_server(proxy, "8.8.8.8");
    add_known_dns_server(proxy, "8.8.4.4");
    add_known_dns_server(proxy, "1.1.1.1");
    add_known_dns_server(proxy, "1.0.0.1");
    add_known_dns_server(proxy, "2001:4860:4860::8888");
    add_known_dns_server(proxy, "2001:4860:4860::8844");
    add_known_dns_server(proxy, "2606:4700:4700::64");
    add_known_dns_server(proxy, "2606:4700:4700::6400");

    tun = zdtun_init(&callbacks, proxy);

    if(tun == NULL) {
        log_f("zdtun_init failed");
        return(-2);
    }

    proxy->tun = tun;
    new_dns_server = 0;

    if(proxy->socks5.enabled) {
        zdtun_ip_t dnatip = {0};
        dnatip.ip4 = proxy->socks5.proxy_ip;
        zdtun_set_socks5_proxy(tun, &dnatip, proxy->socks5.proxy_port, 4);
    }

    zdtun_ip_t ip = {0};
    ip.ip4 = proxy->dns_server;
    zdtun_set_dnat_info(tun, &ip, ntohs(53), 4);

    refresh_time(proxy);
    next_purge_ms = proxy->now_ms + PERIODIC_PURGE_TIMEOUT_MS;

    log_d("Starting packet loop [tunfd=%d]", proxy->tunfd);

    while(running) {
        int max_fd;
        fd_set fdset;
        fd_set wrfds;
        int size;
        struct timeval timeout = {.tv_sec = 0, .tv_usec = SELECT_TIMEOUT_MS * 1000};

        zdtun_fds(tun, &max_fd, &fdset, &wrfds);

        FD_SET(proxy->tunfd, &fdset);
        max_fd = max(max_fd, proxy->tunfd);

        if(select(max_fd + 1, &fdset, &wrfds, NULL, &timeout) < 0) {
            log_e("select failed[%d]: %s", errno, strerror(errno));
            break;
        }

        if(!running)
            break;

        refresh_time(proxy);

        if(FD_ISSET(proxy->tunfd, &fdset)) {
            /* Packet from VPN */
            size = read(proxy->tunfd, buffer, sizeof(buffer));

            if (size > 0) {
                zdtun_pkt_t pkt;

                if(zdtun_parse_pkt(tun, buffer, size, &pkt) != 0) {
                    log_d("zdtun_parse_pkt failed");
                    goto housekeeping;
                }

                if(pkt.flags & ZDTUN_PKT_IS_FRAGMENT) {
                    log_d("discarding IP fragment");
                    proxy->num_discarded_fragments++;
                    goto housekeeping;
                }

                proxy->last_pkt = &pkt;

                if((pkt.tuple.ipver == 6) && (!proxy->ipv6.enabled)) {
                    char buf[512];

                    log_d("ignoring IPv6 packet: %s",
                                zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));
                    goto housekeeping;
                }

                // Skip established TCP connections
                uint8_t is_tcp_established = ((pkt.tuple.ipproto == IPPROTO_TCP) &&
                                              (!(pkt.tcp->th_flags & TH_SYN) || (pkt.tcp->th_flags & TH_ACK)));

                zdtun_conn_t *conn = zdtun_lookup(tun, &pkt.tuple, !is_tcp_established);
                if (!conn) {
                    if(!is_tcp_established) {
                        char buf[512];

                        proxy->num_dropped_connections++;
                        log_e("zdtun_lookup failed: %s",
                                    zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));
                    } else {
                        char buf[512];

                        log_d("skipping established TCP: %s",
                                    zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));
                    }
                    goto housekeeping;
                }

                conn_data_t *data = zdtun_conn_get_userdata(conn);
                if(data->to_block) {
                    data->blocked_pkts++;
                    refresh_pkt_timestamp(proxy);
                    data->last_seen = timeval2ms(&proxy->last_pkt_ts);
                    if(!data->first_seen)
                        data->first_seen = data->last_seen;
                    goto housekeeping;
                }

                if(proxy->socks5.enabled)
                    check_socks5_redirection(proxy, &pkt, conn);

                if(zdtun_forward(tun, &pkt, conn) != 0) {
                    char buf[512];

                    log_e("zdtun_forward failed: %s",
                                zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));

                    proxy->num_dropped_connections++;
                    zdtun_destroy_conn(tun, conn);
                    goto housekeeping;
                }
            } else if (size < 0)
                log_e("recv(tunfd) returned error [%d]: %s", errno,
                            strerror(errno));
        } else
            zdtun_handle_fd(tun, &fdset, &wrfds);


        housekeeping:
            run_housekeeping(proxy);

            if(proxy->now_ms >= next_purge_ms) {
                zdtun_purge_expired(tun);
                next_purge_ms = proxy->now_ms + PERIODIC_PURGE_TIMEOUT_MS;
            }
    }

    zdtun_finalize(tun);
    return(0);
}


