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

#define _GNU_SOURCE
#include <string.h>
#include "pcapdroid.h"
#include "common/utils.h"

typedef struct {
    char *key;
    UT_hash_handle hh;
} string_entry_t;

typedef struct {
    int key;
    UT_hash_handle hh;
} int_entry_t;

struct blacklist {
    string_entry_t *domains;
    int_entry_t *uids;
    ndpi_ptree_t *ptree;
    blacklists_stats_t stats;
};

/* ******************************************************* */

blacklist_t* blacklist_init() {
    blacklist_t *bl = (blacklist_t*) bl_calloc(1, sizeof(blacklist_t));
    if(!bl)
        return NULL;

    bl->ptree = ndpi_ptree_create();
    if(!bl->ptree) {
        bl_free(bl);
        return NULL;
    }

    return bl;
}

/* ******************************************************* */

int blacklist_add_domain(blacklist_t *bl, const char *domain) {
    if(strncmp(domain, "www.", 4) == 0)
        domain += 4;

    if(blacklist_match_domain(bl, domain))
        return -EADDRINUSE; // duplicate domain

    string_entry_t *entry = bl_malloc(sizeof(string_entry_t));
    if(!entry)
        return -ENOMEM;

    entry->key = bl_strdup(domain);
    if(!entry->key) {
        bl_free(entry);
        return -ENOMEM;
    }

    HASH_ADD_KEYPTR(hh, bl->domains, entry->key, strlen(entry->key), entry);
    bl->stats.num_domains++;
    return 0;
}

/* ******************************************************* */

int blacklist_add_ip(blacklist_t *bl, const ndpi_ip_addr_t *addr, uint8_t bits) {
    int rv = ndpi_ptree_insert(bl->ptree, addr, bits, PCAPDROID_NDPI_CATEGORY_MALWARE);
    if(rv != 0)
        return (rv == -2) ? -EADDRINUSE : -EINVAL; // -2 means IP already in ptree

    bl->stats.num_ips++;
    return 0;
}

/* ******************************************************* */

int blacklist_add_ipstr(blacklist_t *bl, const char *ip) {
    ndpi_ip_addr_t addr;
    int ipver = ndpi_parse_ip_string(ip, &addr);

    if((ipver != 4) && (ipver != 6))
        return -EINVAL;

    int bits = (ipver == 4) ? 32 : 128;
    return blacklist_add_ip(bl, &addr, bits);
}

/* ******************************************************* */

int blacklist_add_uid(blacklist_t *bl, int uid) {
    if(blacklist_match_uid(bl, uid))
        return -EADDRINUSE; // duplicate uid

    int_entry_t *entry = bl_malloc(sizeof(int_entry_t));
    if(!entry)
        return -ENOMEM;

    entry->key = uid;
    HASH_ADD_INT(bl->uids, key, entry);

    bl->stats.num_apps++;
    return 0;
}

/* ******************************************************* */

int blacklist_load_file(blacklist_t *bl, const char *path, blacklist_type btype, blacklist_stats_t *bstats) {
    FILE *f;
    char buffer[256];
    int num_ok = 0, num_fail = 0, num_dup = 0;
    int max_file_rules = 500000;

    f = fopen(path, "r");
    if(!f) {
        log_e("Could not open blacklist file \"%s\" [%d]: %s", path, errno, strerror(errno));
        return -1;
    }

    while(1) {
        char *item = fgets(buffer, sizeof(buffer), f);
        if(!item)
            break;

        if(!item[0] || (item[0] == '#') || (item[0] == '\n'))
            continue;

        item[strcspn(buffer, "\r\n")] = '\0';
        char *slash = strchr(buffer, '/');
        if(slash)
            *slash = 0;

        ndpi_ip_addr_t ip_addr;
        int ipver = ndpi_parse_ip_string(buffer, &ip_addr);
        bool is_ip_addr = (ipver == 4) || (ipver == 6);

        if(num_ok >= max_file_rules) {  // limit reached
            num_fail++;
            continue;
        }

        if(btype == IP_BLACKLIST) {
            if(!is_ip_addr) {
                log_w("Invalid IP/net \"%s\" in blacklist %s", buffer, path);
                num_fail++;
                continue;
            }

            int bits;
            if(slash)
                bits = atoi(slash + 1); // subnet
            else if(ipver == 4)
                bits = 32;
            else
                bits = 128;

            // Validate IPv4
            if(((ipver == 4) && (bits == 32)) &&
                    ((ip_addr.ipv4 == 0) || (ip_addr.ipv4 == 0xFFFFFFFF) || (ip_addr.ipv4 == 0x7F000001)))
                continue; // invalid

            // TODO validate IPv6

            int rv = blacklist_add_ip(bl, &ip_addr, bits);
            if(rv == 0)
                num_ok++;
            else if(rv == -EADDRINUSE)
                num_dup++;
            else
                num_fail++;
        } else { // DOMAIN_BLACKLIST
            if(is_ip_addr) {
                log_w("IP/net \"%s\" found instead of domain in %s", buffer, path);
                num_fail++;
                continue;
            }

            int rv = blacklist_add_domain(bl, item);
            if(rv == 0)
                num_ok++;
            else if(rv == -EADDRINUSE)
                num_dup++;
            else
                num_fail++;
        }
    }

    fclose(f);
    log_d("Blacklist loaded[%s][%s]: %d ok, %d dups, %d failed",
          strrchr(path, '/') + 1, (btype == IP_BLACKLIST ? "IP" : "domain"), num_ok, num_dup, num_fail);

    // current list stats
    memset(bstats, 0, sizeof(*bstats));
    bstats->num_failed = num_fail;
    bstats->num_rules = num_ok;

    // cumulative stats
    bl->stats.num_lists++;
    bl->stats.num_failed += num_fail;

    return 0;
}

