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

/* The following tests exercise the blacklist/whitelist/malware match logic of
 * pd_new_connection directly, building the 5-tuple by hand and inspecting the
 * resulting pd_conn_t flags, without going through a full capture. */

static pcapdroid_t* match_test_init() {
  pcapdroid_t *pd = pd_init_test(NULL);

  pd->ip_to_host = ip_lru_init(MAX_HOST_LRU_SIZE);
  assert(pd->ip_to_host != NULL);

  return pd;
}

static void match_test_free(pcapdroid_t *pd) {
  // The active connections table only holds wrappers; the pd_conn_t data is
  // owned by pd->new_conns and freed below
  pcap_free_test_connections(pd);

  for(int i=0; i < pd->new_conns.cur_items; i++)
    pd_purge_connection(pd, pd->new_conns.items[i].data);
  pd_free(pd->new_conns.items);

  ip_lru_destroy(pd->ip_to_host);

  if(pd->malware_detection.bl)
    blacklist_destroy(pd->malware_detection.bl);
  if(pd->malware_detection.whitelist)
    blacklist_destroy(pd->malware_detection.whitelist);
  if(pd->firewall.bl)
    blacklist_destroy(pd->firewall.bl);
  if(pd->firewall.wl)
    blacklist_destroy(pd->firewall.wl);

  pd_free_test(pd);
}

/* Build a 5-tuple for the given destination and run it through pd_new_connection.
 * If host is not NULL, it is first registered in the host LRU cache so that the
 * domain match logic kicks in. */
static pd_conn_t* match_new_conn(pcapdroid_t *pd, int ipproto, const char *dst_ip,
          uint16_t dst_port, int uid, const char *host) {
  zdtun_5tuple_t tuple;
  zdtun_ip_t ip;
  memset(&tuple, 0, sizeof(tuple));

  int ipver = zdtun_parse_ip(dst_ip, &ip);
  assert((ipver == 4) || (ipver == 6));

  tuple.ipver = ipver;
  tuple.ipproto = ipproto;
  tuple.src_port = htons(40000);
  tuple.dst_port = htons(dst_port);
  tuple.dst_ip = ip;

  if(host)
    ip_lru_add(pd->ip_to_host, &ip, host);

  pd_conn_t *data = pd_new_connection(pd, &tuple, uid);
  assert(data != NULL);

  return data;
}

/* Like match_new_conn, but also registers the connection in the active
 * connections table so that the pd_housekeeping reload logic can re-evaluate it.
 * A distinct src_port keeps the 5-tuples unique across calls. */
static pd_conn_t* match_add_active_conn(pcapdroid_t *pd, int ipproto, const char *dst_ip,
          uint16_t dst_port, uint16_t src_port, int uid, const char *host) {
  zdtun_5tuple_t tuple;
  zdtun_ip_t ip;
  memset(&tuple, 0, sizeof(tuple));

  int ipver = zdtun_parse_ip(dst_ip, &ip);
  assert((ipver == 4) || (ipver == 6));

  tuple.ipver = ipver;
  tuple.ipproto = ipproto;
  tuple.src_port = htons(src_port);
  tuple.dst_port = htons(dst_port);
  tuple.dst_ip = ip;

  if(host)
    ip_lru_add(pd->ip_to_host, &ip, host);

  pd_conn_t *data = pd_new_connection(pd, &tuple, uid);
  assert(data != NULL);

  pcap_add_test_connection(pd, &tuple, data);

  return data;
}

/* ******************************************************* */

