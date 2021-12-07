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

#include <linux/if_ether.h>
#include "common/utils.h"
#include "pcapdroid.h"
#include "pcap_utils.h"

#define SNAPLEN 65535
#define LINKTYPE_ETHERNET 1
#define LINKTYPE_RAW      101

static uint8_t pcapdroid_trailer = 0;

/* ******************************************************* */

/* Enable the addition of the pcapdroid_trailer_t to the PCAP */
void pcap_set_pcapdroid_trailer(uint8_t enabled) {
    pcapdroid_trailer = enabled;
}

/* ******************************************************* */

void pcap_build_hdr(struct pcap_hdr_s *pcap_hdr) {
    pcap_hdr->magic_number = 0xa1b2c3d4;
    pcap_hdr->version_major = 2;
    pcap_hdr->version_minor = 4;
    pcap_hdr->thiszone = 0;
    pcap_hdr->sigfigs = 0;
    pcap_hdr->snaplen = SNAPLEN;
    pcap_hdr->network = pcapdroid_trailer ? LINKTYPE_ETHERNET : LINKTYPE_RAW;
}

/* ******************************************************* */

/* Returns the size of a PCAP record */
int pcap_rec_size(int pkt_len) {
    if(pcapdroid_trailer) {
        pkt_len += (int)(sizeof(pcapdroid_trailer_t) + sizeof(struct ethhdr));

        // Pad the frame so that the buffer keeps its 4-bytes alignment
        pkt_len += (~pkt_len + 1) & 0x3;
    }

    return((pkt_len < SNAPLEN ? pkt_len : SNAPLEN) +
            (int)sizeof(struct pcaprec_hdr_s));
}

/* ******************************************************* */

/* Dumps a packet into the provided buffer. The buffer must have at least pcap_rec_size()
 * bytes available */
void pcap_dump_rec(pcapdroid_t *pd, u_char *buffer, pkt_context_t *pctx) {
    const zdtun_pkt_t *pkt = pctx->pkt;
    struct pcaprec_hdr_s *pcap_rec = (pcaprec_hdr_s*) buffer;
    int offset = 0;

    pcap_rec->ts_sec = pctx->tv.tv_sec;
    pcap_rec->ts_usec = pctx->tv.tv_usec;
    pcap_rec->incl_len = pcap_rec_size(pkt->len) - (int)sizeof(struct pcaprec_hdr_s);
    pcap_rec->orig_len = pkt->len;
    buffer += sizeof(struct pcaprec_hdr_s);

    if(pcapdroid_trailer) {
        if((((uint64_t)buffer) & 0x03) != 0)
            log_w("Unaligned buffer!");

        // Insert the bogus header: both the MAC addresses are 0
        struct ethhdr *eth = (struct ethhdr*) buffer;
        memset(eth, 0, sizeof(struct ethhdr));
        eth->h_proto = htons((((*pkt->buf) >> 4) == 4) ? ETH_P_IP : ETH_P_IPV6);

        pcap_rec->orig_len += sizeof(struct ethhdr);
        offset += sizeof(struct ethhdr);
    }

    int payload_to_copy = min(pkt->len, pcap_rec->incl_len - offset);
    memcpy(buffer + offset, pkt->buf, payload_to_copy);
    offset += payload_to_copy;

    if(pcapdroid_trailer &&
       ((pcap_rec->incl_len - offset) >= sizeof(pcapdroid_trailer_t))) {
        // Pad the frame so that the buffer keeps its 4-bytes alignment
        // The padding is inserted before the PCAPdroid trailer so that accesses to pcapdroid_trailer_t
        // are also aligned.
        uint8_t padding = (~offset + 1) & 0x03;

        for(uint8_t i=0; i<padding; i++)
            buffer[offset++] = 0x00;

        // Populate the custom data
        pcapdroid_trailer_t *cdata = (pcapdroid_trailer_t*)(buffer + offset);

        fill_custom_data(cdata, pd, pctx->data);

        //clock_t start = clock();
        cdata->fcs = crc32(buffer, pcap_rec->incl_len - 4, 0);
        //double cpu_time_used = ((double) (clock() - start)) / CLOCKS_PER_SEC;
        //log_d("crc cpu_time_used: %f sec", cpu_time_used);

        pcap_rec->orig_len += padding + sizeof(pcapdroid_trailer_t);
    }
}
