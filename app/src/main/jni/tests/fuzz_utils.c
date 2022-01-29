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

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <unistd.h>
#include <string.h>

/* ******************************************************* */

/* Creates a temporary file to hold the specified buffer data.
 * Returns the file name string. The string must be freed. */
char* buffer_to_tmpfile(const uint8_t *buf, size_t size) {
  char fname[] = "/tmp/pcapdroid_testXXXXXX";

  int filedes = mkstemp(fname);
  if(filedes < 0) {
    perror("mkstemp failed");
    return NULL;
  }

  FILE *fd = fdopen(filedes, "wb");
  if(!fd) {
    perror("fdopen failed");
    return NULL;
  }

  int success = (fwrite(buf, 1, size, fd) == size);

  fclose(fd);
  close(filedes);

  if(!success)
    return NULL;

  return strdup(fname);
}
