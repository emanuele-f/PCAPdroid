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

#include <stdio.h>
#include <signal.h>
#include "test_utils.h"
#include "common/utils.h"

/* ******************************************************* */

static void usage() {
  fprintf(stderr, "pcap_reader - test for PCAPdroid\n"
    "Copyright 2022 Emanuele Faranda <black.silver@hotmail.it>\n\n"
    "Usage: pcap_reader -i ifname\n"
    " -i [ifname]    capture packets on the specified interface or PCAP file.\n"
    "                The '@inet' keyword can be used to capture from the internet\n"
    "                interface\n"
  );

  exit(1);
}

/* ******************************************************* */

static void sig_handler(int signo) {
  if(running) {
    running = false;
    return;
  }

  fprintf(stderr, "exit now");
  exit(1);
}

/* ******************************************************* */

static void dump_connections(pcapdroid_t *pd) {
  char buf[256];

  for(int i=0; i < pd->new_conns.cur_items; i++) {
    conn_and_tuple_t *conn = &pd->new_conns.items[i];

    zdtun_5tuple2str(&conn->tuple, buf, sizeof(buf));
    printf("%s [%s]\n", buf,
      //pd_get_proto_name(pd, conn->data->l7proto, conn->tuple.ipproto),
      conn->data->info ? conn->data->info : "");
  }
}

/* ******************************************************* */

int main(int argc, char *argv[]) {
  int c;
  char *ifname = NULL;
  uint8_t verbose = 0;

  while((c = getopt(argc, argv, "hi:v")) != -1) {
    switch(c) {
      case 'i':
        ifname = strdup(optarg);
        break;
      case 'v':
        verbose = 1;
        break;
      default:
        usage();
    }
  }

  if(ifname == NULL)
    usage();

  loglevel = verbose ? ANDROID_LOG_DEBUG : ANDROID_LOG_INFO;

  pcapdroid_t *pd = pd_init_test(ifname);
  pd->cb.send_connections_dump = dump_connections;

  signal(SIGINT, sig_handler);
  signal(SIGTERM, sig_handler);
  signal(SIGHUP, sig_handler);

  log_i("Capturing packets from %s", ifname);
  pd_run(pd);

  log_i("Cleanup...");
  pd_free_test(pd);
  free(ifname);
}
