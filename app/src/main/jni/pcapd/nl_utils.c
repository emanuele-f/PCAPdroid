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

#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <linux/netlink.h>
#include <linux/rtnetlink.h>
#include <linux/sock_diag.h>
#include <linux/inet_diag.h>
#include <netinet/in.h>
#include <net/if.h>
#include "nl_utils.h"
#include "common/uid_resolver.h"
#include "common/utils.h"

int nl_route_socket(uint32_t groups) {
  struct sockaddr_nl snl;
  int sock;

  sock = socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE);

  if(sock < 0)
    return -1;

  memset(&snl, 0, sizeof(snl));
  snl.nl_family = AF_NETLINK;
  snl.nl_pid = 0; // managed by the kernel, see man 7 netlink
  snl.nl_groups = groups;

  if(bind(sock, (struct sockaddr *)&snl, sizeof(snl)) < 0) {
    close(sock);
    return -1;
  }

  return sock;
}

/* ******************************************************* */

// adapted from libdnet/route-linux.c
int nl_get_route(int af, const addr_t *addr, route_info_t *out) {
  static int seq = 0;
  struct nlmsghdr *nmsg;
  struct rtmsg *rmsg;
  struct rtattr *rta;
  struct sockaddr_nl snl;
  struct iovec iov;
  struct msghdr msg;
  u_char buf[512];
  int i, alen, nlsock, rv = -1;

  nlsock = socket(AF_NETLINK, SOCK_RAW, NETLINK_ROUTE);

  if(nlsock < 0)
    return(-1);

  alen = (af == AF_INET) ? 4 : 16;
  memset(buf, 0, sizeof(buf));

  nmsg = (struct nlmsghdr *)buf;
  nmsg->nlmsg_len = NLMSG_LENGTH(sizeof(*nmsg)) + RTA_LENGTH(alen);
  nmsg->nlmsg_flags = NLM_F_REQUEST;
  nmsg->nlmsg_type = RTM_GETROUTE;
  nmsg->nlmsg_seq = ++seq;

  rmsg = (struct rtmsg *)(nmsg + 1);
  rmsg->rtm_family = af;
  rmsg->rtm_dst_len = alen * 8;

  rta = RTM_RTA(rmsg);
  rta->rta_type = RTA_DST;
  rta->rta_len = RTA_LENGTH(alen);

  /* XXX - gross hack for default route */
  if (af == AF_INET && (addr->v4 == INADDR_ANY)) {
    i = htonl(0x60060606);
    memcpy(RTA_DATA(rta), &i, alen);
  } else
    memcpy(RTA_DATA(rta), addr, alen);

  memset(&snl, 0, sizeof(snl));
  snl.nl_family = AF_NETLINK;

  iov.iov_base = nmsg;
  iov.iov_len = nmsg->nlmsg_len;

  memset(&msg, 0, sizeof(msg));
  msg.msg_name = &snl;
  msg.msg_namelen = sizeof(snl);
  msg.msg_iov = &iov;
  msg.msg_iovlen = 1;

  if(sendmsg(nlsock, &msg, 0) < 0)
    goto out;

  iov.iov_base = buf;
  iov.iov_len = sizeof(buf);

  if ((i = recvmsg(nlsock, &msg, 0)) <= 0)
    goto out;

  if(nmsg->nlmsg_len < (int)sizeof(*nmsg) || nmsg->nlmsg_len > i ||
      nmsg->nlmsg_seq != seq) {
    errno = EINVAL;
    goto out;
  }

  if(nmsg->nlmsg_type == NLMSG_ERROR)
    goto out;

  i -= NLMSG_LENGTH(sizeof(*nmsg));
  out->gw_len = 0;

  for(rta = RTM_RTA(rmsg); RTA_OK(rta, i); rta = RTA_NEXT(rta, i)) {
    if(rta->rta_type == RTA_GATEWAY) {
      // may noy be present
      memcpy(&out->gateway, RTA_DATA(rta), alen);
      out->gw_len = alen;
    } else if (rta->rta_type == RTA_OIF) {
      rv = 0;
      out->ifidx = *(int *) RTA_DATA(rta);
    }
  }

  if(rv != 0) {
    errno = ESRCH;
    goto out;
  }

out:
  close(nlsock);

  return(rv);
}

/* ******************************************************* */

