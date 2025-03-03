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
 * Copyright 2023-25 - Emanuele Faranda
 */

#include <linux/if_ether.h>
#include <net/if.h>
#include <pthread.h>
#include "common/utils.h"
#include "pcapdroid.h"
#include "pcap_dump.h"
#include "third_party/uthash.h"

#define MAX_PCAP_DUMP_DELAY_MS 1000
#define PCAP_BUFFER_SIZE             (512*1024)         // 512K
#define PCAP_BUFFER_ALMOST_FULL_SIZE (450*1024)         // 450K
#define KEYLOG_BUFFER_HEADROOM       (sizeof(pcapng_decr_secrets_block_t))
#define KEYLOG_BUFFER_TAILROOM       8 /* block "total_size" field + max 3 bytes of padding + 1 */

typedef struct {
    int id;
    UT_hash_handle hh;
} mapped_uid_t;

typedef struct {
    u_int ifidx;
    u_int pcapng_ifid;
    UT_hash_handle hh;
} dumped_interface_t;

struct pcap_dumper {
    pcap_dump_format_t format;
    bool dump_extensions;
    pcap_dump_callback *dump_cb;
    pcapdroid_t *pd;
    int snaplen;
    uint64_t max_dump_size;
    bool max_dump_size_reached;
    uint64_t dump_size;
    uint64_t last_dump_ms;
    mapped_uid_t *mapped_uids;
    dumped_interface_t *dumped_interfaces;
    u_int num_dumped_interfaces;

    // the crc32 implementation requires 4-bytes aligned accesses.
    // frames are padded to honor the 4-bytes alignment.
    int8_t *buffer  __attribute__((aligned (4)));
    int buffer_idx;

    int8_t *keylog_buf;
    pthread_mutex_t keylog_mutex;
    int keylog_idx;
};

/* ******************************************************* */

pcap_dumper_t* pcap_new_dumper(pcap_dump_format_t format, bool dump_extensions,
                               int snaplen, uint64_t max_dump_size,
                               pcap_dump_callback dumpcb, pcapdroid_t *pd) {
    pcap_dumper_t *dumper = pd_calloc(1, sizeof(pcap_dumper_t));
    if(!dumper) {
        log_e("calloc(pcap_dumper_t) failed with code %d/%s",
              errno, strerror(errno));
        return NULL;
    }

    dumper->buffer = pd_malloc(PCAP_BUFFER_SIZE);
    if(!dumper->buffer) {
        log_e("malloc(pcap_dumper_t buffer) failed with code %d/%s",
              errno, strerror(errno));
        pd_free(dumper);
        return NULL;
    }

    if(pthread_mutex_init(&dumper->keylog_mutex, NULL) != 0) {
        log_e("pthread_mutex_init failed");
        pd_free(dumper->buffer);
        pd_free(dumper);
        return NULL;
    }

    dumper->snaplen = snaplen;
    dumper->format = format;
    dumper->dump_extensions = dump_extensions;
    dumper->max_dump_size = max_dump_size;
    dumper->dump_cb = dumpcb;
    dumper->pd = pd;

    return dumper;
}

/* ******************************************************* */

static void export_keylog_buffer(pcap_dumper_t *dumper) {
    if(dumper->keylog_idx == 0)
        return;

    // the keylog buffer is written by another thread, so it must be synchronized
    pthread_mutex_lock(&dumper->keylog_mutex);

    int sec_len = dumper->keylog_idx;
    uint8_t padding = (~sec_len + 1) & 0x3;
    int block_size = sizeof(pcapng_decr_secrets_block_t) + sec_len + padding + 4 /* total_length */;

    // refuse to dump if we would exceed the max_dump_size. NOTE: this could be improved to only
    // export what does not exceed the dump size
    if((dumper->max_dump_size > 0) && (dumper->dump_size + block_size >= dumper->max_dump_size)) {
        log_w("max dump size would be exceeded by the keylog dump, discarding keylog");
        dumper->keylog_idx = 0;
        goto unlock;
    }

    // prepare the block header
    pcapng_decr_secrets_block_t *dsb = (pcapng_decr_secrets_block_t*) dumper->keylog_buf;
    dsb->type = 0x0000000A;
    dsb->total_length = block_size;
    dsb->secrets_type = 0x544c534b /* TLS_KEYLOG */;
    dsb->secrets_length = sec_len;

    // padding
    char *ptr = (char*)dumper->keylog_buf + KEYLOG_BUFFER_HEADROOM + sec_len;
    for(uint8_t i=0; i<padding; i++)
        *(ptr++) = 0x00;
    *(uint32_t*)ptr = dsb->total_length;

    if(dumper->dump_cb)
        dumper->dump_cb(dumper->pd, dumper->keylog_buf, block_size);

    dumper->dump_size += block_size;
    dumper->keylog_idx = 0;

unlock:
    pthread_mutex_unlock(&dumper->keylog_mutex);
}

