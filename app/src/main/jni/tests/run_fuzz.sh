#!/bin/bash
# To be run from the current directory (tests)

mkdir -p build
( cd build && cmake .. -DFUZZING=1 && make -j$(nproc) fuzz_pcapd )
( cd build && mkdir -p CORPUS && ./fuzz_pcapd CORPUS ../../../../../../submodules/nDPI/tests/pcap ../pcap >/dev/null )
