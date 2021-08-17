/*
 * This file is part of PCAPdroid.
 *
 * You are allowed to distribute this file with your proprietary app
 * as long as you provide proper attribution to the PCAPdroid project.
 *
 * Copyright 2021 - Emanuele Faranda
 */

#ifndef __PCAPD_H__
#define __PCAPD_H__

#define PCAPD_SOCKET_PATH  "pcapsock"
#define PCAPD_PID          "pcapd.pid"

#define PCAPD_FLAG_TX      (1 << 0)

#include <time.h>
#include <stdint.h>

typedef struct {
  struct timeval ts;
  u_int pkt_drops;
  uid_t uid;
  uint16_t len;
  uint8_t flags;
} __attribute__((packed)) pcapd_hdr_t;

#endif
