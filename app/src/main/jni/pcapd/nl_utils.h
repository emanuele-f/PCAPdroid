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
 * Copyright 2021 - Emanuele Faranda
 */

#ifndef __NL_UTILS_H__
#define __NL_UTILS_H__

#include <stdint.h>

typedef struct {
  union {
    uint32_t v4;
    uint8_t v6[8];
  };
} __attribute__((packed)) addr_t;

typedef struct {
  addr_t gateway;
  int ifidx;
  int gw_len;
} route_info_t;

int nl_get_route(int af, const addr_t *addr, route_info_t *out);
int nl_socket(uint32_t groups);

#endif
