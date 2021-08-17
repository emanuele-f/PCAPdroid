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

//#define LOCAL_DEBUG
//#define READ_FROM_PCAP "/sdcard/test.pcap"
#define MAX_IFACES 16

/* ******************************************************* */

typedef struct {
  char *ifnames[MAX_IFACES];
  char *bpf;
  char *log_file;
  uid_t uid_filter;
  int num_interfaces;
  int daemonize;
  int inet_ifid;
} pcapd_conf_t;

typedef struct {
    char name[IFNAMSIZ];
    int ifidx;
    uint8_t ifid;       // positional interface index
    pcap_t *pd;
    int pf;
    int dlink;
    int ipoffset;
    uint64_t mac;
    uint32_t ip;
    struct in6_addr ip6;
    time_t next_stats_update;
    struct pcap_stat stats;
} pcapd_iface_t;

typedef struct {
    char bpf[512];
    char nlbuf[8192]; /* >= 8192 to avoid truncation, see "man 7 netlink" */

    int nlsock;
    int client;

    zdtun_t *tun;
    uid_lru_t *lru;
    uid_resolver_t *resolver;
    pcapd_iface_t *inet_iface;
    pcapd_iface_t ifaces[MAX_IFACES];
    int maxfd;
    pcapd_conf_t *conf;
} pcapd_runtime_t;

static void init_interface(pcapd_iface_t *iface);
static void close_interface(pcapd_iface_t *iface);

/* ******************************************************* */

static char errbuf[PCAP_ERRBUF_SIZE];
static FILE *logf = NULL;
static sig_atomic_t running;

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

static void logcb(int lvl, const char *msg) {
  char datetime[64];
  struct tm res;
  time_t now;

  now = time(NULL);
  strftime(datetime, sizeof(datetime), "%d/%b/%Y %H:%M:%S", localtime_r(&now, &res));

  fprintf(stderr, "[%c] %s - %s\n", loglvl2char(lvl), datetime, msg);

  if(!logf)
    return;

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
  if(running) {
    log_i("Signal received, terminating");
    running = 0;
  } else {
    log_w("Exit now");
    unlink(PCAPD_PID);
    exit(1);
  }
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
  if(rt->client > 0)
    close(rt->client);
  if(rt->nlsock > 0)
    close(rt->nlsock);
  if(rt->lru)
    uid_lru_destroy(rt->lru);
  if(rt->resolver)
    destroy_uid_resolver(rt->resolver);

  for(int i=0; i<rt->conf->num_interfaces; i++)
    close_interface(&rt->ifaces[i]);

  unlink(PCAPD_PID);
}

/* ******************************************************* */

static int init_pcapd_capture(pcapd_runtime_t *rt, pcapd_conf_t *conf) {
  if(conf->daemonize) {
    int pid = fork();

    if(pid < 0) {
      fprintf(stderr, "fork failed[%d]: %s\n", errno, strerror(errno));
      return -1;
    } else if(pid != 0) {
      // parent
      exit(0);
    }
  }

  rt->nlsock = -1;
  rt->client = -1;
  rt->conf = conf;

  if(!(rt->lru = uid_lru_init(64)))
    goto err;

  if(!(rt->resolver = init_uid_resolver_from_proc()))
    goto err;

  for(int i=0; i<conf->num_interfaces; i++)
    init_interface(&rt->ifaces[i]);

  if(conf->inet_ifid != -1)
    rt->inet_iface = &rt->ifaces[conf->inet_ifid];

  if(create_pid_file() < 0) {
    log_e("pid file creation failed[%d]: %s", errno, strerror(errno));
    goto err;
  }

  rt->nlsock = nl_socket(RTMGRP_IPV4_ROUTE | RTMGRP_IPV4_IFADDR | RTMGRP_IPV4_RULE |
                                 RTMGRP_IPV6_ROUTE | RTMGRP_IPV6_IFADDR | RTMGRP_LINK);
  if(rt->nlsock < 0) {
    log_e("could not create netlink socket[%d]: %s", errno, strerror(errno));
    goto err;
  }
  rt->maxfd = max(rt->maxfd, rt->nlsock);

  signal(SIGINT, &sighandler);
  signal(SIGTERM, &sighandler);
  signal(SIGHUP, &sighandler);

#ifndef LOCAL_DEBUG
  rt->client = socket(AF_UNIX, SOCK_STREAM, 0);
  if(rt->client < 0) {
    log_e("socket creation failed[%d]: %s", errno, strerror(errno));
    goto err;
  }
  rt->maxfd = max(rt->maxfd, rt->client);

  struct sockaddr_un addr;
  memset(&addr, 0, sizeof(addr));
  addr.sun_family = AF_UNIX;
  strcpy(addr.sun_path, PCAPD_SOCKET_PATH);

  log_i("Connecting to client...");

  if(connect(rt->client, (struct sockaddr*) &addr, sizeof(addr)) != 0) {
    log_e("client connection failed[%d]: %s", errno, strerror(errno));
    goto err;
  }

  log_i("Connected to client");
  unlink(PCAPD_SOCKET_PATH);
#endif

  return 0;

err:
  finish_pcapd_capture(rt);
  return -1;
}

