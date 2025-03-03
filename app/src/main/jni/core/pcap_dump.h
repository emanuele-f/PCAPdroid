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
 * Copyright 2023-25 - Emanuele Faranda
 */

#ifndef __PCAPDROID_DUMP_H__
#define __PCAPDROID_DUMP_H__

#include <stdlib.h>
#include <stdint.h>
#include <sys/time.h>

#define PCAPDROID_TRAILER_MAGIC 0x01072021
#define PCAPDROID_PEN 62652
#define PCAPDROID_PCAPNG_VERSION 1

#define PCAPDROID_BLOCK_UID_MAP 1

#define LINKTYPE_ETHERNET   1
#define LINKTYPE_RAW        101
#define LINKTYPE_LINUX_SLL  113
#define LINKTYPE_LINUX_SLL2 276

/*
 * Packet dump module, dumping packet records in the PCAP/PCAPNG format.
 * Packets are first buffered and then exported periodically to the callback. pcap_check_export must
 * be called periodically to ensure that buffered packets are exported on time.
 *
 * The PCAP/PCAPNG preambles are *not* dumped, use pcap_get_preamble to get the preamble to be dumped. This
 * allows, for example, multiple HTTP clients to connect at different times, each one getting a valid
 * PCAP header.
 */
typedef struct pcap_dumper pcap_dumper_t;

// compatible with pcap.h
struct pcap_pkthdr {
    struct timeval ts;
    uint32_t caplen;
    uint32_t len;
};

/* ******************************************************* */

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

/* ******************************************************* */

// NOTE: all the PCAPNG block addresses are aligned to 32-bits
typedef struct pcapng_generic_block {
    uint32_t type;
    uint32_t total_length;

    /* ..options.. */
} __attribute__((packed)) pcapng_generic_block_t;

// NOTE: pd_new_reader assumes sizeof(pcapng_section_hdr_block_t) <= sizeof(pcap_hdr)
typedef struct pcapng_section_hdr_block {
    uint32_t type;
    uint32_t total_length;
    uint32_t magic;
    uint16_t version_major;
    uint16_t version_minor;
    uint64_t section_length;

    /* ..options.. */
} __attribute__((packed)) pcapng_section_hdr_block_t;

typedef struct pcapng_intf_descr_block {
    uint32_t type;
    uint32_t total_length;
    uint16_t linktype;
    uint16_t reserved;
    uint32_t snaplen;
    /* ..options.. */
} __attribute__((packed)) pcapng_intf_descr_block_t;

typedef struct pcapng_decr_secrets_block {
    uint32_t type;
    uint32_t total_length;
    uint32_t secrets_type;
    uint32_t secrets_length;
    /* ..secrets data.. */
    /* ..options.. */
} __attribute__((packed)) pcapng_decr_secrets_block_t;

typedef struct pcapng_enh_packet_block {
    uint32_t type;
    uint32_t total_length;
    uint32_t interface_id;
    uint32_t timestamp_high;
    uint32_t timestamp_low;
    uint32_t captured_len;
    uint32_t original_len;
    /* ..packet data.. */
    /* ..padding.. */
    /* ..options.. */
} __attribute__((packed)) pcapng_enh_packet_block_t;

typedef struct pcapng_enh_option {
    uint16_t code;
    uint16_t length;
} pcapng_enh_option_t;

/* ******************************************************* */

typedef struct pcapng_pd_custom_block {
    int32_t block_type;
    uint32_t total_length;
    uint32_t pen;

    uint16_t version;
    uint8_t type;
    uint8_t padding;
} __attribute__((packed)) pcapng_pd_custom_block_t;

typedef struct pcapng_pd_uid_map_block {
    pcapng_pd_custom_block_t hdr;

    int32_t uid;
    uint8_t package_name_len;
    uint8_t app_name_len;
    /* ..package_name, app_name.. */
} __attribute__((packed)) pcapng_pd_uid_map_block_t;

/* ******************************************************* */

typedef enum {
    PCAP_DUMP,                // PCAP file
    PCAPNG_DUMP,              // PcapNg file
} pcap_dump_format_t;

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

pcap_dumper_t* pcap_new_dumper(pcap_dump_format_t format, bool dump_extensions, int snaplen, uint64_t max_dump_size,
                               pcap_dump_callback dumpcb, struct pcapdroid *pd);
void pcap_destroy_dumper(pcap_dumper_t *dumper);
bool pcap_dump_packet(pcap_dumper_t *dumper, const char *pkt, int pktlen, const struct timeval *tv, int uid, u_int ifidx);
bool pcap_dump_secret(pcap_dumper_t *dumper, int8_t *sec_data, int seclen);
int pcap_get_preamble(pcap_dumper_t *dumper, char **out);
uint64_t pcap_get_dump_size(pcap_dumper_t *dumper);
bool pcap_check_export(pcap_dumper_t *dumper);

#endif // __PCAPDROID_DUMP_H__