/* ******************************************************* */

static void export_dump_buffer(pcap_dumper_t *dumper) {
    // prevents exporting more than the max dump size
    if (dumper->max_dump_size_reached)
        return;

    export_keylog_buffer(dumper);

    if(dumper->buffer_idx == 0)
        return;

    if(dumper->dump_cb)
        dumper->dump_cb(dumper->pd, dumper->buffer, dumper->buffer_idx);

    dumper->buffer_idx = 0;
    dumper->last_dump_ms = dumper->pd->now_ms;
}

/* ******************************************************* */

// Allocates the given size in the PCAP export buffer and returns a pointer to it
// Note: the buffer may be flushed on subsequent invocations, so avoid interleaving calls to this
// Can return NULL, e.g. in case the max dump size was reached
static int8_t* alloc_dump_buffer(pcap_dumper_t *dumper, int size) {
    if (dumper->max_dump_size_reached)
        return NULL;

    if((PCAP_BUFFER_SIZE - dumper->buffer_idx) <= size)
        export_dump_buffer(dumper);

    if ((PCAP_BUFFER_SIZE - dumper->buffer_idx) <= size) {
        log_f("Invalid buffer size [size=%d, idx=%d, dump_size=%d]",
              PCAP_BUFFER_SIZE, dumper->buffer_idx, size);
        return NULL;
    } else if((dumper->max_dump_size > 0) &&
              ((dumper->dump_size + size) >= dumper->max_dump_size)) {
        dumper->max_dump_size_reached = true;
        log_i("Max dump size reached, stop the dump");
        return NULL;
    }

    int8_t* ptr = &dumper->buffer[dumper->buffer_idx];
    dumper->buffer_idx += size;
    dumper->dump_size += size;

    return ptr;
}

/* ******************************************************* */

void pcap_destroy_dumper(pcap_dumper_t *dumper) {
    export_dump_buffer(dumper);

    {
        mapped_uid_t *entry, *tmp;
        HASH_ITER(hh, dumper->mapped_uids, entry, tmp) {
            pd_free(entry);
        }
    }

    {
        dumped_interface_t *entry, *tmp;
        HASH_ITER(hh, dumper->dumped_interfaces, entry, tmp) {
            pd_free(entry);
        }
    }

    pthread_mutex_destroy(&dumper->keylog_mutex);
    if(dumper->keylog_buf)
        pd_free(dumper->keylog_buf);
    pd_free(dumper->buffer);
    pd_free(dumper);
}

/* ******************************************************* */

typedef struct {
    uint16_t type;
    uint16_t length;
    uint16_t padding;
    uint16_t tot_length;
    void *data;
} pcapng_opt_t;

static pcapng_opt_t pcapng_option(uint16_t type, void *data, uint16_t length) {
    pcapng_opt_t opt;

    opt.type = type;
    opt.length = length;
    opt.data = data;
    opt.padding = (~length + 1) & 0x3;
    opt.tot_length = 4 /* type, length */ + opt.length + opt.padding;
    return opt;
}

static int write_pcapng_opt(char *buf, pcapng_opt_t *opt) {
    *(uint16_t*) (buf+0) = opt->type;
    *(uint16_t*) (buf+2) = opt->length;
    buf += 4;

    memcpy(buf, opt->data, opt->length);
    buf += opt->length;

    for(uint8_t i=0; i < opt->padding; i++)
        *(buf++) = 0x00;

    return opt->tot_length;
}

/* ******************************************************* */

