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
#  Copyright 2020 by Emanuele Faranda
#

import socket
import sys
import argparse

# The buffer to hold the received UDP data
BUFSIZE = 65535

# Standard PCAP header. Must be sent before any other PCAP record.
PCAP_HDR_BYTES = bytes.fromhex("d4c3b2a1020004000000000000000000ffff000065000000")

pcap_header_sent = False

parser = argparse.ArgumentParser(
    description='''Receives data from the PCAPdroid app and outputs it to stdout.''')

parser.add_argument('-p', '--port', type=int, help='The UDP port to listen', required=True)
parser.add_argument('-v', '--verbose', help='Enable verbose log to stderr', action='store_true')
args = parser.parse_args()

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", args.port))

# Send the initial PCAP header
# By sending this manually at startup we can be sure that consumer application (e.g. wireshark)
# reads the header before anything else.
if(args.verbose):
	sys.stderr.write("Sending PCAP header\n");
sys.stdout.buffer.write(PCAP_HDR_BYTES)
sys.stdout.flush()

# Send the individual records
while True:
	data, addr = sock.recvfrom(BUFSIZE)

	if(args.verbose):
		sys.stderr.write("Got a {}B packet\n".format(len(data)))

	if(data == PCAP_HDR_BYTES):
		# Ignore the PCAP header as we already sent it above
		if(args.verbose):
			sys.stderr.write("PCAP header detected, skipping\n");
		continue

	# this is a PCAP record, send it
	sys.stdout.buffer.write(data)
	sys.stdout.flush()
