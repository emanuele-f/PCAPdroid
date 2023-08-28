cmake_minimum_required(VERSION 3.18.1)
project(tests)

option(FUZZING "Build for Fuzz Testing" OFF)

if(FUZZING)
  if(NOT DEFINED "LIB_FUZZING_ENGINE")
    set(LIB_FUZZING_ENGINE "-fsanitize=fuzzer")
  endif()

  set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DFUZZING -g -O1 ${LIB_FUZZING_ENGINE} -fsanitize=address,undefined,leak -fno-sanitize=alignment")
endif()

#set(CMAKE_VERBOSE_MAKEFILE ON)
set(ROOTDIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/.. ${CMAKE_CURRENT_BINARY_DIR}/main)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/..)
include_directories(${ROOTDIR}/submodules/zdtun)
include_directories(${ROOTDIR}/submodules/nDPI/src/include)
include_directories(.)

if(FUZZING)
  add_subdirectory(fuzz)
else()
  add_subdirectory(test)
endif()