static int get_pcap_file_header(pcap_dumper_t *dumper, char **out) {
    struct pcap_hdr *pcap_hdr = pd_malloc(sizeof(struct pcap_hdr));
    if(!pcap_hdr)
        return -1;

    pcap_hdr->magic_number = 0xa1b2c3d4;
    pcap_hdr->version_major = 2;
    pcap_hdr->version_minor = 4;
    pcap_hdr->thiszone = 0;
    pcap_hdr->sigfigs = 0;
    pcap_hdr->snaplen = dumper->snaplen;
    pcap_hdr->network = dumper->dump_extensions ? LINKTYPE_ETHERNET : LINKTYPE_RAW;

    *out = (char*)pcap_hdr;
    return sizeof(struct pcap_hdr);
}

static int get_pcapng_preamble(pcap_dumper_t *dumper, char **out) {
    pcapng_opt_t shb_hw = pcapng_option(0x2, pd_device, strlen(pd_device));
    pcapng_opt_t shb_os = pcapng_option(0x3, pd_os, strlen(pd_os));
    pcapng_opt_t shb_app = pcapng_option(0x4, pd_appver, strlen(pd_appver));

    int shb_length = sizeof(pcapng_section_hdr_block_t) + shb_hw.tot_length +
            shb_os.tot_length + shb_app.tot_length + 4 /* total_length */;
    int idb_length = sizeof(pcapng_intf_descr_block_t) + 4 /* total_length */;
    int preamble_sz = shb_length + idb_length;
    char *preamble = (char*) pd_malloc(preamble_sz);
    if(!preamble)
        return -1;

    // Section Header Block
    pcapng_section_hdr_block_t *shb = (pcapng_section_hdr_block_t*) preamble;
    shb->type = 0x0A0D0D0A;
    shb->total_length = shb_length;
    shb->magic = 0x1a2b3c4d;
    shb->version_major = 1;
    shb->version_minor = 0;
    shb->section_length = -1;

    char *ptr = preamble + sizeof(pcapng_section_hdr_block_t);
    ptr += write_pcapng_opt(ptr, &shb_hw);
    ptr += write_pcapng_opt(ptr, &shb_os);
    ptr += write_pcapng_opt(ptr, &shb_app);
    *(uint32_t*)ptr = shb->total_length;
    ptr += 4;

    // Interface Description Block
    pcapng_intf_descr_block_t *idb = (pcapng_intf_descr_block_t*) ptr;
    idb->type = 0x00000001;
    idb->total_length = sizeof(pcapng_intf_descr_block_t) + 4;
    idb->reserved = 0;
    idb->linktype = LINKTYPE_RAW;
    idb->snaplen = dumper->snaplen;
    *(uint32_t*)(idb+1) = idb->total_length;

    *out = preamble;
    return preamble_sz;
}

/* Get a buffer (out) containing the PCAP header or the PCAPNG preamble (Section Header Block and
 * Interface Description Block).
 * Returns the buffer size on success, -1 on error. The out buffer must be free by the called with pd_free. */
int pcap_get_preamble(pcap_dumper_t *dumper, char **out) {
    if(dumper->format == PCAPNG_DUMP)
        return get_pcapng_preamble(dumper, out);
    else
        return get_pcap_file_header(dumper, out);
}

/* ******************************************************* */

// returns true if the buffer was exported
bool pcap_check_export(pcap_dumper_t *dumper) {
    if (dumper->max_dump_size_reached)
        return false;

    if(((dumper->buffer_idx > 0) && (dumper->pd->now_ms - dumper->last_dump_ms) >= MAX_PCAP_DUMP_DELAY_MS) ||
            (dumper->keylog_idx > PCAP_BUFFER_ALMOST_FULL_SIZE)) {
        export_dump_buffer(dumper);
        return true;
    }
    return false;
}

/* ******************************************************* */

uint64_t pcap_get_dump_size(pcap_dumper_t *dumper) {
    return dumper->dump_size;
}

/* ******************************************************* */

