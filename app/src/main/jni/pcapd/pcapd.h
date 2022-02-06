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

// Using a snaplen large enough to avoid truncating packets even with TSO/GRO. Size is currently
// limited to 16 bits by pcapd_hdr_t.len
#define PCAPD_SNAPLEN 65535

// pcap/dlt.h
#define PCAPD_DLT_ETHERNET    1
#define PCAPD_DLT_RAW         12
#define PCAPD_DLT_LINUX_SLL   113
#define PCAPD_DLT_LINUX_SLL2  276

typedef struct {
  struct timeval ts;        // the packet timestamp
  u_int pkt_drops;          // number of dropped packets on this interface
  uid_t uid;                // the UID of the process which sent/received the packet
  uint16_t len;             // the packet length
  uint16_t linktype;        // the link type, see PCAPD_DLT_*
  uint8_t flags;            // packet flags, see PCAPD_FLAG_*
  uint8_t ifid;             // the interface id, which is the interface position in the -i args
  uint8_t pad[2];           // padding for 64bit alignment of the payload
} __attribute__((packed)) pcapd_hdr_t;

#endif
