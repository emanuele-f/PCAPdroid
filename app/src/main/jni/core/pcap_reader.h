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
 * Copyright 2021-25 - Emanuele Faranda
 */

#ifndef __PCAPDROID_PCAP_READER_H__
#define __PCAPDROID_PCAP_READER_H__

/*
 * Module to read PCAP/Pcapng files.
 * It only supports captures created by the pcap_dump module on the same endianness.
 */

#include <stdint.h>
#include <stdbool.h>
#include <sys/types.h>

#include "pcapd/pcapd.h"
#include "pcap_dump.h"

typedef struct pd_reader pd_reader_t;

typedef enum {
    READER_PACKET_OK,       // a packet was read successfully into the buffer
    READER_CONTINUE,        // continue reading next packet (internal use)
    READER_EOF,
    READER_ERROR
} reader_rv;

typedef struct pd_read_callbacks {
    void (*on_uid_mapping)(void *userdata, uid_t uid, const char *package_name, const char *app_name);
    void (*on_dump_extensions_seen)(void *userdata);
} pd_read_callbacks_t;

pd_reader_t* pd_new_reader(const char *fpath, char **error);
void pd_destroy_reader(pd_reader_t *reader);
pcap_dump_format_t pd_get_dump_format(pd_reader_t *reader);
bool pd_has_unsupported_dlt_packets(pd_reader_t *reader);
bool pd_has_seen_dump_extensions(pd_reader_t *reader);

/**
 * Read the next packet
 * @param hdr will be filled with header information on successful read
 * @param buffer must be of PCAPD_SNAPLEN size, will contain the packet data
 * @param cb a structure defining the possibly null callbacks
 * @param userdata opaque data passed to the callbacks
 */
reader_rv pd_read_next(pd_reader_t *reader, pcapd_hdr_t *hdr, char* buffer, pd_read_callbacks_t *cb, void *userdata);

#endif