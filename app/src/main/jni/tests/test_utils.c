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
