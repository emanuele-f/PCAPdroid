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
 * Copyright 2021-22 - Emanuele Faranda
 */

#include <pcap.h>
#include "core/pcapdroid.h"
#include "common/utils.h"
#include "pcapd/pcapd.h"
#include "test_utils.h"

static pcap_t *pcap_fd;
static char errbuf[PCAP_ERRBUF_SIZE];

/* ******************************************************* */

int openPcap(pcapdroid_t *pd) {
  char *pcap_path = pd->pcap.capture_interface;

  if(!pcap_path) {
    log_e("NULL pcap path");
    return -1;
  }

  pcap_fd = pcap_open_offline(pcap_path, errbuf);
  if(!pcap_fd) {
    log_e("pcap_open(%s) failed: %s", pcap_path, errbuf);
    return -1;
  }

  // As part of the fuzzing, only deal with Ethernet
  int dlink = pcap_datalink(pcap_fd);
  if(dlink != DLT_EN10MB) {
    log_e("Unsupported datalink: %d", dlink);
    return -1;
  }

  // Set the BPF
  struct bpf_program fcode;
  if(pcap_compile(pcap_fd, &fcode, "ip or ip6", 1, PCAP_NETMASK_UNKNOWN) < 0) {
    log_e("pcap_compile failed");
    return -1;
  }
  if(pcap_setfilter(pcap_fd, &fcode) < 0) {
    log_e("pcap_setfilter failed: %s", pcap_geterr(pcap_fd));
    pcap_freecode(&fcode);
    return -1;
  }
  pcap_freecode(&fcode);

  return pcap_get_selectable_fd(pcap_fd);
}

/* ******************************************************* */

// returns <0 on error, 0 if packet should be skipped, otherwise the packet size
int nextPacket(pcapdroid_t *pd, pcapd_hdr_t *phdr, char *out_buf, size_t bufsize) {
  struct pcap_pkthdr *pcap_hdr;
  const u_char *pkt_buf;
  int to_skip = 14; // ethernet size

  int rv = pcap_next_ex(pcap_fd, &pcap_hdr, &pkt_buf);
  if(rv != 1)
    return -1;

  if((pcap_hdr->caplen > to_skip) && ((pcap_hdr->caplen - to_skip) <= bufsize)) {
    zdtun_pkt_t zpkt;
    int len = pcap_hdr->caplen - to_skip;

    pkt_buf += to_skip;

    if(zdtun_parse_pkt(pd->zdt, (const char*)pkt_buf, len, &zpkt) == 0) {
      // Valid packet
      memset(phdr, 0, sizeof(pcapd_hdr_t));
      phdr->len = len;
      phdr->ts = pcap_hdr->ts;
      phdr->linktype = DLT_RAW;

      memcpy(out_buf, pkt_buf, len);
      return len;
    }
  }

  return 0;
}

/* ******************************************************* */

#include "fuzz_utils.c"

int LLVMFuzzerTestOneInput(const uint8_t *Data, size_t Size) {
  char *pcap_path;

  if(!(pcap_path = buffer_to_tmpfile(Data, Size)))
    return -1;

  pcap_fd = NULL;
  pcapdroid_t *pd = pd_init_test(pcap_path);

  loglevel = ANDROID_LOG_FATAL;
  pd_run(pd);

  if(pcap_fd != NULL) {
    pcap_close(pcap_fd);
    pcap_fd = NULL;
  }

  pd_free_test(pd);
  unlink(pcap_path);
  free(pcap_path);

  return 0;
}
