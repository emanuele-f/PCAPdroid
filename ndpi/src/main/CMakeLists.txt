cmake_minimum_required(VERSION 2.8.11)
include(ExternalProject)

set(CMAKE_VERBOSE_MAKEFILE ON)
set(ROOTDIR ../../../../../submodules/nDPI)
add_definitions(-DNDPI_LIB_COMPILATION)

ExternalProject_Add(libndpi
    SOURCE_DIR ${ROOTDIR}
    CONFIGURE_COMMAND ./autogen.sh COMMAND ./configure --disable-gcrypt)