static void test_malware_match() {
  pcapdroid_t *pd = match_test_init();

  blacklist_t *bl = blacklist_init();
  assert(bl != NULL);
  blacklist_t *wl = blacklist_init();
  assert(wl != NULL);

  pd->malware_detection.enabled = true;
  pd->malware_detection.bl = bl;
  pd->malware_detection.whitelist = wl;

  assert0(blacklist_add_ipstr(bl, "1.2.3.4"));
  assert0(blacklist_add_ipstr(bl, "dead::beef"));
  assert0(blacklist_add_domain(bl, "evil.com"));
  assert0(blacklist_add_uid(bl, 1000)); // uid blacklist is ignored by malware detection
  assert0(blacklist_add_ipstr(wl, "5.6.7.8"));
  assert0(blacklist_add_domain(wl, "good.com"));
  assert0(blacklist_add_uid(wl, 4242));

  pd_conn_t *data;

  // Blacklisted IPv4 -> flagged and blocked
  data = match_new_conn(pd, IPPROTO_TCP, "1.2.3.4", 443, 100, NULL);
  assert1(data->blacklisted_ip);
  assert1(data->to_block);
  assert0(data->blacklisted_domain);
  assert0(data->whitelisted_app);

  // Blacklisted IPv6 -> flagged and blocked
  data = match_new_conn(pd, IPPROTO_TCP, "dead::beef", 443, 100, NULL);
  assert1(data->blacklisted_ip);
  assert1(data->to_block);

  // Clean IP -> not flagged
  data = match_new_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 100, NULL);
  assert0(data->blacklisted_ip);
  assert0(data->blacklisted_domain);
  assert0(data->to_block);

  // A uid in the malware blacklist must NOT cause a block: malware detection
  // only matches on IP and domain, not on uid
  data = match_new_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 1000, NULL);
  assert0(data->blacklisted_ip);
  assert0(data->to_block);

  // Blacklisted domain (resolved via the host LRU) -> flagged and blocked
  data = match_new_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 100, "sub.evil.com");
  assert1(data->blacklisted_domain);
  assert1(data->to_block);
  assert0(data->blacklisted_ip);

  // Whitelisted IP overrides the IP blacklist
  assert0(blacklist_add_ipstr(bl, "5.6.7.8"));
  data = match_new_conn(pd, IPPROTO_TCP, "5.6.7.8", 443, 100, NULL);
  assert0(data->blacklisted_ip);
  assert0(data->to_block);

  // Whitelisted domain overrides the domain blacklist
  assert0(blacklist_add_domain(bl, "good.com"));
  data = match_new_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 100, "www.good.com");
  assert0(data->blacklisted_domain);
  assert0(data->to_block);

  // Whitelisted app: blacklisted IP/domain checks are skipped entirely
  data = match_new_conn(pd, IPPROTO_TCP, "1.2.3.4", 443, 4242, "sub.evil.com");
  assert1(data->whitelisted_app);
  assert0(data->blacklisted_ip);
  assert0(data->blacklisted_domain);
  assert0(data->to_block);

  match_test_free(pd);
}

/* ******************************************************* */

static void test_firewall_match() {
  pcapdroid_t *pd = match_test_init();

  blacklist_t *bl = blacklist_init();
  assert(bl != NULL);

  pd->firewall.enabled = true;
  pd->firewall.bl = bl;

  assert0(blacklist_add_ipstr(bl, "1.2.3.4"));
  assert0(blacklist_add_domain(bl, "blocked.com"));
  assert0(blacklist_add_uid(bl, 1000));

  pd_conn_t *data;

  // Blocked by IP. The firewall only sets to_block, it does not set
  // blacklisted_ip (that flag is specific to malware detection)
  data = match_new_conn(pd, IPPROTO_TCP, "1.2.3.4", 443, 100, NULL);
  assert1(data->to_block);
  assert0(data->blacklisted_ip);

  // Blocked by uid
  data = match_new_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 1000, NULL);
  assert1(data->to_block);

  // Blocked by domain
  data = match_new_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 100, "www.blocked.com");
  assert1(data->to_block);
  assert0(data->blacklisted_domain);

  // Not in any firewall list -> allowed
  data = match_new_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 100, "www.allowed.com");
  assert0(data->to_block);

  match_test_free(pd);
}

/* ******************************************************* */

