add_executable(fuzz_pcapd fuzz_pcapd.c)
target_link_libraries(fuzz_pcapd pcapdlib)

# Link with the fuzzer
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fsanitize=fuzzer")

add_executable(fuzz_capture ../test_utils.c fuzz_capture.c)
add_dependencies(fuzz_capture libpcapd.so)
target_link_libraries(fuzz_capture capture)
