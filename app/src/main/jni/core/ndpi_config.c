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
 * Copyright 2020-21 - Emanuele Faranda
 */

#include "ndpi_api.h"
#include "ndpi_protocol_ids.h"

#define MASTER_PROTOS_MAX_BITS 1024

/* ******************************************************* */

// protocols which are not application protocols
void init_ndpi_protocols_bitmask(struct ndpi_bitmask *b) {
    ndpi_bitmask_alloc(b, MASTER_PROTOS_MAX_BITS);

    // https://github.com/ntop/nDPI/blob/dev/src/include/ndpi_protocol_ids.h
    ndpi_bitmask_set(b, NDPI_PROTOCOL_FTP_CONTROL);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_MAIL_POP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_MAIL_SMTP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_MAIL_IMAP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_DNS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_IPP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_HTTP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_MDNS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_NTP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_NETBIOS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_NFS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_SSDP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_SNMP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_XDMCP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_SMBV1);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_SYSLOG);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_DHCP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_POSTGRES);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_MYSQL);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_MAIL_POPS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_TAILSCALE);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_COAP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_VMWARE);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_MAIL_SMTPS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_DTLS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_UBNTAC2);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_BITTORRENT);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_SMBV23);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_RTSP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_MAIL_IMAPS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_ICECAST);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_IRC);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_NATS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_TELNET);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_STUN);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_IPSEC);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_IP_GRE);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_RTP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_RDP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_VNC);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_TLS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_SSH);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_TFTP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_SIP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_DHCPV6);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_KERBEROS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_PPTP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_NETFLOW);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_SFLOW);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_HTTP_CONNECT);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_HTTP_PROXY);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_RADIUS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_TEAMVIEWER);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_OPENVPN);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_CISCOVPN);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_TOR);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_RTCP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_SOCKS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_RTMP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_FTP_DATA);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_ZMQ);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_RESP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_QUIC);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_WIREGUARD);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_DNSCRYPT);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_TINC);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_MQTT);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_RX);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_GIT);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_DRDA);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_VALVE_SDR);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_WEBSOCKET);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_Z3950);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_MPEGDASH);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_FTPS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_NATPMP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_SRTP);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_HTTP2);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_PROTOBUF);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_RTPS);
    ndpi_bitmask_set(b, NDPI_PROTOCOL_TRDP);
}
