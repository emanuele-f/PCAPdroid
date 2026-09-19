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
 * Copyright 2022-26 - Emanuele Faranda
 */

#define _GNU_SOURCE // for memmem
#include "test_utils.h"

/* ******************************************************* */

// Called on send_connections_dump. pd->new_conns contains the dumped
// connections. Ensures that metadata is correctly extracted from
// network traffic.
static void extract_metadata_cb(pcapdroid_t *pd) {
  conn_and_tuple_t *conn;

  // DNS request without reply
  conn = assert_conn(pd, IPPROTO_UDP, "8.8.8.8", 53, "example.org");
  assert(conn->tuple.src_port == htons(48037));

  // DNS (TCP)
  assert_conn(pd, IPPROTO_TCP, "8.8.8.8", 53, "f-droid.org");

  // Guess host name from previous DNS request
  assert_conn(pd, IPPROTO_TCP, "149.202.95.241", 80, "f-droid.org");

  // HTTP
  conn = assert_conn(pd, IPPROTO_TCP, "216.58.208.164", 80, "www.google.com");
  assert(!strcmp(conn->data->url, "www.google.com/imghp?test=1&v2=2"));
  conn = assert_conn(pd, IPPROTO_TCP, "385d:1ee:e3c9:9c5f::2004", 80, "www.google.com");
  assert(!strcmp(conn->data->url, "www.google.com/imghp?test=1&v2=2"));

  // TLS
  conn = assert_conn(pd, IPPROTO_TCP, "142.250.180.131", 443, "google.it");
  assert(conn->data->l7proto == NDPI_PROTOCOL_TLS);
  conn = assert_conn(pd, IPPROTO_TCP, "2ed5:9050:81e9:4b68:248:1893:25c8:1946", 443, "example.org");
  assert(conn->data->l7proto == NDPI_PROTOCOL_TLS);
}

static void test_metadata_extraction() {
  pcapdroid_t *pd = pd_init_test(PCAP_PATH "/metadata.pcap");

  pd->cb.send_connections_dump = extract_metadata_cb;
  pd_run(pd);

  pd_free_test(pd);
}

/* ******************************************************* */

static void extract_proxy_cb(pcapdroid_t *pd) {
    // HTTP proxy
    conn_and_tuple_t * conn = assert_conn(pd, IPPROTO_TCP, "85.25.246.38", 8080, "weberblog.net");
    assert(conn->data->l7proto == NDPI_PROTOCOL_HTTP);
    assert(!strcmp(conn->data->url, "15.35.226.136:443"));

    // payload extraction
    payload_chunk_t *chunk = (payload_chunk_t*) conn->data->payload_chunks;
    assert(chunk != NULL);
    assert(memmem(chunk->payload, chunk->size, "CONNECT 15.35.226.136:443", 25) != 0);
}

static void test_proxy_extraction() {
    pcapdroid_t *pd = pd_init_test(PCAP_PATH "/http_proxy.pcap");

    pd->cb.dump_payload_chunk = dump_cb_payload_chunk;
    pd->cb.send_connections_dump = extract_proxy_cb;
    pd_run(pd);

    pd_free_test(pd);
}

/* ******************************************************* */

static FILE *offset_check_fp = NULL;
static int num_checked_chunks = 0;
static long checked_bytes = 0;

// Verifies that the chunk data can be read back from the pcap file at the reported offset
static bool check_offset_payload_chunk(pcapdroid_t *pd, pd_conn_t *conn, bool is_tx, uint64_t ms, uint32_t stream_id,
                                       const char *dump_data, int dump_size, int64_t file_offset) {
    assert(file_offset >= 0);

    char *file_data = malloc(dump_size);
    assert(file_data != NULL);

    assert(fseek(offset_check_fp, (long) file_offset, SEEK_SET) == 0);
    assert(fread(file_data, dump_size, 1, offset_check_fp) == 1);
    assert(memcmp(file_data, dump_data, dump_size) == 0);
    free(file_data);

    num_checked_chunks++;
    checked_bytes += dump_size;
    return true;
}

// returns the number of payload bytes checked
static long run_payload_offsets_check(const char *pcap_path) {
    pcapdroid_t *pd = pd_init_test(pcap_path);

    offset_check_fp = fopen(pcap_path, "rb");
    assert(offset_check_fp != NULL);
    num_checked_chunks = 0;
    checked_bytes = 0;

    pd->cb.dump_payload_chunk = check_offset_payload_chunk;
    pd_run(pd);

    assert(num_checked_chunks > 0);

    fclose(offset_check_fp);
    offset_check_fp = NULL;
    pd_free_test(pd);

    return checked_bytes;
}

static void test_payload_offsets() {
    long full_bytes = run_payload_offsets_check(PCAP_PATH "/metadata.pcap");

    // the snaplen files contain truncated packets, whose partial payload must still match
    long pcap_snaplen_bytes = run_payload_offsets_check(PCAP_PATH "/metadata_snaplen.pcap");
    long pcapng_snaplen_bytes = run_payload_offsets_check(PCAP_PATH "/metadata_snaplen.pcapng");

    assert(pcap_snaplen_bytes < full_bytes);
    assert(pcapng_snaplen_bytes == pcap_snaplen_bytes);
}

/* ******************************************************* */

int main(int argc, char **argv) {
  add_test("extract", test_metadata_extraction);
  add_test("extract_proxy", test_proxy_extraction);
  add_test("payload_offsets", test_payload_offsets);

  run_test(argc, argv);
  return 0;
}
