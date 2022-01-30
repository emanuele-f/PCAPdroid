project(pcapd C)

# libpcap
set(LIBPCAP_ROOT ${ROOTDIR}/submodules/libpcap)
add_definitions(-DPACKAGE_VERSION="1.10.1-PRE-GIT")
add_definitions(-DHAVE_STRERROR)
add_definitions(-DHAVE_SOCKLEN_T)
include_directories(${LIBPCAP_ROOT})

set(LIBPCAP_GEN ${ROOTDIR}/generated/libpcap)
configure_file(${LIBPCAP_GEN}/grammar.c ${LIBPCAP_ROOT}/grammar.c)
configure_file(${LIBPCAP_GEN}/grammar.h ${LIBPCAP_ROOT}/grammar.h)
configure_file(${LIBPCAP_GEN}/scanner.c ${LIBPCAP_ROOT}/scanner.c)
configure_file(${LIBPCAP_GEN}/scanner.h ${LIBPCAP_ROOT}/scanner.h)

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

if(FUZZING)
  add_library(libpcapd.so SHARED pcapd.c nl_utils.c)
else()
  # Executables must be names as libraries to be stored into corresponding native folder
  # NOTE: only works if extractNativeLibs is true
  add_executable(libpcapd.so pcapd.c nl_utils.c)
endif()

# Better to link static libs to avoid changing the library path
target_link_libraries(libpcapd.so pcap zdtun common)
