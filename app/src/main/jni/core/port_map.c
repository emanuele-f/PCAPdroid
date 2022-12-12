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
 * Copyright 2022 - Emanuele Faranda
 */

#include "common/memtrack.h"
#include "log_writer.h"
#include "port_map.h"

typedef struct {
    zdtun_ip_t redirect_ip;
    int ipver;
    int orig_port;
    int redirect_port;
} port_map_t;

typedef struct {
    port_map_t *items;
    int num_items;
} port_map_list_t;

static struct {
    port_map_list_t tcp;
    port_map_list_t udp;
} mappings;

/* ******************************************************* */

static inline port_map_list_t* get_map_list(int ipproto) {
    if(ipproto == IPPROTO_TCP)
        return &mappings.tcp;
    else if(ipproto == IPPROTO_UDP)
        return &mappings.udp;
    else
        return NULL;
}

/* ******************************************************* */

bool pd_add_port_map(int ipver, int ipproto, int orig_port, int redirect_port, const zdtun_ip_t *redirect_ip) {
    port_map_list_t *mlist = get_map_list(ipproto);
    if(!mlist)
        return false;

    mlist->items = (port_map_t*) pd_realloc(mlist->items, (++mlist->num_items) * sizeof(port_map_t));
    if(!mlist->items) {
        mlist->num_items = 0;
        return false;
    }

    port_map_t *mapping = &mlist->items[mlist->num_items - 1];
    mapping->orig_port = htons(orig_port);
    mapping->ipver = ipver;
    mapping->redirect_ip = *redirect_ip;
    mapping->redirect_port = htons(redirect_port);

    return true;
}

/* ******************************************************* */

bool pd_check_port_map(zdtun_conn_t *conn) {
    const zdtun_5tuple_t *tuple = zdtun_conn_get_5tuple(conn);
    port_map_list_t *mlist = get_map_list(tuple->ipproto);
    if(!mlist)
        return false;

    for(int i=0; i<mlist->num_items; i++) {
        port_map_t *mapping = &mlist->items[i];

        if(mapping->orig_port == tuple->dst_port) {
            log_d("Port mapping found: %d -> %d", ntohs(tuple->dst_port), ntohs(mapping->redirect_port));
            zdtun_conn_dnat(conn, &mapping->redirect_ip, mapping->redirect_port, mapping->ipver);
            return true;
        }
    }

    return false;
}

/* ******************************************************* */

static void clear_map_list(port_map_list_t *mlist) {
    pd_free(mlist->items);
    mlist->items = NULL;
    mlist->num_items = 0;
}

void pd_reset_port_map() {
    clear_map_list(&mappings.tcp);
    clear_map_list(&mappings.udp);
}