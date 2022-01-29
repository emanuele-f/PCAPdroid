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

#include "pcapd/pcapd.c"
#define TEST_PCAP "/tmp/fuzz_test_target.pcap"


/* ******************************************************* */

static int bufferToFile(const uint8_t *Data, size_t Size) {
  FILE *fd;

  fd = fopen(TEST_PCAP, "wb");
  if(fd == NULL) {
    perror("fopen failed");
    return -1;
  }

  if(fwrite(Data, 1, Size, fd) != Size) {
    fclose(fd);
    return -1;
  }

  fclose(fd);
  return 0;
}

/* ******************************************************* */

int LLVMFuzzerTestOneInput(const uint8_t *Data, size_t Size) {
  pcapd_conf_t conf;

  if(bufferToFile(Data, Size) != 0)
    return -1;

  init_conf(&conf);
  conf.ifnames[0] = strdup(TEST_PCAP);
  conf.num_interfaces = 1;
  conf.no_client = 1;

  loglevel = ANDROID_LOG_FATAL;
  run_pcap_dump(&conf);

  return 0;
}
