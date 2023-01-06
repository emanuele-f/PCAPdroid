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

#include <linux/if_ether.h>
#include "common/utils.h"
#include "pcapdroid.h"
#include "pcap_dump.h"

#define LINKTYPE_ETHERNET 1
#define LINKTYPE_RAW      101

#define PCAPDROID_TRAILER_MAGIC 0x01072021
#define MAX_PCAP_DUMP_DELAY_MS 1000
#define PCAP_BUFFER_SIZE (512*1024) // 512K

struct pcap_dumper {
    pcap_dump_mode_t mode;
    pcap_dump_callback *dump_cb;
    pcapdroid_t *pd;
    int snaplen;
    uint64_t max_dump_size;
    uint64_t dump_size;
    uint64_t last_dump_ms;

    // the crc32 implementation requires 4-bytes aligned accesses.
    // frames are padded to honor the 4-bytes alignment.
    int8_t *buffer  __attribute__((aligned (4)));
    int bufsize;
    int buffer_idx;
};

/* ******************************************************* */

pcap_dumper_t* pcap_new_dumper(pcap_dump_mode_t mode, int snaplen, uint64_t max_dump_size,
                               pcap_dump_callback dumpcb, pcapdroid_t *pd) {
    pcap_dumper_t *dumper = pd_calloc(1, sizeof(pcap_dumper_t));
    if(!dumper) {
        log_e("calloc(pcap_dumper_t) failed with code %d/%s",
              errno, strerror(errno));
        return NULL;
    }

    dumper->buffer = pd_malloc(PCAP_BUFFER_SIZE);
    if(!dumper->buffer) {
        log_e("malloc(pcap_dumper_t buffer) failed with code %d/%s",
              errno, strerror(errno));
        pd_free(dumper);
        return NULL;
    }

    dumper->snaplen = snaplen;
    dumper->mode = mode;
    dumper->max_dump_size = max_dump_size;
    dumper->dump_cb = dumpcb;
    dumper->pd = pd;

    return dumper;
}

/* ******************************************************* */

static void export_buffer(pcap_dumper_t *dumper) {
    if(dumper->buffer_idx == 0)
        return;

    if(dumper->dump_cb)
        dumper->dump_cb(dumper->pd, dumper->buffer, dumper->buffer_idx);

    dumper->buffer_idx = 0;
    dumper->last_dump_ms = dumper->pd->now_ms;
}

/* ******************************************************* */

void pcap_destroy_dumper(pcap_dumper_t *dumper) {
    export_buffer(dumper);

    pd_free(dumper->buffer);
    pd_free(dumper);
}

/* ******************************************************* */

/* Get a buffer (out) representing a PCAP header.
 * Returns the buffer size on success, -1 on error. The out buffer must be free by the called with pd_free. */
int pcap_get_header(pcap_dumper_t *dumper, char **out) {
    struct pcap_hdr *pcap_hdr = pd_malloc(sizeof(struct pcap_hdr));
    if(!pcap_hdr)
        return -1;

    pcap_hdr->magic_number = 0xa1b2c3d4;
    pcap_hdr->version_major = 2;
    pcap_hdr->version_minor = 4;
    pcap_hdr->thiszone = 0;
    pcap_hdr->sigfigs = 0;
    pcap_hdr->snaplen = dumper->snaplen;
    pcap_hdr->network = (dumper->mode == PCAP_DUMP_WITH_TRAILER) ? LINKTYPE_ETHERNET : LINKTYPE_RAW;

    *out = (char*)pcap_hdr;
    return sizeof(struct pcap_hdr);
}

/* ******************************************************* */

/* Returns the size of a PCAP record */
int pcap_rec_size(pcap_dumper_t *dumper, int pkt_len) {
    if(dumper->mode == PCAP_DUMP_WITH_TRAILER) {
        pkt_len += (int)(sizeof(pcapdroid_trailer_t) + sizeof(struct ethhdr));

        // Pad the frame so that the buffer keeps its 4-bytes alignment
        pkt_len += (~pkt_len + 1) & 0x3;
    }

    return(min(pkt_len, dumper->snaplen) + (int)sizeof(struct pcap_rec));
}

/* ******************************************************* */

