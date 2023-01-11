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

#ifndef PCAPDROID_PORTMAP_H
#define PCAPDROID_PORTMAP_H

#include <stdbool.h>
#include "zdtun.h"

bool pd_add_port_map(int ipver, int ipproto, int orig_port, int redirect_port, const zdtun_ip_t *redirect_ip);
bool pd_check_port_map(zdtun_conn_t *conn);
void pd_reset_port_map();

#endif //PCAPDROID_PORTMAP_H
