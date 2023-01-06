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
 * Copyright 2023 - Emanuele Faranda
 */

#ifndef __MY_PCAP_H__
#define __MY_PCAP_H__

#include <stdlib.h>
#include <stdint.h>

/* Packet dump module, dumping packet records in the PCAP/PCAPNG format.
 * Packets are first buffered and then exported periodically to the callback. pcap_check_export must
 * be called periodically to ensure that buffered packets are exported on time.
 *
 * The PCAP/PCAPNG headers are *not* dumped, use pcap_get_header to get the header to be dumped. This
 * allows, for example, multiple HTTP clients to connect at different times, each one getting a valid
 * PCAP header. */
typedef struct pcap_dumper pcap_dumper_t;

typedef struct pcap_hdr {
    uint32_t magic_number;
    uint16_t version_major;
    uint16_t version_minor;
    int32_t thiszone;
    uint32_t sigfigs;
    uint32_t snaplen;
    uint32_t network;
} __attribute__((packed)) pcap_hdr_t;

typedef struct pcap_rec {
    uint32_t ts_sec;
    uint32_t ts_usec;
    uint32_t incl_len;
    uint32_t orig_len;
} __attribute__((packed)) pcap_rec_t;

typedef enum {
    PCAP_DUMP,                // PCAP file
    PCAP_DUMP_WITH_TRAILER,   // PCAP file with PCAPdroid trailer
    PCAPNG_DUMP,              // PcapNg file
} pcap_dump_mode_t;

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
} __attribute__((packed)) pcapdroid_trailer_t;

struct pcapdroid;
typedef void pcap_dump_callback(struct pcapdroid *pd, const int8_t *buf, int dump_size);

pcap_dumper_t* pcap_new_dumper(pcap_dump_mode_t mode, int snaplen, uint64_t max_dump_size,
                               pcap_dump_callback dumpcb, struct pcapdroid *pd);
void pcap_destroy_dumper(pcap_dumper_t *dumper);
bool pcap_dump_packet(pcap_dumper_t *dumper, const char *pkt, int pktlen, const struct timeval *tv, int uid);
int pcap_get_header(pcap_dumper_t *dumper, char **out);
int pcap_get_dump_size(pcap_dumper_t *dumper);
bool pcap_check_export(pcap_dumper_t *dumper);

#endif // __MY_PCAP_H__
