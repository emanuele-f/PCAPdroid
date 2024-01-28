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
#include "port_map.h"

/* ******************************************************* */

static int resolve_uid(pcapdroid_t *pd, const zdtun_5tuple_t *conn_info) {
    char buf[256];
    jint uid;

    zdtun_5tuple2str(conn_info, buf, sizeof(buf));
    uid = get_uid(pd->vpn.resolver, conn_info);

    if(uid >= 0) {
        char appbuf[64];

        get_appname_by_uid(pd, uid, appbuf, sizeof(appbuf));
        log_d( "%s [%d/%s]", buf, uid, appbuf);
    } else {
        uid = UID_UNKNOWN;
        log_w("%s => UID not found!", buf);
    }

    return(uid);
}

static void protectSocketCallback(zdtun_t *zdt, socket_t sock) {
#if ANDROID
    pcapdroid_t *pd = ((pcapdroid_t*)zdtun_userdata(zdt));
    JNIEnv *env = pd->env;

    if(!pd->vpn_capture)
        return;

    /* Call VpnService protect */
    jboolean isProtected = (*env)->CallBooleanMethod(
            env, pd->capture_service, mids.protect, sock);
    jniCheckException(env);

    if(!isProtected)
        log_e("socket protect failed");
#endif
}

/* ******************************************************* */

static struct timeval* get_pkt_timestamp(pcapdroid_t *pd, struct timeval *tv) {
    struct timespec ts;

    if(!clock_gettime(CLOCK_REALTIME, &ts)) {
        tv->tv_sec = ts.tv_sec;
        tv->tv_usec = ts.tv_nsec / 1000;
        return tv;
    }

    log_w("clock_gettime failed[%d]: %s", errno, strerror(errno));
    return tv;
}

/* ******************************************************* */

static int remote2vpn(zdtun_t *zdt, zdtun_pkt_t *pkt, const zdtun_conn_t *conn_info) {
    if(!running)
        // e.g. during zdtun_finalize
        return 0;

    pcapdroid_t *pd = (pcapdroid_t*) zdtun_userdata(zdt);
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn_info);
    pd_conn_t *data = zdtun_conn_get_userdata(conn_info);

    // if this is called inside zdtun_forward, account the egress packet before the subsequent ingress packet
    if(data->vpn.fw_pctx) {
        pd_account_stats(pd, data->vpn.fw_pctx);
        data->vpn.fw_pctx = NULL;
    }

    struct timeval tv;
    pkt_context_t pctx;
    pd_refresh_time(pd);

    pd_process_packet(pd, pkt, false, tuple, data, get_pkt_timestamp(pd, &tv), &pctx);
    if(data->to_block) {
        data->blocked_pkts++;
        data->update_type |= CONN_UPDATE_STATS;
        pd_notify_connection_update(pd, tuple, data);

        // Returning -1 will result into an error condition on the connection, forcing a connection
        // close. Closing the connection is mandatory as it's not possible to handle dropped packets
        // via zdtun, since data received via the zdtun TCP sockets must be delivered to the client.
        return -1;
    }

    int rv = write(pd->vpn.tunfd, pkt->buf, pkt->len);
    if(rv < 0) {
        if(errno == ENOBUFS) {
            char buf[256];

            // Do not abort, the connection will be terminated
            log_e("Got ENOBUFS %s", zdtun_5tuple2str(tuple, buf, sizeof(buf)));
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
    } else {
        // Success
        rv = 0;
        pd_account_stats(pd, &pctx);
    }

    return rv;
}

/* ******************************************************* */

/*
 * If the packet contains a DNS request directed to the IP address used internally by PCAPdroid,
 * then rewrite the server address with the actual DNS server.
 * Moreover, if a private DNS connection is detected in opportunistic mode (block_private_dns true),
 * then block this connection to force the fallback to non-private DNS mode.
 */
