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
 * Copyright 2021-25 - Emanuele Faranda
 */

#include <stdio.h>
#include <errno.h>
#include <assert.h>
#include <net/if.h>

#include "pcap_reader.h"
#include "errors.h"
#include "common/memtrack.h"
#include "common/utils.h"
#include "third_party/uthash.h"

typedef struct {
    char name[IFNAMSIZ];
    int dlt;
    int ifidx;
} pcapng_intf_t;

typedef struct {
    int uid;
    UT_hash_handle hh;
} mapped_uid_t;

struct pd_reader {
    FILE* fp;
    pcap_dump_format_t dump_format;
    bool has_error;
    bool has_seen_dump_extensions;
    bool has_unsupported_dlt_packets;
    int dlt;
    pcapng_intf_t *interfaces;
    int num_interfaces;
    u_char *buffer;
    size_t buffer_size;
    mapped_uid_t *mapped_uids;
    long cur_block_pos;
    size_t cur_block_size;
};

static int linktype_to_dlt(int linktype) {
    // this should support all the DLTs of get_ip_offset
    switch (linktype) {
        case LINKTYPE_RAW:
            // NOTE: different from linktype
            return PCAPD_DLT_RAW;
        case LINKTYPE_ETHERNET:
        case LINKTYPE_LINUX_SLL:
        case LINKTYPE_LINUX_SLL2:
            return linktype;
        default:
            return 0;
    }
}

pd_reader_t* pd_new_reader(const char *fpath, char **error) {
    *error = NULL;

    pd_reader_t *reader = pd_calloc(1, sizeof(pd_reader_t));
    if(!reader) {
        log_e("calloc(pd_reader_t) failed with code %d/%s",
              errno, strerror(errno));
        goto fail;
    }

    reader->fp = fopen(fpath, "rb");
    if (!reader->fp) {
        log_e("Open capture %s failed[%d]: %s", fpath, errno, strerror(errno));
        if (errno == ENOENT)
            *error = PD_ERR_PCAP_DOES_NOT_EXIST;
        goto fail;
    }

    // determine the format
    pcap_hdr_t pcap_hdr;
    if (!fread(&pcap_hdr, sizeof(pcap_hdr), 1, reader->fp)) {
        log_e("Error reading the capture header[%d]: %s", errno, strerror(errno));
        *error = PD_ERR_INVALID_PCAP_FILE;
        goto fail;
    }

    if (pcap_hdr.magic_number == 0xa1b2c3d4) {
        // PCAP file
        reader->dlt = linktype_to_dlt(pcap_hdr.network);

        if ((pcap_hdr.version_major != 2) || (reader->dlt == 0)) {
            log_e("Unsupported PCAP file: version=%u.%u, linktype=%u",
                  pcap_hdr.version_major, pcap_hdr.version_minor, pcap_hdr.network);
            *error = PD_ERR_UNSUPPORTED_PCAP_FILE;
            goto fail;
        }

        reader->dump_format = PCAP_DUMP;
    } else {
        // this precondition allows for simpler logic
        assert(sizeof(pcap_hdr_t) >= sizeof(pcapng_section_hdr_block_t));
        if (!(sizeof(pcap_hdr_t) >= sizeof(pcapng_section_hdr_block_t))) {
            *error = PD_ERR_INVALID_PCAP_FILE;
            goto fail;
        }

        pcapng_section_hdr_block_t* pcapng_hdr = (pcapng_section_hdr_block_t*) &pcap_hdr;
        if ((pcapng_hdr->type != 0x0A0D0D0A) ||
            (pcapng_hdr->magic != 0x1a2b3c4d) &&
            (pcapng_hdr->magic != 0x4d32b1a)
        ) {
            log_e("Not a PCAP/Pcapng file");
            *error = PD_ERR_INVALID_PCAP_FILE;
            goto fail;
        }

        // Pcapng file
        if ((pcapng_hdr->magic != 0x1a2b3c4d) ||
            (pcapng_hdr->version_major != 1) ||
            (pcapng_hdr->section_length != -1)
        ) {
            log_e("Unsupported Pcapng file: version=%u.%u, magic=%u",
                  pcapng_hdr->version_major, pcapng_hdr->version_minor, pcapng_hdr->magic);
            *error = PD_ERR_UNSUPPORTED_PCAP_FILE;
            goto fail;
        }

        reader->cur_block_pos = 0;
        reader->cur_block_size = pcapng_hdr->total_length;
        reader->dump_format = PCAPNG_DUMP;
    }

    // success
    return reader;

fail:
    pd_destroy_reader(reader);
    return NULL;
}

