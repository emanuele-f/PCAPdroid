project(core)

add_library(capture SHARED
        pcapdroid.c
        capture_vpn.c
        capture_root.c
        ip_lru.c
        ndpi_master_protos.c
        crc32.c
        blacklist.c
        pcap_utils.c
        jni_impl.c)

# nDPI
set(NDPI_ROOT ${ROOTDIR}/submodules/nDPI)
add_definitions(-DNDPI_LIB_COMPILATION)
include_directories(${NDPI_ROOT}/src/include ${NDPI_ROOT}/src/lib/third_party/include)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/src/lib ndpiSources)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/src/lib/third_party/src ndpiSources)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/src/lib/protocols ndpiSources)

set(NDPI_GEN ${ROOTDIR}/generated/nDPI)
configure_file(${NDPI_GEN}/ndpi_api.h ${NDPI_ROOT}/src/include/ndpi_api.h)
configure_file(${NDPI_GEN}/ndpi_config.h ${NDPI_ROOT}/src/include/ndpi_config.h)
configure_file(${NDPI_GEN}/ndpi_define.h ${NDPI_ROOT}/src/include/ndpi_define.h)

ADD_LIBRARY(ndpi SHARED ${ndpiSources} ${NDPI_ROOT}/src/include/ndpi_api.h)


if(ANDROID)
        find_library(log-lib log)
else()
        set(log-lib "")
endif()

target_link_libraries(capture
        zdtun
        ndpi
        common
        ${log-lib})
