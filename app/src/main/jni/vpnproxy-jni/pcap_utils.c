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

#include "common/utils.h"
#include "pcap_utils.h"

#define SNAPLEN 65535
#define LINKTYPE_RAW 101

/* ******************************************************* */

void pcap_build_hdr(struct pcap_hdr_s *pcap_hdr) {
    pcap_hdr->magic_number = 0xa1b2c3d4;
    pcap_hdr->version_major = 2;
    pcap_hdr->version_minor = 4;
    pcap_hdr->thiszone = 0;
    pcap_hdr->sigfigs = 0;
    pcap_hdr->snaplen = SNAPLEN;
    pcap_hdr->network = LINKTYPE_RAW;
}

/* ******************************************************* */

/* Returns the size of a PCAP record */
int pcap_rec_size(int pkt_len) {
    return((pkt_len < SNAPLEN ? pkt_len : SNAPLEN) + (int)sizeof(struct pcaprec_hdr_s));
}

/* ******************************************************* */

/* Dumps a packet into the provided buffer. The buffer must have at least pcap_rec_size()
 * bytes available */
void pcap_dump_rec(u_char *buffer, const u_char *pkt, int pkt_len) {
    struct pcaprec_hdr_s *pcap_rec = (pcaprec_hdr_s*) buffer;
    struct timespec ts = {0};

    if(clock_gettime(CLOCK_REALTIME, &ts))
        log_d("clock_gettime failed[%d]: %s", errno, strerror(errno));

    pcap_rec->ts_sec = ts.tv_sec;
    pcap_rec->ts_usec = (ts.tv_nsec / 1000);
    pcap_rec->incl_len = (pkt_len < SNAPLEN ? pkt_len : SNAPLEN);
    pcap_rec->orig_len = pkt_len;

    memcpy(buffer + sizeof(struct pcaprec_hdr_s), pkt, pcap_rec->incl_len);
}