void pd_destroy_reader(pd_reader_t *reader) {
    if (!reader)
        return;

    mapped_uid_t *entry, *tmp;
    HASH_ITER(hh, reader->mapped_uids, entry, tmp) {
        pd_free(entry);
    }

    if (reader->buffer)
        pd_free(reader->buffer);

    if (reader->interfaces)
        pd_free(reader->interfaces);

    if (reader->fp)
        fclose(reader->fp);

    pd_free(reader);
}

pcap_dump_format_t pd_get_dump_format(pd_reader_t *reader) {
    return reader->dump_format;
}

bool pd_has_seen_dump_extensions(pd_reader_t *reader) {
    return reader->has_seen_dump_extensions;
}

bool pd_has_unsupported_dlt_packets(pd_reader_t *reader) {
    return reader->has_unsupported_dlt_packets;
}

static bool reserve_buffer(pd_reader_t *reader, size_t size) {
    if (reader->buffer_size < size) {
        reader->buffer = pd_realloc(reader->buffer, size);

        if (!reader->buffer) {
            log_e("Cannot allocate Pcapng buffer of size %u", size);
            reader->has_error = true;
            return false;
        }

        reader->buffer_size = size;
    }

    return true;
}

static reader_rv pd_pcap_read_next(pd_reader_t *reader, pcapd_hdr_t *hdr, char* buffer, pd_read_callbacks_t *cb, void *userdata) {
    pcap_rec_t rec;

    if (fread(&rec, sizeof(rec), 1, reader->fp) != 1) {
        if (ferror(reader->fp)) {
            log_e("Error reading the next PCAP record [%d]: %s", errno, strerror(errno));
            reader->has_error = true;
            return READER_ERROR;
        } else
            return READER_EOF;
    }

    if (rec.incl_len > rec.orig_len) {
        log_e("PCAP record bad length: included=%u, orig=%u", rec.incl_len, rec.orig_len);
        errno = EINVAL;
        reader->has_error = true;
        return READER_ERROR;
    }

    int size = min(rec.incl_len, PCAPD_SNAPLEN);

    if (fread(buffer, size, 1, reader->fp) != 1) {
        if (ferror(reader->fp)) {
            log_e("Error reading the PCAP packed data[%d]: %s", errno, strerror(errno));
            reader->has_error = true;
            return READER_ERROR;
        } else {
            log_w("Capture stopped in the middle of a packet");
            return READER_EOF;
        }
    }

    // successfully read packet data
    memset(hdr, 0, sizeof(*hdr));
    hdr->ts.tv_sec = rec.ts_sec;
    hdr->ts.tv_usec = rec.ts_usec;
    hdr->len = size;
    hdr->uid = -1;
    hdr->linktype = reader->dlt;
    hdr->flags = PCAPD_FLAG_TX;

    // possibly retrieve the UID from the PCAPdroid trailer
    if ((reader->dlt == PCAPD_DLT_ETHERNET) && (size > (14 + sizeof(pcapdroid_trailer_t)))) {
        const struct pcapdroid_trailer* trailer =
                (const struct pcapdroid_trailer*) (buffer + size - sizeof(pcapdroid_trailer_t));

        if(ntohl(trailer->magic) == PCAPDROID_TRAILER_MAGIC) {
            hdr->uid = ntohl(trailer->uid);

            if (!reader->has_seen_dump_extensions) {
                reader->has_seen_dump_extensions = true;

                if (cb->on_dump_extensions_seen)
                    cb->on_dump_extensions_seen(userdata);
            }
        }
    }

    return READER_PACKET_OK;
}

