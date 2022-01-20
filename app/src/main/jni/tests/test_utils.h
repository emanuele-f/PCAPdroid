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

#ifndef __TEST_UTILS_H__
#define __TEST_UTILS_H__

#include "core/pcapdroid.h"
#include <assert.h>

#define assert0(x) assert((x) == 0)
#define assert1(x) assert((x) == 1)

void add_test(const char *name, void (*test_cb)());
void run_test(int argc, char **argv);

pcapdroid_t* pd_init(const char *ifname);

static inline void pd_free(pcapdroid_t *pd) {
  free(pd);
}

#endif
