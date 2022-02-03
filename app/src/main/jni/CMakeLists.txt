project("PCAPdroid")
cmake_minimum_required(VERSION 3.18.1)

set(CMAKE_VERBOSE_MAKEFILE ON)
set(ROOTDIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)

# Add debug symbols
# See packagingOptions.doNotStrip in build.gradle to avoid stripping
SET(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -g -fPIC")

# zdtun
set(ZDTUN_ROOT ${ROOTDIR}/submodules/zdtun)
include_directories(${ZDTUN_ROOT})
add_subdirectory(${ZDTUN_ROOT} zdtun_build)

# nDPI
set(NDPI_ROOT ${ROOTDIR}/submodules/nDPI)
include_directories(${NDPI_ROOT}/src/include ${NDPI_ROOT}/src/lib/third_party/include)

# base
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

add_subdirectory(common)
add_subdirectory(pcapd)
add_subdirectory(core)