/* ******************************************************* */

static void init_interface(pcapd_iface_t *iface) {
  memset(iface, 0, sizeof(pcapd_iface_t));
  iface->pf = -1;
  iface->ifidx = -1;
}

/* ******************************************************* */

static int open_interface(pcapd_iface_t *iface, pcapd_runtime_t *rt, const char *ifname, int ifid) {
#ifndef READ_FROM_PCAP
  int mtu = get_iface_mtu(ifname);

  if(mtu < 0) {
    mtu = 1500;
    log_w("Could not get \"%s\" interface MTU, assuming %d", ifname, mtu);
  }

  /* The snaplen includes the datalink overhead. Max datalink overhead (SLL): 16 B */
  int snaplen = mtu + SLL_HDR_LEN;
  log_d("Using a %d snaplen (MTU %d)", snaplen, mtu);

  pcap_t *pd = pcap_open_live(ifname, snaplen, 0, 1, errbuf);

  if(!pd) {
    log_i("pcap_open_live(%s) failed: %s", ifname, errbuf);
    return -1;
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
      return -1;
  }

  struct bpf_program fcode;

  // Only IP traffic
  if(pcap_compile(pd, &fcode, rt->bpf, 1, PCAP_NETMASK_UNKNOWN) < 0) {
    log_i("[%s] could not set capture filter: %s", ifname, pcap_geterr(pd));
    pcap_close(pd);
    return -1;
  }

  if(pcap_setfilter(pd, &fcode) < 0) {
    log_e("[%s] pcap_setfilter failed: %s", ifname, pcap_geterr(pd));
    pcap_freecode(&fcode);
    pcap_close(pd);
    return -1;
  }
  pcap_freecode(&fcode);

  // Success
  iface->pd = pd;
  iface->mac = 0;
  iface->ifid = ifid;
  iface->ifidx = if_nametoindex(ifname);

  errno = 0;
  if((dlink == DLT_EN10MB) && (get_iface_mac(ifname, &iface->mac) < 0))
    log_i("Could not get interface \"%s\" MAC[%d]: %s", ifname, errno, strerror(errno));

  uint32_t netmask;
  iface->ip = 0;
  if(get_iface_ip(ifname, &iface->ip, &netmask) < 0)
    log_i("Could not get interface \"%s\" IP[%d]: %s", ifname, errno, strerror(errno));

  if(get_iface_ip6(ifname, &iface->ip6) < 0) {
    log_i("Could not get interface \"%s\" IPv6[%d]: %s", ifname, errno, strerror(errno));
    memset(&iface->ip6, 0, sizeof(iface->ip6));
  }

  iface->dlink = dlink;
  iface->ipoffset = ipoffset;
  iface->pf = pcap_get_selectable_fd(pd);
  rt->maxfd = max(rt->maxfd, iface->pf);
  strncpy(iface->name, ifname, IFNAMSIZ);
  iface->name[IFNAMSIZ - 1] = '\0';

  return 0;
}

/* ******************************************************* */

static void close_interface(pcapd_iface_t *iface) {
  if(!iface->pd)
    return;

  pcap_close(iface->pd);
  iface->pd = NULL;
  iface->pf = -1;
  iface->ifidx = -1;
  iface->name[0] = '\0';
}

/* ******************************************************* */

