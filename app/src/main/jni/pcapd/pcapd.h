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

// https://www.tcpdump.org/linktypes.html
#define PCAPD_DLT_ETHERNET    1
#define PCAPD_DLT_RAW         101
#define PCAPD_DLT_LINUX_SLL   113

typedef struct {
  struct timeval ts;        // the packet timestamp
  u_int pkt_drops;          // number of dropped packets on this interface
  uid_t uid;                // the UID of the process which sent/received the packet
  uint16_t len;             // the packet length
  uint16_t linktype;        // the link type, see PCAPD_DLT_*
  uint8_t flags;            // packet flags, see PCAPD_FLAG_*
  uint8_t ifid;             // the interface id, which is the interface position in the -i args
} __attribute__((packed)) pcapd_hdr_t;

#endif