static bool dump_packet_pcap(pcap_dumper_t *dumper, const char *pkt, int pktlen,
                                    const struct timeval *tv, int uid) {
    int incl_len = min(pktlen, dumper->snaplen);
    int pre_trailer_padding = 0;
    bool with_trailer = false;
    int trailer_overhead = 0;

    if(dumper->dump_extensions) {
        // Pad the frame so that the buffer keeps its 4-bytes alignment
        pre_trailer_padding = (~(sizeof(struct ethhdr) + incl_len) + 1) & 0x3;
        trailer_overhead = (int)(sizeof(struct ethhdr) + pre_trailer_padding + sizeof(pcapdroid_trailer_t));

        if ((incl_len + trailer_overhead) > dumper->snaplen) {
            static bool warning_shown = false;
            if (!warning_shown) {
                log_w("PCAPdroid trailer would exceed snaplen, skipping. Consider increasing the snaplen");
                warning_shown = true;
            }
            pre_trailer_padding = 0;
            trailer_overhead = 0;
        } else {
            incl_len += trailer_overhead;
            with_trailer = true;
        }
    }

    int8_t *buffer = alloc_dump_buffer(dumper, sizeof(pcap_rec_t) + incl_len);
    if (!buffer)
        return false;

    pcap_rec_t *pcap_rec = (pcap_rec_t*) buffer;
    int offset = 0;

    pcap_rec->ts_sec = tv->tv_sec;
    pcap_rec->ts_usec = tv->tv_usec;
    pcap_rec->incl_len = incl_len;
    pcap_rec->orig_len = pktlen + trailer_overhead;
    buffer += sizeof(struct pcap_rec);

    if(with_trailer) {
        if((((uint64_t)buffer) & 0x3) != 0) {
            static bool warning_shown = false;
            if (!warning_shown) {
                log_w("Unaligned buffer!");
                warning_shown = true;
            }
        }

        // Insert the bogus header: both the MAC addresses are 0
        struct ethhdr *eth = (struct ethhdr*) buffer;
        memset(eth, 0, sizeof(struct ethhdr));
        eth->h_proto = htons((((*pkt) >> 4) == 4) ? ETH_P_IP : ETH_P_IPV6);

        offset += sizeof(struct ethhdr);
    }

    int payload_to_copy = min(pktlen, pcap_rec->incl_len - offset);
    memcpy(buffer + offset, pkt, payload_to_copy);
    offset += payload_to_copy;

    if(with_trailer) {
        // Pad the frame so that the buffer keeps its 4-bytes alignment
        // The padding is inserted before the PCAPdroid trailer so that accesses to pcapdroid_trailer_t
        // are also aligned.
        for(uint8_t i=0; i<pre_trailer_padding; i++)
            buffer[offset++] = 0x00;

        // Populate the trailer
        pcapdroid_trailer_t *trailer = (pcapdroid_trailer_t*)(buffer + offset);
        memset(trailer, 0, sizeof(*trailer));

        trailer->magic = htonl(PCAPDROID_TRAILER_MAGIC);
        trailer->uid = htonl(uid);
        get_appname_by_uid(dumper->pd, uid, trailer->appname, sizeof(trailer->appname));

        //clock_t start = clock();
        trailer->fcs = crc32((u_char*) buffer, pcap_rec->incl_len - 4, 0);
        //double cpu_time_used = ((double) (clock() - start)) / CLOCKS_PER_SEC;
        //log_d("crc cpu_time_used: %f sec", cpu_time_used);
    }

    return true;
}

static void make_custom_block(pcapng_pd_custom_block_t *block, uint8_t block_type, int total_length) {
    // https://datatracker.ietf.org/doc/draft-ietf-opsawg-pcapng - 4.8 Custom Block
    block->block_type = 0x00000bad;
    block->total_length = total_length;
    block->pen = PCAPDROID_PEN;

    block->version = PCAPDROID_PCAPNG_VERSION;
    block->type = block_type;
}

