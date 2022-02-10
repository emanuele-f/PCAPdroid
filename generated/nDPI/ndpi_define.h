/*
 *
 * Copyright (C) 2011-17 - ntop.org
 *
 * This file is part of nDPI, an open source deep packet inspection
 * library based on the OpenDPI and PACE technology by ipoque GmbH
 *
 * nDPI is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * nDPI is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with nDPI.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

#ifndef __NDPI_DEFINE_INCLUDE_FILE__
#define __NDPI_DEFINE_INCLUDE_FILE__

/*
  gcc -E -dM - < /dev/null |grep ENDIAN
*/

#if defined(__FreeBSD__) || defined(__NetBSD__)
#include <sys/endian.h>
#endif

#ifdef __OpenBSD__
#include <endian.h>
#define __BYTE_ORDER BYTE_ORDER
#if BYTE_ORDER == LITTLE_ENDIAN
#ifndef __LITTLE_ENDIAN__
#define __LITTLE_ENDIAN__
#endif /* __LITTLE_ENDIAN__ */
#else
#define __BIG_ENDIAN__
#endif/* BYTE_ORDER */
#endif/* __OPENBSD__ */


#if __BYTE_ORDER == __LITTLE_ENDIAN
#ifndef __LITTLE_ENDIAN__
#define __LITTLE_ENDIAN__
#endif
#else
#ifndef __BIG_ENDIAN__
#define __BIG_ENDIAN__
#endif
#endif

#ifdef WIN32
#ifndef __LITTLE_ENDIAN__
#define __LITTLE_ENDIAN__ 1
#endif
#endif

#if !(defined(__LITTLE_ENDIAN__) || defined(__BIG_ENDIAN__))
#if defined(__mips__)
#undef __LITTLE_ENDIAN__
#undef __LITTLE_ENDIAN
#define __BIG_ENDIAN__
#endif

/* Everything else */
#if (defined(__BYTE_ORDER__) && defined(__ORDER_LITTLE_ENDIAN__))
#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#define __LITTLE_ENDIAN__
#else
#define __BIG_ENDIAN__
#endif
#endif

#endif

#define NDPI_USE_ASYMMETRIC_DETECTION                           0
#define NDPI_SELECTION_BITMASK_PROTOCOL_SIZE			u_int32_t

#define NDPI_SELECTION_BITMASK_PROTOCOL_IP			(1<<0)
#define NDPI_SELECTION_BITMASK_PROTOCOL_INT_TCP			(1<<1)
#define NDPI_SELECTION_BITMASK_PROTOCOL_INT_UDP			(1<<2)
#define NDPI_SELECTION_BITMASK_PROTOCOL_INT_TCP_OR_UDP		(1<<3)
#define NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD		(1<<4)
#define NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION	(1<<5)
#define NDPI_SELECTION_BITMASK_PROTOCOL_IPV6			(1<<6)
#define NDPI_SELECTION_BITMASK_PROTOCOL_IPV4_OR_IPV6		(1<<7)
#define NDPI_SELECTION_BITMASK_PROTOCOL_COMPLETE_TRAFFIC	(1<<8)
/* now combined detections */

/* v4 */
#define NDPI_SELECTION_BITMASK_PROTOCOL_TCP (NDPI_SELECTION_BITMASK_PROTOCOL_IP | NDPI_SELECTION_BITMASK_PROTOCOL_INT_TCP)
#define NDPI_SELECTION_BITMASK_PROTOCOL_UDP (NDPI_SELECTION_BITMASK_PROTOCOL_IP | NDPI_SELECTION_BITMASK_PROTOCOL_INT_UDP)
#define NDPI_SELECTION_BITMASK_PROTOCOL_TCP_OR_UDP (NDPI_SELECTION_BITMASK_PROTOCOL_IP | NDPI_SELECTION_BITMASK_PROTOCOL_INT_TCP_OR_UDP)

/* v6 */
#define NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP (NDPI_SELECTION_BITMASK_PROTOCOL_IPV6 | NDPI_SELECTION_BITMASK_PROTOCOL_INT_TCP)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V6_UDP (NDPI_SELECTION_BITMASK_PROTOCOL_IPV6 | NDPI_SELECTION_BITMASK_PROTOCOL_INT_UDP)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP_OR_UDP (NDPI_SELECTION_BITMASK_PROTOCOL_IPV6 | NDPI_SELECTION_BITMASK_PROTOCOL_INT_TCP_OR_UDP)

