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

#include "test_utils.h"

#define TEST_NAME_MAX_LENGTH 32
#define MAX_TESTS 32

typedef struct {
  char name[TEST_NAME_MAX_LENGTH];
  void (*cb)();
} test_spec;

static test_spec all_tests[MAX_TESTS] = {0};
static FILE *out_fp = NULL;
static u_char pcap_read_buf[65535];

static payload_chunk_t **chunks_lists_heads = NULL;
static int num_chunks_lists = 0;
static void free_payload_chunks(pcapdroid_t *pd);

/* ******************************************************* */

static void getPcapdPath(struct pcapdroid *pd, const char *prog_name, char *buf, int bufsize) {
  snprintf(buf, bufsize, "../main/pcapd/libpcapd.so");
}

/* ******************************************************* */

void add_test(const char *name, void (*test_cb)()) {
  int len = strlen(name);
  assert(len < TEST_NAME_MAX_LENGTH);

  for(int i=0; i<MAX_TESTS; i++) {
    if(all_tests[i].cb == NULL) {
      memcpy(all_tests[i].name, name, len); // \0 is already there
      all_tests[i].cb = test_cb;
      return;
    }
  }

  // MAX_TESTS exceeded
  assert(0);
}

/* ******************************************************* */

void run_test(int argc, char **argv) {
  assert(argc == 2);
  const char *test_name = argv[1];
  test_spec *test = NULL;

  for(int i=0; ((i < MAX_TESTS) && (all_tests[i].cb != NULL)); i++) {
    if(!strcmp(all_tests[i].name, test_name)) {
      test = &all_tests[i];
      break;
    }
  }

  assert(test != NULL);

  // run the test
  test->cb();
}

/* ******************************************************* */

pcapdroid_t* pd_init_test(const char *ifname) {
  pcapdroid_t *pd = calloc(1, sizeof(pcapdroid_t));
  assert(pd != NULL);

  pd->vpn_capture = false;
  pd->pcap_file_capture = true;
  pd->pcap.capture_interface = (char*) ifname;
  pd->pcap.as_root = false;   // don't run as root
  pd->cb.get_libprog_path = getPcapdPath;
  pd->payload_mode = PAYLOAD_MODE_FULL;

  strcpy(pd->cachedir, ".");
  pd->cachedir_len = 1;

  return pd;
}

/* ******************************************************* */

void pd_free_test(pcapdroid_t *pd) {
  free(pd);

  if(out_fp)
    fclose(out_fp);
  free_payload_chunks(pd);
}

/* ******************************************************* */

/* To be called during send_connections_dump. Looks up a connection
 * matching the specified protocol, IP port and info (if not NULL).
 * If no connection is found, abort is called. Only the first match is
 * returned.
 */
conn_and_tuple_t* assert_conn(pcapdroid_t *pd, int ipproto, const char *dst_ip,
          uint16_t dst_port, const char *info) {
  conn_and_tuple_t *found = NULL;
  zdtun_ip_t ip;
  dst_port = htons(dst_port);

  int ipver = zdtun_parse_ip(dst_ip, &ip);
  assert((ipver == 4) || (ipver == 6));

  for(int i=0; i < pd->new_conns.cur_items; i++) {
    conn_and_tuple_t *conn = &pd->new_conns.items[i];
    zdtun_ip_t dst_ip = conn->tuple.dst_ip;

    if((conn->tuple.ipproto == ipproto) &&
       (conn->tuple.dst_port == dst_port) &&
       (conn->tuple.ipver == ipver) &&
       (!zdtun_cmp_ip(ipver, &dst_ip, &ip)) &&
       ((info == NULL) || ((conn->data->info != NULL) && !strcmp(info, conn->data->info)))) {
      found = conn;
      break;
    }
  }

  assert(found);
  return found;
}

/* ******************************************************* */

static void dump_to_file_cb(struct pcapdroid *pd, const int8_t *buf, int len) {
  if(out_fp == NULL) {
    out_fp = fopen(PCAP_OUT_PATH, "wb+");

    if(!out_fp) {
      perror("Could not create PCAP file");
      exit(1);
    }

    // write the PCAP header
    pcap_hdr_t *hdr;
    assert(pcap_get_preamble(pd->pcap_dump.dumper, (char **)&hdr) == sizeof(*hdr));
    assert(fwrite(hdr, sizeof(*hdr), 1, out_fp) == 1);
    pd_free(hdr);
  }

  assert(fwrite(buf, len, 1, out_fp) == 1);
  fflush(out_fp);
}

/* Dump the packets to PCAP_OUT_PATH */
void pd_dump_to_file(pcapdroid_t *pd) {
  pd->cb.send_pcap_dump = dump_to_file_cb;
  pd->pcap_dump.enabled = 1;
}

/* ******************************************************* */

/* To be called with pd_dump_to_file after finishing dumping the file,
 * before any assert_pcap_*. */
void pd_done_dump() {
  assert(out_fp != NULL);

  fseek(out_fp, 0, SEEK_SET);
}

/* ******************************************************* */

/* Reads the PCAP header from the dump file and verify that is valid. */
void assert_pcap_header(pcap_hdr_t *hdr) {
  assert(out_fp != NULL);

  assert(fread(hdr, sizeof(pcap_hdr_t), 1, out_fp) == 1);

  assert(hdr->magic_number == 0xa1b2c3d4);
  assert(hdr->version_major == 2);
  assert(hdr->version_minor == 4);
}

/* ******************************************************* */

/* Reads a PCAP record and returns a buffer pointing to its data.
 * The data length available in the buffer is rec->incl_len.
 * Returns NULL on EOF. */
u_char* next_pcap_record(pcap_rec_t *rec) {
  int rv = fread(rec, sizeof(pcap_rec_t), 1, out_fp);

  if((rv != 1) && feof(out_fp))
    return NULL;

  assert(rv == 1);
  assert(rec->incl_len <= rec->orig_len);
  assert(rec->incl_len <= sizeof(pcap_read_buf));

  assert(fread(pcap_read_buf, rec->incl_len, 1, out_fp) == 1);
  return pcap_read_buf;
}

/* ******************************************************* */

/* Dumps all the payload chunks into a linked list. The linked list is accessible via
 * (payload_chunk_t*)data->payload_chunks */
bool dump_cb_payload_chunk(pcapdroid_t *pd, const pkt_context_t *pctx, const char *dump_data, int dump_size) {
  payload_chunk_t *chunk = calloc(1, sizeof(payload_chunk_t));
  assert(chunk != NULL);
  chunk->payload = (u_char*)malloc(dump_size);
  assert(chunk->payload != NULL);

  memcpy(chunk->payload, dump_data, dump_size);
  chunk->size = dump_size;
  chunk->is_tx = pctx->is_tx;

  // append to the linked list
  payload_chunk_t *last = (payload_chunk_t*)pctx->data->payload_chunks;
  if(last) {
    while(last->next)
      last = last->next;
    last->next = chunk;
  } else {
    // First chunk
    num_chunks_lists++;
    chunks_lists_heads = realloc(chunks_lists_heads, num_chunks_lists * sizeof(void*));
    chunks_lists_heads[num_chunks_lists - 1] = chunk;
    pctx->data->payload_chunks = chunk;
  }

  return true;
}

/* ******************************************************* */

static void free_payload_chunks(pcapdroid_t *pd) {
  for(int i=0; i<num_chunks_lists; i++) {
    payload_chunk_t *cur = chunks_lists_heads[i];

    while(cur) {
      payload_chunk_t *next = cur->next;
      free(cur->payload);
      free(cur);
      cur = next;
    }
  }

  free(chunks_lists_heads);
}
