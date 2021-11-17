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
 * Copyright 2021 - Emanuele Faranda
 */

#ifndef PCAPDROID_BLACKLIST_H
#define PCAPDROID_BLACKLIST_H

#include "ndpi_api.h"

#define PCAPDROID_NDPI_CATEGORY_MALWARE NDPI_PROTOCOL_CATEGORY_CUSTOM_1

typedef struct blacklist blacklist_t;

typedef struct {
    int num_rules;
    int num_failed;
} blacklist_stats_t;

typedef struct {
    int num_lists;
    int num_domains;
    int num_ips;
    int num_failed;
} blacklists_stats_t;

typedef enum {
    DOMAIN_BLACKLIST,
    IP_BLACKLIST
} blacklist_type;

blacklist_t* blacklist_init(struct ndpi_detection_module_struct *ndpi);
void blacklist_destroy(blacklist_t *bl);
void blacklist_clear(blacklist_t *bl);
int blacklist_add_domain(blacklist_t *bl, const char *domain);
int blacklist_add_ip(blacklist_t *bl, const char *ip_or_net);
int blacklist_load_file(blacklist_t *bl, const char *path, blacklist_type btype, blacklist_stats_t *bstats);
void blacklist_ready(blacklist_t *bl);
bool blacklist_match_ip(blacklist_t *bl, uint32_t ip);
bool blacklist_match_domain(blacklist_t *bl, const char *domain);
void blacklist_get_stats(const blacklist_t *bl, blacklists_stats_t *stats);

#endif //PCAPDROID_BLACKLIST_H
