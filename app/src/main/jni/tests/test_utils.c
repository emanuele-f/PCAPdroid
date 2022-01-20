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

static void getPcapdPath(struct pcapdroid *pd, const char *prog_name, char *buf, int bufsize) {
  snprintf(buf, bufsize, "main/pcapd/libpcapd.so");
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

pcapdroid_t* pd_init(const char *ifname) {
  pcapdroid_t *pd = calloc(1, sizeof(pcapdroid_t));
  assert(pd != NULL);

  pd->root_capture = true;
  pd->root.capture_interface = (char*) ifname;
  pd->root.as_root = false;   // don't run as root
  pd->app_filter = -1;        // don't filter
  pd->cb.get_libprog_path = getPcapdPath;

  strcpy(pd->cachedir, ".");
  pd->cachedir_len = 1;

  return pd;
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

    if((conn->tuple.ipproto == ipproto) &&
       (conn->tuple.dst_port == dst_port) &&
       (conn->tuple.ipver == ipver) &&
       (!zdtun_cmp_ip(ipver, &conn->tuple.dst_ip, &ip)) &&
       ((info == NULL) || ((conn->data->info != NULL) && !strcmp(info, conn->data->info)))) {
      found = conn;
      break;
    }
  }

  assert(found);
  return found;
}
