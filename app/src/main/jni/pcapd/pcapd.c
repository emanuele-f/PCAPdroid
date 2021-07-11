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

/*
 * A daemon to capture network packets and send them to a UNIX socket.
 * When running as daemon, the PCAPD_PID file is created, storing the the daemon pid. It is
 * automatically deleted when the daemon exits. The daemon captures the packets from the internet
 * interface (the interface of the default gateway) and detects its changes.
 * The daemon expects the PCAPD_SOCKET_PATH UNIX socket to be present. It will connect and
 * start dumping the packets to it. When the socket is closed, the daemon exits.
 */

#include <linux/netlink.h>
#include <linux/rtnetlink.h>

#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <linux/if_ether.h>
#include <linux/ip.h>
#include <net/if.h>
#include <time.h>
#include <pcap.h>
#include <pcap/sll.h>
#include "pcapd.h"
#include "nl_utils.h"
#include "common/uid_resolver.h"
#include "common/uid_lru.h"
#include "common/utils.h"
#include "zdtun.h"

//#define READ_FROM_PCAP "/sdcard/test.pcap"

/* ******************************************************* */

typedef struct {
    int nlsock;
    int client;

    char bpf[512];
    zdtun_t *tun;
    pcap_t *pd;
    int dlink;
    int ipoffset;
    int pf;
    int ifidx;
    char ifname[IFNAMSIZ];
    uint64_t mac;
    uint32_t ip;
    struct in6_addr ip6;

    char nlbuf[8192]; /* >= 8192 to avoid truncation, see "man 7 netlink" */
} pcapd_runtime_t;

static char errbuf[PCAP_ERRBUF_SIZE];
static FILE *logf = NULL;
static uint8_t no_logf = 0;

/* ******************************************************* */

static uint64_t bytes2mac(const uint8_t *buf) {
  uint64_t m = 0;

  memcpy(&m, buf, 6);

  return m;
}

/* ******************************************************* */

static int str2mac(const char *buf, uint64_t *mac) {
    uint8_t mac_bytes[6];
    int m[6] = {0};

    if(sscanf(buf, "%02X:%02X:%02X:%02X:%02X:%02X", m+0, m+1, m+2, m+3, m+4, m+5) != 6)
        return -1;

    for(int i = 0; i < 6; i++)
        mac_bytes[i] = m[i];

    *mac = bytes2mac(mac_bytes);
    return 0;
}

/* ******************************************************* */

static void log_to_file(int lvl, const char *msg) {
  char datetime[64];
  struct tm res;
  time_t now;

  if(no_logf)
    return;

  if(logf == NULL) {
    mode_t old_mask = umask(033);
    logf = fopen(PCAPD_LOGFILE_PATH, "w");
    umask(old_mask);

    if(logf == NULL) {
      log_e("Could not open log file[%d]: %s", errno, strerror(errno));
      no_logf = 1;
      return;
    }
  }

  now = time(NULL);
  strftime(datetime, sizeof(datetime), "%d/%b/%Y %H:%M:%S", localtime_r(&now, &res));

  fprintf(logf, "[%c] %s - %s\n", loglvl2char(lvl), datetime, msg);
  fflush(logf);
}

/* ******************************************************* */

static int get_iface_mac(const char *iface, uint64_t *mac) {
  char fpath[128];
  char buf[24];

  // avoid using ioctl as it sometimes triggers SELINUX errors
  snprintf(fpath, sizeof(fpath), "/sys/class/net/%s/address", iface);

  FILE *f = fopen(fpath, "r");

  if(f == NULL)
    return -1;

  buf[0] = '\0';
  fgets(buf, sizeof(buf), f);
  fclose(f);

  if(str2mac(buf, mac) != 0)
      return -1;

  return(0);
}

/* ******************************************************* */

static int get_iface_mtu(const char *iface) {
  char fpath[128];
  int mtu = -1;

  snprintf(fpath, sizeof(fpath), "/sys/class/net/%s/mtu", iface);

  FILE *f = fopen(fpath, "r");

  if(f == NULL)
    return -1;

  fscanf(f, "%d", &mtu);
  fclose(f);

  return mtu;
}