/* v4 or v6 */
#define NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP (NDPI_SELECTION_BITMASK_PROTOCOL_IPV4_OR_IPV6 | NDPI_SELECTION_BITMASK_PROTOCOL_INT_TCP)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_UDP (NDPI_SELECTION_BITMASK_PROTOCOL_IPV4_OR_IPV6 | NDPI_SELECTION_BITMASK_PROTOCOL_INT_UDP)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP_OR_UDP (NDPI_SELECTION_BITMASK_PROTOCOL_IPV4_OR_IPV6 | NDPI_SELECTION_BITMASK_PROTOCOL_INT_TCP_OR_UDP)


#define NDPI_SELECTION_BITMASK_PROTOCOL_TCP_WITH_PAYLOAD		(NDPI_SELECTION_BITMASK_PROTOCOL_TCP | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP_WITH_PAYLOAD		(NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP_WITH_PAYLOAD		(NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)

/* does it make sense to talk about udp with payload ??? have you ever seen empty udp packets ? */
#define NDPI_SELECTION_BITMASK_PROTOCOL_UDP_WITH_PAYLOAD		(NDPI_SELECTION_BITMASK_PROTOCOL_UDP | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V6_UDP_WITH_PAYLOAD		(NDPI_SELECTION_BITMASK_PROTOCOL_V6_UDP | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_UDP_WITH_PAYLOAD		(NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_UDP | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)

#define NDPI_SELECTION_BITMASK_PROTOCOL_TCP_OR_UDP_WITH_PAYLOAD		(NDPI_SELECTION_BITMASK_PROTOCOL_TCP_OR_UDP | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP_OR_UDP_WITH_PAYLOAD		(NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP_OR_UDP | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP_OR_UDP_WITH_PAYLOAD		(NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP_OR_UDP | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)

#define NDPI_SELECTION_BITMASK_PROTOCOL_TCP_WITHOUT_RETRANSMISSION	(NDPI_SELECTION_BITMASK_PROTOCOL_TCP | NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP_WITHOUT_RETRANSMISSION	(NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP | NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP_WITHOUT_RETRANSMISSION	(NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP | NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION)

#define NDPI_SELECTION_BITMASK_PROTOCOL_TCP_OR_UDP_WITHOUT_RETRANSMISSION	(NDPI_SELECTION_BITMASK_PROTOCOL_TCP_OR_UDP | NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP_OR_UDP_WITHOUT_RETRANSMISSION	(NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP_OR_UDP | NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP_OR_UDP_WITHOUT_RETRANSMISSION	(NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP_OR_UDP | NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION)

#define NDPI_SELECTION_BITMASK_PROTOCOL_TCP_WITH_PAYLOAD_WITHOUT_RETRANSMISSION	(NDPI_SELECTION_BITMASK_PROTOCOL_TCP | NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP_WITH_PAYLOAD_WITHOUT_RETRANSMISSION	(NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP | NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP_WITH_PAYLOAD_WITHOUT_RETRANSMISSION	(NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP | NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)

#define NDPI_SELECTION_BITMASK_PROTOCOL_TCP_OR_UDP_WITH_PAYLOAD_WITHOUT_RETRANSMISSION	(NDPI_SELECTION_BITMASK_PROTOCOL_TCP_OR_UDP | NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP_OR_UDP_WITH_PAYLOAD_WITHOUT_RETRANSMISSION	(NDPI_SELECTION_BITMASK_PROTOCOL_V6_TCP_OR_UDP | NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)
#define NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP_OR_UDP_WITH_PAYLOAD_WITHOUT_RETRANSMISSION	(NDPI_SELECTION_BITMASK_PROTOCOL_V4_V6_TCP_OR_UDP | NDPI_SELECTION_BITMASK_PROTOCOL_NO_TCP_RETRANSMISSION | NDPI_SELECTION_BITMASK_PROTOCOL_HAS_PAYLOAD)

/* safe src/dst protocol check macros... */

