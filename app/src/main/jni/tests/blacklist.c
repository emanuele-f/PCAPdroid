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
  //assert1(blacklist_match_domain(bl, "some.example.org")); // TODO support subdomains matching

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

int main(int argc, char **argv) {
  add_test("match", test_match);
  run_test(argc, argv);

  return 0;
}