static bool dump_pcapng_uid_mapping(pcap_dumper_t *dumper, int uid) {
    char package_name[64];
    char app_name[64];

#ifdef ANDROID
    getPackageNameByUid(dumper->pd, uid, package_name, sizeof(package_name));
    getApplicationByUid(dumper->pd, uid, app_name, sizeof(app_name));
#else
    return true;
#endif

    int package_name_len = strlen(package_name);
    int app_name_len = strlen(app_name);

    int total_length = sizeof(pcapng_pd_uid_map_block_t) + package_name_len + app_name_len + 4 /* total length */;
    uint8_t padding = (~total_length + 1) & 0x3 /* padding */;
    total_length += padding;

    int8_t *buffer = alloc_dump_buffer(dumper, total_length);
    if (!buffer)
        return false;

    pcapng_pd_uid_map_block_t* block = (pcapng_pd_uid_map_block_t*) buffer;
    make_custom_block(&block->hdr, PCAPDROID_BLOCK_UID_MAP, total_length);
    block->uid = uid;
    block->package_name_len = package_name_len;
    block->app_name_len = app_name_len;

    buffer += sizeof(pcapng_pd_uid_map_block_t);
    memcpy(buffer, package_name, block->package_name_len);
    buffer += block->package_name_len;
    memcpy(buffer, app_name, block->app_name_len);
    buffer += block->app_name_len;

    for(uint8_t i=0; i<padding; i++)
        *(buffer++) = 0x00;

    *(uint32_t*)buffer = block->hdr.total_length;
    return true;
}

static bool dump_pcapng_interface(pcap_dumper_t *dumper, u_int ifidx) {
    int total_length = sizeof(pcapng_intf_descr_block_t) + 4 /* total length */;

    // try to get the interface name
    char ifname[IFNAMSIZ];
    uint8_t ifname_padding = 0;
    if (!if_indextoname(ifidx, ifname))
        ifname[0] = '\0';

    if (ifname[0]) {
        total_length += sizeof(pcapng_enh_option_t) + strlen(ifname);
        ifname_padding = (~total_length + 1) & 0x3;
        total_length += ifname_padding;
    }

    int8_t *buffer = alloc_dump_buffer(dumper, total_length);
    if (!buffer)
        return false;

    pcapng_intf_descr_block_t *idb = (pcapng_intf_descr_block_t*) buffer;
    idb->type = 0x00000001;
    idb->total_length = total_length;
    idb->reserved = 0;
    idb->linktype = LINKTYPE_RAW /* even with root, we always dump IP packets */;
    idb->snaplen = dumper->snaplen;
    buffer += sizeof(*idb);

    if (ifname[0]) {
        pcapng_enh_option_t *opt = (pcapng_enh_option_t*) buffer;
        opt->code = 2; // if_name
        opt->length = strlen(ifname);
        buffer += sizeof(*opt);

        memcpy(buffer, ifname, opt->length);
        buffer += opt->length;

        for(uint8_t i=0; i<ifname_padding; i++)
            *(buffer++) = 0x00;
    }

    *buffer = idb->total_length;
    return true;
}