#define NDPI_SRC_HAS_PROTOCOL(src,protocol) ((src) != NULL && NDPI_COMPARE_PROTOCOL_TO_BITMASK((src)->detected_protocol_bitmask,(protocol)) != 0)

#define NDPI_DST_HAS_PROTOCOL(dst,protocol) ((dst) != NULL && NDPI_COMPARE_PROTOCOL_TO_BITMASK((dst)->detected_protocol_bitmask,(protocol)) != 0)

#define NDPI_SRC_OR_DST_HAS_PROTOCOL(src,dst,protocol) (NDPI_SRC_HAS_PROTOCOL(src,protocol) || NDPI_SRC_HAS_PROTOCOL(dst,protocol))

/**
 * convenience macro to check for excluded protocol
 * a protocol is excluded if the flow is known and either the protocol is not detected at all
 * or the excluded bitmask contains the protocol
 */
#define NDPI_FLOW_PROTOCOL_EXCLUDED(ndpi_struct,flow,protocol) ((flow) != NULL && \
								( NDPI_COMPARE_PROTOCOL_TO_BITMASK((ndpi_struct)->detection_bitmask, (protocol)) == 0 || \
								  NDPI_COMPARE_PROTOCOL_TO_BITMASK((flow)->excluded_protocol_bitmask, (protocol)) != 0 ) )

/* misc definitions */
#define NDPI_DEFAULT_MAX_TCP_RETRANSMISSION_WINDOW_SIZE 0x10000
#define NDPI_MAX_NUM_PKTS_PER_FLOW_TO_DISSECT           32


/* TODO: rebuild all memory areas to have a more aligned memory block here */

/* DEFINITION OF MAX LINE NUMBERS FOR line parse algorithm */
#define NDPI_MAX_PARSE_LINES_PER_PACKET                         64

#define MAX_PACKET_COUNTER                                   65000
#define MAX_DEFAULT_PORTS                                        5

#define NDPI_DIRECTCONNECT_CONNECTION_IP_TICK_TIMEOUT          600
#define NDPI_IRC_CONNECTION_TIMEOUT                            120
#define NDPI_GNUTELLA_CONNECTION_TIMEOUT                        60
#define NDPI_BATTLEFIELD_CONNECTION_TIMEOUT                     60
#define NDPI_THUNDER_CONNECTION_TIMEOUT                         30
#define NDPI_TVANTS_CONNECTION_TIMEOUT                           5
#define NDPI_ZATTOO_CONNECTION_TIMEOUT                         120
#define NDPI_ZATTOO_FLASH_TIMEOUT                                5
#define NDPI_JABBER_STUN_TIMEOUT                                30
#define NDPI_JABBER_FT_TIMEOUT				         5

#ifdef NDPI_ENABLE_DEBUG_MESSAGES
 #define NDPI_LOG(proto, m, log_level, args...)		                                 \
  {								                         \
    struct ndpi_detection_module_struct *mod = (struct ndpi_detection_module_struct*) m; \
    if(mod != NULL && mod->ndpi_debug_printf != NULL)		                         \
      (*(mod->ndpi_debug_printf))(proto, mod, log_level, __FILE__, __FUNCTION__, __LINE__, args); \
  }

 /* We must define NDPI_CURRENT_PROTO before include ndpi_main.h !!!
  *
  * #include "ndpi_protocol_ids.h"
  * #define NDPI_CURRENT_PROTO NDPI_PROTOCOL_XXXX
  * #include "ndpi_api.h"
  *
  */

 #ifndef NDPI_CURRENT_PROTO
 #define NDPI_CURRENT_PROTO NDPI_PROTOCOL_UNKNOWN
 #endif

 #define NDPI_LOG_ERR(mod, args...)		                                 \
  if(mod && mod->ndpi_log_level >= NDPI_LOG_ERROR) {	                         \
    if(mod != NULL && mod->ndpi_debug_printf != NULL)		                         \
      (*(mod->ndpi_debug_printf))(NDPI_CURRENT_PROTO, mod, NDPI_LOG_ERROR , __FILE__, __FUNCTION__, __LINE__, args); \
  }

 #define NDPI_LOG_INFO(mod, args...)		                                 \
  if(mod && mod->ndpi_log_level >= NDPI_LOG_TRACE) {	                         \
    if(mod != NULL && mod->ndpi_debug_printf != NULL)		                         \
      (*(mod->ndpi_debug_printf))(NDPI_CURRENT_PROTO, mod, NDPI_LOG_TRACE , __FILE__, __FUNCTION__, __LINE__, args); \
  }

 #define NDPI_LOG_DBG(mod, args...)		                                 \
  if(mod && mod->ndpi_log_level >= NDPI_LOG_DEBUG) {	                         \
    if(mod != NULL && mod->ndpi_debug_printf != NULL)		                         \
      (*(mod->ndpi_debug_printf))(NDPI_CURRENT_PROTO, mod, NDPI_LOG_DEBUG , __FILE__, __FUNCTION__, __LINE__, args); \
  }

 #define NDPI_LOG_DBG2(mod, args...)		                                 \
  if(mod && mod->ndpi_log_level >= NDPI_LOG_DEBUG_EXTRA) {	                         \
    if(mod != NULL && mod->ndpi_debug_printf != NULL)		                         \
      (*(mod->ndpi_debug_printf))(NDPI_CURRENT_PROTO, mod, NDPI_LOG_DEBUG_EXTRA , __FILE__, __FUNCTION__, __LINE__, args); \
  }

