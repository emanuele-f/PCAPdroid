project("PCAPdroid")
cmake_minimum_required(VERSION 3.18.1)

set(CMAKE_VERBOSE_MAKEFILE ON)
set(ROOTDIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../..)

# Add debug symbols
# See packagingOptions.doNotStrip in build.gradle to avoid stripping
SET(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -g -fPIC")

# Make the build reproducible
get_filename_component(ROOTDIR_ABS ${ROOTDIR} ABSOLUTE)
get_filename_component(ROOTDIR_REAL ${ROOTDIR} REALPATH)
SET(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffile-prefix-map=${ROOTDIR_ABS}=.")
if(NOT ROOTDIR_REAL STREQUAL ROOTDIR_ABS)
  SET(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffile-prefix-map=${ROOTDIR_REAL}=.")
endif()

if(ANDROID_NDK)
  get_filename_component(NDK_REAL ${ANDROID_NDK} REALPATH)
  SET(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffile-prefix-map=${ANDROID_NDK}=/ndk")
  if(NOT NDK_REAL STREQUAL ANDROID_NDK)
    SET(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffile-prefix-map=${NDK_REAL}=/ndk")
  endif()
endif()

SET(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffile-prefix-map=${CMAKE_BINARY_DIR}=build")

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
add_subdirectory(zstd)
