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

package com.emanuelef.remote_capture.model;

public class ConnectionUpdate {
    public static final int UPDATE_STATS = 1;
    public static final int UPDATE_INFO = 2;
    public final int incr_id;
    public int update_type;

    /* set if update_type & UPDATE_STATS */
    public long last_seen;
    public long sent_bytes;
    public long rcvd_bytes;
    public int sent_pkts;
    public int rcvd_pkts;
    public int blocked_pkts;
    public int tcp_flags;
    public int status;

    /* set if update_type & UPDATE_INFO */
    public String info;
    public String url;
    public String request_plaintext;
    public String l7proto;

    public ConnectionUpdate(int _incr_id) {
        incr_id = _incr_id;
    }

    public void setStats(long _last_seen, long _sent_bytes, long _rcvd_bytes,
                         int _sent_pkts, int _rcvd_pkts, int _blocked_pkts,
                         int _tcp_flags, int _status) {
        update_type |= UPDATE_STATS;

        last_seen = _last_seen;
        sent_bytes = _sent_bytes;
        rcvd_bytes = _rcvd_bytes;
        sent_pkts = _sent_pkts;
        blocked_pkts = _blocked_pkts;
        rcvd_pkts = _rcvd_pkts;
        tcp_flags = _tcp_flags;
        status = _status;
    }

    public void setInfo(String _info, String _url, String _req, String _l7proto) {
        update_type |= UPDATE_INFO;

        info = _info;
        url = _url;
        request_plaintext = _req;
        l7proto = _l7proto;
    }
}
