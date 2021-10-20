//
// Created by emanuele on 10/20/21.
//

#ifndef PCAPDROID_BLACKLIST_H
#define PCAPDROID_BLACKLIST_H

#include "ndpi_api.h"

#define PCAPDROID_NDPI_CATEGORY_MALWARE NDPI_PROTOCOL_CATEGORY_CUSTOM_1

typedef struct blacklist blacklist_t;

blacklist_t* blacklist_init(struct ndpi_detection_module_struct *ndpi);
void blacklist_destroy(blacklist_t *bl);
void blacklist_clear(blacklist_t *bl);
int blacklist_load_file(blacklist_t *bl, const char *path);
bool blacklist_match_ip(blacklist_t *bl, uint32_t ip);
bool blacklist_match_domain(blacklist_t *bl, const char *domain);

#endif //PCAPDROID_BLACKLIST_H
