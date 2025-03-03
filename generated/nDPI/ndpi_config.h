/* src/include/ndpi_config.h.  Generated from ndpi_config.h.in by configure.  */
/* src/include/ndpi_config.h.in.  Generated from configure.ac by autoheader.  */

/* Define to 1 if you have the <dlfcn.h> header file. */
#define HAVE_DLFCN_H 1

/* Define to 1 if you have the <float.h> header file. */
#define HAVE_FLOAT_H 1

/* Define to 1 if you have the <inttypes.h> header file. */
#define HAVE_INTTYPES_H 1

/* Define to 1 if you have the 'gcrypt' library (-lgcrypt). */
/* #undef HAVE_LIBGCRYPT */

/* Define to 1 if you have the 'gpg-error' library (-lgpg-error). */
/* #undef HAVE_LIBGPG_ERROR */

/* libjson-c is present */
/* #undef HAVE_LIBJSON_C */

/* Define to 1 if you have the 'm' library (-lm). */
/* #undef HAVE_LIBM */

/* Define to 1 if you have the 'maxminddb' library (-lmaxminddb). */
/* #undef HAVE_LIBMAXMINDDB */

/* Define to 1 if you have the 'pcap' library (-lpcap). */
/* #undef HAVE_LIBPCAP */

/* Define to 1 if you have the 'pthread' library (-lpthread). */
/* #undef HAVE_LIBPTHREAD */

/* Define to 1 if you have the <math.h> header file. */
#define HAVE_MATH_H 1

/* MaxMind DB support */
/* #undef HAVE_MAXMINDDB */

/* Define to 1 if you have the <maxminddb.h> header file. */
/* #undef HAVE_MAXMINDDB_H */

/* PF_RING nBPF is present */
/* #undef HAVE_NBPF */

/* Define to 1 if you have the <netinet/in.h> header file. */
#define HAVE_NETINET_IN_H 1

/* libpcre2(-dev) is present */
/* #undef HAVE_PCRE2 */

/* Define if you have POSIX threads libraries and header files. */
/* #undef HAVE_PTHREAD */

/* Have PTHREAD_PRIO_INHERIT. */
/* #undef HAVE_PTHREAD_PRIO_INHERIT */

/* libc has pthread_setaffinity_np */
/* #undef HAVE_PTHREAD_SETAFFINITY_NP */

/* rrdtool is present */
/* #undef HAVE_RRDTOOL */

/* Define to 1 if you have the <stdint.h> header file. */
#define HAVE_STDINT_H 1

/* Define to 1 if you have the <stdio.h> header file. */
#define HAVE_STDIO_H 1

/* Define to 1 if you have the <stdlib.h> header file. */
#define HAVE_STDLIB_H 1

/* Define to 1 if you have the <strings.h> header file. */
#define HAVE_STRINGS_H 1

/* Define to 1 if you have the <string.h> header file. */
#define HAVE_STRING_H 1

/* Define to 1 if you have the <sys/stat.h> header file. */
#define HAVE_SYS_STAT_H 1

/* Define to 1 if you have the <sys/types.h> header file. */
#define HAVE_SYS_TYPES_H 1

/* Define to 1 if you have the <unistd.h> header file. */
#define HAVE_UNISTD_H 1

/* Define to the sub-directory where libtool stores uninstalled libraries. */
#define LT_OBJDIR ".libs/"

/* nDPI base directory */
#define NDPI_BASE_DIR "/home/emanuele/src/PCAPdroid/submodules/nDPI"

/* Enable ndpi_debug_messages */
/* #undef NDPI_ENABLE_DEBUG_MESSAGES */

/* Last GIT change */
#define NDPI_GIT_DATE "Thu Jan 2 07:07:49 2025 +0100"

/* GIT Release */
#define NDPI_GIT_RELEASE "4.12.0-5032-46a2fbf"

/* nDPI major release */
#define NDPI_MAJOR_RELEASE "4"

/* nDPI minor release */
#define NDPI_MINOR_RELEASE "12"

/* nDPI patch level */
#define NDPI_PATCH_LEVEL "0"

/* Define to 1 if your C compiler doesn't accept -c and -o together. */
/* #undef NO_MINUS_C_MINUS_O */

/* Name of package */
#define PACKAGE "libndpi"

/* Define to the address where bug reports for this package should be sent. */
#define PACKAGE_BUGREPORT ""

/* Define to the full name of this package. */
#define PACKAGE_NAME "libndpi"

/* Define to the full name and version of this package. */
#define PACKAGE_STRING "libndpi 4.12.0"

/* Define to the one symbol short name of this package. */
#define PACKAGE_TARNAME "libndpi"

/* Define to the home page for this package. */
#define PACKAGE_URL ""

/* Define to the version of this package. */
#define PACKAGE_VERSION "4.12.0"

/* Define to necessary symbol if this constant uses a non-standard name on
   your system. */
/* #undef PTHREAD_CREATE_JOINABLE */

/* Define to 1 if all of the C89 standard headers exist (not just the ones
   required in a freestanding environment). This macro is provided for
   backward compatibility; new code need not use it. */
#define STDC_HEADERS 1

/* Use locally installed libgcrypt instead of builtin gcrypt-light */
/* #undef USE_HOST_LIBGCRYPT */

/* Use CRoaring 2.1.x */
/* #undef USE_ROARING_V2 */

/* Version number of package */
#define VERSION "4.12.0"

/* Define to '__inline__' or '__inline' if that's what the C compiler
   calls it, or to nothing if 'inline' is not supported under any name.  */
#ifndef __cplusplus
/* #undef inline */
#endif
