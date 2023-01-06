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

#define NUM_PKTS 15

/* ******************************************************* */

/* Tests that invalid/unsupported IP packets are still dumped by PCAPdroid */
static void invalid_pkts() {
  pcap_hdr_t hdr;
  pcap_rec_t rec;
  pcapdroid_t *pd = pd_init_test(PCAP_PATH "/invalid_or_unsupported.pcap");
  int num_pkts = 0;

  // Run
  pd_dump_to_file(pd);
  pd_run(pd);
  pd_done_dump();

  // Verify
  assert_pcap_header(&hdr);

  while(next_pcap_record(&rec))
    num_pkts++;

  assert(num_pkts == NUM_PKTS);
  pd_free_test(pd);
}

/* ******************************************************* */

int main(int argc, char **argv) {
  add_test("invalid_pkts", invalid_pkts);

  run_test(argc, argv);
  return 0;
}
