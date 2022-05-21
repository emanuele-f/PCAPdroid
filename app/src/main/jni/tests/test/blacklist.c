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

/* ******************************************************* */

static void test_match() {
  blacklist_t *bl = blacklist_init();
  assert(bl != NULL);

  // Load blacklist
  assert0(blacklist_add_domain(bl, "example.org"));
  assert0(blacklist_add_ipstr(bl, "1.2.3.4"));
  assert0(blacklist_add_ipstr(bl, "::2"));
  assert0(blacklist_add_uid(bl, 777));
  assert0(blacklist_add_uid(bl, 888));

  // Use blacklist
  assert1(blacklist_match_domain(bl, "www.example.org"));
  assert1(blacklist_match_domain(bl, "some.example.org"));
  assert1(blacklist_match_domain(bl, "evil.some.example.org"));

  assert0(blacklist_match_ipstr(bl, "1.2.3.0"));
  assert1(blacklist_match_ipstr(bl, "1.2.3.4"));

  assert0(blacklist_match_ipstr(bl, "::1"));
  assert1(blacklist_match_ipstr(bl, "::2"));

  assert0(blacklist_match_uid(bl, 0));
  assert0(blacklist_match_uid(bl, 999));
  assert1(blacklist_match_uid(bl, 777));

  blacklist_destroy(bl);
}

/* ******************************************************* */

static void detection_cb(pcapdroid_t *pd) {
  conn_and_tuple_t *conn;

  // IP blacklist
  conn = assert_conn(pd, IPPROTO_ICMP, "1.1.1.1", 0, NULL);
  assert1(conn->data->blacklisted_ip);
  conn = assert_conn(pd, IPPROTO_TCP, "216.58.208.164", 80, NULL);
  assert1(conn->data->blacklisted_ip);
  conn = assert_conn(pd, IPPROTO_TCP, "2c9b:a9b9:83dd:d9d1::2003", 443, NULL);
  assert1(conn->data->blacklisted_ip);

  // Host blacklist
  conn = assert_conn(pd, IPPROTO_UDP, "8.8.8.8", 53, "www.google.it");
  assert0(conn->data->blacklisted_ip);
  assert1(conn->data->blacklisted_domain);
  conn = assert_conn(pd, IPPROTO_TCP, "146.112.255.155", 80, "www.internetbadguys.com");
  assert1(conn->data->blacklisted_domain);
  conn = assert_conn(pd, IPPROTO_TCP, "3a5d:15fe:e3cb:9c5f::2003", 443, "www.google.it");
  assert0(conn->data->blacklisted_ip);
  assert1(conn->data->blacklisted_domain);

  // Whitelist
  conn = assert_conn(pd, IPPROTO_TCP, "149.202.95.241", 80, "f-droid.org");
  assert0(conn->data->blacklisted_domain);
  assert0(conn->data->blacklisted_ip);
  conn = assert_conn(pd, IPPROTO_TCP, "2ed5:9050:81e9:4b68:248:1893:25c8:1946", 443, "example.org");
  assert0(conn->data->blacklisted_domain);
  assert0(conn->data->blacklisted_ip);
}

static void test_detection() {
  pcapdroid_t *pd = pd_init_test(PCAP_PATH "/metadata.pcap");

  blacklist_t *bl = blacklist_init();
  assert(bl != NULL);
  blacklist_t *wl = blacklist_init();
  assert(wl != NULL);
  pd->malware_detection.enabled = true;
  pd->malware_detection.bl = bl;
  pd->malware_detection.whitelist = wl;

  // Load blacklist
  blacklist_add_ipstr(bl, "1.1.1.1");
  blacklist_add_ipstr(bl, "216.58.208.164");
  blacklist_add_ipstr(bl, "2c9b:a9b9:83dd:d9d1::2003");
  blacklist_add_ipstr(bl, "149.202.95.241");
  blacklist_add_domain(bl, "google.it");
  blacklist_add_domain(bl, "www.internetbadguys.com");
  blacklist_add_domain(bl, "example.org");

  // Load whitelist
  blacklist_add_ipstr(wl, "149.202.95.241");
  blacklist_add_domain(wl, "example.org");

  // Run
  pd->cb.send_connections_dump = detection_cb;
  pd_run(pd);

  pd_free_test(pd);
}

/* ******************************************************* */

int main(int argc, char **argv) {
  add_test("match", test_match);
  add_test("detection", test_detection);

  run_test(argc, argv);
  return 0;
}
