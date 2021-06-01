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

#ifndef __PCAPD_H__
#define __PCAPD_H__

#define PCAPD_SOCKET_PATH  "pcapsock"
#define PCAPD_LOGFILE_PATH "pcapd.log"
#define PCAPD_PID          "pcapd.pid"

#define PCAPD_FLAG_TX      (1 << 0)

#include <time.h>
#include <stdint.h>

typedef struct {
  struct timeval ts;
  uid_t uid;
  uint16_t len;
  uint8_t flags;
} __attribute__((packed)) pcapd_hdr_t;

#endif