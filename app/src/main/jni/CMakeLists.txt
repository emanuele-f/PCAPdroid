cmake_minimum_required(VERSION 3.10.2)

set(CMAKE_VERBOSE_MAKEFILE ON)
set(ROOTDIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)

# zdtun
set(ZDTUN_ROOT ${ROOTDIR}/submodules/zdtun)
include_directories(${ZDTUN_ROOT})
add_subdirectory(${ZDTUN_ROOT} zdtun_build)

# base
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

add_subdirectory(common)
add_subdirectory(pcapd)
add_subdirectory(vpnproxy-jni)