/* ******************************************************* */

static int get_iface_ip(const char *iface, uint32_t *ip, uint32_t *netmask) {
  struct ifreq ifr;
  int fd;
  int rv;

  fd = socket(AF_INET, SOCK_DGRAM, 0);

  ifr.ifr_addr.sa_family = AF_INET;
  strncpy((char *)ifr.ifr_name, iface, IFNAMSIZ-1);

  if((rv = ioctl(fd, SIOCGIFADDR, &ifr)) != -1) {
    *ip = ((struct sockaddr_in *)&ifr.ifr_addr)->sin_addr.s_addr;

    if((rv = ioctl(fd, SIOCGIFNETMASK, &ifr)) != -1)
      *netmask = ((struct sockaddr_in *)&ifr.ifr_addr)->sin_addr.s_addr;
  }

  close(fd);
  return(rv);
}

/* ******************************************************* */

static int get_iface_ip6(const char *iface, struct in6_addr *ip) {
  FILE *f = fopen("/proc/net/if_inet6", "r");
  char line[128];
  int found = 0;

  if(f == NULL)
    return -1;

  __be32 *ip6 = ip->in6_u.u6_addr32;

  while(fgets(line, sizeof(line), f)) {
    if((strstr(line, iface) != NULL) &&
            (sscanf(line, "%08x%08x%08x%08x", &ip6[0], &ip6[1], &ip6[2], &ip6[3]) == 4)) {
      for(int i=0; i<4; i++)
        ip6[i] = htonl(ip6[i]);

      if((ip->in6_u.u6_addr8[0] == 0xfe) &&
         ((ip->in6_u.u6_addr8[1] & 0xC0) == 0x80)) // link local address
        continue;

      char addr[INET6_ADDRSTRLEN];
      inet_ntop(AF_INET6, ip, addr, INET6_ADDRSTRLEN);

      log_d("IPv6 address[%s]: %s", iface, addr);
      found = 1;
      break;
    }
  }

  fclose(f);
  return(found ? 0 : -1);
}

/* ******************************************************* */

static void sighandler(__unused int signo) {
  log_i("SIGTERM received, terminating");
  unlink(PCAPD_PID);

  if(logf)
    fclose(logf);

  exit(0);
}

/* ******************************************************* */

static int create_pid_file() {
  FILE *f = fopen(PCAPD_PID, "w");

  if(!f)
    return -1;

  fprintf(f, "%d\n", getpid());
  fclose(f);

  return 0;
}

/* ******************************************************* */

static void finish_pcapd_capture(pcapd_runtime_t *rt) {
  if(rt->client)
    close(rt->client);
  if(rt->pd)
    pcap_close(rt->pd);
  if(rt->nlsock > 0)
    close(rt->nlsock);

  unlink(PCAPD_PID);
}

/* ******************************************************* */

