#include <pthread.h>
#include <stdio.h>
#include <errno.h>
#include "log_writer.h"

struct log_writer;

static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static struct log_writer **loggers = NULL;
static int num_loggers = 0;

struct log_writer {
    FILE *f;
    char *path;
    int id;
    int level;
    bool errored;
};

static void pd_destroy_logger(struct log_writer *logger) {
    if(logger->f)
        fclose(logger->f);
    pd_free(logger->path);
    pd_free(logger);
}

void pd_close_loggers() {
    pthread_mutex_lock(&mutex);

    for(int i=0; i<num_loggers; i++)
        pd_destroy_logger(loggers[i]);
    pd_free(loggers);
    loggers = NULL;
    num_loggers = 0;

    pthread_mutex_unlock(&mutex);
}

int pd_init_logger(const char *path, int min_lvl) {
    int rv;
    struct log_writer *logger = (struct log_writer*) pd_calloc(1, sizeof(struct log_writer));
    if(!logger)
        return -errno;

    logger->level = min_lvl;
    logger->path = pd_strdup(path);
    if(!logger->path) {
        pd_free(logger);
        return -errno;
    }

    pthread_mutex_lock(&mutex);
    loggers = pd_realloc(loggers, sizeof(void*) * (num_loggers + 1));
    if(!loggers) {
        pd_destroy_logger(logger);
        rv = -1;
    } else {
        loggers[num_loggers] = logger;
        logger->id = rv = num_loggers++;
    }
    pthread_mutex_unlock(&mutex);

    return rv;
}

int pd_log_write(int logger_id, int lvl, const char *msg) {
    int rv = 0;
    char dtbuf[64];
    struct tm tm;
    time_t tnow = time(NULL);
    strftime(dtbuf, sizeof(dtbuf), "%d/%b/%Y %H:%M:%S", localtime_r(&tnow, &tm));

    pthread_mutex_lock(&mutex);

    if((logger_id < 0) || (logger_id >= num_loggers)) {
        rv = -ENOENT;
        goto unlock;
    }

    struct log_writer *logger = loggers[logger_id];

    if(logger->level > lvl)
        goto unlock;

    if(!logger->f && !logger->errored) {
        // only overwrite the file when writing to it
        logger->f = fopen(logger->path, "w");

        if(!logger->f) {
#ifdef ANDROID
            __android_log_print(ANDROID_LOG_ERROR, logtag,
                                "pd_init_logger %s failed[%d]: %s", logger->path, errno, strerror(errno));
#endif
            rv = -errno;
            logger->errored = true;
            goto unlock;
        }
    }

    if(!logger->f || ferror(logger->f)) {
        rv = -EINVAL;
        goto unlock;
    }

    fprintf(logger->f, "[%c] %s - %s\n", loglvl2char(lvl), dtbuf, msg);
    if(ferror(logger->f)) {
#ifdef ANDROID
        __android_log_print(ANDROID_LOG_ERROR, logtag,
                            "pd_log %d failed[%d]: %s", logger_id, errno, strerror(errno));
#endif
        rv = -errno;
        goto unlock;
    }

    fflush(logger->f);

unlock:
    pthread_mutex_unlock(&mutex);
    return rv;
}