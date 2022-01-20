project(test)
cmake_minimum_required(VERSION 3.18.1)

set(ROOTDIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/.. ${CMAKE_CURRENT_BINARY_DIR}/main)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/..)
include_directories(${ROOTDIR}/submodules/zdtun)
include_directories(${ROOTDIR}/submodules/nDPI/src/include)

include(CTest)
list(APPEND CMAKE_CTEST_ARGUMENTS "--output-on-failure")

# Target to run tests and build them if necessary
add_custom_target(run_tests COMMAND ${CMAKE_CTEST_COMMAND})

# build_tests(target)
macro(build_tests)
  ADD_EXECUTABLE(${ARGV0} ${ARGV0}.c test_utils.c)
  add_dependencies(${ARGV0} libpcapd.so)
  add_dependencies(run_tests ${ARGV0})

  target_link_libraries(${ARGV0}
    capture
    pthread
    m)
endmacro()

build_tests(pcap_reader)

add_test(NAME blacklist_match COMMAND ./blacklist match)
build_tests(blacklist)
