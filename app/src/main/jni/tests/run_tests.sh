#!/bin/bash

# To be run from the current directory
mkdir -p build
cd build && cmake .. && make -j$(nproc) run_tests