static bool check_dns_req_allowed(pcapdroid_t *pd, zdtun_conn_t *conn, pkt_context_t *pctx) {
    const zdtun_5tuple_t *tuple = pctx->tuple;

    if(new_dns_server != 0) {
        log_i("Using new DNS server");
        pd->vpn.ipv4.dns_server = new_dns_server;
        new_dns_server = 0;
    }

    if(pctx->tuple->ipproto == IPPROTO_ICMP)
        return true;

    bool is_internal_dns = pd->vpn.ipv4.enabled && (tuple->ipver == 4) && (tuple->dst_ip.ip4 == pd->vpn.ipv4.internal_dns);
    bool is_dns_server = is_internal_dns
                         || (pd->vpn.ipv6.enabled && (tuple->ipver == 6) && (memcmp(&tuple->dst_ip.ip6, &pd->vpn.ipv6.dns_server, 16) == 0));

    if(!is_dns_server) {
        // try with known DNS servers
        zdtun_ip_t dst_ip = tuple->dst_ip;

        if(blacklist_match_ip(pd->vpn.known_dns_servers, &dst_ip, tuple->ipver)) {
            char ip[INET6_ADDRSTRLEN];
            int family = (tuple->ipver == 4) ? AF_INET : AF_INET6;

            is_dns_server = true;
            ip[0] = '\0';
            inet_ntop(family, &dst_ip, (char *)&ip, sizeof(ip));

            log_d("Matched known DNS server: %s", ip);
        }
    }

    if(!is_dns_server)
        return(true);

    if((tuple->ipproto == IPPROTO_UDP) && (ntohs(tuple->dst_port) == 53)) {
        zdtun_pkt_t *pkt = pctx->pkt;
        int dns_length = pkt->l7_len;

        if(dns_length >= sizeof(dns_packet_t)) {
            dns_packet_t *dns_data = (dns_packet_t*) pkt->l7;

            if((dns_data->flags & DNS_FLAGS_MASK) != DNS_TYPE_REQUEST)
                return(true);

            pd->num_dns_requests++;

            if(is_internal_dns) {
                /*
                 * Direct the packet to the public DNS server. Checksum recalculation is not strictly necessary
                 * here as zdtun will pd the connection.
                 */
                zdtun_ip_t ip = {0};
                ip.ip4 = pd->vpn.ipv4.dns_server;
                zdtun_conn_dnat(conn, &ip, htons(53), 4);
            }

            return(true);
        }
    }

    if(block_private_dns) {
        log_d("blocking packet directed to the DNS server");
        return(false);
    }

    // allow
    return(true);
}

/* ******************************************************* */

