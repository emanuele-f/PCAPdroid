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
 * Copyright 2020 - Emanuele Faranda
 */

#include <stdlib.h>
#include <time.h>
#include <string.h>
#include <sys/socket.h>
#include <android/log.h>
#include <errno.h>
#include "pcap.h"

/* ******************************************************* */

#define LINKTYPE_RAW 101
#define PCAP_TAG "PCAP_DUMP"
#define SNAPLEN 65535

/* ******************************************************* */

static size_t frame_id = 1;
static uint8_t pcap_buffer[sizeof(struct pcaprec_hdr_s) + SNAPLEN];

/* ******************************************************* */

static void write_pcap(int fd, const struct sockaddr *srv, size_t srv_size, const void *ptr, size_t len) {
  if(sendto(fd, ptr, len, 0, srv, srv_size) < 0)
      __android_log_print(ANDROID_LOG_ERROR, PCAP_TAG, "sendto error[%d]: %s", errno, strerror(errno));
}

/* ******************************************************* */

static size_t init_pcap_rec_hdr(struct pcaprec_hdr_s *pcap_rec, int length) {
    size_t incl_len;
    struct timespec ts;

    if (clock_gettime(CLOCK_REALTIME, &ts))
        __android_log_print(ANDROID_LOG_ERROR, PCAP_TAG, "clock_gettime error[%d]: %s", errno, strerror(errno));

    incl_len = (length < SNAPLEN ? length : SNAPLEN);

    pcap_rec->ts_sec = (guint32_t) ts.tv_sec;
    pcap_rec->ts_usec = (guint32_t) (ts.tv_nsec / 1000);
    pcap_rec->incl_len = (guint32_t) incl_len;
    pcap_rec->orig_len = (guint32_t) length;

    pcap_rec->ts_sec = (guint32_t) ts.tv_sec;
    pcap_rec->ts_usec = (guint32_t) (ts.tv_nsec / 1000);
    pcap_rec->incl_len = (guint32_t) incl_len;
    pcap_rec->orig_len = (guint32_t) length;

    return(incl_len);
}

/* ******************************************************* */

void write_pcap_hdr(int fd, const struct sockaddr *srv, size_t srv_size) {
    struct pcap_hdr_s pcap_hdr;
    pcap_hdr.magic_number = 0xa1b2c3d4;
    pcap_hdr.version_major = 2;
    pcap_hdr.version_minor = 4;
    pcap_hdr.thiszone = 0;
    pcap_hdr.sigfigs = 0;
    pcap_hdr.snaplen = SNAPLEN;
    pcap_hdr.network = LINKTYPE_RAW;
    write_pcap(fd, srv, srv_size, &pcap_hdr, sizeof(struct pcap_hdr_s));
}

/* ******************************************************* */

void write_pcap_rec(int fd, const struct sockaddr *srv, size_t srv_size, const uint8_t *buffer, size_t length) {
    struct pcaprec_hdr_s *pcap_rec = (struct pcaprec_hdr_s *) pcap_buffer;

    size_t incl_len = init_pcap_rec_hdr(pcap_rec, length);
    size_t tot_len = sizeof(struct pcaprec_hdr_s) + incl_len;

    // NOTE: use incl_size as the packet may be cut due to the SNAPLEN
    memcpy(pcap_buffer + sizeof(struct pcaprec_hdr_s), buffer, incl_len);

    write_pcap(fd, srv, srv_size, pcap_rec, tot_len);
}

/* ******************************************************* */

size_t dump_pcap_rec(u_char *buffer, const u_char *pkt, size_t pkt_len) {
    struct pcaprec_hdr_s *pcap_rec = (pcaprec_hdr_s*) buffer;

    size_t incl_len = init_pcap_rec_hdr(pcap_rec, pkt_len);
    size_t tot_len = sizeof(struct pcaprec_hdr_s) + incl_len;

    // NOTE: use incl_size as the packet may be cut due to the SNAPLEN
    // Assumption: there is enough available space in buffer
    memcpy(buffer + sizeof(struct pcaprec_hdr_s), pkt, incl_len);

    return(tot_len);
}