static void check_inet_interface(pcapd_runtime_t *rt) {
  addr_t pubaddr = {.v4 = 0x60060606}; // arbitrary IPv4 public address
  route_info_t ri = {.ifidx = -1};
  char ifname[IFNAMSIZ];

  if(!rt->inet_iface)
    return;

  if((nl_get_route(AF_INET, &pubaddr, &ri) < 0) || (ri.ifidx == rt->inet_iface->ifidx)) {
    //log_i("check_inet_interface: nope [%s] - %d", rt->ifname, ri.ifidx);
    return;
  }

  if(if_indextoname(ri.ifidx, ifname) == NULL) {
    log_i("could not get ifidx %d ifname", ri.ifidx);
    return;
  }

  log_i("Internet interface changed [%d -> %d], (re)starting capture", rt->inet_iface->ifidx, ri.ifidx);

  pcap_t *old_pd = rt->inet_iface->pd;

  if(open_interface(rt->inet_iface, rt, ifname, rt->conf->inet_ifid) < 0)
    return;

  // Success
  if(old_pd)
    pcap_close(old_pd);

  log_i("\"%s\" is the new internet interface", ifname);
}

/* ******************************************************* */

static pcapd_iface_t* iface_by_ifidx(pcapd_runtime_t *rt, int ifidx) {
  for(int i=0; i<rt->conf->num_interfaces; i++) {
    if(rt->ifaces[i].ifidx == ifidx)
      return &rt->ifaces[i];
  }

  return NULL;
}

/* ******************************************************* */

static int handle_nl_message(pcapd_runtime_t *rt) {
  pcapd_iface_t *iface;

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
  uint8_t recheck_inet = 0;

#ifdef READ_FROM_PCAP
  return 0;
#endif

  if(len <= 0) {
    log_e("netlink recvmsg failed [%d]: %s\n", errno, strerror(errno));
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
        recheck_inet = 1;
        break;
      case RTM_NEWADDR:
        if((iface = iface_by_ifidx(rt, ((struct ifaddrmsg *) NLMSG_DATA(nh))->ifa_index)) != NULL) {
          log_i("Detected possible IP address change");

          uint32_t netmask;
          if(get_iface_ip(iface->name, &iface->ip, &netmask) < 0)
            log_i("Could not get interface \"%s\" IP[%d]: %s", iface->name, errno, strerror(errno));

          if(get_iface_ip6(iface->name, &iface->ip6) < 0) {
            log_i("Could not get interface \"%s\" IPv6[%d]: %s", iface->name, errno, strerror(errno));
            memset(&iface->ip6, 0, sizeof(iface->ip6));
          }
        }
        break;
      case RTM_DELLINK:
        if((iface = iface_by_ifidx(rt, ((struct ifaddrmsg *) NLMSG_DATA(nh))->ifa_index)) != NULL) {
          // libpcap sometimes does not detect that an interface was removed. Making it necessary
          // to subscribe to RTMGRP_LINK
          log_i("RTM_DELLINK: interface %s deleted", iface->name);

          if(rt->inet_iface && (iface->ifidx == rt->inet_iface->ifidx))
            recheck_inet = 1;

          close_interface(iface);
        }
        break;
    }

    if(do_break)
      break;
  }

  if(recheck_inet)
    check_inet_interface(rt);

  return 0;
}

/* ******************************************************* */

// try to determine the packet direction as it is only available in SLL / SLL2 ("any" interface)
static int is_tx_packet(pcapd_iface_t *iface, const u_char *pkt, u_int16_t len) {
  // TODO check for broadcast / multicast
  if((iface->dlink == DLT_EN10MB) && (len >= 14)) {
    // Ethernet header present
    struct ethhdr *eth = (struct ethhdr *) pkt;
    uint64_t smac = bytes2mac(eth->h_source);
    uint64_t dmac = bytes2mac(eth->h_dest);

    if(smac != dmac) {
      if(smac == iface->mac)
        return 1; // TX
      else if(dmac == iface->mac)
        return 0; // RX
    }

    len -= 14;
    pkt += 14;
  } else if((iface->dlink == DLT_LINUX_SLL) && (len >= SLL_HDR_LEN)) {
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
    if(ip->daddr == iface->ip)
      return 0; // RX
  } else if(ip->version == 6) {
    struct ipv6hdr *hdr = (struct ipv6hdr *) pkt;

    if(memcmp(&hdr->daddr, &iface->ip6, sizeof(iface->ip6)) == 0)
      return 0; // RX
  }

  return 1; // by default assume TX
}

/* ******************************************************* */

static void get_selectable_fds(pcapd_runtime_t *rt, fd_set *fds) {
  FD_ZERO(fds);

#ifndef LOCAL_DEBUG
  FD_SET(rt->client, fds);
#endif
  FD_SET(rt->nlsock, fds);

  for(int i=0; i<rt->conf->num_interfaces; i++) {
    if(rt->ifaces[i].pf != -1)
      FD_SET(rt->ifaces[i].pf, fds);
  }
}

