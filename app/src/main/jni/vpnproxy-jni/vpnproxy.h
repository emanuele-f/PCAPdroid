/*
    This file is part of RemoteCapture.

    RemoteCapture is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    RemoteCapture is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with RemoteCapture.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2019 by Emanuele Faranda
*/

#include <jni.h>
#include <android/log.h>
#include "zdtun.h"

#ifndef REMOTE_CAPTURE_VPNPROXY_H
#define REMOTE_CAPTURE_VPNPROXY_H

typedef struct vpnproxy_data {
    int tapfd;
    jint sdk;
    JNIEnv *env;
    jobject handler_cls; // TODO remove?
    jobject vpn_service;
    u_int32_t vpn_dns;
    u_int32_t public_dns;
    u_int32_t vpn_ipv4;
    bool dns_changed;

    struct {
        u_int32_t collector_addr;
        u_int16_t collector_port;
        int uid_filter;
        bool enabled;
    } pcap_dump;
} vpnproxy_data_t;

/* ******************************************************* */

jint get_uid(struct vpnproxy_data *proxy, const zdtun_conn_t *conn_info);

#endif //REMOTE_CAPTURE_H
