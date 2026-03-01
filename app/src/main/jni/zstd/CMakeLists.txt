cmake_minimum_required(VERSION 3.18.1)
project(zstd_jni C)

if(NOT DEFINED ROOTDIR)
    set(ROOTDIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../..)
endif()

set(ZSTD_ROOT ${ROOTDIR}/submodules/zstd/lib)

# Decoder-only: common + decompress sources
set(ZSTD_SOURCES
        ${ZSTD_ROOT}/common/debug.c
        ${ZSTD_ROOT}/common/entropy_common.c
        ${ZSTD_ROOT}/common/error_private.c
        ${ZSTD_ROOT}/common/fse_decompress.c
        ${ZSTD_ROOT}/common/xxhash.c
        ${ZSTD_ROOT}/common/zstd_common.c
        ${ZSTD_ROOT}/decompress/huf_decompress.c
        ${ZSTD_ROOT}/decompress/zstd_ddict.c
        ${ZSTD_ROOT}/decompress/zstd_decompress.c
        ${ZSTD_ROOT}/decompress/zstd_decompress_block.c)

add_library(zstd_dec SHARED
        zstd_jni.c
        ${ZSTD_SOURCES})

# Build decoder only
target_compile_definitions(zstd_dec PRIVATE
        ZSTD_DISABLE_COMPRESS=1
        ZSTD_DISABLE_ASM=1)

target_include_directories(zstd_dec PRIVATE
        ${ZSTD_ROOT}
        ${ZSTD_ROOT}/common
        ${CMAKE_CURRENT_SOURCE_DIR}/..)

if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(zstd_dec common ${log-lib})
else()
    find_package(JNI REQUIRED)
    target_include_directories(zstd_dec PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(zstd_dec common)
endif()