/* ******************************************************* */

static int read_pkt(pcapd_runtime_t *rt, pcapd_iface_t *iface, time_t now) {
  struct pcap_pkthdr *hdr;
  const u_char *pkt;
  int to_skip = iface->ipoffset;
  int rv1 = pcap_next_ex(iface->pd, &hdr, &pkt);

  if(rv1 == PCAP_ERROR) {
    log_i("pcap_next_ex failed: %s", pcap_geterr(iface->pd));

    // Do not abort, just wait for route changes
    close_interface(iface);
  } else if((rv1 == 1) && (hdr->caplen >= to_skip)) {
    pcapd_hdr_t phdr;
    zdtun_pkt_t zpkt;
    uint8_t is_tx = is_tx_packet(iface, pkt, hdr->caplen);

    if(hdr->caplen < hdr->len)
      log_w("Packet truncated: %d/%d", hdr->caplen, hdr->len);

    pkt += to_skip;
    hdr->caplen -= to_skip;

    if(zdtun_parse_pkt(rt->tun, (const char*)pkt, hdr->caplen, &zpkt) == 0) {
      if(!is_tx) {
        // Packet from the internet, swap src and dst
        tupleSwapPeers(&zpkt.tuple);
      }

      int uid = uid_lru_find(rt->lru, &zpkt.tuple);

      if(uid == -2) {
        uid = get_uid(rt->resolver, &zpkt.tuple);
        uid_lru_add(rt->lru, &zpkt.tuple, uid);
      }

      if((rt->conf->uid_filter == -1) || (rt->conf->uid_filter == uid)) {
        phdr.ts = hdr->ts;
        phdr.len = hdr->caplen;
        phdr.pkt_drops = iface->stats.ps_drop;
        phdr.uid = uid;
        phdr.flags = is_tx ? PCAPD_FLAG_TX : 0;
        phdr.ifid = iface->ifid;

#ifndef LOCAL_DEBUG
        // Send the pcapd_hdr_t first, then the packet data. The packet data always starts with
        // the IP header.
        if((xwrite(rt->client, &phdr, sizeof(phdr)) < 0) ||
           (xwrite(rt->client, pkt, phdr.len) < 0)) {
          log_e("write failed[%d]: %s", errno, strerror(errno));
          return -1;
        }
#else
        char buf[512];
        zdtun_5tuple2str(&zpkt.tuple, buf, sizeof(buf));

        log_d("[%s:%d] %s [%cX]", iface->name, iface->ifid, buf, is_tx ? 'T' : 'R');
#endif
      }
    }
  }

  if(now >= iface->next_stats_update) {
    // TODO stats for all the interfaces
    pcap_stats(iface->pd, &iface->stats);
    iface->next_stats_update = now + 3;
  }

  return 0;
}

/* ******************************************************* */

static int run_pcap_dump(pcapd_conf_t *conf) {
  int rv = -1;
  struct timespec ts = {0};
  pcapd_runtime_t rt = {0};
  time_t next_interface_recheck = 0;
  zdtun_callbacks_t callbacks = {.send_client = (void*)1};
  fd_set all_fds;

  if(!(rt.tun = zdtun_init(&callbacks, NULL)))
    goto cleanup;

  if(init_pcapd_capture(&rt, conf) < 0)
    goto cleanup;

  int l = snprintf(rt.bpf, sizeof(rt.bpf), "ip or ip6");

  if(conf->bpf && conf->bpf[0])
    snprintf(rt.bpf + l, sizeof(rt.bpf) - l, " and (%s)", conf->bpf);

  log_d("Using BPF: %s", rt.bpf);

  check_inet_interface(&rt);

  for(int i=0; i<conf->num_interfaces; i++) {
    if((strcmp(conf->ifnames[i], "@inet") != 0)
          && open_interface(&rt.ifaces[i], &rt, conf->ifnames[i], i) < 0)
      goto cleanup;
  }

  rv = 0;
  running = 1;
  get_selectable_fds(&rt, &all_fds);

  while(running) {
    struct timeval timeout = {.tv_sec = 1, .tv_usec = 0};
    fd_set fds = all_fds;

    if(select(rt.maxfd + 1, &fds, NULL, NULL, &timeout) < 0) {
      log_e("select failed[%d]: %s", errno, strerror(errno));
      rv = -1;
      break;
    }

    clock_gettime(CLOCK_MONOTONIC_COARSE, &ts);
    time_t now = ts.tv_sec;

#ifndef LOCAL_DEBUG
    if(FD_ISSET(rt.client, &fds)) {
      log_i("Client closed");
      break;
    } else
#endif
    if(FD_ISSET(rt.nlsock, &fds)) {
      if(handle_nl_message(&rt) < 0) {
        rv = -1;
        break;
      }

      // Interfaces may have changed, refresh fds
      get_selectable_fds(&rt, &all_fds);
    } else {
      for(int i=0; i<rt.conf->num_interfaces; i++) {
        if((rt.ifaces[i].pf != -1) && FD_ISSET(rt.ifaces[i].pf, &fds)) {
          if(read_pkt(&rt, &rt.ifaces[i], now) < 0) {
            rv = -1;
            break;
          }
        }
      }
    }

    if(rt.inet_iface && (rt.inet_iface->pd == NULL) && (now >= next_interface_recheck)) {
      check_inet_interface(&rt);
      next_interface_recheck = now + 2;
    }
  }

  log_i("Terminating...");

cleanup:
  finish_pcapd_capture(&rt);

  if(rt.tun)
    zdtun_finalize(rt.tun);

  return rv;
}

