/*
    This file is part of RemoteCapture.

    RemoteCapture is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    RemoteCapture is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with RemoteCapture.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2019 by Emanuele Faranda
*/

#include <stdlib.h>
#include <time.h>
#include <string.h>
#include <sys/socket.h>
#include <android/log.h>
#include <errno.h>

/* ******************************************************* */

#define LINKTYPE_RAW 101
#define PCAP_TAG "PCAP_DUMP"
#define SNAPLEN 65535

typedef uint16_t guint16_t;
typedef uint32_t guint32_t;
typedef int32_t gint32_t;

typedef struct pcap_hdr_s {
    guint32_t magic_number;
    guint16_t version_major;
    guint16_t version_minor;
    gint32_t thiszone;
    guint32_t sigfigs;
    guint32_t snaplen;
    guint32_t network;
} __packed pcap_hdr_s;

typedef struct pcaprec_hdr_s {
    guint32_t ts_sec;
    guint32_t ts_usec;
    guint32_t incl_len;
    guint32_t orig_len;
} __packed pcaprec_hdr_s;

/* ******************************************************* */

static size_t frame_id = 1;
static uint8_t pcap_buffer[sizeof(struct pcaprec_hdr_s) + SNAPLEN];

/* ******************************************************* */

static void write_pcap(int fd, const struct sockaddr *srv, size_t srv_size, const void *ptr, size_t len) {
  if(sendto(fd, ptr, len, 0, srv, srv_size) < 0)
      __android_log_print(ANDROID_LOG_ERROR, PCAP_TAG, "sendto error[%d]: %s", errno, strerror(errno));
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
    size_t incl_len, tot_len;
    struct timespec ts;
    struct pcaprec_hdr_s *pcap_rec;

    if (clock_gettime(CLOCK_REALTIME, &ts))
        __android_log_print(ANDROID_LOG_ERROR, PCAP_TAG, "clock_gettime error[%d]: %s", errno, strerror(errno));

    incl_len = (length < SNAPLEN ? length : SNAPLEN);
    tot_len = sizeof(struct pcaprec_hdr_s) + incl_len;

    pcap_rec = (struct pcaprec_hdr_s *) pcap_buffer;
    pcap_rec->ts_sec = (guint32_t) ts.tv_sec;
    pcap_rec->ts_usec = (guint32_t) (ts.tv_nsec / 1000);
    pcap_rec->incl_len = (guint32_t) incl_len;
    pcap_rec->orig_len = (guint32_t) length;

    memcpy(pcap_buffer + sizeof(struct pcaprec_hdr_s), buffer, incl_len);

    write_pcap(fd, srv, srv_size, pcap_rec, tot_len);
}
