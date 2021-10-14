project(vpnproxy-jni)

add_library(vpnproxy-jni SHARED
        vpnproxy.c
        capture_proxy.c
        capture_root.c
        ip_lru.c
        ndpi_master_protos.c
        crc32.c
        pcap_utils.c)

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

find_library(log-lib log)

target_link_libraries(vpnproxy-jni
        zdtun
        ndpi
        common
        ${log-lib})