#else							/* not defined NDPI_ENABLE_DEBUG_MESSAGES */
# ifdef WIN32
# define NDPI_LOG(...) {}
# define NDPI_LOG_ERR(...) {}
# define NDPI_LOG_INFO(...) {}
# define NDPI_LOG_DBG(...) {}
# define NDPI_LOG_DBG2(...) {}
# else
# define NDPI_LOG(proto, mod, log_level, args...) { /* printf(args); */ }
# define NDPI_LOG_ERR(mod, args...)  { printf(args); }
# define NDPI_LOG_INFO(mod, args...) { /* printf(args); */ }
# define NDPI_LOG_DBG(mod,  args...) { /* printf(args); */ }
# define NDPI_LOG_DBG2(mod, args...) { /* printf(args); */ }
# endif
#endif							/* NDPI_ENABLE_DEBUG_MESSAGES */

#define NDPI_EXCLUDE_PROTO(mod,flow) ndpi_exclude_protocol(mod, flow, NDPI_CURRENT_PROTO, __FILE__, __FUNCTION__, __LINE__)

/**
 * macro for getting the string len of a static string
 *
 * use it instead of strlen to avoid runtime calculations
 */
#define NDPI_STATICSTRING_LEN( s ) ( sizeof( s ) - 1 )

/** macro to compare 2 IPv6 addresses with each other to identify the "smaller" IPv6 address  */
#define NDPI_COMPARE_IPV6_ADDRESS_STRUCTS(x,y)  \
  ((x.u6_addr.u6_addr64[0] < y.u6_addr.u6_addr64[0]) || ((x.u6_addr.u6_addr64[0] == y.u6_addr.u6_addr64[0]) && (x.u6_addr.u6_addr64[1] < y.u6_addr.u6_addr64[1])))

#define NDPI_NUM_BITS              512
#define NDPI_NUM_BITS_MASK         (512-1)

#define NDPI_BITS /* 32 */ (sizeof(ndpi_ndpi_mask) * 8 /* number of bits in a byte */)        /* bits per mask */
#define howmanybits(x, y)   (((x)+((y)-1))/(y))


#define NDPI_SET(p, n)    ((p)->fds_bits[(n)/NDPI_BITS] |=  (1ul << (((u_int32_t)n) % NDPI_BITS)))
#define NDPI_CLR(p, n)    ((p)->fds_bits[(n)/NDPI_BITS] &= ~(1ul << (((u_int32_t)n) % NDPI_BITS)))
#define NDPI_ISSET(p, n)  ((p)->fds_bits[(n)/NDPI_BITS] &   (1ul << (((u_int32_t)n) % NDPI_BITS)))
#define NDPI_ZERO(p)      memset((char *)(p), 0, sizeof(*(p)))
#define NDPI_ONE(p)       memset((char *)(p), 0xFF, sizeof(*(p)))

#define NDPI_NUM_FDS_BITS     howmanybits(NDPI_NUM_BITS, NDPI_BITS)

#define NDPI_PROTOCOL_BITMASK ndpi_protocol_bitmask_struct_t

