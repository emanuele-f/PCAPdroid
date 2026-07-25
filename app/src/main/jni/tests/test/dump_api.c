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

#include "test_utils.h"
#include "core/pcap_reader.h"
#include "pcapd/pcapd.h"

#define TEST_SNAPLEN 256
#define TEST_MAX_PKTS 8
#define TEST_MAX_SIZE 10240

/* ******************************************************* */

/* Tests that packets are correctly truncated to honor the "snaplen"
 * dump parameter. */
static void test_snaplen() {
  pcap_hdr_t hdr;
  pcap_rec_t rec;
  pcapdroid_t *pd = pd_init_test(PCAP_PATH "/metadata.pcap");
  bool at_least_one_pkt_truncated = false;

  // Run
  pd->pcap_dump.snaplen = TEST_SNAPLEN;
  pd_dump_to_file(pd);
  pd_run(pd);
  pd_done_dump();

  // Verify
  assert_pcap_header(&hdr);
  assert(hdr.snaplen == TEST_SNAPLEN);

  // The size of all the packets must be <= of the snaplen
  while(next_pcap_record(&rec)) {
    assert(rec.incl_len <= TEST_SNAPLEN);
    at_least_one_pkt_truncated |= (rec.incl_len != rec.orig_len);
  }

  // For this test to be effective, at least one packet must be trucated
  // due to the snaplen
  assert(at_least_one_pkt_truncated);

  pd_free_test(pd);
}

/* ******************************************************* */

/* Tests that at most "max_pkts_per_flow" packets are dumped for each
 * flow. */
static void max_pkts_per_flow() {
  pcap_hdr_t hdr;
  pcap_rec_t rec;
  u_char *buf;
  int num_pkts = 0;
  pcapdroid_t *pd = pd_init_test(PCAP_PATH "/two_flows.pcap");
  zdtun_ip_t local_ip, ip1, ip2;

  assert(zdtun_parse_ip("192.168.1.10", &local_ip) == 4);
  assert(zdtun_parse_ip("216.58.208.164", &ip1) == 4);
  assert(zdtun_parse_ip("142.250.180.131", &ip2) == 4);

  // Run
  pd->pcap_dump.max_pkts_per_flow = TEST_MAX_PKTS;
  pd_dump_to_file(pd);
  pd_run(pd);
  pd_done_dump();

  // Verify
  assert_pcap_header(&hdr);

  while((buf = next_pcap_record(&rec))) {
    zdtun_pkt_t pkt;
    zdtun_ip_t *expected_ip;
    zdtun_ip_t *remote_ip;
    zdtun_ip_t src_ip, dst_ip;

    assert0(zdtun_parse_pkt(pd->zdt, (char*)buf, rec.incl_len, &pkt));
    src_ip = pkt.tuple.src_ip;
    dst_ip = pkt.tuple.dst_ip;

    remote_ip = (zdtun_cmp_ip(4, &src_ip, &local_ip) == 0) ?
        &dst_ip : &src_ip;

#if 0
    char ip[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, remote_ip, ip, sizeof(ip));
    log_i("IP: %s", ip);
#endif

    // The pcap files contains two consecutive flows. PCAPdroid must
    // only dump the first TEST_MAX_PKTS of each flow.
    expected_ip = (num_pkts < TEST_MAX_PKTS) ? &ip1 : &ip2;
    assert_ip_equal(4, remote_ip, expected_ip);

    num_pkts++;
  }

  assert(num_pkts == 2 * TEST_MAX_PKTS);

  pd_free_test(pd);
}

/* ******************************************************* */

/* Tests that at most "max_dump_size" bytes are dumped. */
static void max_dump_size() {
  pcap_hdr_t hdr;
  pcap_rec_t rec;
  pcapdroid_t *pd = pd_init_test(PCAP_PATH "/metadata.pcap");
  u_int dump_size;

  // Run
  pd->pcap_dump.max_dump_size = TEST_MAX_SIZE;
  pd_dump_to_file(pd);
  pd_run(pd);
  pd_done_dump();

  // Verify
  assert_pcap_header(&hdr);
  dump_size = sizeof(hdr);

  while(next_pcap_record(&rec))
    dump_size += sizeof(rec) + rec.incl_len;

  assert(dump_size <= TEST_MAX_SIZE);

  pd_free_test(pd);
}

/* ******************************************************* */

static FILE *roundtrip_fp = NULL;

static void roundtrip_dump_cb(struct pcapdroid *pd, const int8_t *buf, int len) {
  assert(fwrite(buf, len, 1, roundtrip_fp) == 1);
}

/* Tests that the packet direction survives a Pcapng dump + reload. The direction
 * is carried by the EPB flags option: without it the reader would default every
 * reloaded packet to TX, losing the direction set by the capture heuristic.
 *
 * dump_extensions is enabled to increase coverage.
 */
static void direction_roundtrip() {
  pcapdroid_t *pd = pd_init_test(PCAP_PATH "/metadata.pcap");

  // A minimal IPv4 packet: its content is irrelevant to the direction check
  u_char pkt[20] = {0};
  pkt[0] = 0x45; // version 4, IHL 5
  struct timeval tv = { .tv_sec = 1234, .tv_usec = 567 };

  pcap_dumper_t *dumper = pcap_new_dumper(PCAPNG_DUMP, true, PCAPD_SNAPLEN,
                                          0, roundtrip_dump_cb, pd);
  assert(dumper != NULL);

  roundtrip_fp = fopen(PCAP_OUT_PATH, "wb+");
  assert(roundtrip_fp != NULL);

  char *preamble;
  int preamble_sz = pcap_get_preamble(dumper, &preamble);
  assert(preamble_sz > 0);
  assert(fwrite(preamble, preamble_sz, 1, roundtrip_fp) == 1);
  pd_free(preamble);

  // Dump a TX packet followed by an RX packet
  assert(pcap_dump_packet(dumper, (char*)pkt, sizeof(pkt), &tv, 1000, 0, true));
  assert(pcap_dump_packet(dumper, (char*)pkt, sizeof(pkt), &tv, 1000, 0, false));

  pcap_destroy_dumper(dumper); // flushes the buffered packets
  fclose(roundtrip_fp);
  roundtrip_fp = NULL;

  // Read the packets back and verify the direction is preserved
  char *error = NULL;
  pd_reader_t *reader = pd_new_reader(PCAP_OUT_PATH, &error);
  assert(reader != NULL);
  assert(pd_get_dump_format(reader) == PCAPNG_DUMP);

  char buffer[PCAPD_SNAPLEN];
  pcapd_hdr_t hdr;
  pd_read_callbacks_t cb = {0};

  assert(pd_read_next(reader, &hdr, buffer, &cb, NULL) == READER_PACKET_OK);
  assert(hdr.flags & PCAPD_FLAG_TX);

  assert(pd_read_next(reader, &hdr, buffer, &cb, NULL) == READER_PACKET_OK);
  assert(!(hdr.flags & PCAPD_FLAG_TX));

  assert(pd_read_next(reader, &hdr, buffer, &cb, NULL) == READER_EOF);

  pd_destroy_reader(reader);
  pd_free_test(pd);
}

/* ******************************************************* */

int main(int argc, char **argv) {
  add_test("snaplen", test_snaplen);
  add_test("max_pkts_per_flow", max_pkts_per_flow);
  add_test("max_dump_size", max_dump_size);
  add_test("direction_roundtrip", direction_roundtrip);

  run_test(argc, argv);
  return 0;
}
