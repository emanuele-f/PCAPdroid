cmake_minimum_required(VERSION 2.8.11)

# A C project
project(C)

set(CMAKE_VERBOSE_MAKEFILE ON)

add_definitions(-DNDPI_LIB_COMPILATION)

include_directories(./src/include ./src/lib/third_party/include)

AUX_SOURCE_DIRECTORY(./src/lib/third_party/src AllSources)
AUX_SOURCE_DIRECTORY(./src/lib/protocols AllSources)
AUX_SOURCE_DIRECTORY(./src/lib AllSources)

# libndpi.so target
ADD_LIBRARY(ndpi SHARED ${AllSources})
