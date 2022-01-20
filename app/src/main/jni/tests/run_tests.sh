#!/bin/bash

mkdir -p build
cd build && cmake .. && make -j$(nproc) run_tests
