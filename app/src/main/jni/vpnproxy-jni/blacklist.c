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

#include "vpnproxy.h"
#include "common/utils.h"

typedef struct string_entry {
    char *key;
    UT_hash_handle hh;
} string_entry_t;

struct blacklist {
    string_entry_t *domains;
    struct ndpi_detection_module_struct *ndpi;
    bool ready;
    blacklists_stats_t stats;
};

/* ******************************************************* */

blacklist_t* blacklist_init(struct ndpi_detection_module_struct *ndpi) {
    if(!ndpi)
        return NULL;

    blacklist_t *bl = (blacklist_t*) bl_calloc(1, sizeof(blacklist_t));
    if(!bl)
        return NULL;

    bl->ndpi = ndpi;
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

int blacklist_add_ip(blacklist_t *bl, const char *ip_or_net) {
    if(ndpi_load_ip_category(bl->ndpi, ip_or_net, PCAPDROID_NDPI_CATEGORY_MALWARE) == 0) {
        // NOTE: duplicate IPs are not detected, so they will be counted multiple times.
        // This could be fixed by using the ndpi_ptree_t API instead.
        bl->stats.num_ips++;
        return 0;
    }
    return -EINVAL;
}

/* ******************************************************* */

int blacklist_load_file(blacklist_t *bl, const char *path, blacklist_type btype, blacklist_stats_t *bstats) {
    FILE *f;
    char buffer[256];
    int num_ok = 0, num_fail = 0, num_dup = 0;
    int max_file_rules = 500000;

    if(bl->ready) {
        log_e("Blacklist is locked. Run blacklist_clear and load it again.");
        return -1;
    }

    f = fopen(path, "r");
    if(!f) {
        log_e("Could not open blacklist file \"%s\" [%d]: %s", path, errno, strerror(errno));
        return -1;
    }

    while(1) {
        struct in_addr in_addr;
        char *item = fgets(buffer, sizeof(buffer), f);
        if(!item)
            break;

        if(!item[0] || (item[0] == '#') || (item[0] == '\n'))
            continue;

        item[strcspn(buffer, "\r\n")] = '\0';
        bool is_net = strchr(buffer, '/');
        bool is_ipv6 = strchr(buffer, ':');
        bool is_addr = (inet_pton(AF_INET, item, &in_addr) == 1);

        if((num_ok >= max_file_rules)  // limit reached
                || is_ipv6) {          // IPv6 not supported
            num_fail++;
            continue;
        }

        if(btype == IP_BLACKLIST) {
            if(!is_net && !is_addr) {
                log_w("Invalid IP/net \"%s\" in blacklist %s", buffer, path);
                num_fail++;
                continue;
            }

            // IPv4 Address/subnet
            if(!is_net && ((in_addr.s_addr == 0) || (in_addr.s_addr == 0xFFFFFFFF) || (in_addr.s_addr == 0x7F000001)))
                continue; // invalid

            if(blacklist_add_ip(bl, item) == 0)
                num_ok++;
            else
                num_fail++;
        } else { // DOMAIN_BLACKLIST
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

// Neded to properly load nDPI. Must be called on the capture thread.
void blacklist_ready(blacklist_t *bl) {
    if(!bl->ready) {
        ndpi_enable_loaded_categories(bl->ndpi);
        bl->ready = true;
    }
}

/* ******************************************************* */

void blacklist_clear(blacklist_t *bl) {
    string_entry_t *entry, *tmp;

    HASH_ITER(hh, bl->domains, entry, tmp) {
        HASH_DELETE(hh, bl->domains, entry);
        bl_free(entry->key);
        bl_free(entry);
    }
    bl->domains = NULL;
    bl->ready = false;
    memset(&bl->stats, 0, sizeof(bl->stats));
}

/* ******************************************************* */

void blacklist_destroy(blacklist_t *bl) {
    blacklist_clear(bl);
    bl_free(bl);
}

/* ******************************************************* */

bool blacklist_match_ip(blacklist_t *bl, uint32_t ip) {
    char ipstr[INET_ADDRSTRLEN];
    struct in_addr addr;
    ipstr[0] = '\0';
    ndpi_protocol_category_t cat = 0;

    if(!bl->ready)
        return false;

    addr.s_addr = ip;
    inet_ntop(AF_INET, &addr, ipstr, sizeof(ipstr));

    ndpi_get_custom_category_match(bl->ndpi, ipstr, strlen(ipstr), &cat);
    return(cat == PCAPDROID_NDPI_CATEGORY_MALWARE);
}

/* ******************************************************* */

bool blacklist_match_domain(blacklist_t *bl, const char *domain) {
    string_entry_t *entry = NULL;

    if(strncmp(domain, "www.", 4) == 0)
        domain += 4;

    HASH_FIND_STR(bl->domains, domain, entry);
    return(entry != NULL);
}

/* ******************************************************* */

void blacklist_get_stats(const blacklist_t *bl, blacklists_stats_t *stats) {
    *stats = bl->stats;
}