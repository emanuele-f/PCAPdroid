add_executable(fuzz_pcapd fuzz_pcapd.c)
target_link_libraries(fuzz_pcapd libpcapd.so)

add_executable(fuzz_capture ../test_utils.c fuzz_capture.c)
target_link_libraries(fuzz_capture capture pcap)