static bool spoof_dns_reply(pcapdroid_t *pd, zdtun_conn_t *conn, pkt_context_t *pctx) {
    // Step 1: ensure that this is a valid query
    zdtun_pkt_t *pkt = pctx->pkt;
    if(pkt->l7_len < (sizeof(dns_packet_t) + 5))
        return false;

    dns_packet_t *req = (dns_packet_t*) pkt->l7;
    if(ntohs(req->questions) != 1)
        return false;

    int remaining = pkt->l7_len - sizeof(dns_packet_t);
    int qlen=0;
    while(remaining >= 5) {
        if(!req->queries[qlen])
            break;
        qlen++;
        remaining--;
    }

    if((req->queries[qlen] != 0) || (req->queries[qlen + 1] != 0) ||
       (req->queries[qlen + 3] != 0) || (req->queries[qlen + 4] != 1))
        return false; // invalid

    uint8_t qtype = req->queries[qlen + 2];
    if((qtype != 0x01) && (qtype != 0x1c))
        return false; // invalid query type

    // Step 2: spoof the reply
    log_d("Spoofing %s DNS reply", (qtype == 0x01) ? "A" : "AAAA");

    const zdtun_5tuple_t *tuple = pctx->tuple;
    uint8_t alen = (qtype == 0x01) ? 4 : 16;
    int iplen = zdtun_iphdr_len(pd->zdt, conn);
    unsigned int len = iplen + 8 /* UDP */ + sizeof(dns_packet_t) + qlen + 5 /* type, ... */ + 12 /* answer */ + alen;
    char buf[len];
    memset(buf, 0, len);

    zdtun_make_iphdr(pd->zdt, conn, buf, len - iplen);

    struct udphdr *udp = (struct udphdr*)(buf + iplen);
    udp->uh_sport = tuple->dst_port;
    udp->uh_dport = tuple->src_port;
    udp->len = htons(len - iplen);

    dns_packet_t *dns = (dns_packet_t*)(buf + iplen + 8);
    dns->transaction_id = req->transaction_id;
    dns->flags = htons(0x8180);
    dns->questions = req->questions;
    dns->answ_rrs = dns->questions;
    dns->auth_rrs = dns->additional_rrs = 0;

    // Queries
    memcpy(dns->queries, req->queries, qlen + 5);

    // Answers
    uint8_t *answ = dns->queries + qlen + 5;

    answ[0] = 0xc0, answ[1] = 0x0c;        // name ptr
    answ[2] = 0x00, answ[3] = qtype;       // type
    answ[4] = 0x00, answ[5] = 0x01;        // class IN
    *(uint32_t*)(answ + 6) = htonl(10); // TTL: 10s
    answ[10] = 0x00, answ[11] = alen;      // addr length
    memset(answ + 12, 0, alen);      // addr: 0.0.0.0/::

    // checksum
    udp->uh_sum = 0;
    udp->uh_sum = zdtun_l3_checksum(pd->zdt, conn, buf, (char*)udp, len - iplen);

    //hexdump(buf, len);
    write(pd->vpn.tunfd, buf, len);

    return true;
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

    /* accept connection */
    return(0);
}

/* ******************************************************* */

static void connection_closed(zdtun_t *zdt, const zdtun_conn_t *conn_info) {
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
    if(pd_notify_connection_update(pd, tuple, data) < 0) {
        pd_purge_connection(pd, data);
        return;
    }

    pd_giveup_dpi(pd, data, tuple);
    data->status = zdtun_conn_get_status(conn_info);
    data->to_purge = true;
}

/* ******************************************************* */

// This is called after remote2vpn or zdtun_forward
// No need to call pd_notify_connection_update, pd_account_stats is executed before
static void update_conn_status(zdtun_t *zdt, const zdtun_pkt_t *pkt, uint8_t from_tun, const zdtun_conn_t *conn_info) {
    pd_conn_t *data = zdtun_conn_get_userdata(conn_info);

    // Update the connection status
    data->status = zdtun_conn_get_status(conn_info);
    if(data->status >= CONN_STATUS_CLOSED)
        data->to_purge = true;
}

/* ******************************************************* */

static bool matches_decryption_whitelist(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, pd_conn_t *data) {
    zdtun_ip_t dst_ip = tuple->dst_ip;

    if(!pd->tls_decryption.list)
        return false;

    // NOTE: domain matching only works if a prior DNS reply is seen (see ip_lru_find in pd_new_connection)
    return blacklist_match_ip(pd->tls_decryption.list, &dst_ip, tuple->ipver) ||
        blacklist_match_uid(pd->tls_decryption.list, data->uid) ||
        (data->info && blacklist_match_domain(pd->tls_decryption.list, data->info));
}

/* ******************************************************* */

// NOTE: this handles both user-specified SOCKS5 and TLS decryption
static bool should_proxify(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, pd_conn_t *data) {
    if(!pd->socks5.enabled)
        return false;

    if (pd->tls_decryption.list) {
        // TLS decryption
        if(!matches_decryption_whitelist(pd, tuple, data)) {
            data->decryption_ignored = true;
            return false;
        }

        // Since we cannot reliably determine TLS connections with 1 packet, and connections must be
        // proxified on the 1st packet, we proxify all the TCP connections
    }

    return (tuple->ipproto == IPPROTO_TCP);
}