static reader_rv read_enhanced_packet_block(pd_reader_t *reader, pcapd_hdr_t *hdr, char* buffer, pd_read_callbacks_t *cb, void *userdata) {
    pcapng_enh_packet_block_t enh;

    if (fread(&enh, sizeof(enh), 1, reader->fp) != 1) {
        log_e("Error reading the EPB block[%u]: %s", errno, strerror(errno));
        reader->has_error = true;
        return READER_ERROR;
    }

    if ((enh.total_length < sizeof(pcapng_enh_packet_block_t)) ||
        (enh.captured_len > enh.original_len) ||
        (enh.captured_len > (enh.total_length - sizeof(pcapng_enh_packet_block_t)))
    ) {
        log_e("Packet EPB block bad length: total=%u, captured=%u, orig=%u",
              enh.total_length, enh.captured_len, enh.original_len);
        errno = EINVAL;
        reader->has_error = true;
        return READER_ERROR;
    }

    // get capture interface
    uint32_t ifid = enh.interface_id;
    if (ifid >= reader->num_interfaces) {
        log_e("Unknown Pcapng interface: %u", ifid);
        reader->has_error = true;
        return READER_ERROR;
    }

    pcapng_intf_t* intf = &reader->interfaces[ifid];
    if (intf->dlt == 0) {
        // ignore packet from interface with unsupported linktype
        reader->has_unsupported_dlt_packets = true;
        return READER_CONTINUE;
    }

    int size = min(enh.captured_len, PCAPD_SNAPLEN);

    if (fread(buffer, size, 1, reader->fp) != 1) {
        if (ferror(reader->fp)) {
            log_e("Error reading the PCAP packed data[%d]: %s", errno, strerror(errno));
            reader->has_error = true;
            return READER_ERROR;
        } else {
            log_w("Capture stopped in the middle of a packet");
            return READER_EOF;
        }
    }

    // successfully read packet data
    uint64_t usec = ((uint64_t) enh.timestamp_high << 32) | enh.timestamp_low;

    memset(hdr, 0, sizeof(*hdr));
    hdr->ts.tv_sec = usec / 1000000;
    hdr->ts.tv_usec = usec % 1000000;
    hdr->len = size;
    hdr->uid = -1;
    hdr->linktype = intf->dlt;
    hdr->flags = PCAPD_FLAG_TX;
    hdr->ifid = intf->ifidx;

    // Possibly parse the UID
    uint8_t packet_padding = (~enh.captured_len + 1) & 0x3;
    int opts_offset = sizeof(enh) + enh.captured_len + packet_padding;
    int max_read = enh.total_length - opts_offset - 4 /* total size */;

    if (fseek(reader->fp, reader->cur_block_pos + opts_offset, SEEK_SET) == 0) {
        pcapng_enh_option_t opt;

        while ((max_read >= sizeof(opt)) &&
               fread(&opt, sizeof(opt), 1, reader->fp) == 1
        ) {
            if ((opt.code == 1) && (opt.length > 0)) { // comment
                char comment[16];

                size_t read_size = min(opt.length, sizeof(comment) - 1);
                if (fread(comment, read_size, 1, reader->fp) == 1) {
                    int parsed_length;
                    unsigned int uid;
                    comment[read_size] = '\0';

                    if ((sscanf(comment, "u-%u%n", &uid, &parsed_length) == 1) &&
                        (comment[parsed_length] == '\0')
                    ) {
                        // a previous UID mapping must be present
                        mapped_uid_t *item;
                        HASH_FIND_INT(reader->mapped_uids, &uid, item);

                        if (item) {
                            hdr->uid = uid;

                            if (!reader->has_seen_dump_extensions) {
                                reader->has_seen_dump_extensions = true;

                                if (cb->on_dump_extensions_seen)
                                    cb->on_dump_extensions_seen(userdata);
                            }
                        } else
                            log_w("Ignore UID without mapping: %u", uid);

                        break;
                    }
                }
            }

            int length_padded = opt.length + (uint8_t)((~opt.length + 1) & 0x3);
            max_read -= length_padded + sizeof(opt);
            fseek(reader->fp, length_padded, SEEK_CUR);
        }
    }

    return READER_PACKET_OK;
}

static reader_rv read_interface_description_block(pd_reader_t *reader) {
    pcapng_intf_descr_block_t idb;

    if (fread(&idb, sizeof(idb), 1, reader->fp) != 1) {
        log_e("Error reading the IDB block[%u]: %s", errno, strerror(errno));
        reader->has_error = true;
        return READER_ERROR;
    }

    int idx = reader->num_interfaces++;
    reader->interfaces = pd_realloc(reader->interfaces, reader->num_interfaces * sizeof(pcapng_intf_t));
    if (!reader->interfaces) {
        log_e("Allocating Pcapng interface failed");
        reader->has_error = true;
        return READER_ERROR;
    }

    pcapng_intf_t* intf = &reader->interfaces[idx];
    memset(intf, 0, sizeof(*intf));

    intf->dlt = linktype_to_dlt(idb.linktype);
    if (intf->dlt == 0)
        log_w("Pcapng interface #%d has unsupported linktype %d. Its packets will be ignored", idx, idb.linktype);

    // check if interface name is specified
    int max_read = idb.total_length - sizeof(idb) - 4 /* total size */;
    pcapng_enh_option_t opt;

    while ((max_read >= sizeof(opt)) &&
        fread(&opt, sizeof(opt), 1, reader->fp) == 1
    ) {
        if (opt.code == 2) { // if_name
            size_t read_size = min(opt.length, sizeof(intf->name) - 1);
            fread(intf->name, read_size, 1, reader->fp);
            intf->name[read_size] = '\0';
            break;
        }

        int length_padded = opt.length + (uint8_t)((~opt.length + 1) & 0x3);
        max_read -= length_padded + sizeof(opt);
        fseek(reader->fp, length_padded, SEEK_CUR);
    }

    if (intf->name[0] != '\0')
        // try to get the ifidx as well
        intf->ifidx = if_nametoindex(intf->name);
    else
        // fallback
        snprintf(intf->name, sizeof(intf->name), "if_%d", idx);

    log_d("Pcapng interface #%d: name=%s, linktype=%u\n", idx, intf->name, idb.linktype);
    return READER_CONTINUE;
}

