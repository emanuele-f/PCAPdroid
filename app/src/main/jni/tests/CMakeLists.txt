option(FUZZING "Build for Fuzz Testing" OFF)

# Build with address/leak sanitizer
set(CMAKE_C_COMPILER "clang")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fno-omit-frame-pointer")

project(tests)
cmake_minimum_required(VERSION 3.18.1)

if(FUZZING)
  if(NOT DEFINED "LIB_FUZZING_ENGINE")
    set(LIB_FUZZING_ENGINE "-fsanitize=fuzzer,address -fno-sanitize-recover=undefined")
  endif()

  set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -DFUZZING -g ${LIB_FUZZING_ENGINE}")
else()
  # Tests
  set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fsanitize=address")
endif()

#set(CMAKE_VERBOSE_MAKEFILE ON)
set(ROOTDIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/.. ${CMAKE_CURRENT_BINARY_DIR}/main)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/..)
include_directories(${ROOTDIR}/submodules/zdtun)
include_directories(${ROOTDIR}/submodules/nDPI/src/include)

if(FUZZING)
  add_subdirectory(fuzz)
else()
  add_subdirectory(test)
endif()
