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

#ifndef __UID_LRU_H__
#define __UID_LRU_H__

#include "zdtun.h"

typedef struct uid_lru uid_lru_t;

uid_lru_t* uid_lru_init(int max_size);
void uid_lru_destroy(uid_lru_t *lru);
void uid_lru_add(uid_lru_t *lru, const zdtun_5tuple_t *tuple, int uid);
int uid_lru_find(uid_lru_t *lru, const zdtun_5tuple_t *tuple);
int uid_lru_size(uid_lru_t *lru);

#endif // __UID_LRU_H__
