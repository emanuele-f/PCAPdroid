# Build with address/leak sanitizer
set(CMAKE_C_COMPILER "clang")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fsanitize=address -fno-omit-frame-pointer")

project(test)
cmake_minimum_required(VERSION 3.18.1)

#set(CMAKE_VERBOSE_MAKEFILE ON)
set(ROOTDIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/.. ${CMAKE_CURRENT_BINARY_DIR}/main)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/..)
include_directories(${ROOTDIR}/submodules/zdtun)
include_directories(${ROOTDIR}/submodules/nDPI/src/include)

include(CTest)

# Target to run tests and build them if necessary
add_custom_target(run_tests COMMAND CTEST_OUTPUT_ON_FAILURE=1 ${CMAKE_CTEST_COMMAND})

# build_tests(target)
macro(build_tests)
  ADD_EXECUTABLE(${ARGV0} ${ARGV0}.c test_utils.c)
  add_dependencies(${ARGV0} libpcapd.so)
  add_dependencies(run_tests ${ARGV0})

  target_link_libraries(${ARGV0} capture)
endmacro()

# Tests
build_tests(pcap_reader)

add_test(NAME dpi_extract COMMAND ./dpi extract)
build_tests(dpi)

add_test(NAME blacklist_match COMMAND ./blacklist match)
add_test(NAME blacklist_detection COMMAND ./blacklist detection)
build_tests(blacklist)

add_test(NAME dump_api_snaplen COMMAND ./dump_api snaplen)
add_test(NAME dump_api_max_pkts_flow COMMAND ./dump_api max_pkts_per_flow)
add_test(NAME dump_api_max_size COMMAND ./dump_api max_dump_size)
build_tests(dump_api)
