#!/bin/bash

# Set this to your NDK path
ANDROID_NDK="${ANDROID_NDK:-${HOME}/Android/Sdk/ndk-bundle}"

# https://developer.android.com/ndk/guides/other_build_systems
export TOOLCHAIN=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64
export TARGET=x86_64-linux-android21
export ANDROID_NATIVE_API_LEVEL=21
export AR=$TOOLCHAIN/bin/llvm-ar
export CC=$TOOLCHAIN/bin/$TARGET$API-clang
export AS=$CC
export CXX=$TOOLCHAIN/bin/$TARGET$API-clang++
export LD=$TOOLCHAIN/bin/ld
export RANLIB=$TOOLCHAIN/bin/llvm-ranlib
export STRIP=$TOOLCHAIN/bin/llvm-strip

SUBMODULES_PATH=../submodules

# libpcap
( cd $SUBMODULES_PATH/libpcap && ./configure --host $TARGET )
cp $SUBMODULES_PATH/libpcap/{grammar.c,grammar.h,scanner.c,scanner.h} ./libpcap

# nDPI
( cd $SUBMODULES_PATH/nDPI && ./autogen.sh; ./configure --host $TARGET --disable-gcrypt --with-only-libndpi )
cp $SUBMODULES_PATH/nDPI/src/include/{ndpi_api.h,ndpi_config.h,ndpi_define.h} ./nDPI