/* ******************************************************* */

void vpn_process_ndpi(pcapdroid_t *pd, const zdtun_5tuple_t *tuple, pd_conn_t *data) {
    if(data->l7proto == NDPI_PROTOCOL_QUIC) {
        block_quic_mode_t block_mode = pd->vpn.block_quic_mode;

        if ((block_mode == BLOCK_QUIC_MODE_ALWAYS) ||
                ((block_mode == BLOCK_QUIC_MODE_TO_DECRYPT) && matches_decryption_whitelist(pd, tuple, data))) {
            data->blacklisted_internal = true;
            data->to_block = true;
        }
    }

    if(block_private_dns && !data->to_block &&
            (data->l7proto == NDPI_PROTOCOL_TLS) &&
            data->info && blacklist_match_domain(pd->vpn.known_dns_servers, data->info)) {
        log_d("blocking connection to private DNS server %s", data->info);
        data->blacklisted_internal = true;
        data->to_block = true;
    }
}

/* ******************************************************* */

static void load_dns_servers(pcapdroid_t *pd) {
    // IP addresses (both legacy and private DNS). These are used to count DNS queries and
    // redirect DNS queries to the public DNS server (see check_dns_req_allowed)
    blacklist_add_ipstr(pd->vpn.known_dns_servers, "8.8.8.8");
    blacklist_add_ipstr(pd->vpn.known_dns_servers, "8.8.4.4");
    blacklist_add_ipstr(pd->vpn.known_dns_servers, "1.1.1.1");
    blacklist_add_ipstr(pd->vpn.known_dns_servers, "1.0.0.1");
    blacklist_add_ipstr(pd->vpn.known_dns_servers, "2001:4860:4860::8888");
    blacklist_add_ipstr(pd->vpn.known_dns_servers, "2001:4860:4860::8844");
    blacklist_add_ipstr(pd->vpn.known_dns_servers, "2606:4700:4700::64");
    blacklist_add_ipstr(pd->vpn.known_dns_servers, "2606:4700:4700::6400");
    blacklist_add_ipstr(pd->vpn.known_dns_servers, "2606:4700:4700::1111");
    blacklist_add_ipstr(pd->vpn.known_dns_servers, "2606:4700:4700::1001");

    // Domains (only private DNS)
    // https://help.firewalla.com/hc/en-us/articles/360060661873-Dealing-DNS-over-HTTPS-and-DNS-over-TLS-on-your-network
    blacklist_add_domain(pd->vpn.known_dns_servers, "dns.google");
    blacklist_add_domain(pd->vpn.known_dns_servers, "chrome.cloudflare-dns.com");
    blacklist_add_domain(pd->vpn.known_dns_servers, "mozilla.cloudflare-dns.com");
    blacklist_add_domain(pd->vpn.known_dns_servers, "doh.cleanbrowsing.org");
    blacklist_add_domain(pd->vpn.known_dns_servers, "chromium.dns.nextdns.io");
    blacklist_add_domain(pd->vpn.known_dns_servers, "firefox.dns.nextdns.io");
    blacklist_add_domain(pd->vpn.known_dns_servers, "dns.quad9.net");
    blacklist_add_domain(pd->vpn.known_dns_servers, "doh.opendns.com");
    blacklist_add_domain(pd->vpn.known_dns_servers, "dns.adguard.com");
    blacklist_add_domain(pd->vpn.known_dns_servers, "dot.libredns.gr");
    blacklist_add_domain(pd->vpn.known_dns_servers, "dns.dnslify.com");
    blacklist_add_domain(pd->vpn.known_dns_servers, "dns-tls.qis.io");
}

/* ******************************************************* */