static int init_pcapd_capture(pcapd_runtime_t *rt) {
  // daemonize
  int pid = fork();

  if(pid < 0) {
    fprintf(stderr, "fork failed[%d]: %s\n", errno, strerror(errno));
    return -1;
  } else if(pid != 0) {
    // parent
    exit(0);
  }

  rt->nlsock = -1;
  rt->client = -1;
  rt->pd = NULL;
  rt->pf = -1;
  rt->ifidx = -1;
  rt->ifname[0] = '\0';
  rt->mac = 0;
  rt->ip = 0;

  if(create_pid_file() < 0) {
    log_e("pid file creation failed[%d]: %s", errno, strerror(errno));
    finish_pcapd_capture(rt);
    return -1;
  }

  rt->nlsock = nl_socket(RTMGRP_IPV4_ROUTE | RTMGRP_IPV4_IFADDR | RTMGRP_IPV4_RULE |
                                 RTMGRP_IPV6_ROUTE | RTMGRP_IPV6_IFADDR | RTMGRP_LINK);

  if(rt->nlsock < 0) {
    log_e("could not create netlink socket[%d]: %s", errno, strerror(errno));
    finish_pcapd_capture(rt);
    return -1;
  }

  rt->client = socket(AF_UNIX, SOCK_STREAM, 0);

  if(rt->client < 0) {
    log_e("socket creation failed[%d]: %s", errno, strerror(errno));
    finish_pcapd_capture(rt);
    return -1;
  }

  signal(SIGTERM, &sighandler);

  struct sockaddr_un addr;
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  strcpy(addr.sun_path, PCAPD_SOCKET_PATH);

  log_i("Connecting to client...");

  if(connect(rt->client, (struct sockaddr*) &addr, sizeof(addr)) != 0) {
    log_e("client connection failed[%d]: %s", errno, strerror(errno));
    finish_pcapd_capture(rt);
    return -1;
  }

  log_i("Connected to client");
  unlink(PCAPD_SOCKET_PATH);

  return 0;
}

/* ******************************************************* */

static void check_capture_interface(pcapd_runtime_t *rt) {
  addr_t pubaddr = {.v4 = 0x60060606}; // arbitrary IPv4 public address
  route_info_t ri = {.ifidx = -1};
  char ifname[IFNAMSIZ];

  if((nl_get_route(AF_INET, &pubaddr, &ri) < 0) || (ri.ifidx == rt->ifidx)) {
    //log_i("check_capture_interface: nope [%s] - %d", rt->ifname, ri.ifidx);
    return;
  }

  if(if_indextoname(ri.ifidx, ifname) == NULL) {
    log_i("could not get ifidx %d ifname", ri.ifidx);
    return;
  }

  log_i("interface changed [%d -> %d], (re)starting capture", rt->ifidx, ri.ifidx);

#ifndef READ_FROM_PCAP
  int mtu = get_iface_mtu(ifname);

  if(mtu < 0) {
    mtu = 1500;
    log_w("Could not get %s MTU, assuming %d", ifname, mtu);
  }

  /* The snaplen includes the datalink overhead. Max datalink overhead (SLL): 16 B */
  int snaplen = mtu + SLL_HDR_LEN;
  log_d("Using a %d snaplen (MTU %d)", snaplen, mtu);

  pcap_t *pd = pcap_open_live(ifname, snaplen, 0, 1, errbuf);

  if(!pd) {
    log_i("pcap_open_live(%s) failed: %s", ifname, errbuf);
    return;
  }

  // Fixes pcap_next_ex sometimes hanging on interface down
  // https://github.com/the-tcpdump-group/libpcap/issues/899
  pcap_setnonblock(pd, 1, errbuf);
#else
  pcap_t *pd = pcap_open_offline(READ_FROM_PCAP, errbuf);

  if(!pd) {
    log_i("pcap_open_offline(%s) failed: %s", READ_FROM_PCAP, errbuf);
    return;
  }

  strcpy(ifname, "pcap");
#endif

  int dlink = pcap_datalink(pd);
  int ipoffset;

  switch(dlink) {
    case DLT_RAW:
      ipoffset = 0;
      break;
    case DLT_EN10MB:
      ipoffset = 14;
      break;
    case DLT_LINUX_SLL:
      ipoffset = SLL_HDR_LEN;
      break;
    default:
      log_i("[%s] unsupported datalink: %d", ifname, dlink);
      pcap_close(pd);
      return;
  }

  struct bpf_program fcode;

  // Only IP traffic
  if(pcap_compile(pd, &fcode, rt->bpf, 1, PCAP_NETMASK_UNKNOWN) < 0) {
    log_i("[%s] could not set capture filter: %s", ifname, pcap_geterr(pd));
    pcap_close(pd);
    return;
  }

  if(pcap_setfilter(pd, &fcode) < 0) {
    log_e("[%s] pcap_setfilter failed: %s", ifname, pcap_geterr(pd));
    pcap_freecode(&fcode);
    pcap_close(pd);
    return;
  }

  pcap_freecode(&fcode);

  // Success
  if(rt->pd)
    pcap_close(rt->pd);
  rt->pd = pd;

  rt->mac = 0;
  if((dlink == DLT_EN10MB) && (get_iface_mac(ifname, &rt->mac) < 0))
    log_i("Could not get interface %s MAC[%d]: %s", ifname, errno, strerror(errno));

  uint32_t netmask;
  rt->ip = 0;
  if(get_iface_ip(ifname, &rt->ip, &netmask) < 0)
    log_i("Could not get interface %s IP[%d]: %s", ifname, errno, strerror(errno));

  if(get_iface_ip6(ifname, &rt->ip6) < 0) {
    log_i("Could not get interface %s IPv6[%d]: %s", ifname, errno, strerror(errno));
    memset(&rt->ip6, 0, sizeof(rt->ip6));
  }

  rt->dlink = dlink;
  rt->ipoffset = ipoffset;
  rt->pf = pcap_get_selectable_fd(pd);
  rt->ifidx = ri.ifidx;
  memcpy(rt->ifname, ifname, sizeof(ifname));

  log_i("Capturing packets from %s", ifname);
}

