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

/* ******************************************************* */

// protocols which are not application protocols
void init_ndpi_protocols_bitmask(ndpi_protocol_bitmask_struct_t *b) {
    NDPI_ZERO(b);

    // https://github.com/ntop/nDPI/blob/dev/src/include/ndpi_protocol_ids.h
    NDPI_SET(b, NDPI_PROTOCOL_FTP_CONTROL);
    NDPI_SET(b, NDPI_PROTOCOL_MAIL_POP);
    NDPI_SET(b, NDPI_PROTOCOL_MAIL_SMTP);
    NDPI_SET(b, NDPI_PROTOCOL_MAIL_IMAP);
    NDPI_SET(b, NDPI_PROTOCOL_DNS);
    NDPI_SET(b, NDPI_PROTOCOL_IPP);
    NDPI_SET(b, NDPI_PROTOCOL_HTTP);
    NDPI_SET(b, NDPI_PROTOCOL_MDNS);
    NDPI_SET(b, NDPI_PROTOCOL_NTP);
    NDPI_SET(b, NDPI_PROTOCOL_NETBIOS);
    NDPI_SET(b, NDPI_PROTOCOL_NFS);
    NDPI_SET(b, NDPI_PROTOCOL_SSDP);
    NDPI_SET(b, NDPI_PROTOCOL_SNMP);
    NDPI_SET(b, NDPI_PROTOCOL_SMBV1);
    NDPI_SET(b, NDPI_PROTOCOL_SYSLOG);
    NDPI_SET(b, NDPI_PROTOCOL_DHCP);
    NDPI_SET(b, NDPI_PROTOCOL_POSTGRES);
    NDPI_SET(b, NDPI_PROTOCOL_MYSQL);
    NDPI_SET(b, NDPI_PROTOCOL_MAIL_POPS);
    NDPI_SET(b, NDPI_PROTOCOL_MAIL_SMTPS);
    NDPI_SET(b, NDPI_PROTOCOL_DTLS);
    NDPI_SET(b, NDPI_PROTOCOL_BITTORRENT);
    NDPI_SET(b, NDPI_PROTOCOL_SMBV23);
    NDPI_SET(b, NDPI_PROTOCOL_RTSP);
    NDPI_SET(b, NDPI_PROTOCOL_MAIL_IMAPS);
    NDPI_SET(b, NDPI_PROTOCOL_IRC);
    NDPI_SET(b, NDPI_PROTOCOL_NATS);
    NDPI_SET(b, NDPI_PROTOCOL_TELNET);
    NDPI_SET(b, NDPI_PROTOCOL_STUN);
    NDPI_SET(b, NDPI_PROTOCOL_IP_IPSEC);
    NDPI_SET(b, NDPI_PROTOCOL_IP_GRE);
    NDPI_SET(b, NDPI_PROTOCOL_RTP);
    NDPI_SET(b, NDPI_PROTOCOL_RDP);
    NDPI_SET(b, NDPI_PROTOCOL_VNC);
    NDPI_SET(b, NDPI_PROTOCOL_TLS);
    NDPI_SET(b, NDPI_PROTOCOL_SSH);
    NDPI_SET(b, NDPI_PROTOCOL_TFTP);
    NDPI_SET(b, NDPI_PROTOCOL_STEALTHNET);
    NDPI_SET(b, NDPI_PROTOCOL_SIP);
    NDPI_SET(b, NDPI_PROTOCOL_DHCPV6);
    NDPI_SET(b, NDPI_PROTOCOL_KERBEROS);
    NDPI_SET(b, NDPI_PROTOCOL_PPTP);
    NDPI_SET(b, NDPI_PROTOCOL_NETFLOW);
    NDPI_SET(b, NDPI_PROTOCOL_SFLOW);
    NDPI_SET(b, NDPI_PROTOCOL_HTTP_CONNECT);
    NDPI_SET(b, NDPI_PROTOCOL_HTTP_PROXY);
    NDPI_SET(b, NDPI_PROTOCOL_RADIUS);
    NDPI_SET(b, NDPI_PROTOCOL_TEAMVIEWER);
    NDPI_SET(b, NDPI_PROTOCOL_OPENVPN);
    NDPI_SET(b, NDPI_PROTOCOL_CISCOVPN);
    NDPI_SET(b, NDPI_PROTOCOL_TOR);
    NDPI_SET(b, NDPI_PROTOCOL_RTCP);
    NDPI_SET(b, NDPI_PROTOCOL_SOCKS);
    NDPI_SET(b, NDPI_PROTOCOL_RTMP);
    NDPI_SET(b, NDPI_PROTOCOL_FTP_DATA);
    NDPI_SET(b, NDPI_PROTOCOL_ZMQ);
    NDPI_SET(b, NDPI_PROTOCOL_REDIS);
    NDPI_SET(b, NDPI_PROTOCOL_QUIC);
    NDPI_SET(b, NDPI_PROTOCOL_WIREGUARD);
    NDPI_SET(b, NDPI_PROTOCOL_DNSCRYPT);
    NDPI_SET(b, NDPI_PROTOCOL_TINC);
    NDPI_SET(b, NDPI_PROTOCOL_DNSCRYPT);
    NDPI_SET(b, NDPI_PROTOCOL_MQTT);
    NDPI_SET(b, NDPI_PROTOCOL_RX);
    NDPI_SET(b, NDPI_PROTOCOL_GIT);
    NDPI_SET(b, NDPI_PROTOCOL_WEBSOCKET);
    NDPI_SET(b, NDPI_PROTOCOL_Z3950);
}