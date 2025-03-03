project(core)

add_library(capture SHARED
        pcapdroid.c
        capture_vpn.c
        capture_pcap.c
        ip_lru.c
        ndpi_config.c
        crc32.c
        blacklist.c
        pcap_dump.c
        pcap_reader.c
        log_writer.c
        port_map.c
        ushark_dll.c
        jni_impl.c)

# nDPI
add_definitions(-DNDPI_LIB_COMPILATION)
add_definitions(-D__bswap_64=bswap_64)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/src/lib ndpiSources)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/src/lib/third_party/src ndpiSources)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/src/lib/third_party/src/hll ndpiSources)
AUX_SOURCE_DIRECTORY(${NDPI_ROOT}/src/lib/protocols ndpiSources)

# slim build, see nDPI lib/Makefile.in
add_definitions(-DNDPI_SLIM)
list(REMOVE_ITEM ndpiSources
        ${NDPI_ROOT}/src/lib/third_party/src/libinjection_html5.c
        ${NDPI_ROOT}/src/lib/third_party/src/libinjection_xss.c
        ${NDPI_ROOT}/src/lib/third_party/src/libinjection_sqli.c
        ${NDPI_ROOT}/src/lib/third_party/src/roaring.c
        ${NDPI_ROOT}/src/lib/third_party/src/roaring_v2.c
        ${NDPI_ROOT}/src/lib/ndpi_bitmap.c
        ${NDPI_ROOT}/src/lib/ndpi_bitmap64_fuse.c
        ${NDPI_ROOT}/src/lib/ndpi_binary_bitmap.c
        ${NDPI_ROOT}/src/lib/ndpi_filter.c)

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