/* ******************************************************* */

static void reset_capture_interface(pcapd_runtime_t *rt) {
  if(!rt->pd)
    return;

  pcap_close(rt->pd);
  rt->pd = NULL;
  rt->pf = -1;
  rt->ifidx = -1;
  rt->ifname[0] = '\0';
}

/* ******************************************************* */

static int handle_nl_message(pcapd_runtime_t *rt) {
  struct iovec iov = {
    .iov_base = rt->nlbuf,
    .iov_len = sizeof(rt->nlbuf)
  };

  struct sockaddr_nl snl;
  struct msghdr msg = {
    .msg_name = (void *)&snl,
    .msg_namelen = sizeof(snl),
    .msg_iov = &iov,
    .msg_iovlen = 1
  };

  ssize_t len = recvmsg(rt->nlsock, &msg, 0);
  uint8_t found = 0;

#ifdef READ_FROM_PCAP
  return 0;
#endif

  if(len <= 0) {
    log_e("netlink recvmsg failed [%d]: %s\n", errno, sizeof(errno));
    return -1;
  }

  for(struct nlmsghdr *nh = (struct nlmsghdr *)rt->nlbuf; NLMSG_OK(nh, len); nh = NLMSG_NEXT(nh, len)) {
    uint8_t do_break = 0;

    switch(nh->nlmsg_type) {
      case NLMSG_DONE:
        do_break = 1;
        break;
      case RTM_NEWROUTE:
      case RTM_NEWRULE:
        found = 1;
        do_break = 1;
        break;
      case RTM_NEWADDR:
        if(rt->ifidx == ((struct ifaddrmsg *) NLMSG_DATA(nh))->ifa_index) {
          log_i("Detected possible IP address change");

          uint32_t netmask;
          if(get_iface_ip(rt->ifname, &rt->ip, &netmask) < 0)
            log_i("Could not get interface %s IP[%d]: %s", rt->ifname, errno, strerror(errno));

          if(get_iface_ip6(rt->ifname, &rt->ip6) < 0) {
            log_i("Could not get interface %s IPv6[%d]: %s", rt->ifname, errno, strerror(errno));
            memset(&rt->ip6, 0, sizeof(rt->ip6));
          }
        }
        break;
      case RTM_DELLINK:
        if(rt->ifidx == ((struct ifinfomsg *) NLMSG_DATA(nh))->ifi_index) {
          // libpcap sometimes does not detect that an interface was removed. Making it necessary
          // to subscribe to RTMGRP_LINK
          log_i("RTM_DELLINK: interface %s deleted", rt->ifname);
          reset_capture_interface(rt);
          found = 1;
          do_break = 1;
        }
        break;
    }

    if(do_break)
      break;
  }

  if(found)
    check_capture_interface(rt);

  return 0;
}

