/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2020-21 - Emanuele Faranda
 */

#include <stdint.h>

/* ******************************************************* */

// from DHCPd
static u_int16_t in_cksum(const char *buf, size_t nbytes, u_int32_t sum) {
    u_int16_t i;

    /* Checksum all the pairs of bytes first... */
    for (i = 0; i < (nbytes & ~1U); i += 2) {
        sum += (u_int16_t) ntohs(*((u_int16_t *)(buf + i)));
        /* Add carry. */
        if(sum > 0xFFFF)
            sum -= 0xFFFF;
    }

    /* If there's a single byte left over, checksum it, too.   Network
       byte order is big-endian, so the remaining byte is the high byte. */
    if(i < nbytes) {
        sum += buf [i] << 8;
        /* Add carry. */
        if(sum > 0xFFFF)
            sum -= 0xFFFF;
    }

    return sum;
}

/* ******************************************************* */

static inline u_int16_t wrapsum(u_int32_t sum) {
    sum = ~sum & 0xFFFF;
    return htons(sum);
}

/* ******************************************************* */

static u_int16_t ip_checksum(const void *buf, size_t hdr_len) {
    return wrapsum(in_cksum(buf, hdr_len, 0));
}