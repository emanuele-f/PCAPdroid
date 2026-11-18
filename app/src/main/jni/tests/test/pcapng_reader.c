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
 * Copyright 2026 - Emanuele Faranda
 */

#include "test_utils.h"
#include "core/pcap_reader.h"
#include "pcapd/pcapd.h"

// a minimal IPv4 packet, its content is irrelevant to these tests
static const uint8_t test_pkt[20] = {0x45};

/* ******************************************************* */

static FILE* open_pcapng() {
  FILE *fp = fopen(PCAP_OUT_PATH, "wb+");
  assert(fp != NULL);

  pcapng_section_hdr_block_t shb = {
    .type = 0x0A0D0D0A,
    .total_length = sizeof(shb) + 4,
    .magic = 0x1a2b3c4d,
    .version_major = 1,
    .version_minor = 0,
    .section_length = -1,
  };
  uint32_t total_length = shb.total_length;

  assert1(fwrite(&shb, sizeof(shb), 1, fp));
  assert1(fwrite(&total_length, sizeof(total_length), 1, fp));

  pcapng_intf_descr_block_t idb = {
    .type = 0x00000001,
    .total_length = sizeof(idb) + 4,
    .linktype = LINKTYPE_RAW,
  };
  total_length = idb.total_length;

  assert1(fwrite(&idb, sizeof(idb), 1, fp));
  assert1(fwrite(&total_length, sizeof(total_length), 1, fp));

  return fp;
}

/* Writes an EPB with an "EPB flags" option marking the packet as inbound.
 * If truncated_options is set, the block declares no room for the options,
 * which the reader must not try to parse. */
static void write_epb(FILE *fp, bool truncated_options) {
  pcapng_enh_packet_block_t epb = {
    .type = 0x00000006,
    .interface_id = 0,
    .captured_len = sizeof(test_pkt),
    .original_len = sizeof(test_pkt),
  };
  pcapng_enh_option_t flags_opt = { .code = 2, .length = 4 };
  pcapng_enh_option_t end_opt = {0};
  uint32_t epb_flags = 1 /* inbound */;

  epb.total_length = sizeof(epb) + sizeof(test_pkt);
  if (!truncated_options)
    epb.total_length += sizeof(flags_opt) + sizeof(epb_flags) + sizeof(end_opt) + 4;
  uint32_t total_length = epb.total_length;

  assert1(fwrite(&epb, sizeof(epb), 1, fp));
  assert1(fwrite(test_pkt, sizeof(test_pkt), 1, fp));

  if (truncated_options)
    return;

  assert1(fwrite(&flags_opt, sizeof(flags_opt), 1, fp));
  assert1(fwrite(&epb_flags, sizeof(epb_flags), 1, fp));
  assert1(fwrite(&end_opt, sizeof(end_opt), 1, fp));
  assert1(fwrite(&total_length, sizeof(total_length), 1, fp));
}

static pd_reader_t* open_reader() {
  char *error = NULL;
  pd_reader_t *reader = pd_new_reader(PCAP_OUT_PATH, &error);

  assert(reader != NULL);
  assert(pd_get_dump_format(reader) == PCAPNG_DUMP);

  return reader;
}

/* ******************************************************* */

/* An EPB whose declared length leaves no room for the options must not scan
 * the blocks which follow it, otherwise it would pick up their options. */
static void epb_truncated_options() {
  FILE *fp = open_pcapng();

  write_epb(fp, true);
  write_epb(fp, false);
  fclose(fp);

  pd_reader_t *reader = open_reader();
  char buffer[PCAPD_SNAPLEN];
  pcapd_hdr_t hdr;
  pd_read_callbacks_t cb = {0};

  // the direction of the second packet must not leak into the first one
  assert(pd_read_next(reader, &hdr, buffer, &cb, NULL) == READER_PACKET_OK);
  assert(hdr.flags & PCAPD_FLAG_TX);

  assert(pd_read_next(reader, &hdr, buffer, &cb, NULL) == READER_PACKET_OK);
  assert(!(hdr.flags & PCAPD_FLAG_TX));

  assert(pd_read_next(reader, &hdr, buffer, &cb, NULL) == READER_EOF);

  pd_destroy_reader(reader);
}

/* An IDB shorter than the block header must be rejected instead of being
 * parsed with the data of the next block. */
static void idb_bad_length() {
  FILE *fp = open_pcapng();

  pcapng_intf_descr_block_t idb = {
    .type = 0x00000001,
    .total_length = 12,
    .linktype = LINKTYPE_RAW,
  };
  assert1(fwrite(&idb, 12, 1, fp));

  write_epb(fp, false);
  fclose(fp);

  pd_reader_t *reader = open_reader();
  char buffer[PCAPD_SNAPLEN];
  pcapd_hdr_t hdr;
  pd_read_callbacks_t cb = {0};

  assert(pd_read_next(reader, &hdr, buffer, &cb, NULL) == READER_ERROR);

  pd_destroy_reader(reader);
}

/* An option whose declared length overruns the block must not be parsed,
 * otherwise it takes the data of the next block. */
static void epb_overlong_option() {
  FILE *fp = open_pcapng();

  pcapng_enh_packet_block_t epb = {
    .type = 0x00000006,
    .interface_id = 0,
    .captured_len = sizeof(test_pkt),
    .original_len = sizeof(test_pkt),
  };
  pcapng_enh_option_t opt = { .code = 2 /* EPB flags */, .length = 0x2000 };

  // the block only leaves room for the option header and the trailing length
  epb.total_length = sizeof(epb) + sizeof(test_pkt) + sizeof(opt) + 4;

  assert1(fwrite(&epb, sizeof(epb), 1, fp));
  assert1(fwrite(test_pkt, sizeof(test_pkt), 1, fp));
  assert1(fwrite(&opt, sizeof(opt), 1, fp));

  /* The trailing length is not written, so the IDB below starts right after
   * the option header: an out of bounds read of the option would take the
   * flags from the IDB type (1, which means inbound). */
  pcapng_intf_descr_block_t idb = {
    .type = 0x00000001,
    .total_length = sizeof(idb) + 4,
    .linktype = LINKTYPE_RAW,
  };
  uint32_t total_length = idb.total_length;

  assert1(fwrite(&idb, sizeof(idb), 1, fp));
  assert1(fwrite(&total_length, sizeof(total_length), 1, fp));
  fclose(fp);

  pd_reader_t *reader = open_reader();
  char buffer[PCAPD_SNAPLEN];
  pcapd_hdr_t hdr;
  pd_read_callbacks_t cb = {0};

  assert(pd_read_next(reader, &hdr, buffer, &cb, NULL) == READER_PACKET_OK);
  assert(hdr.flags & PCAPD_FLAG_TX);

  pd_destroy_reader(reader);
}

/* ******************************************************* */

int main(int argc, char **argv) {
  add_test("epb_truncated_options", epb_truncated_options);
  add_test("idb_bad_length", idb_bad_length);
  add_test("epb_overlong_option", epb_overlong_option);

  run_test(argc, argv);
}
