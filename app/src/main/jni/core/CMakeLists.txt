project(core)

add_library(capture SHARED
        pcapdroid.c
        capture_vpn.c
        capture_libpcap.c
        ip_lru.c
        ndpi_config.c
        crc32.c
        blacklist.c
        pcap_dump.c
        log_writer.c
        port_map.c
        jni_impl.c)

# nDPI
add_definitions(-DNDPI_LIB_COMPILATION)
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
        set(extra-libs ${log-lib})
else()
        set(extra-libs "pthread" "m")
endif()

target_link_libraries(capture
        zdtun
        ndpi
        common
        ${extra-libs})
