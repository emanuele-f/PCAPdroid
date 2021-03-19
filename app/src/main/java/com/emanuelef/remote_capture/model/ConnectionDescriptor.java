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
 * Copyright 2020-21 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.model;

import java.io.Serializable;

/* Equivalent of zdtun_conn_t from zdtun and conn_data_t from vpnproxy.c */
public class ConnectionDescriptor implements Serializable {
    /* Metadata */
    public int ipver;
    public int ipproto;
    public String src_ip;
    public String dst_ip;
    public int src_port;
    public int dst_port;

    /* Data */
    public long first_seen;
    public long last_seen;
    public long sent_bytes;
    public long rcvd_bytes;
    public int sent_pkts;
    public int rcvd_pkts;
    public String info;
    public String url;
    public String l7proto;
    public int uid;
    public int incr_id;
    public String status;

    /* Invoked by native code
    * NOTE: interleaving String and int in the parameters is not good as it makes the app crash
    * nto the emulator! Better to put the strings first. */
    public void setData(String _src_ip, String _dst_ip, String _status, String _info, String _url, String _l7proto,
                        int _ipver, int _ipproto, int _src_port, int _dst_port, long _first_seen, long _last_seen,
                        long _sent_bytes, long _rcvd_bytes, int _sent_pkts, int _rcvd_pkts, int _uid,
                        int _incr_id) {
        /* Metadata */
        ipver = _ipver;
        ipproto = _ipproto;
        src_ip = _src_ip;
        dst_ip = _dst_ip;
        src_port = _src_port;
        dst_port = _dst_port;

        /* Data */
        first_seen = _first_seen;
        last_seen = _last_seen;
        sent_bytes = _sent_bytes;
        rcvd_bytes = _rcvd_bytes;
        sent_pkts = _sent_pkts;
        rcvd_pkts = _rcvd_pkts;
        status = _status;
        info = _info;
        url = _url;
        l7proto = _l7proto;
        uid = _uid;
        incr_id = _incr_id;
    }
}
