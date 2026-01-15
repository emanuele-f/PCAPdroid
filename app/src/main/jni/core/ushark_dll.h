#ifndef _USHARK_H_
#define _USHARK_H_

#include "pcapdroid.h"

typedef struct ushark ushark_t;
struct pcap_pkthdr;

bool ushark_init(pcapdroid_t *pd);
void ushark_cleanup();

ushark_t* ushark_new(int pcap_encap, const char *dfilter);
void ushark_destroy(ushark_t *sk);
void ushark_set_pref(const char *name, const char *val);

typedef struct {
    void (*on_http1_data)(uint32_t conversation_id, const unsigned char *plain_data, size_t data_len);
    void (*on_http2_request)(uint32_t conversation_id, uint32_t stream_id, const unsigned char *plain_data, size_t data_len);
    void (*on_http2_response)(uint32_t conversation_id, uint32_t stream_id, const unsigned char *plain_data, size_t data_len);
    void (*on_http2_reset)(uint32_t conversation_id, uint32_t stream_id);
} ushark_data_callbacks_t;
void ushark_set_callbacks(ushark_t *sk, const ushark_data_callbacks_t *cbs);

const char* ushark_dissect(ushark_t *sk, const unsigned char *buf, const struct pcap_pkthdr *hdr);

#endif