/* ******************************************************* */

void blacklist_destroy(blacklist_t *bl) {
    string_entry_t *entry, *tmp;
    HASH_ITER(hh, bl->domains, entry, tmp) {
        HASH_DELETE(hh, bl->domains, entry);
        bl_free(entry->key);
        bl_free(entry);
    }

    int_entry_t *entry_i, *tmp_i;
    HASH_ITER(hh, bl->uids, entry_i, tmp_i) {
        HASH_DELETE(hh, bl->uids, entry_i);
        bl_free(entry_i);
    }

    ndpi_ptree_destroy(bl->ptree);
    bl_free(bl);
}

/* ******************************************************* */

bool blacklist_match_ip(blacklist_t *bl, const zdtun_ip_t *ip, int ipver) {
    ndpi_ip_addr_t addr = {0};
    if(ipver == 4)
        addr.ipv4 = ip->ip4;
    else
        memcpy(&addr.ipv6, &ip->ip6, 16);

    u_int64_t res = 0;
    ndpi_ptree_match_addr(bl->ptree, &addr, &res);

    return(res == PCAPDROID_NDPI_CATEGORY_MALWARE);
}

/* ******************************************************* */

bool blacklist_match_ipstr(blacklist_t *bl, const char *ip_str) {
    zdtun_ip_t parsed;

    int ipver = zdtun_parse_ip(ip_str, &parsed);
    if(ipver < 0)
        return false;

    return blacklist_match_ip(bl, &parsed, ipver);
}

/* ******************************************************* */

static char* get_second_level_domain(const char *domain) {
    char *dot = (char*) memrchr(domain, '.', strlen(domain));
    if(!dot || (dot == domain))
        return (char*)domain;

    dot = (char*) memrchr(domain, '.', dot - domain);
    if(!dot)
        return (char*)domain;

    return dot + 1;
}

/* ******************************************************* */

bool blacklist_match_domain(blacklist_t *bl, const char *domain) {
    // Keep in sync with MatchList.matchesHost
    string_entry_t *entry = NULL;

    if(strncmp(domain, "www.", 4) == 0)
        domain += 4;

    // exact domain match
    HASH_FIND_STR(bl->domains, domain, entry);
    if(entry != NULL)
        return true;

    // 2nd-level domain match
    char *domain2 = get_second_level_domain(domain);
    if(domain2 != domain) {
        HASH_FIND_STR(bl->domains, domain2, entry);
        if(entry != NULL)
            return true;
    }

    return false;
}

/* ******************************************************* */

bool blacklist_match_uid(blacklist_t *bl, int uid) {
    int_entry_t *entry = NULL;

    HASH_FIND_INT(bl->uids, &uid, entry);
    return(entry != NULL);
}

/* ******************************************************* */

void blacklist_get_stats(const blacklist_t *bl, blacklists_stats_t *stats) {
    *stats = bl->stats;
}

/* ******************************************************* */

#if ANDROID

static int bl_load_list_of_type(blacklist_t *bl, JNIEnv *env, jobject list, blacklist_type tp) {
    int num_items = (*env)->CallIntMethod(env, list, mids.listSize);
    int num_loaded = 0;

    for(int i=0; i<num_items; i++) {
        jstring *obj = (*env)->CallObjectMethod(env, list, mids.listGet, i);
        if(obj != NULL) {
            int rv;
            const char *val = (*env)->GetStringUTFChars(env, obj, NULL);

            switch (tp) {
                case IP_BLACKLIST:
                    rv = blacklist_add_ipstr(bl, val);
                    break;
                case DOMAIN_BLACKLIST:
                    rv = blacklist_add_domain(bl, val);
                    break;
                case UID_BLACKLIST:
                    rv = blacklist_add_uid(bl, atoi(val));
                    break;
                default:
                    rv = -1;
            }
            (*env)->ReleaseStringUTFChars(env, obj, val);
            (*env)->DeleteLocalRef(env, obj);

            if(rv == 0) {
                num_loaded++;
            } else if(rv != -EADDRINUSE) {
                log_e("bl add %s failed: %d", val, rv);
                return -1;
            }
        }
    }

    return num_loaded;
}

/* ******************************************************* */

int blacklist_load_list_descriptor(blacklist_t *bl, JNIEnv *env, jobject ld) {
    jobject apps = (*env)->GetObjectField(env, ld, fields.ld_apps);
    jobject hosts = (*env)->GetObjectField(env, ld, fields.ld_hosts);
    jobject ips = (*env)->GetObjectField(env, ld, fields.ld_ips);

    int num_apps = bl_load_list_of_type(bl, env, apps, UID_BLACKLIST);
    int num_domains = bl_load_list_of_type(bl, env, hosts, DOMAIN_BLACKLIST);
    int num_ips = bl_load_list_of_type(bl, env, ips, IP_BLACKLIST);

    if((num_apps == -1) || (num_ips == -1) || (num_domains == -1))
        return -1;

    (*env)->DeleteLocalRef(env, apps);
    (*env)->DeleteLocalRef(env, hosts);
    (*env)->DeleteLocalRef(env, ips);
    return 0;
}

#endif // ANDROID
