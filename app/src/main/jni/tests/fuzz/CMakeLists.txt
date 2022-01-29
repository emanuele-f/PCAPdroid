add_executable(fuzz_pcapd fuzz_pcapd.c)
target_link_libraries(fuzz_pcapd libpcapd.so)