static reader_rv read_uid_map_block(pd_reader_t *reader, pd_read_callbacks_t *cb, void *userdata) {
    pcapng_pd_uid_map_block_t umb;

    if (fread(&umb, sizeof(umb), 1, reader->fp) != 1) {
        log_e("Error reading UID mapping block[%u]: %s", errno, strerror(errno));
        reader->has_error = true;
        return READER_ERROR;
    }

    mapped_uid_t *item;
    HASH_FIND_INT(reader->mapped_uids, &umb.uid, item);
    if (item) {
        log_w("Ignoring already mapped UID: %u", umb.uid);
        return READER_CONTINUE;
    }

    item = pd_calloc(sizeof(mapped_uid_t), 1);
    item->uid = umb.uid;
    HASH_ADD_INT(reader->mapped_uids, uid, item);

    size_t needed_size = umb.package_name_len + umb.app_name_len + 2 /* NULL terminators */;
    if (!reserve_buffer(reader, needed_size))
        return READER_ERROR;

    char *package_name = (char *) reader->buffer;
    char *app_name = (char *) reader->buffer + umb.package_name_len + 1;

    if (fread(package_name, umb.package_name_len, 1, reader->fp) != 1) {
        log_e("Error reading package name[%u]: %s", errno, strerror(errno));
        reader->has_error = true;
        return READER_ERROR;
    }

    if (fread(app_name, umb.app_name_len, 1, reader->fp) != 1) {
        log_e("Error reading app name[%u]: %s", errno, strerror(errno));
        reader->has_error = true;
        return READER_ERROR;
    }

    package_name[umb.package_name_len] = '\0';
    app_name[umb.app_name_len] = '\0';

    if (cb->on_uid_mapping)
        cb->on_uid_mapping(userdata, umb.uid, package_name, app_name);

    return READER_CONTINUE;
}

static reader_rv read_dsb_block(pd_reader_t *reader, pd_read_callbacks_t *cb, void *userdata) {
    pcapng_decr_secrets_block_t dsb;

    if (fread(&dsb, sizeof(dsb), 1, reader->fp) != 1) {
        log_e("Error reading DSB block[%u]: %s", errno, strerror(errno));
        reader->has_error = true;
        return READER_ERROR;
    }

    if ((dsb.total_length < sizeof(pcapng_decr_secrets_block_t)) ||
        (dsb.secrets_length > (dsb.total_length - sizeof(pcapng_decr_secrets_block_t)))
    ) {
        log_e("DSB bad length: total=%u, secrets_length=%u",
              dsb.total_length, dsb.secrets_length);
        errno = EINVAL;
        reader->has_error = true;
        return READER_ERROR;
    }

    if ((dsb.secrets_type == 0x544c534b /* TLS_KEYLOG */) &&
        (dsb.secrets_length > 0) &&
        (cb->on_dsb_secrets != NULL)
    ) {
        uint32_t secrets_len = dsb.secrets_length;

        if (secrets_len <= MAX_DSB_SECRETS_LENGTH) {
            if (!reserve_buffer(reader, secrets_len))
                return READER_ERROR;

            if (fread(reader->buffer, secrets_len, 1, reader->fp) != 1) {
                log_e("Error reading the DSB secrets");
                return READER_ERROR;
            }

            cb->on_dsb_secrets(userdata, (u_char*) reader->buffer, secrets_len);
        } else
            log_w("Invalid secrets length (%u), ignored", secrets_len);
    }

    return READER_CONTINUE;
}