// Returns:
//      >= 0 on success
//      UID_UNKNOWN if the UID could not be resolved
//      other on error, errno is set accordingly
static int diag_uid_lookup(int nlsock, int family, int ipproto,
          const pd_sockaddr_t *local, const pd_sockaddr_t *remote,
          int flags) {
  struct sockaddr_nl snl = {0};
  struct msghdr msg = {0};
  struct iovec iov;
  u_char buf[512];
  static int seq = 0;
  ssize_t rv;
  uint8_t do_retry = 1;

  struct nlmsghdr *nmsg = (struct nlmsghdr*) buf;
  struct inet_diag_req_v2 *req = (struct inet_diag_req_v2*) (nmsg + 1);

  memset(req, 0, sizeof(*req));
  req->sdiag_family = family;
  req->sdiag_protocol = ipproto;
  req->idiag_states = -1 /* ANY state */;
  req->id.idiag_sport = local->port;
  req->id.idiag_dport = remote->port;
  req->id.idiag_cookie[0] = -1, req->id.idiag_cookie[1] = -1; /* no cookie */

  if(family == AF_INET) {
    memcpy(req->id.idiag_src, &local->addr.ip4, 4);
    memcpy(req->id.idiag_dst, &remote->addr.ip4, 4);
  } else {
    memcpy(req->id.idiag_src, &local->addr.ip6, 16);
    memcpy(req->id.idiag_dst, &remote->addr.ip6, 16);
  }

  memset(nmsg, 0, sizeof(*nmsg));
  nmsg->nlmsg_len = sizeof(*nmsg) + sizeof(*req);
  nmsg->nlmsg_type = SOCK_DIAG_BY_FAMILY;
  nmsg->nlmsg_flags = flags;
  nmsg->nlmsg_seq = ++seq;

  iov.iov_base = (void*) nmsg;
  iov.iov_len = nmsg->nlmsg_len;

  snl.nl_family = AF_NETLINK;

  msg.msg_name = (void*) &snl;
  msg.msg_namelen = sizeof(snl);
  msg.msg_iov = &iov;
  msg.msg_iovlen = 1;

  // Send request
  if(sendmsg(nlsock, &msg, 0) < 0)
    return -2;

  iov.iov_base = buf;
  iov.iov_len = sizeof(buf);

retry:
  // Recv reply
  if((rv = recvmsg(nlsock, &msg, 0)) <= 0)
    return -3;

  // NOTE: nmsg points to buf
  if(nmsg->nlmsg_len < (int)sizeof(*nmsg) || nmsg->nlmsg_len > rv) {
    errno = EINVAL;
    return -4;
  }

  if(nmsg->nlmsg_seq != seq) {
    if(do_retry && (nmsg->nlmsg_seq == (seq - 1))) {
      do_retry = 0;
      goto retry; // this issue is recoverable, retry once
    }

    log_e("out of sequence: %d/%d", nmsg->nlmsg_seq, seq);
    errno = EINVAL;
    return -5;
  }

  if(nmsg->nlmsg_type == NLMSG_ERROR) {
    const struct nlmsgerr *err = NLMSG_DATA(nmsg);

    if(nmsg->nlmsg_len >= NLMSG_LENGTH(sizeof(*err))) {
        errno = -err->error;
        if(errno == ENOENT)
            return UID_UNKNOWN;
    } else
        errno = EINVAL;

    return -6;
  }

  if(nmsg->nlmsg_type == NLMSG_DONE)
    return UID_UNKNOWN;

  if(nmsg->nlmsg_len < NLMSG_LENGTH(sizeof(struct inet_diag_msg))) {
      errno = EINVAL;
      return -7;
  }

  struct inet_diag_msg *diag_msg = (struct inet_diag_msg*) NLMSG_DATA(nmsg);
  return diag_msg->idiag_uid;
}

/* ******************************************************* */

// On some Android versions (e.g. emulator API 21) the DIAG socket always returns ENOENT
// Perform a wildcard dump query to verify this
int nl_is_diag_working() {
  int working;
  pd_sockaddr_t wildcard = {0};

  // NOTE: don't use an existing socket, as the NLM_F_DUMP query may span multiple datagrams
  int nlsock = socket(AF_NETLINK, SOCK_DGRAM, NETLINK_INET_DIAG);
  if(nlsock < 0)
    return 0;

  // Assume at least 1 open UDP AF_INET open socket exists
  int rv = diag_uid_lookup(nlsock, AF_INET, IPPROTO_UDP, &wildcard, &wildcard, NLM_F_REQUEST | NLM_F_DUMP);
  working = (rv >= 0);

  close(nlsock);
  return working;
}

/* ******************************************************* */

int nl_get_uid(int nlsock, const zdtun_5tuple_t *tuple) {
  int uid;

  int family = (tuple->ipver == 4) ? AF_INET : AF_INET6;
  int ipproto = tuple->ipproto;
  pd_sockaddr_t src = {.addr = tuple->src_ip, .port = tuple->src_port};
  pd_sockaddr_t dst = {.addr = tuple->dst_ip, .port = tuple->dst_port};

  // fix to known bug with UDP: https://www.mail-archive.com/netdev@vger.kernel.org/msg248638.html
  const pd_sockaddr_t *local = (ipproto == IPPROTO_UDP) ? &dst : &src;
  const pd_sockaddr_t *remote = (ipproto == IPPROTO_UDP) ? &src : &dst;

  uid = diag_uid_lookup(nlsock, family, ipproto, local, remote, NLM_F_REQUEST);
  if((uid >= 0) || (uid != UID_UNKNOWN))
    return uid;

  // Search for IPv4-mapped IPv6 addresses
  if(family == AF_INET) {
    uid = diag_uid_lookup(nlsock, AF_INET6, ipproto, local, remote, NLM_F_REQUEST);
    if((uid >= 0) || (uid != UID_UNKNOWN))
      return uid;
  }

  // For UDP it's possible for a socket to send packets to arbitrary destinations
  // See InetDiagMessage.java in Android
  if(ipproto == IPPROTO_UDP) {
    pd_sockaddr_t wildcard = {0};

    uid = diag_uid_lookup(nlsock, family, ipproto, &src, &wildcard, NLM_F_REQUEST | NLM_F_DUMP);
    if((uid >= 0) || (uid != UID_UNKNOWN))
      return uid;

    // Search for IPv4-mapped IPv6 addresses
    if(family == AF_INET) {
      uid = diag_uid_lookup(nlsock, AF_INET6, ipproto, &src, &wildcard, NLM_F_REQUEST | NLM_F_DUMP);
      if((uid >= 0) || (uid != UID_UNKNOWN))
        return uid;
    }
  }

  return UID_UNKNOWN;
}
