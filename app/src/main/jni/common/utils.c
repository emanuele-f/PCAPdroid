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

#include <stdio.h>
#include <errno.h>
#include <unistd.h>
#include <stdarg.h>
#include <sys/wait.h>
#include <paths.h>
#include "utils.h"

memtrack_t memtrack = {0};
int loglevel = 0;
const char *logtag = "pcapdroid-native";
void (*logcallback)(int lvl, const char *msg) = NULL;

// Needed for local compilation, don't remove
extern char **environ;

/* ******************************************************* */

void set_log_level(int lvl) {
    loglevel = lvl;
}

/* ******************************************************* */

void log_android(int lvl, const char *fmt, ...) {
    if(lvl >= loglevel) {
        char line[1024];
        va_list argptr;

        va_start(argptr, fmt);
        vsnprintf(line, sizeof(line), fmt, argptr);
        va_end(argptr);

#ifdef ANDROID
        __android_log_print(lvl, logtag, "%s", line);
#else
        {
            char ch = '?';

            switch(lvl) {
                case ANDROID_LOG_DEBUG: ch = 'D'; break;
                case ANDROID_LOG_INFO:  ch = 'I'; break;
                case ANDROID_LOG_WARN:  ch = 'W'; break;
                case ANDROID_LOG_ERROR: ch = 'E'; break;
                case ANDROID_LOG_FATAL: ch = 'F'; break;
            }

            fprintf(lvl >= ANDROID_LOG_WARN ? stderr : stdout, "[%c] %s\n", ch, line);
        }
#endif

        if(logcallback != NULL)
            logcallback(lvl, line);
    }
}

/* ******************************************************* */

char loglvl2char(int lvl) {
    switch (lvl) {
        case ANDROID_LOG_DEBUG: return 'D';
        case ANDROID_LOG_INFO:  return 'I';
        case ANDROID_LOG_WARN:  return 'W';
        case ANDROID_LOG_ERROR: return 'E';
        case ANDROID_LOG_FATAL: return 'F';
        default:                return '?';
    }
}

/* ******************************************************* */

ssize_t xwrite(int fd, const void *buf, size_t count) {
    size_t sofar = 0;
    ssize_t ret;

    do {
        ret = write(fd, (u_char*)buf + sofar, count - sofar);

        if(ret < 0) {
            if(errno == EINTR)
                continue;

            return ret;
        }

        sofar += ret;
    } while((sofar != count) && (ret != 0));

    if(sofar != count)
        return -1;

    return 0;
}

/* ******************************************************* */

// returns < 0 on error, 0 if fd is closed
ssize_t xread(int fd, void *buf, size_t count) {
    size_t sofar = 0;
    ssize_t rv;

    do {
        rv = read(fd, (char*)buf + sofar, count - sofar);

        if(rv < 0) {
            if(errno == EINTR)
                continue;
            return rv;
        }

        sofar += rv;
    } while((sofar != count) && (rv != 0));

    if(sofar != count)
        return (rv == 0) ? 0 : -1;

    return count;
}

/* ******************************************************* */

void tupleSwapPeers(zdtun_5tuple_t *tuple) {
    uint16_t tmp = tuple->dst_port;
    tuple->dst_port = tuple->src_port;
    tuple->src_port = tmp;

    zdtun_ip_t tmp1 = tuple->dst_ip;
    tuple->dst_ip = tuple->src_ip;
    tuple->src_ip = tmp1;
}

/* ******************************************************* */

char* humanSize(char *buf, int bufsize, double bytes) {
    static char *suffix[] = {"B", "KB", "MB", "GB", "TB"};
    int num_suffix = sizeof(suffix) / sizeof(suffix[0]);
    int i;

    for(i = 0; (bytes >= 1024) && (i < num_suffix); i++)
        bytes /= 1024;

    snprintf(buf, bufsize, "%.02f %s", bytes, suffix[i]);
    return buf;
}

/* ******************************************************* */