/* ******************************************************* */

// try to determine the packet direction as it is only available in SLL / SLL2 ("any" interface)
static int is_tx_packet(pcapd_runtime_t *rt, const u_char *pkt, u_int16_t len) {
  // TODO check for broadcast / multicast
  if((rt->dlink == DLT_EN10MB) && (len >= 14)) {
    // Ethernet header present
    struct ethhdr *eth = (struct ethhdr *) pkt;
    uint64_t smac = bytes2mac(eth->h_source);
    uint64_t dmac = bytes2mac(eth->h_dest);

    if(smac != dmac) {
      if(smac == rt->mac)
        return 1; // TX
      else if(dmac == rt->mac)
        return 0; // RX
    }

    len -= 14;
    pkt += 14;
  } else if((rt->dlink == DLT_LINUX_SLL) && (len >= SLL_HDR_LEN)) {
    struct sll_header *sll = (struct sll_header*) pkt;
    uint16_t pkttype = ntohs(sll->sll_pkttype);

    if(pkttype == LINUX_SLL_HOST)
      return 0; // RX
    else if(pkttype == LINUX_SLL_OUTGOING)
      return 1; // TX

    len -= SLL_HDR_LEN;
    pkt += SLL_HDR_LEN;
  }

  // NOTE: this must be IPv4/IPv6 traffic due to the PCAP filter
  if(len < 20)
    return 0;

  struct iphdr *ip = (struct iphdr *) pkt;

  if(ip->version == 4) {
    if(ip->daddr == rt->ip)
      return 0; // RX
  } else if(ip->version == 6) {
    struct ipv6hdr *hdr = (struct ipv6hdr *) pkt;

    if(memcmp(&hdr->daddr, &rt->ip6, sizeof(rt->ip6)) == 0)
      return 0; // RX
  }

  return 1; // by default assume TX
}

/* ******************************************************* */

