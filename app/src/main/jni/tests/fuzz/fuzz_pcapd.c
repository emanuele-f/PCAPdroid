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

#include "pcapd/pcapd_priv.h"

/* ******************************************************* */

#include "fuzz_utils.c"

int LLVMFuzzerTestOneInput(const uint8_t *Data, size_t Size) {
  pcapd_conf_t conf;
  char *pcap_path;

  if(!(pcap_path = buffer_to_tmpfile(Data, Size)))
    return -1;

  init_conf(&conf);
  conf.ifnames[0] = strdup(pcap_path);
  conf.num_interfaces = 1;
  conf.no_client = 1;
  conf.quiet = 1;

  run_pcap_dump(&conf);

  unlink(pcap_path);
  free(pcap_path);

  return 0;
}