static bool dump_packet_pcapng(pcap_dumper_t *dumper, const char *pkt, int pktlen,
                                      const struct timeval *tv, int uid, u_int ifidx) {
    u_int pcapng_ifid = 0;

    if(ifidx > 0) {
        dumped_interface_t *item;
        HASH_FIND_INT(dumper->dumped_interfaces, &ifidx, item);

        if (!item) {
            if (dump_pcapng_interface(dumper, ifidx)) {
                item = pd_calloc(sizeof(dumped_interface_t), 1);
                item->ifidx = ifidx;
                item->pcapng_ifid = pcapng_ifid = ++dumper->num_dumped_interfaces;
                HASH_ADD_INT(dumper->dumped_interfaces, ifidx, item);
            }
        } else
            pcapng_ifid = item->pcapng_ifid;
    }

    if(dumper->dump_extensions) {
        mapped_uid_t *item;
        HASH_FIND_INT(dumper->mapped_uids, &uid, item);

        if (!item) {
            if (!dump_pcapng_uid_mapping(dumper, uid))
                return false;

            item = pd_calloc(sizeof(mapped_uid_t), 1);
            item->id = uid;
            HASH_ADD_INT(dumper->mapped_uids, id, item);
        }
    }

    int incl_len = min(pktlen, dumper->snaplen);
    int epb_size = sizeof(pcapng_enh_packet_block_t) + 4 /* total length */;
    uint8_t padding = (~incl_len + 1) & 0x3; // packet data must be padded to 32 bits
    int total_length = epb_size + incl_len + padding;

    char comment[32];
    int comment_len = 0;
    uint8_t comment_padding = 0;
    bool has_comment = false;

    if(dumper->dump_extensions) {
        comment_len = snprintf(comment, sizeof(comment), "u-%d", uid);
        comment_padding = (~comment_len + 1) & 0x3;
        total_length += sizeof(pcapng_enh_option_t) + comment_len + comment_padding;
        has_comment = true;
    }

    uint64_t now_usec = (uint64_t)tv->tv_sec * 1000000 + tv->tv_usec;
    int8_t *buffer = alloc_dump_buffer(dumper, total_length);

    pcapng_enh_packet_block_t *epb = (pcapng_enh_packet_block_t*) buffer;
    epb->type = 0x00000006;
    epb->total_length = total_length;
    epb->interface_id = pcapng_ifid;
    epb->timestamp_high = now_usec >> 32;
    epb->timestamp_low = now_usec;
    epb->captured_len = incl_len;
    epb->original_len = pktlen;

    memcpy(buffer + sizeof(pcapng_enh_packet_block_t), pkt, epb->captured_len);
    buffer += sizeof(pcapng_enh_packet_block_t) + epb->captured_len;

    // packet data must be padded to 32 bits
    for(uint8_t i=0; i<padding; i++)
        *(buffer++) = 0x00;

    if (has_comment) {
        // specify the UID as a comment
        // this is necessary until custom options are supported by Wireshark
        // https://gitlab.com/wireshark/wireshark/-/issues/18614
        pcapng_enh_option_t *option = (pcapng_enh_option_t *) buffer;
        option->code = 0x0001;
        option->length = comment_len;
        buffer += sizeof(pcapng_enh_option_t);

        memcpy(buffer, comment, comment_len);
        buffer += comment_len;

        for(uint8_t i=0; i<comment_padding; i++)
            *(buffer++) = 0x00;
    }

    *(uint32_t*)buffer = epb->total_length;
    return true;
}

/* Dump a single packet into the buffer. Returns false if PCAP dump must be stopped (e.g. if max
 * dump size reached or an error occurred). */
bool pcap_dump_packet(pcap_dumper_t *dumper, const char *pkt, int pktlen,
                      const struct timeval *tv, int uid, u_int ifidx) {
    bool rv;
    if (dumper->format == PCAPNG_DUMP)
        rv = dump_packet_pcapng(dumper, pkt, pktlen, tv, uid, ifidx);
    else
        rv = dump_packet_pcap(dumper, pkt, pktlen, tv, uid);

    if (!rv)
        return false;

    pcap_check_export(dumper);
    return true;
}

/* ******************************************************* */

// Master secrets are received by the MitmReceiver thread and stored into the keylog_buf, which is
// later exporter in export_buffer by the capture thread.
// This allows to dump the secrets before the packets, and also to avoid locks in the data path.
//
// NOTE: there is still a small chance to dump the packets before the corresponding master secrets,
// which would result in the inability to decrypt such packets in some tools (e.g. Wireshark)
bool pcap_dump_secret(pcap_dumper_t *dumper, int8_t *sec_data, int sec_len) {
    if(!dumper->keylog_buf) {
        // [ DSB | KEYLOG[PCAP_BUFFER_SIZE] | PADDING[0-3] | total_length ]
        dumper->keylog_buf = pd_malloc(KEYLOG_BUFFER_HEADROOM + PCAP_BUFFER_SIZE + KEYLOG_BUFFER_TAILROOM);
        if(!dumper->keylog_buf) {
            log_e("malloc(keylog_buf) failed with code %d/%s",
                  errno, strerror(errno));
            return false;
        }
    }

    if((dumper->keylog_idx + (sec_len + 1 /* will add a \n */)) >= PCAP_BUFFER_SIZE) {
        log_w("the keylog is not being exported, discarding secret");
        return false;
    }

    pthread_mutex_lock(&dumper->keylog_mutex);

    memcpy(dumper->keylog_buf + KEYLOG_BUFFER_HEADROOM + dumper->keylog_idx, sec_data, sec_len);
    dumper->keylog_idx += sec_len;
    dumper->keylog_buf[KEYLOG_BUFFER_HEADROOM + dumper->keylog_idx++] = '\n';

    pthread_mutex_unlock(&dumper->keylog_mutex);
    return true;
}