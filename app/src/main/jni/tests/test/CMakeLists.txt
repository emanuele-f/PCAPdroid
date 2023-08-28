include(CTest)

# Target to run tests and build them if necessary
add_custom_target(build_tests)
add_custom_target(run_tests COMMAND CTEST_OUTPUT_ON_FAILURE=1 ${CMAKE_CTEST_COMMAND})
add_dependencies(run_tests build_tests)

# test_source(target)
macro(test_source)
  add_executable(${ARGV0} ${ARGV0}.c ../test_utils.c)
  add_dependencies(${ARGV0} libpcapd.so)
  add_dependencies(build_tests ${ARGV0})

  target_link_libraries(${ARGV0} capture)
endmacro()

# Tests
test_source(pcap_reader)

test_source(dpi)
add_test(NAME dpi_extract COMMAND ./dpi extract)
add_test(NAME dpi_extract_proxy COMMAND ./dpi extract_proxy)

test_source(blacklist)
add_test(NAME blacklist_match COMMAND ./blacklist match)
add_test(NAME blacklist_detection COMMAND ./blacklist detection)

test_source(dump_api)
add_test(NAME dump_api_snaplen COMMAND ./dump_api snaplen)
add_test(NAME dump_api_max_pkts_flow COMMAND ./dump_api max_pkts_per_flow)
add_test(NAME dump_api_max_size COMMAND ./dump_api max_dump_size)

test_source(root_capture)
add_test(NAME root_capture COMMAND ./root_capture invalid_pkts)
