cmake_minimum_required(VERSION 3.4.1)
project(vpnproxy-jni)
include(ExternalProject)

set(CMAKE_VERBOSE_MAKEFILE ON)
set(ROOTDIR ../../../../..)

include_directories(../../../..)

add_library(vpnproxy-jni SHARED
        vpnproxy.c
        uid_resolver.c
        pcap)

# nDPI
set(NDPI_ROOT ${ROOTDIR}/submodules/nDPI)
add_definitions(-DNDPI_LIB_COMPILATION)
include_directories(${NDPI_ROOT}/src/include ${NDPI_ROOT}/src/lib/third_party/include)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/src/lib ndpiSources)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/src/lib/third_party/src ndpiSources)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/src/lib/protocols ndpiSources)

add_custom_command(OUTPUT ${NDPI_ROOT}/src/include/ndpi_api.h
        COMMAND cd ${NDPI_ROOT} && ./autogen.sh && ./configure --disable-gcrypt
        DEPENDS ${NDPI_ROOT}/src/include/ndpi_api.h.in)

ADD_LIBRARY(ndpi SHARED ${ndpiSources} ${NDPI_ROOT}/src/include/ndpi_api.h)

# zdtun
set(ZDTUN_ROOT ${ROOTDIR}/submodules/zdtun)
include_directories(${ZDTUN_ROOT})
add_subdirectory(${ZDTUN_ROOT} build)

# Link
find_library(log-lib log)

target_link_libraries(vpnproxy-jni
        zdtun
        ndpi
        ${log-lib})