bool pcap_check_export(pcap_dumper_t *dumper) {
    if((dumper->buffer_idx > 0) && (dumper->pd->now_ms - dumper->last_dump_ms) >= MAX_PCAP_DUMP_DELAY_MS) {
        export_buffer(dumper);
        return true;
    }
    return false;
}

/* ******************************************************* */

int pcap_get_dump_size(pcap_dumper_t *dumper) {
    return dumper->dump_size;
}

/* ******************************************************* */

/* Dump a single packet into the buffer. Returns false if PCAP dump must be stopped (e.g. if max
 * dump size reached or an error occurred). */
bool pcap_dump_packet(pcap_dumper_t *dumper, const char *pkt, int pktlen,
                      const struct timeval *tv, int uid) {
    int tot_rec_size = pcap_rec_size(dumper, pktlen);

    if((PCAP_BUFFER_SIZE - dumper->buffer_idx) <= tot_rec_size)
        export_buffer(dumper);

    if ((PCAP_BUFFER_SIZE - dumper->buffer_idx) <= tot_rec_size) {
        log_e("Invalid buffer size [size=%d, idx=%d, dump_size=%d]",
              PCAP_BUFFER_SIZE, dumper->buffer_idx, tot_rec_size);
        return false;
    } else if((dumper->max_dump_size > 0) &&
            ((dumper->dump_size + tot_rec_size) >= dumper->max_dump_size)) {
        log_i("Max dump size reached, stop the dump");
        return false;
    }

    // NOTE: buffer_idx may be reset by export_buffer above
    int8_t *buffer = dumper->buffer + dumper->buffer_idx;
    pcap_rec_t *pcap_rec = (pcap_rec_t*) buffer;
    int offset = 0;

    pcap_rec->ts_sec = tv->tv_sec;
    pcap_rec->ts_usec = tv->tv_usec;
    pcap_rec->incl_len = tot_rec_size - (int)sizeof(struct pcap_rec);
    pcap_rec->orig_len = pktlen;
    buffer += sizeof(struct pcap_rec);

    if(dumper->mode == PCAP_DUMP_WITH_TRAILER) {
        if((((uint64_t)buffer) & 0x03) != 0)
            log_w("Unaligned buffer!");

        // Insert the bogus header: both the MAC addresses are 0
        struct ethhdr *eth = (struct ethhdr*) buffer;
        memset(eth, 0, sizeof(struct ethhdr));
        eth->h_proto = htons((((*pkt) >> 4) == 4) ? ETH_P_IP : ETH_P_IPV6);

        pcap_rec->orig_len += sizeof(struct ethhdr);
        offset += sizeof(struct ethhdr);
    }

    int payload_to_copy = min(pktlen, pcap_rec->incl_len - offset);
    memcpy(buffer + offset, pkt, payload_to_copy);
    offset += payload_to_copy;

    if((dumper->mode == PCAP_DUMP_WITH_TRAILER) &&
       ((pcap_rec->incl_len - offset) >= sizeof(pcapdroid_trailer_t))) {
        // Pad the frame so that the buffer keeps its 4-bytes alignment
        // The padding is inserted before the PCAPdroid trailer so that accesses to pcapdroid_trailer_t
        // are also aligned.
        uint8_t padding = (~offset + 1) & 0x03;

        for(uint8_t i=0; i<padding; i++)
            buffer[offset++] = 0x00;

        // Populate the trailer
        pcapdroid_trailer_t *trailer = (pcapdroid_trailer_t*)(buffer + offset);
        memset(trailer, 0, sizeof(*trailer));

        trailer->magic = htonl(PCAPDROID_TRAILER_MAGIC);
        trailer->uid = htonl(uid);
        get_appname_by_uid(dumper->pd, uid, trailer->appname, sizeof(trailer->appname));

        //clock_t start = clock();
        trailer->fcs = crc32((u_char*) buffer, pcap_rec->incl_len - 4, 0);
        //double cpu_time_used = ((double) (clock() - start)) / CLOCKS_PER_SEC;
        //log_d("crc cpu_time_used: %f sec", cpu_time_used);

        pcap_rec->orig_len += padding + sizeof(*trailer);
    }

    dumper->buffer_idx += tot_rec_size;
    dumper->dump_size += tot_rec_size;
    pcap_check_export(dumper);
    return true;
}