#define NDPI_BITMASK_ADD(a,b)     NDPI_SET(&a,b)
#define NDPI_BITMASK_DEL(a,b)     NDPI_CLR(&a,b)
#define NDPI_BITMASK_RESET(a)     NDPI_ZERO(&a)
#define NDPI_BITMASK_SET_ALL(a)   NDPI_ONE(&a)
#define NDPI_BITMASK_SET(a, b)    { memcpy(&a, &b, sizeof(NDPI_PROTOCOL_BITMASK)); }

#define NDPI_SET_BIT(num, n)    num |= 1ULL << ( n )
#define NDPI_CLR_BIT(num, n)    num &= ~(1ULL << ( n ))
#define NDPI_CLR_BIT(num, n)    num &= ~(1ULL << ( n ))
#define NDPI_ISSET_BIT(num, n)  (num & (1ULL << ( n )))
#define NDPI_ZERO_BIT(num)      num = 0

/* this is a very very tricky macro *g*,
 * the compiler will remove all shifts here if the protocol is static...
 */
#define NDPI_ADD_PROTOCOL_TO_BITMASK(bmask,value)     NDPI_SET(&bmask,   value & NDPI_NUM_BITS_MASK)
#define NDPI_DEL_PROTOCOL_FROM_BITMASK(bmask,value)   NDPI_CLR(&bmask,   value & NDPI_NUM_BITS_MASK)
#define NDPI_COMPARE_PROTOCOL_TO_BITMASK(bmask,value) NDPI_ISSET(&bmask, value & NDPI_NUM_BITS_MASK)

#define NDPI_SAVE_AS_BITMASK(bmask,value)  { NDPI_ZERO(&bmask) ; NDPI_ADD_PROTOCOL_TO_BITMASK(bmask, value); }


#define ndpi_min(a,b)   ((a < b) ? a : b)
#define ndpi_max(a,b)   ((a > b) ? a : b)

#define NDPI_PARSE_PACKET_LINE_INFO(ndpi_struct,flow,packet)		\
                        if (packet->packet_lines_parsed_complete != 1) {        \
			  ndpi_parse_packet_line_info(ndpi_struct,flow);	\
                        }                                                       \

#define NDPI_IPSEC_PROTOCOL_ESP	   50
#define NDPI_IPSEC_PROTOCOL_AH	   51
#define NDPI_GRE_PROTOCOL_TYPE	   0x2F
#define NDPI_ICMP_PROTOCOL_TYPE	   0x01
#define NDPI_IGMP_PROTOCOL_TYPE	   0x02
#define NDPI_EGP_PROTOCOL_TYPE	   0x08
#define NDPI_OSPF_PROTOCOL_TYPE	   0x59
#define NDPI_SCTP_PROTOCOL_TYPE	   132
#define NDPI_IPIP_PROTOCOL_TYPE    0x04
#define NDPI_ICMPV6_PROTOCOL_TYPE  0x3a

/* the get_uXX will return raw network packet bytes !! */
#define get_u_int8_t(X,O)   (*(u_int8_t  *)((&(((u_int8_t *)X)[O]))))
#define get_u_int16_t(X,O)  (*(u_int16_t *)((&(((u_int8_t *)X)[O]))))
#define get_u_int32_t(X,O)  (*(u_int32_t *)((&(((u_int8_t *)X)[O]))))
#if defined(__arm__)
static inline u_int64_t get_u_int64_t(const u_int8_t* X, int O)
{
  u_int64_t tmp;
  memcpy(&tmp, X + O, sizeof(tmp));
  return tmp;
}
#else
#define get_u_int64_t(X,O)  (*(u_int64_t *)((&(((u_int8_t *)X)[O]))))
#endif // __arm__

/* new definitions to get little endian from network bytes */
#define get_ul8(X,O) get_u_int8_t(X,O)


#if defined(__LITTLE_ENDIAN__) || defined(_LITTLE_ENDIAN)
#define get_l16(X,O)  get_u_int16_t(X,O)
#define get_l32(X,O)  get_u_int32_t(X,O)
#elif defined(__BIG_ENDIAN__) || defined(__BIG_ENDIAN)
/* convert the bytes from big to little endian */
# define get_l16(X,O) bswap_16(get_u_int16_t(X,O))
# define get_l32(X,O) bswap_32(get_u_int32_t(X,O))
#else
#error "__BYTE_ORDER MUST BE DEFINED !"
#endif							/* __BYTE_ORDER */