/* ******************************************************* */

static void usage() {
  fprintf(stderr, "pcapd - root capture tool of PCAPdroid\n"
                  "Copyright 2021 Emanuele Faranda <black.silver@hotmail.it>\n\n"
                  "Usage: pcapd [-i ifname, -i ...] [-d] [-u uid] [-b bpf] [-l]\n"
                  " -i [ifname]    capture packets on the specified interface. Can be specified\n"
                  "                multiple times. The '@inet' keyword can be used to capture from\n"
                  "                the internet interface\n"
                  " -d             daemonize the process\n"
                  " -u [uid]       filter packets by uid\n"
                  " -b [bpf]       filter packets by BPF filter\n"
                  " -l [file]      log output to the specified file\n"
  );

  exit(1);
}

/* ******************************************************* */

static void parse_args(pcapd_conf_t *conf, int argc, char **argv) {
  int c;

  memset(conf, 0, sizeof(pcapd_conf_t));
  conf->uid_filter = -1;
  conf->inet_ifid = -1;
  opterr = 0;

  while ((c = getopt (argc, argv, "hdi:u:b:l:")) != -1) {
    switch(c) {
      case 'i':
        if(conf->num_interfaces >= MAX_IFACES) {
          fprintf(stderr, "Maximum number of interfaces reached (%d)\n", MAX_IFACES);
          exit(1);
        }
        if(strcmp(optarg, "@inet") == 0) {
          if(conf->inet_ifid != -1) {
            fprintf(stderr, "@inet interface already specified\n");
            exit(1);
          }
          conf->inet_ifid = conf->num_interfaces;
        }
        conf->ifnames[conf->num_interfaces++] = strdup(optarg);
        break;
      case 'd':
        conf->daemonize = 1;
        break;
      case 'u':
        conf->uid_filter = atoi(optarg);
        if(conf->uid_filter < -1) {
          fprintf(stderr, "Invalid UID: %s\n", optarg);
          exit(1);
        }
        break;
      case 'b':
        if(conf->bpf) free(conf->bpf);
        conf->bpf = strdup(optarg);
        break;
      case 'l':
        if(conf->log_file) free(conf->log_file);
        conf->log_file = strdup(optarg);
        break;
      default:
        usage();
    }
  }

  // No positional args
  if(optind < argc)
    usage();

  if(conf->log_file) {
    mode_t old_mask = umask(033);
    logf = fopen(conf->log_file, "w");
    umask(old_mask);

    if(logf == NULL)
      log_e("Could not open log file[%d]: %s", errno, strerror(errno));
  }

  if(conf->num_interfaces == 0) {
    conf->inet_ifid = 0;
    conf->ifnames[conf->num_interfaces++] = "@inet";
  }
}

/* ******************************************************* */

int main(int argc, char *argv[]) {
  pcapd_conf_t conf;
  int rv;

  logtag = "pcapd";
  logcallback = logcb;
  parse_args(&conf, argc, argv);

  rv = run_pcap_dump(&conf);
  unlink(PCAPD_PID);

  if(conf.bpf)
    free(conf.bpf);
  if(conf.log_file)
    free(conf.log_file);
  if(logf)
    fclose(logf);

  return rv;
}
