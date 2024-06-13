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

import java.util.ArrayList;

public class ConnectionUpdate {
    public static final int UPDATE_STATS = 0x1;
    public static final int UPDATE_INFO = 0x2;
    public static final int UPDATE_PAYLOAD = 0x4;
    public static final int UPDATE_INFO_FLAG_ENCRYPTED_L7 = 0x1;
    public final int incr_id;
    public int update_type;

    /* set if update_type & UPDATE_STATS */
    public long last_seen;
    public long payload_length;
    public long sent_bytes;
    public long rcvd_bytes;
    public int sent_pkts;
    public int rcvd_pkts;
    public int blocked_pkts;
    public int tcp_flags;
    public int status;
    public int info_flags;

    /* set if update_type & UPDATE_INFO */
    public String info;
    public String url;
    public String l7proto;

    /* set if update_type & UPDATE_PAYLOAD */
    public ArrayList<PayloadChunk> payload_chunks;
    public boolean payload_truncated;
    public boolean payload_decrypted;

    public ConnectionUpdate(int _incr_id) {
        incr_id = _incr_id;
    }

    public void setStats(long _last_seen, long _payload_length, long _sent_bytes, long _rcvd_bytes,
                         int _sent_pkts, int _rcvd_pkts, int _blocked_pkts,
                         int _tcp_flags, int _status) {
        update_type |= UPDATE_STATS;

        last_seen = _last_seen;
        payload_length = _payload_length;
        sent_bytes = _sent_bytes;
        rcvd_bytes = _rcvd_bytes;
        sent_pkts = _sent_pkts;
        blocked_pkts = _blocked_pkts;
        rcvd_pkts = _rcvd_pkts;
        tcp_flags = _tcp_flags;
        status = _status;
    }

    public void setInfo(String _info, String _url, String _l7proto, int flags) {
        update_type |= UPDATE_INFO;

        info = _info;
        url = _url;
        l7proto = _l7proto;
        info_flags = flags;
    }

    public void setPayload(ArrayList<PayloadChunk> _chunks, int flags) {
        update_type |= UPDATE_PAYLOAD;

        payload_chunks = _chunks;
        payload_truncated = (flags & 0x1) != 0;
        payload_decrypted = (flags & 0x2) != 0;
    }
}
