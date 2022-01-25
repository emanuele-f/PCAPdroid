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

#ifndef __MY_PCAP_H__
#define __MY_PCAP_H__

#include <stdlib.h>
#include <stdint.h>
#include "common/utils.h"

typedef struct pcap_hdr_s {
    uint32_t magic_number;
    uint16_t version_major;
    uint16_t version_minor;
    int32_t thiszone;
    uint32_t sigfigs;
    uint32_t snaplen;
    uint32_t network;
} __packed pcap_hdr_s;

typedef struct pcaprec_hdr_s {
    uint32_t ts_sec;
    uint32_t ts_usec;
    uint32_t incl_len;
    uint32_t orig_len;
} __packed pcaprec_hdr_s;

#define PCAPDROID_TRAILER_MAGIC 0x01072021

/* A trailer to the packet which contains PCAPdroid-specific information.
 * When pcapdroid_trailer is set, the raw packet will be prepended with a bogus ethernet header,
 * whose size spans the raw packet data. The pcapdroid_trailer_t will be appended after the L3 data
 * so that PCAP parsers which are not aware of this data will just ignore it.
 *
 *  original: [IP | Payload]
 * pcapdroid: [ETH | IP | Payload | CustomData]
 */
typedef struct pcapdroid_trailer {
    uint32_t magic;
    int32_t uid;
    char appname[20];
    uint32_t fcs;
} __packed pcapdroid_trailer_t;

void pcap_set_pcapdroid_trailer(uint8_t enabled);
void pcap_build_hdr(int snaplen, struct pcap_hdr_s *pcap_hdr);
int pcap_rec_size(int snaplen, int pkt_len);
void pcap_dump_rec(pcapdroid_t *pd, u_char *buffer, pkt_context_t *pctx);

#endif // __MY_PCAP_H__
