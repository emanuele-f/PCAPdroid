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
    bool locked;
    uint64_t num_ips;
    uint64_t num_domains;
};

/* ******************************************************* */

blacklist_t* blacklist_init(struct ndpi_detection_module_struct *ndpi) {
    if(!ndpi)
        return NULL;

    blacklist_t *bl = (blacklist_t*) calloc(1, sizeof(blacklist_t));
    if(!bl)
        return NULL;

    bl->ndpi = ndpi;
    return bl;
}

/* ******************************************************* */

int blacklist_load_file(blacklist_t *bl, const char *path) {
    FILE *f;
    char buffer[256];
    int num_dm_ok = 0, num_dm_fail = 0;
    int num_ip_ok = 0, num_ip_fail = 0;

    if(bl->locked) {
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

        if(strchr(buffer, ':')) {
            // IPv6 not supported
            num_ip_fail++;
            continue;
        } else if(is_net || inet_pton(AF_INET, item, &in_addr) == 1) {
            // IPv4 Address/subnet
            if(!is_net && ((in_addr.s_addr == 0) || (in_addr.s_addr == 0xFFFFFFFF) || (in_addr.s_addr == 0x7F000001)))
                continue; // invalid

            if(ndpi_load_ip_category(bl->ndpi, item, PCAPDROID_NDPI_CATEGORY_MALWARE) == 0)
                num_ip_ok++;
            else
                num_ip_fail++;
        } else {
            // Domain
            if(blacklist_match_domain(bl, item))
                continue; // duplicate domain

            string_entry_t *entry = malloc(sizeof(string_entry_t));
            if(!entry) {
                num_dm_fail++;
                continue;
            }

            entry->key = strdup(item);
            if(!entry->key) {
                free(entry);
                num_dm_fail++;
                continue;
            }

            HASH_ADD_KEYPTR(hh, bl->domains, entry->key, strlen(entry->key), entry);
            num_dm_ok++;
        }
    }

    fclose(f);
    log_d("Blacklist loaded[%s]: %d domains (%d failed), %d IPs (%d failed)",
          strrchr(path, '/') + 1, num_dm_ok, num_dm_fail, num_ip_ok, num_ip_fail);

    bl->num_domains += num_dm_ok;
    bl->num_ips += num_ip_ok;

    return 0;
}

/* ******************************************************* */

void blacklist_clear(blacklist_t *bl) {
    string_entry_t *entry, *tmp;

    HASH_ITER(hh, bl->domains, entry, tmp) {
        HASH_DELETE(hh, bl->domains, entry);
        free(entry->key);
        free(entry);
    }

    bl->domains = NULL;
    bl->locked = false;
    bl->num_ips = bl->num_domains = 0;
}

/* ******************************************************* */

void blacklist_destroy(blacklist_t *bl) {
    blacklist_clear(bl);
    free(bl);
}

/* ******************************************************* */

bool blacklist_match_ip(blacklist_t *bl, uint32_t ip) {
    char ipstr[INET_ADDRSTRLEN];
    struct in_addr addr;
    ipstr[0] = '\0';
    ndpi_protocol_category_t cat = 0;

    if(!bl->locked) {
        ndpi_enable_loaded_categories(bl->ndpi);
        bl->locked = true;
    }

    addr.s_addr = ip;
    inet_ntop(AF_INET, &addr, ipstr, sizeof(ipstr));

    ndpi_get_custom_category_match(bl->ndpi, ipstr, strlen(ipstr), &cat);
    return(cat == PCAPDROID_NDPI_CATEGORY_MALWARE);
}

/* ******************************************************* */

bool blacklist_match_domain(blacklist_t *bl, const char *domain) {
    string_entry_t *entry = NULL;

    HASH_FIND_STR(bl->domains, domain, entry);
    return(entry != NULL);
}