/* Dumps packets in the hex format of "od -A x -t x1", which makes it compatible with
 * text2pcap. */
void hexdump(const char *buf, size_t bufsize) {
    size_t off = 0;
    char out[64];
    int idx = 0;
    static const char hex[] = "0123456789abcdef";

    while(off < bufsize) {
        if((off % 16) == 0) {
            if(off > 0) {
                out[idx] = '\0';
                log_d("%s", out);
            }
            idx = sprintf(out, "%06zx", off);
        }

        out[idx++] = ' ';
        out[idx++] = hex[(buf[off] & 0xF0) >> 4];
        out[idx++] = hex[buf[off] & 0x0F];
        off++;
    }

    if((off % 16) != 0) {
        out[idx] = '\0';
        log_d("%s", out);
        idx = sprintf(out, "%06zx", off);
    }

    out[idx] = '\0';
    log_d("%s", out);
}

/* ******************************************************* */

// Start a sub-process, running a command with some arguments, either as root or as the current user.
// If out_fd is not NULL, on success the out_fd parameter will receive an open file descriptor
// to read the command output.
// Returns the pid of the child process, or -1 on failure
// NOTE: the caller MUST call waitpid or equivalent to prevent process zombification and close the out_fd
int start_subprocess(const char *prog, const char *args, bool as_root, int* out_fd) {
    int in_p[2], out_p[2];
    pid_t pid;

    if((pipe(in_p) != 0) || (out_fd && (pipe(out_p) != 0))) {
        log_f("pipe failed[%d]: %s", errno, strerror(errno));
        return -1;
    }

    if((pid = fork()) == 0) {
        // child
        char *argp[] = {"sh", "-c", as_root ? "su" : "sh", NULL};

        close(in_p[1]);
        dup2(in_p[0], STDIN_FILENO);

        if(out_fd) {
            close(out_p[0]);

            dup2(out_p[1], STDOUT_FILENO);
            dup2(out_p[1], STDERR_FILENO);
        }

        execve(_PATH_BSHELL, argp, environ);
        fprintf(stderr, "execve failed[%d]: %s", errno, strerror(errno));
        exit(1);
    } else if(pid > 0) {
        // parent
        if(out_fd) {
            *out_fd = out_p[0];
            close(out_p[1]);
        }

        close(in_p[0]);

        // write "su" command input
        log_d("start_subprocess[%d]: %s %s", pid, prog, args);
        write(in_p[1], prog, strlen(prog));
        write(in_p[1], " ", 1);
        write(in_p[1], args, strlen(args));
        write(in_p[1], "\n", 1);
        close(in_p[1]);
    } else {
        log_f("fork() failed[%d]: %s", errno, strerror(errno));
        close(in_p[0]);
        close(in_p[1]);

        if(out_fd) {
            close(out_p[0]);
            close(out_p[1]);
        }
        return -1;
    }

    return pid;
}

/* ******************************************************* */

int run_shell_cmd(const char *prog, const char *args, bool as_root, bool check_error) {
    int out_fd;
    int pid = start_subprocess(prog, args, as_root, &out_fd);
    if(pid <= 0)
        return -1;

    int rv;
    if(waitpid(pid, &rv, 0) <= 0) {
        log_f("waitpid %d failed[%d]: %s", pid, errno, strerror(errno));
        return -1;
    }

    if(check_error && (rv != 0)) {
        char buf[128];
        struct timeval timeout = {0};
        fd_set fds;

        buf[0] = '\0';
        FD_ZERO(&fds);
        FD_SET(out_fd, &fds);

        select(out_fd + 1, &fds, NULL, NULL, &timeout);
        if (FD_ISSET(out_fd, &fds)) {
            int num = read(out_fd, buf, sizeof(buf) - 1);
            if (num > 0)
                buf[num] = '\0';
        }

        log_f("\"%s\" invocation failed: %s", prog, buf);
    }

    close(out_fd);
    return rv;
}
