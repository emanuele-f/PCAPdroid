project(pcapd C)

# libpcap
set(LIBPCAP_ROOT ${ROOTDIR}/submodules/libpcap)
add_definitions(-DPACKAGE_VERSION="1.10.1-PRE-GIT")
add_definitions(-DHAVE_STRERROR)
add_definitions(-DHAVE_SOCKLEN_T)
include_directories(${LIBPCAP_ROOT})

add_custom_command(OUTPUT ${LIBPCAP_ROOT}/grammar.c ${LIBPCAP_ROOT}/scanner.c
        COMMAND cd ${LIBPCAP_ROOT} && ./configure && make grammar.c scanner.c
        DEPENDS ${LIBPCAP_ROOT}/grammar.y.in)

ADD_LIBRARY(pcap STATIC
        ${LIBPCAP_ROOT}/pcap.c
        ${LIBPCAP_ROOT}/pcap-common.c
        ${LIBPCAP_ROOT}/sf-pcap.c
        ${LIBPCAP_ROOT}/sf-pcapng.c
        ${LIBPCAP_ROOT}/pcap-linux.c
        ${LIBPCAP_ROOT}/fad-gifc.c
        ${LIBPCAP_ROOT}/nametoaddr.c
        ${LIBPCAP_ROOT}/etherent.c
        ${LIBPCAP_ROOT}/savefile.c
        ${LIBPCAP_ROOT}/fmtutils.c
        ${LIBPCAP_ROOT}/gencode.c
        ${LIBPCAP_ROOT}/grammar.c
        ${LIBPCAP_ROOT}/scanner.c
        ${LIBPCAP_ROOT}/bpf_filter.c
        ${LIBPCAP_ROOT}/optimize.c
        ${LIBPCAP_ROOT}/missing/strlcpy.c)

# Executables must be names as libraries to be stored into corresponding native folder
add_executable(libpcapd.so pcapd.c nl_utils.c)

# Better to link static libs to avoid changing the library path
target_link_libraries(libpcapd.so pcap zdtun common)
