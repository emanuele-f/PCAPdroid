option(FUZZING "Build for Fuzz Testing" OFF)

# Build with address/leak sanitizer
set(CMAKE_C_COMPILER "clang")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fno-omit-frame-pointer")

project(test)
cmake_minimum_required(VERSION 3.18.1)

if(FUZZING)
  if(NOT DEFINED "LIB_FUZZING_ENGINE")
    set(LIB_FUZZING_ENGINE "-fsanitize=fuzzer,address -fno-sanitize-recover=undefined")
  endif()

  set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DFUZZING -g ${LIB_FUZZING_ENGINE}")
else()
  set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fsanitize=address")
endif()

#set(CMAKE_VERBOSE_MAKEFILE ON)
set(ROOTDIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/.. ${CMAKE_CURRENT_BINARY_DIR}/main)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/..)
include_directories(${ROOTDIR}/submodules/zdtun)
include_directories(${ROOTDIR}/submodules/nDPI/src/include)

include(CTest)

# Target to run tests and build them if necessary
add_custom_target(build_tests)
add_custom_target(run_tests COMMAND CTEST_OUTPUT_ON_FAILURE=1 ${CMAKE_CTEST_COMMAND})
add_dependencies(run_tests build_tests)

# build_group(target)
macro(build_group)
  add_executable(${ARGV0} ${ARGV0}.c test_utils.c)
  add_dependencies(${ARGV0} libpcapd.so)
  add_dependencies(build_tests ${ARGV0})

  target_link_libraries(${ARGV0} capture)
endmacro()

# Fuzz targets
if(FUZZING)
  add_executable(fuzz_pcapd fuzz_pcapd.c)
  target_link_libraries(fuzz_pcapd libpcapd.so)
endif()

# Tests
build_group(pcap_reader)

add_test(NAME dpi_extract COMMAND ./dpi extract)
build_group(dpi)

add_test(NAME blacklist_match COMMAND ./blacklist match)
add_test(NAME blacklist_detection COMMAND ./blacklist detection)
build_group(blacklist)

add_test(NAME dump_api_snaplen COMMAND ./dump_api snaplen)
add_test(NAME dump_api_max_pkts_flow COMMAND ./dump_api max_pkts_per_flow)
add_test(NAME dump_api_max_size COMMAND ./dump_api max_dump_size)
build_group(dump_api)
