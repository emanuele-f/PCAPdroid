#!/usr/bin/env python3
#
#  This file is part of PCAPdroid.
#
#  PCAPdroid is free software: you can redistribute it and/or modify
#  it under the terms of the GNU General Public License as published by
#  the Free Software Foundation, either version 3 of the License, or
#  (at your option) any later version.
#
#  PCAPdroid is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#  GNU General Public License for more details.
#
#  You should have received a copy of the GNU General Public License
#  along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
#
#  Copyright 2020-21 - Emanuele Faranda
#

import socket
import sys
import argparse

# The buffer to hold the received UDP data
BUFSIZE = 65535

PCAP_HEADER_SIZE = 24

# Standard PCAP header (struct pcap_hdr_s). Must be sent before any other PCAP record (struct pcaprec_hdr_s).
# magic: 0xa1b2c3d4, v2.4
PCAP_HDR_BYTES_PREFIX = bytes.fromhex("d4c3b2a1020004000000000000000000")

# snaplen: 65535, LINKTYPE_RAW
PCAP_HDR_BYTES_RAW_SUFFIX = bytes.fromhex("ffff000065000000")

# snaplen: 65535, LINKTYPE_ETHERNET
PCAP_HDR_BYTES_ETHER_SUFFIX = bytes.fromhex("ffff000001000000")

# PCAP header when PCAPDroid trailer is in use
# magic: 0xa1b2c3d4, v2.4

PCAPDROID_TRAILER_MAGIC = bytes.fromhex("01072021")
PCAPDROID_TRAILER_SIZE = 32

example = '''example:
  udp_receiver.py -p 1234 | wireshark -k -i -
'''

parser = argparse.ArgumentParser(
    description='''Receives data from the PCAPdroid app and outputs it to stdout.''',
    epilog=example,
    formatter_class=argparse.RawDescriptionHelpFormatter)

parser.add_argument('-p', '--port', type=int, help='The UDP port to listen', default=1234)
parser.add_argument('-v', '--verbose', help='Enable verbose log to stderr', action='store_true')
parser.add_argument('-w', '--write', help='Write the PCAP to the specified file', metavar='FILE', default="-")
args = parser.parse_args()

outf = None
if args.write != '-':
  outf = open(args.write, "wb")

def write(data):
  if outf:
    outf.write(data)
  else:
    sys.stdout.buffer.write(data)
    sys.stdout.flush()

def main_loop():
  sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
  sock.bind(("0.0.0.0", args.port))

  pcap_header_sent = False

  # Send the individual records (struct pcaprec_hdr_s)
  while True:
    data, addr = sock.recvfrom(BUFSIZE)
    is_pcap_header = (len(data) == PCAP_HEADER_SIZE) and (data.startswith(PCAP_HDR_BYTES_PREFIX))

    if(args.verbose):
      sys.stderr.write("Got a {}B packet\n".format(len(data)))

    if(not pcap_header_sent) and (not is_pcap_header):
      # This tool was started after PCAPdroid, so we must build a PCAP header

      # Determine is the PCAPDroid trailer is in use
      offset = len(data) - PCAPDROID_TRAILER_SIZE
      has_trailer = ((offset > 0) and (data[offset:offset+4] == PCAPDROID_TRAILER_MAGIC))

      if(args.verbose):
        sys.stderr.write("Sending PCAP header (trailer " + ("not " if not has_trailer else "") + "detected)\n")

      # Send the PCAP header before any other data
      suffix = PCAP_HDR_BYTES_RAW_SUFFIX if not has_trailer else PCAP_HDR_BYTES_ETHER_SUFFIX
      write(PCAP_HDR_BYTES_PREFIX + suffix)
      pcap_header_sent = True
    elif is_pcap_header:
      if pcap_header_sent:
        # PCAPdroid has been restarted, ignore the PCAP header
        if(args.verbose):
          sys.stderr.write("PCAP header detected, skipping\n");
        continue
      else:
        if(args.verbose):
          sys.stderr.write("PCAP header detected\n");
        pcap_header_sent = True

    # this is a PCAP header/record, send it
    write(data)

if __name__ == "__main__":
  try:
    main_loop()
  except KeyboardInterrupt:
    sys.stderr.write("Terminating...")

    if outf:
      outf.close()
    exit(0)
