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
add_test(NAME blacklist_malware_match COMMAND ./blacklist malware_match)
add_test(NAME blacklist_firewall_match COMMAND ./blacklist firewall_match)
add_test(NAME blacklist_whitelist_mode_match COMMAND ./blacklist whitelist_mode_match)
add_test(NAME blacklist_netd_block_missed COMMAND ./blacklist netd_block_missed)
add_test(NAME blacklist_country_match COMMAND ./blacklist country_match)
add_test(NAME blacklist_whitelisted_app_firewall_block COMMAND ./blacklist whitelisted_app_firewall_block)
add_test(NAME blacklist_firewall_reload COMMAND ./blacklist firewall_reload)
add_test(NAME blacklist_firewall_whitelist_reload COMMAND ./blacklist firewall_whitelist_reload)
add_test(NAME blacklist_malware_whitelist_reload COMMAND ./blacklist malware_whitelist_reload)

test_source(dump_api)
add_test(NAME dump_api_snaplen COMMAND ./dump_api snaplen)
add_test(NAME dump_api_max_pkts_flow COMMAND ./dump_api max_pkts_per_flow)
add_test(NAME dump_api_max_size COMMAND ./dump_api max_dump_size)

test_source(root_capture)
add_test(NAME root_capture COMMAND ./root_capture invalid_pkts)
