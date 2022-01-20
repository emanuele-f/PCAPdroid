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

#ifdef ANDROID
#include <jni.h>
#endif

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
    int num_apps;
    int num_failed;
} blacklists_stats_t;

typedef enum {
    DOMAIN_BLACKLIST,
    IP_BLACKLIST,
    UID_BLACKLIST
} blacklist_type;

typedef struct {
    char *fname;
    blacklist_type type;
} bl_info_t;

typedef struct {
    char *fname;
    int num_rules;
} bl_status_t;

typedef struct {
    bl_status_t *items;
    int size;
    int cur_items;
} bl_status_arr_t;

blacklist_t* blacklist_init();
void blacklist_destroy(blacklist_t *bl);
int blacklist_add_domain(blacklist_t *bl, const char *domain);
int blacklist_add_ip(blacklist_t *bl, const ndpi_ip_addr_t *addr, uint8_t ipver);
int blacklist_add_ipstr(blacklist_t *bl, const char *ip);
int blacklist_add_uid(blacklist_t *bl, int uid);
int blacklist_load_file(blacklist_t *bl, const char *path, blacklist_type btype, blacklist_stats_t *bstats);
#ifdef ANDROID
int blacklist_load_list_descriptor(blacklist_t *bl, JNIEnv *env, jobject ld);
#endif
bool blacklist_match_ip(blacklist_t *bl, const zdtun_ip_t *ip, int ipver);
bool blacklist_match_ipstr(blacklist_t *bl, const char *ip);
bool blacklist_match_domain(blacklist_t *bl, const char *domain);
bool blacklist_match_uid(blacklist_t *bl, int uid);
void blacklist_get_stats(const blacklist_t *bl, blacklists_stats_t *stats);

#endif //PCAPDROID_BLACKLIST_H