static void test_whitelist_mode_match() {
  pcapdroid_t *pd = match_test_init();

  blacklist_t *bl = blacklist_init();
  assert(bl != NULL);
  blacklist_t *wl = blacklist_init();
  assert(wl != NULL);

  // Whitelist mode: block every app unless it is explicitly whitelisted
  pd->firewall.enabled = true;
  pd->firewall.bl = bl;
  pd->firewall.wl_enabled = true;
  pd->firewall.wl = wl;

  assert0(blacklist_add_uid(wl, 100));

  pd_conn_t *data;

  // Whitelisted app -> allowed
  data = match_new_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 100, NULL);
  assert0(data->to_block);

  // Non-whitelisted app -> blocked
  data = match_new_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 200, NULL);
  assert1(data->to_block);

  // DNS traffic from the unspecified/netd/phone uids is always allowed, even
  // in whitelist mode
  data = match_new_conn(pd, IPPROTO_UDP, "8.8.8.8", 53, UID_UNKNOWN, NULL);
  assert0(data->to_block);
  data = match_new_conn(pd, IPPROTO_UDP, "8.8.8.8", 53, UID_NETD, NULL);
  assert0(data->to_block);
  data = match_new_conn(pd, IPPROTO_UDP, "8.8.8.8", 53, UID_PHONE, NULL);
  assert0(data->to_block);

  // DNS traffic from a known, non-whitelisted app is still blocked
  data = match_new_conn(pd, IPPROTO_UDP, "8.8.8.8", 53, 200, NULL);
  assert1(data->to_block);

  // Non-DNS traffic from the unspecified uid is blocked like any other app
  data = match_new_conn(pd, IPPROTO_TCP, "8.8.8.8", 443, UID_UNKNOWN, NULL);
  assert1(data->to_block);

  match_test_free(pd);
}

/* ******************************************************* */

/* When a DNS request issued by netd is later associated to an app (because the
 * app connects to the resolved host), pd_new_connection retroactively assigns
 * the app uid to the netd connection and, if that app would have been blocked,
 * flags it with netd_block_missed (the block could not be enforced in time). */
static void test_netd_block_missed() {
  // Resolved app is in the firewall blocklist
  {
    pcapdroid_t *pd = match_test_init();

    blacklist_t *bl = blacklist_init();
    assert(bl != NULL);
    pd->firewall.enabled = true;
    pd->firewall.bl = bl;
    assert0(blacklist_add_uid(bl, 1000));

    // DNS request from netd, resolved to a host via the LRU cache
    pd_conn_t *netd = match_new_conn(pd, IPPROTO_UDP, "8.8.8.8", 53, UID_NETD, "evil.com");
    assert0(netd->to_block);
    assert0(netd->netd_block_missed);

    // A later connection to the same host from a blocklisted app: the netd
    // request gets the app uid and is flagged, as the block was missed
    match_new_conn(pd, IPPROTO_TCP, "1.1.1.1", 443, 1000, "evil.com");
    assert(netd->uid == 1000);
    assert1(netd->netd_block_missed);

    match_test_free(pd);
  }

  // Resolved app is not blocked: the netd connection is not flagged
  {
    pcapdroid_t *pd = match_test_init();

    blacklist_t *bl = blacklist_init();
    assert(bl != NULL);
    pd->firewall.enabled = true;
    pd->firewall.bl = bl;
    assert0(blacklist_add_uid(bl, 1000));

    pd_conn_t *netd = match_new_conn(pd, IPPROTO_UDP, "8.8.8.8", 53, UID_NETD, "good.com");
    assert0(netd->netd_block_missed);

    match_new_conn(pd, IPPROTO_TCP, "1.1.1.1", 443, 100, "good.com");
    assert(netd->uid == 100);
    assert0(netd->netd_block_missed);

    match_test_free(pd);
  }

  // Whitelist mode: the netd request is resolved to a non-whitelisted app
  {
    pcapdroid_t *pd = match_test_init();

    blacklist_t *bl = blacklist_init();
    assert(bl != NULL);
    blacklist_t *wl = blacklist_init();
    assert(wl != NULL);
    pd->firewall.enabled = true;
    pd->firewall.bl = bl;
    pd->firewall.wl_enabled = true;
    pd->firewall.wl = wl;
    assert0(blacklist_add_uid(wl, 100));

    // DNS from netd is exempt from the whitelist-mode block, so it is not
    // blocked outright but can later be flagged as a missed block
    pd_conn_t *netd = match_new_conn(pd, IPPROTO_UDP, "8.8.8.8", 53, UID_NETD, "host.com");
    assert0(netd->to_block);

    match_new_conn(pd, IPPROTO_TCP, "1.1.1.1", 443, 2000, "host.com");
    assert(netd->uid == 2000);
    assert1(netd->netd_block_missed);

    match_test_free(pd);
  }
}