static int run_pcap_dump(int uid_filter, const char *bpf) {
  int rv = -1;
  struct timespec ts = {0};
  struct pcap_stat stats = {0};
  pcapd_runtime_t rt = {0};
  time_t next_interface_recheck = 0;
  time_t next_stats_update = 0;
  uid_resolver_t *resolver = NULL;
  uid_lru_t *lru = NULL;
  zdtun_callbacks_t callbacks = {.send_client = (void*)1};

  if(!(rt.tun = zdtun_init(&callbacks, NULL)))
    goto cleanup;

  if(!(resolver = init_uid_resolver_from_proc()))
    goto cleanup;

  if(!(lru = uid_lru_init(64)))
    goto cleanup;

  if(init_pcapd_capture(&rt) < 0)
    goto cleanup;

  int l = snprintf(rt.bpf, sizeof(rt.bpf), "ip or ip6");

  if(bpf[0])
    snprintf(rt.bpf + l, sizeof(rt.bpf) - l, " and (%s)", bpf);

  log_d("Using BPF: %s", rt.bpf);

  rt.pf = -1;
  rt.ifidx = -1;
  check_capture_interface(&rt);
  rv = 0;

  while(1) {
    struct timeval timeout = {.tv_sec = 1, .tv_usec = 0};
    fd_set fds = {0};
    int maxfd = (rt.client > rt.nlsock) ? rt.client : rt.nlsock;

    FD_SET(rt.client, &fds);
    FD_SET(rt.nlsock, &fds);

    if(rt.pf != -1) {
      FD_SET(rt.pf, &fds);
      maxfd = (maxfd > rt.pf) ? maxfd : rt.pf;
    }

    if(select(maxfd + 1, &fds, NULL, NULL, &timeout) < 0) {
      log_e("select failed[%d]: %s", errno, strerror(errno));
      rv = -1;
      break;
    }

    if(FD_ISSET(rt.client, &fds)) {
      log_i("Client closed");
      break;
    }
    if(FD_ISSET(rt.nlsock, &fds)) {
      if(handle_nl_message(&rt) < 0) {
        rv = -1;
        break;
      }
    }
    if((rt.pf != -1) && FD_ISSET(rt.pf, &fds)) {
      struct pcap_pkthdr *hdr;
      const u_char *pkt;
      int to_skip = rt.ipoffset;
      int rv1 = pcap_next_ex(rt.pd, &hdr, &pkt);

      if(rv1 == PCAP_ERROR) {
        log_i("pcap_next_ex failed: %s", pcap_geterr(rt.pd));

        // Do not abort, just wait for route changes
        reset_capture_interface(&rt);
      } else if((rv1 == 1) && (hdr->caplen >= to_skip)) {
        pcapd_hdr_t phdr;
        zdtun_pkt_t zpkt;
        uint8_t is_tx = is_tx_packet(&rt, pkt, hdr->caplen);

        if(hdr->caplen < hdr->len)
          log_w("Packet truncated: %d/%d", hdr->caplen, hdr->len);

        pkt += to_skip;
        hdr->caplen -= to_skip;

        if(zdtun_parse_pkt(rt.tun, (const char*)pkt, hdr->caplen, &zpkt) == 0) {
          if(!is_tx) {
            // Packet from the internet, swap src and dst
            tupleSwapPeers(&zpkt.tuple);
          }

          int uid = uid_lru_find(lru, &zpkt.tuple);

          if(uid == -2) {
            uid = get_uid(resolver, &zpkt.tuple);
            uid_lru_add(lru, &zpkt.tuple, uid);
          }

          if((uid_filter == -1) || (uid_filter == uid)) {
            phdr.ts = hdr->ts;
            phdr.len = hdr->caplen;
            phdr.pkt_drops = stats.ps_drop;
            phdr.uid = uid;
            phdr.flags = is_tx ? PCAPD_FLAG_TX : 0;

            // Send the pcapd_hdr_t first, then the packet data. The packet data always starts with
            // the IP header.
            if((xwrite(rt.client, &phdr, sizeof(phdr)) < 0) ||
               (xwrite(rt.client, pkt, phdr.len) < 0)) {
              log_e("write failed[%d]: %s", errno, strerror(errno));
              rv = -1;
              break;
            }
          }
        }
      }
    }

    clock_gettime(CLOCK_MONOTONIC_COARSE, &ts);
    time_t now = ts.tv_sec;

    if((rt.pd == NULL) && (now >= next_interface_recheck)) {
      check_capture_interface(&rt);
      next_interface_recheck = now + 2;
    } else if((rt.pd != NULL) && (now >= next_stats_update)) {
      pcap_stats(rt.pd, &stats);
      next_stats_update = now + 3;
    }
  }

  log_i("Terminating...");

cleanup:
  finish_pcapd_capture(&rt);

  if(rt.tun)
    zdtun_finalize(rt.tun);
  if(resolver)
    destroy_uid_resolver(resolver);
  if(lru)
    uid_lru_destroy(lru);

  return rv;
}

/* ******************************************************* */

static void usage() {
  fprintf(stderr, "pcapd - root companion for PCAPdroid\n"
                  "Copyright 2021 Emanuele Faranda <black.silver@hotmail.it>\n\n"
                  "Usage: pcapd -d uid [-b bpf]\n"
                  " -d [uid]       daemonize and dump packets from the internet interface, filtered by uid (-1 for no filter)\n"
                  " -b [bpf]       specify a BPF filter to apply"
  );

  exit(1);
}

/* ******************************************************* */

int main(int argc, char *argv[]) {
  logtag = "pcapd";
  logcallback = log_to_file;
  int uid_filter;
  char *bpf = "";

  if((argc < 3) || (strcmp(argv[1], "-d") != 0))
    usage();

  uid_filter = atoi(argv[2]);

  if((argc == 5) && (strcmp(argv[3], "-b") == 0))
    bpf = argv[4];

  return run_pcap_dump(uid_filter, bpf);
}
