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

#ifndef __IP_LRU_H__
#define __IP_LRU_H__

#include "zdtun.h"

typedef struct ip_lru ip_lru_t;

ip_lru_t* ip_lru_init(int max_size);
void ip_lru_destroy(ip_lru_t *lru);
void ip_lru_add(ip_lru_t *lru, const zdtun_ip_t *ip, const char *hostname);
char* ip_lru_find(ip_lru_t *lru, const zdtun_ip_t *ip);
int ip_lru_size(ip_lru_t *lru);

#endif // __IP_LRU_H__
