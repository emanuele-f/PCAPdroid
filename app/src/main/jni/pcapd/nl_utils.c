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
#include <netinet/in.h>
#include <net/if.h>
#include "nl_utils.h"

int nl_socket(uint32_t groups) {
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
  int i, alen, ok, nlsock, rv = -1;

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
  rmsg->rtm_dst_len = alen;

  rta = RTM_RTA(rmsg);
  rta->rta_type = RTA_DST;
  rta->rta_len = RTA_LENGTH(alen);

  /* XXX - gross hack for default route */
  if (af == AF_INET && (addr->v4 == INADDR_ANY)) {
    i = htonl(0x60060606);
    memcpy(RTA_DATA(rta), &i, alen);
  } else
    memcpy(RTA_DATA(rta), &addr, alen);

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