/* ******************************************************* */

/* The test getCountryCode stand-in (see test_utils.c) maps the last byte of the
 * destination IP to a 2-hex-char country code, e.g. x.x.x.10 -> "0A". */
static void test_country_match() {
  pcapdroid_t *pd = match_test_init();

  blacklist_t *bl = blacklist_init();
  assert(bl != NULL);
  pd->firewall.enabled = true;
  pd->firewall.bl = bl;

  assert0(blacklist_add_country(bl, "0A")); // last byte 10
  assert0(blacklist_add_country(bl, "FF")); // last byte 255

  pd_conn_t *data;

  // IPv4 ending in .10 -> "0A" -> blocked by country
  data = match_new_conn(pd, IPPROTO_TCP, "8.8.8.10", 443, 100, NULL);
  assert1(data->to_block);

  // IPv6 ending in ::ff -> "FF" -> blocked by country
  data = match_new_conn(pd, IPPROTO_TCP, "2001:db8::ff", 443, 100, NULL);
  assert1(data->to_block);

  // Last byte not in the country blocklist -> allowed
  data = match_new_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 100, NULL);
  assert0(data->to_block);

  match_test_free(pd);
}

/* ******************************************************* */

/* A malware-whitelisted app skips the malware IP/domain checks, but the
 * firewall block still applies to it. */
static void test_whitelisted_app_firewall_block() {
  pcapdroid_t *pd = match_test_init();

  blacklist_t *mbl = blacklist_init();
  assert(mbl != NULL);
  blacklist_t *mwl = blacklist_init();
  assert(mwl != NULL);
  blacklist_t *fbl = blacklist_init();
  assert(fbl != NULL);

  pd->malware_detection.enabled = true;
  pd->malware_detection.bl = mbl;
  pd->malware_detection.whitelist = mwl;
  pd->firewall.enabled = true;
  pd->firewall.bl = fbl;

  assert0(blacklist_add_ipstr(mbl, "1.2.3.4"));
  assert0(blacklist_add_uid(mwl, 4242));
  assert0(blacklist_add_uid(fbl, 4242));

  pd_conn_t *data = match_new_conn(pd, IPPROTO_TCP, "1.2.3.4", 443, 4242, NULL);
  assert1(data->whitelisted_app);
  assert0(data->blacklisted_ip);
  assert1(data->to_block);

  match_test_free(pd);
}

/* ******************************************************* */

static void run_housekeeping_reload(pcapdroid_t *pd) {
  // route pd_housekeeping through the capture-stats branch, so that it does not
  // take the connections-dump branch (which would clear pd->new_conns)
  dump_capture_stats_now = true;
  pd_housekeeping(pd);
}

/* ******************************************************* */

/* A firewall blocklist/whitelist reload (via pd_housekeeping) re-evaluates the
 * block verdict of every active connection through recompute_conn_block_cb:
 * firewall rules are re-applied, while the malware blacklisted_* flags persist. */
