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

typedef void (*ushark_tls_data_callback)(const unsigned char *plain_data, unsigned int data_len);
void ushark_dissect_tls(ushark_t *sk, const unsigned char *buf, const struct pcap_pkthdr *hdr, ushark_tls_data_callback cb);

#endif
