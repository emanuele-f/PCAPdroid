#ifndef PCAPDROID_LOG_WRITER_H
#define PCAPDROID_LOG_WRITER_H

#include "common/utils.h"

#define PD_DEFAULT_LOGGER 0
#define PD_DEFAULT_LOGGER_LEVEL ANDROID_LOG_INFO

int pd_init_logger(const char *path, int min_lvl);
int pd_log_write(int logger, int lvl, const char *msg);
void pd_close_loggers();

#endif //PCAPDROID_LOG_WRITER_H
