
cmake_minimum_required(VERSION 3.4.1)

add_library(vpnproxy-jni
             SHARED
        vpnproxy.c
             uid_resolver.c
             pcap)

include_directories(../../../..)
include_directories(../../../../../../zdtun)
include_directories(../../../../../../nDPI/src/include)

find_library( log-lib
              log)

# TODO Fix PATHS
#find_library( zdtun-lib
#        zdtun
#        PATHS ../../../../../zdtun/build/intermediates/library_and_local_jars_jni/debug/${ANDROID_ABI})

set(zdtun-lib ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../zdtun/build/intermediates/library_and_local_jars_jni/debug/${ANDROID_ABI}/libzdtun.so)

if (NOT zdtun-lib)
    message(FATAL_ERROR "zdtun not found!")
endif()

set(ndpi-lib ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ndpi/build/intermediates/library_and_local_jars_jni/debug/${ANDROID_ABI}/libndpi.so)

if (NOT ndpi-lib)
    message(FATAL_ERROR "nDPI not found!")
endif()

target_link_libraries( vpnproxy-jni
        ${zdtun-lib}
        ${ndpi-lib}
        ${log-lib})
