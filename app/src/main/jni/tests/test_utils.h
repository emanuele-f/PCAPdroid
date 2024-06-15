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
 * Copyright 2022 - Emanuele Faranda
 */

#ifndef __TEST_UTILS_H__
#define __TEST_UTILS_H__

#include "core/pcapdroid.h"
#include "core/pcap_dump.h"
#include "common/memtrack.h"
#include <assert.h>

#define assert0(x) assert((x) == 0)
#define assert1(x) assert((x) == 1)
#define assert_ip_equal(ipver, a, b) assert(zdtun_cmp_ip((ipver), (a), (b)) == 0)

#define PCAP_PATH "../../../pcap"
#define PCAP_OUT_PATH "/tmp/pcapdroid_test_out.pcap"

void add_test(const char *name, void (*test_cb)());
void run_test(int argc, char **argv);

typedef struct payload_chunk {
  u_char *payload;
  int size;
  bool is_tx;
  struct payload_chunk *next;
} payload_chunk_t;

pcapdroid_t* pd_init_test(const char *ifname);
void pd_free_test(pcapdroid_t *pd);

// PCAP dump
void pd_dump_to_file(pcapdroid_t *pd);
void pd_done_dump();
void assert_pcap_header(pcap_hdr_t *hdr);
u_char* next_pcap_record(pcap_rec_t *rec);

// Callbacks
bool dump_cb_payload_chunk(pcapdroid_t *pd, const pkt_context_t *pctx, const char *dump_data, int dump_size);

conn_and_tuple_t* assert_conn(pcapdroid_t *pd, int ipproto, const char *dst_ip, uint16_t dst_port, const char *info);

#endif