static void test_firewall_reload() {
  pcapdroid_t *pd = match_test_init();

  blacklist_t *mbl = blacklist_init();
  assert(mbl != NULL);
  blacklist_t *fbl = blacklist_init();
  assert(fbl != NULL);

  pd->malware_detection.enabled = true;
  pd->malware_detection.bl = mbl;
  pd->firewall.enabled = true;
  pd->firewall.bl = fbl;

  assert0(blacklist_add_ipstr(mbl, "6.6.6.6")); // malware
  assert0(blacklist_add_uid(fbl, 200));         // initial firewall block

  // Active connections, each with a distinct src_port to keep the 5-tuples unique
  pd_conn_t *malware  = match_add_active_conn(pd, IPPROTO_TCP, "6.6.6.6", 443, 50001, 100, NULL);
  pd_conn_t *by_uid   = match_add_active_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 50002, 1000, NULL);
  pd_conn_t *by_ip    = match_add_active_conn(pd, IPPROTO_TCP, "1.2.3.4", 443, 50003, 100, NULL);
  pd_conn_t *by_dom   = match_add_active_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 50004, 100, "www.blocked.com");
  pd_conn_t *allowed  = match_add_active_conn(pd, IPPROTO_TCP, "8.8.8.8", 443, 50005, 100, NULL);
  pd_conn_t *prev_blk = match_add_active_conn(pd, IPPROTO_TCP, "8.8.8.8", 443, 50006, 200, NULL);

  // Initial verdicts, computed by pd_new_connection against the initial lists
  assert1(malware->blacklisted_ip);
  assert1(malware->to_block);
  assert0(by_uid->to_block);   // uid 1000 not in the initial firewall list
  assert0(by_ip->to_block);
  assert0(by_dom->to_block);
  assert0(allowed->to_block);
  assert1(prev_blk->to_block); // uid 200 is in the initial firewall list

  // New firewall blocklist: block uid 1000, ip 1.2.3.4 and domain blocked.com,
  // and no longer block uid 200
  blacklist_t *new_bl = blacklist_init();
  assert(new_bl != NULL);
  assert0(blacklist_add_uid(new_bl, 1000));
  assert0(blacklist_add_ipstr(new_bl, "1.2.3.4"));
  assert0(blacklist_add_domain(new_bl, "blocked.com"));
  pd->firewall.new_bl = new_bl;

  run_housekeeping_reload(pd);

  // Every active connection has been re-evaluated against the new firewall list
  assert1(malware->blacklisted_ip); // malware flag persists across a firewall reload
  assert1(malware->to_block);
  assert1(by_uid->to_block);   // now blocked by uid
  assert1(by_ip->to_block);    // now blocked by ip
  assert1(by_dom->to_block);   // now blocked by domain
  assert0(allowed->to_block);
  assert0(prev_blk->to_block); // unblocked: uid 200 no longer listed

  match_test_free(pd);
}

/* ******************************************************* */

/* A malware whitelist reload (via pd_housekeeping) un-flags and unblocks the
 * active connections that the new whitelist now covers. */
