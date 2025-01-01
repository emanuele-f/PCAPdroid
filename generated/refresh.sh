#!/bin/bash -x
set -e

# Set this to your NDK path
# Should match the ndkVersion in app/build.gradle
ANDROID_NDK="${ANDROID_NDK:-${HOME}/Android/Sdk/ndk}/26.1.10909125"

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
( cd $SUBMODULES_PATH/libpcap && \
  rm -f scanner.c grammar.h scanner.c scanner.h config.h && \
  ./autogen.sh && \
  ac_cv_netfilter_can_compile=no ./configure --host $TARGET --without-libnl --enable-usb=no --enable-netmap=no --enable-bluetooth=no --enable-dbus=no --enable-rdma=no && \
  make scanner.h grammar.h )
cp $SUBMODULES_PATH/libpcap/{grammar.c,grammar.h,scanner.c,scanner.h,config.h} ./libpcap

# nDPI
( cd $SUBMODULES_PATH/nDPI && ./autogen.sh || true; ./configure --host $TARGET --disable-gcrypt --with-only-libndpi )
cp $SUBMODULES_PATH/nDPI/src/include/{ndpi_api.h,ndpi_config.h,ndpi_define.h} ./nDPI