int run_vpn(pcapdroid_t *pd) {
    zdtun_t *zdt;
    char buffer[VPN_BUFFER_SIZE];
    u_int64_t next_purge_ms;

    int flags = fcntl(pd->vpn.tunfd, F_GETFL, 0);
    if (flags < 0 || fcntl(pd->vpn.tunfd, F_SETFL, flags & ~O_NONBLOCK) < 0) {
        log_f("fcntl ~O_NONBLOCK error [%d]: %s", errno,
                    strerror(errno));
        return (-1);
    }

#if ANDROID
    pd->vpn.resolver = init_uid_resolver(pd->sdk_ver, pd->env, pd->capture_service);
    pd->vpn.known_dns_servers = blacklist_init();
    pd->vpn.block_quic_mode = getIntPref(pd->env, pd->capture_service, "getBlockQuickMode");

    pd->vpn.ipv4.enabled = (bool) getIntPref(pd->env, pd->capture_service, "getIPv4Enabled");
    pd->vpn.ipv4.dns_server = getIPv4Pref(pd->env, pd->capture_service, "getDnsServer");
    pd->vpn.ipv4.internal_dns = getIPv4Pref(pd->env, pd->capture_service, "getVpnDns");

    pd->vpn.ipv6.enabled = (bool) getIntPref(pd->env, pd->capture_service, "getIPv6Enabled");
    pd->vpn.ipv6.dns_server = getIPv6Pref(pd->env, pd->capture_service, "getIpv6DnsServer");
#endif

    zdtun_callbacks_t callbacks = {
        .send_client = remote2vpn,
        .account_packet = update_conn_status,
        .on_socket_open = protectSocketCallback,
        .on_connection_open = handle_new_connection,
        .on_connection_close = connection_closed,
    };

    load_dns_servers(pd);

    zdt = zdtun_init(&callbacks, pd);
    if(zdt == NULL) {
        log_f("zdtun_init failed");
        return(-2);
    }

#if ANDROID
    zdtun_set_mtu(zdt, getIntPref(pd->env, pd->capture_service, "getVpnMTU"));
#endif

    pd->zdt = zdt;
    new_dns_server = 0;

    if(pd->socks5.enabled) {
        zdtun_set_socks5_proxy(zdt, &pd->socks5.proxy_ip, pd->socks5.proxy_port, pd->socks5.proxy_ipver);

        if(pd->socks5.proxy_user[0] && pd->socks5.proxy_pass[0])
            zdtun_set_socks5_userpass(zdt, pd->socks5.proxy_user, pd->socks5.proxy_pass);
    }

    pd_refresh_time(pd);
    next_purge_ms = pd->now_ms + PERIODIC_PURGE_TIMEOUT_MS;

    log_i("Starting packet loop");
    if(pd->cb.notify_service_status && running)
        pd->cb.notify_service_status(pd, "started");

    while(running) {
        int max_fd;
        fd_set fdset;
        fd_set wrfds;
        int size;
        struct timeval timeout = {.tv_sec = 0, .tv_usec = SELECT_TIMEOUT_MS * 1000};

        zdtun_fds(zdt, &max_fd, &fdset, &wrfds);

        FD_SET(pd->vpn.tunfd, &fdset);
        max_fd = max(max_fd, pd->vpn.tunfd);

        if((select(max_fd + 1, &fdset, &wrfds, NULL, &timeout) < 0) && (errno != EINTR)) {
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

                bool is_internal_dns = pd->vpn.ipv4.enabled && (pkt.tuple.ipver == 4) && (pkt.tuple.dst_ip.ip4 == pd->vpn.ipv4.internal_dns);
                if(is_internal_dns && ntohs(pkt.tuple.dst_port) == 853) {
                    // accepting this packet could result in multiple TCP connections being spammed
                    log_d("discarding private DNS packet directed to internal DNS");
                    goto housekeeping;
                }

                if(((pkt.tuple.ipver == 6) && !pd->vpn.ipv6.enabled) ||
                        ((pkt.tuple.ipver == 4) && !pd->vpn.ipv4.enabled)) {
                    char buf[512];

                    log_d("ignoring IPv%d packet: %s", pkt.tuple.ipver,
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

                // Process the packet
                struct timeval tv;
                const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn);
                pkt_context_t pctx;
                pd_conn_t *data = zdtun_conn_get_userdata(conn);

                // To be run before pd_process_packet/process_payload
                if(data->sent_pkts == 0) {
                    if(pd_check_port_map(conn))
                        data->port_mapping_applied = true;
                    else if(should_proxify(pd, tuple, data)) {
                        zdtun_conn_proxy(conn);
                        data->proxied = true;
                    }
                }

                pd_process_packet(pd, &pkt, true, tuple, data, get_pkt_timestamp(pd, &tv), &pctx);
                if(data->sent_pkts == 0) {
                    // Newly created connections
                    if (!data->port_mapping_applied)
                        data->blacklisted_internal |= !check_dns_req_allowed(pd, conn, &pctx);
                    data->to_block |= data->blacklisted_internal;

                    if(data->to_block) {
                        // blocking a DNS query can cause multiple requests to be spammed. Better to
                        // spoof a reply with an invalid IP.
                        if((data->l7proto == NDPI_PROTOCOL_DNS) && (tuple->ipproto == IPPROTO_UDP)) {
                            spoof_dns_reply(pd, conn, &pctx);
                            zdtun_conn_close(zdt, conn, CONN_STATUS_CLOSED);
                        }
                    }
                }

                if(data->to_block) {
                    data->blocked_pkts++;
                    data->update_type |= CONN_UPDATE_STATS;
                    pd_notify_connection_update(pd, tuple, data);
                    goto housekeeping;
                }

                // NOTE: zdtun_forward will call remote2vpn
                data->vpn.fw_pctx = &pctx;
                if(zdtun_forward(zdt, &pkt, conn) != 0) {
                    char buf[512];
                    zdtun_conn_status_t status = zdtun_conn_get_status(conn);

                    if(status != CONN_STATUS_UNREACHABLE) {
                        log_e("zdtun_forward failed[%d]: %s", status,
                              zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));

                        pd->num_dropped_connections++;
                    } else
                        log_w("%s: net/host unreachable", zdtun_5tuple2str(&pkt.tuple, buf, sizeof(buf)));

                    zdtun_conn_close(zdt, conn, CONN_STATUS_ERROR);
                    goto housekeeping;
                } else {
                    // zdtun_forward was successful
                    if(data->vpn.fw_pctx) {
                        // it was not accounted in remote2vpn, account here
                        pd_account_stats(pd, data->vpn.fw_pctx);
                        data->vpn.fw_pctx = NULL;
                    }

                    // First forwarded packet
                    if(data->sent_pkts == 1) {
                        // The socket is open only after zdtun_forward is called
                        socket_t sock = zdtun_conn_get_socket(conn);

                        // In SOCKS5 with the MitmReceiver, we need the local port to the SOCKS5 proxy
                        if((sock != INVALID_SOCKET) && (tuple->ipver == 4)) {
                            // NOTE: the zdtun SOCKS5 implementation only supports IPv4 right now.
                            // If it also supported IPv6, than we would need to expose "sock_ipver"
                            struct sockaddr_in local_addr;
                            socklen_t addrlen = sizeof(local_addr);

                            if(getsockname(sock, (struct sockaddr*) &local_addr, &addrlen) == 0)
                                data->vpn.local_port = local_addr.sin_port;
                        }
                    }
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

    pd_reset_port_map();
    zdtun_finalize(zdt);

#if ANDROID
    destroy_uid_resolver(pd->vpn.resolver);
    blacklist_destroy(pd->vpn.known_dns_servers);
#endif

    return(0);
}