/* define memory callback function */
#define match_first_bytes(payload,st) (memcmp((payload),(st),(sizeof(st)-1))==0)

#if defined(WIN32) && !defined(snprintf)
#define snprintf _snprintf
#endif

#if defined(WIN32)
#undef strtok_r
#define strtok_r strtok_s

#if BYTE_ORDER == LITTLE_ENDIAN
#define le16toh(x) (x)
#define le32toh(x) (x)
#else
#error "byte order not supported"
#endif

#endif /* WIN32 */

#define NDPI_MAX_DNS_REQUESTS                   16
#define NDPI_MIN_NUM_STUN_DETECTION             8

#define NDPI_MAJOR                              4
#define NDPI_MINOR                              2
#define NDPI_PATCH                              0

/* IMPORTANT: order according to its severity */
#define NDPI_CIPHER_SAFE                        0
#define NDPI_CIPHER_WEAK                        1
#define NDPI_CIPHER_INSECURE                    2

#define NDPI_OPTIMAL_HLL_NUM_BUCKETS           16

#define NDPI_MAX_NUM_TLS_APPL_BLOCKS            8

#ifdef __APPLE__

#include <libkern/OSByteOrder.h>

#define htobe16(x) OSSwapHostToBigInt16(x)
#define htole16(x) OSSwapHostToLittleInt16(x)
#define be16toh(x) OSSwapBigToHostInt16(x)
#define le16toh(x) OSSwapLittleToHostInt16(x)

#define htobe32(x) OSSwapHostToBigInt32(x)
#define htole32(x) OSSwapHostToLittleInt32(x)
#define be32toh(x) OSSwapBigToHostInt32(x)
#define le32toh(x) OSSwapLittleToHostInt32(x)

#define htobe64(x) OSSwapHostToBigInt64(x)
#define htole64(x) OSSwapHostToLittleInt64(x)
#define be64toh(x) OSSwapBigToHostInt64(x)
#define le64toh(x) OSSwapLittleToHostInt64(x)

#endif /* __APPLE__ */


#if defined(__MINGW32__)

#if __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__

#define htobe16(x) htons(x)
#define htole16(x) (x)
#define be16toh(x) ntohs(x)
#define le16toh(x) (x)
#define htobe32(x) htonl(x)
#define htole32(x) (x)
#define be32toh(x) ntohl(x)
#define le32toh(x) (x)
#define htobe64(x) htonll(x)
#define htole64(x) (x)
#define be64toh(x) ntohll(x)
#define le64toh(x) (x)

#elif __BYTE_ORDER__ == __ORDER_BIG_ENDIAN__

#define htobe16(x) (x)
#define htole16(x) __builtin_bswap16(x)
#define be16toh(x) (x)
#define le16toh(x) __builtin_bswap16(x)
#define htobe32(x) (x)
#define htole32(x) __builtin_bswap32(x)
#define be32toh(x) (x)
#define le32toh(x) __builtin_bswap32(x)
#define htobe64(x) (x)
#define htole64(x) __builtin_bswap64(x)
#define be64toh(x) (x)
#define le64toh(x) __builtin_bswap64(x)

#else
#error Unexpected __BYTE_ORDER__

#endif /* __BYTE_ORDER__ */
#endif /* __MINGW32__ */


#ifndef ETH_ARP
#define ETH_ARP                0x0806
#endif

#ifndef ETH_P_IP
#define ETH_P_IP               0x0800 	/* IPv4 */
#endif

#ifndef ETH_P_IPV6
#define ETH_P_IPV6	       0x86dd	/* IPv6 */
#endif

#ifndef ETH_P_VLAN
#define ETH_P_VLAN             0x8100
#endif

#ifndef ETH_P_MPLS_UNI
#define ETH_P_MPLS_UNI         0x8847
#endif

#ifndef ETH_P_MPLS_MULTI
#define ETH_P_MPLS_MULTI       0x8848
#endif

#ifndef ETH_P_PPPoE
#define ETH_P_PPPoE            0x8864
#endif

#endif /* __NDPI_DEFINE_INCLUDE_FILE__ */