static void test_malware_whitelist_reload() {
  pcapdroid_t *pd = match_test_init();

  blacklist_t *mbl = blacklist_init();
  assert(mbl != NULL);
  blacklist_t *fbl = blacklist_init();
  assert(fbl != NULL);

  pd->malware_detection.enabled = true;
  pd->malware_detection.bl = mbl;
  pd->firewall.enabled = true;
  pd->firewall.bl = fbl;

  assert0(blacklist_add_ipstr(mbl, "1.2.3.4"));
  assert0(blacklist_add_ipstr(mbl, "5.6.7.8"));
  assert0(blacklist_add_domain(mbl, "evil.com"));

  pd_conn_t *bad_ip  = match_add_active_conn(pd, IPPROTO_TCP, "1.2.3.4", 443, 50001, 100, NULL);
  pd_conn_t *bad_app = match_add_active_conn(pd, IPPROTO_TCP, "5.6.7.8", 443, 50002, 4242, NULL);
  pd_conn_t *bad_dom = match_add_active_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 50003, 100, "sub.evil.com");

  assert1(bad_ip->blacklisted_ip);
  assert1(bad_ip->to_block);
  assert1(bad_app->blacklisted_ip);
  assert1(bad_app->to_block);
  assert1(bad_dom->blacklisted_domain);
  assert1(bad_dom->to_block);

  // New malware whitelist: whitelist 1.2.3.4 by IP, uid 4242 (app) and evil.com by domain
  blacklist_t *new_wl = blacklist_init();
  assert(new_wl != NULL);
  assert0(blacklist_add_ipstr(new_wl, "1.2.3.4"));
  assert0(blacklist_add_uid(new_wl, 4242));
  assert0(blacklist_add_domain(new_wl, "evil.com"));
  pd->malware_detection.new_wl = new_wl;

  run_housekeeping_reload(pd);

  // bad_ip: whitelisted by IP -> flag cleared and unblocked
  assert0(bad_ip->blacklisted_ip);
  assert0(bad_ip->to_block);

  // bad_app: whitelisted app (uid) -> flag cleared and unblocked
  assert1(bad_app->whitelisted_app);
  assert0(bad_app->blacklisted_ip);
  assert0(bad_app->to_block);

  // bad_dom: whitelisted by domain -> flag cleared and unblocked
  assert0(bad_dom->blacklisted_domain);
  assert0(bad_dom->to_block);

  match_test_free(pd);
}

/* ******************************************************* */

/* In whitelist mode, a firewall whitelist reload (via pd_housekeeping)
 * re-evaluates every active connection through recompute_conn_block_cb: apps
 * are blocked unless the new whitelist allows them, while DNS traffic from the
 * unspecified apps stays allowed. */
static void test_firewall_whitelist_reload() {
  pcapdroid_t *pd = match_test_init();

  blacklist_t *fbl = blacklist_init();
  assert(fbl != NULL);
  blacklist_t *wl = blacklist_init();
  assert(wl != NULL);

  pd->firewall.enabled = true;
  pd->firewall.bl = fbl;
  pd->firewall.wl_enabled = true;
  pd->firewall.wl = wl;

  assert0(blacklist_add_uid(wl, 100)); // initially only uid 100 is allowed

  pd_conn_t *app_a = match_add_active_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 50001, 100, NULL);
  pd_conn_t *app_b = match_add_active_conn(pd, IPPROTO_TCP, "9.9.9.9", 443, 50002, 200, NULL);
  pd_conn_t *dns   = match_add_active_conn(pd, IPPROTO_UDP, "8.8.8.8", 53, 50003, UID_NETD, NULL);

  assert0(app_a->to_block); // uid 100 whitelisted
  assert1(app_b->to_block); // uid 200 not whitelisted
  assert0(dns->to_block);   // DNS from netd is always allowed

  // New firewall whitelist: allow uid 200 instead of uid 100
  blacklist_t *new_wl = blacklist_init();
  assert(new_wl != NULL);
  assert0(blacklist_add_uid(new_wl, 200));
  pd->firewall.new_wl = new_wl;

  run_housekeeping_reload(pd);

  assert1(app_a->to_block); // uid 100 no longer whitelisted -> blocked
  assert0(app_b->to_block); // uid 200 now whitelisted -> allowed
  assert0(dns->to_block);   // DNS from netd still allowed

  match_test_free(pd);
}

/* ******************************************************* */

int main(int argc, char **argv) {
  add_test("match", test_match);
  add_test("detection", test_detection);
  add_test("malware_match", test_malware_match);
  add_test("firewall_match", test_firewall_match);
  add_test("whitelist_mode_match", test_whitelist_mode_match);
  add_test("netd_block_missed", test_netd_block_missed);
  add_test("country_match", test_country_match);
  add_test("whitelisted_app_firewall_block", test_whitelisted_app_firewall_block);
  add_test("firewall_reload", test_firewall_reload);
  add_test("firewall_whitelist_reload", test_firewall_whitelist_reload);
  add_test("malware_whitelist_reload", test_malware_whitelist_reload);

  run_test(argc, argv);
  return 0;
}
