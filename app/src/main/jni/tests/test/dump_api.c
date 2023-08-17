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

int main(int argc, char **argv) {
  add_test("snaplen", test_snaplen);
  add_test("max_pkts_per_flow", max_pkts_per_flow);
  add_test("max_dump_size", max_dump_size);

  run_test(argc, argv);
  return 0;
}