static reader_rv read_pd_custom_block(pd_reader_t *reader, pd_read_callbacks_t *cb, void *userdata) {
    pcapng_pd_custom_block_t block;

    if (fread(&block, sizeof(block), 1, reader->fp) != 1) {
        log_e("Error reading custom block[%u]: %s", errno, strerror(errno));
        reader->has_error = true;
        return READER_ERROR;
    }

    if ((block.pen != PCAPDROID_PEN) || (block.version > PCAPDROID_PCAPNG_VERSION))
        // ignore unsupported
        return READER_CONTINUE;

    uint8_t block_type = block.type;

    if (fseek(reader->fp, reader->cur_block_pos, SEEK_SET))
        return READER_CONTINUE;

    if (block_type == PCAPDROID_BLOCK_UID_MAP)
        return read_uid_map_block(reader, cb, userdata);
    else
        log_w("Unsupported PCAPdroid extension block: type=%u, length=%u",
              block_type, block.total_length);

    return READER_CONTINUE;
}

static reader_rv pd_pcapng_read_next(pd_reader_t *reader, pcapd_hdr_t *hdr, char* buffer, pd_read_callbacks_t *cb, void *userdata) {
    while (!ferror(reader->fp)) {
        // position to the start of the next block
        long offset = reader->cur_block_pos + reader->cur_block_size;
        if (fseek(reader->fp, offset, SEEK_SET)) {
            log_e("Seek to %u failed[%d]: %s", offset, errno, strerror(errno));
            reader->has_error = true;
            return READER_ERROR;
        }

        reader->cur_block_pos += reader->cur_block_size;

        pcapng_generic_block_t block;
        if (fread(&block, sizeof(block), 1, reader->fp) != 1) {
            if (ferror(reader->fp)) {
                log_e("Error reading the next Pcapng block");
                reader->has_error = true;
                return READER_ERROR;
            } else
                return READER_EOF;
        }

        if (block.total_length < (sizeof(block) + 4 /* total length */)) {
            log_e("Invalid Block length: %u", block.total_length);
            errno = EINVAL;
            reader->has_error = true;
            return READER_ERROR;
        }

        //log_d("Block: %08x - %u B", block.type, block.total_length);
        uint32_t block_length = block.total_length;
        reader->cur_block_size = block_length;

        // seek back to the start of the block
        if (fseek(reader->fp, offset, SEEK_SET))
            continue;

        reader_rv rv;

        if (block.type == 0x00000006)
            rv = read_enhanced_packet_block(reader, hdr, buffer, cb, userdata);
        else if (block.type == 0x00000001)
            rv = read_interface_description_block(reader);
        else if (block.type == 0x0000000a)
            rv = read_dsb_block(reader, cb, userdata);
        else if (block.type == 0x00000bad)
            rv = read_pd_custom_block(reader, cb, userdata);
        else
            rv = READER_CONTINUE;

        if (rv != READER_CONTINUE)
            return rv;
    }

    reader->has_error = true;
    return READER_ERROR;
}

reader_rv pd_read_next(pd_reader_t *reader, pcapd_hdr_t *hdr, char* buffer, pd_read_callbacks_t *cb, void *userdata) {
    if (feof(reader->fp))
        return READER_EOF;

    if (reader->has_error || ferror(reader->fp))
        return READER_ERROR;

    if (reader->dump_format == PCAPNG_DUMP)
        return pd_pcapng_read_next(reader, hdr, buffer, cb, userdata);
    else
        return pd_pcap_read_next(reader, hdr, buffer, cb, userdata);
}

static void dump_dsb_secrets(void *userdata, const char *secrets, size_t length) {
    FILE *fout = (FILE*) userdata;

    if (fwrite(secrets, length, 1, fout) != 1)
        log_e("Error writing the KEYLOG file[%d]: %s", errno, strerror(errno));
}

bool pcapng_to_keylog(const char *pcapng_path, const char *out_path) {
    char* error = NULL;
    pd_reader_t* reader = pd_new_reader(pcapng_path, &error);

    if (!reader)
        return false;

    if (reader->dump_format != PCAPNG_DUMP) {
        log_e("Input file is not a Pcapng");
        pd_destroy_reader(reader);
        return false;
    }

    FILE *fout = fopen(out_path, "w");
    if (!fout) {
        pd_destroy_reader(reader);
        log_e("Open keylog failed[%d]: %s", errno, strerror(errno));
        return false;
    }

    pcapd_hdr_t hdr;
    char buffer[PCAPD_SNAPLEN];
    pd_read_callbacks_t cb = { .on_dsb_secrets = dump_dsb_secrets };
    reader_rv rv;

    // process all the blocks, DSB will be handled via the callbacks
    while (((rv = pd_read_next(reader, &hdr, buffer, &cb, fout)) == READER_PACKET_OK) &&
           (!ferror(fout)))
        ;

    fclose(fout);
    pd_destroy_reader(reader);
    return (rv == READER_EOF);
}
