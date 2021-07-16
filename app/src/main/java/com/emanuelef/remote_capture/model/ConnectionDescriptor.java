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

import android.content.Context;

import androidx.annotation.NonNull;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.R;

import java.io.Serializable;

/* Equivalent of zdtun_conn_t from zdtun and conn_data_t from vpnproxy.c */
public class ConnectionDescriptor implements Serializable {
    // sync with zdtun_conn_status_t

    public static final int CONN_STATUS_NEW = 0,
        CONN_STATUS_CONNECTING = 1,
        CONN_STATUS_CONNECTED = 2,
        CONN_STATUS_CLOSED = 3,
        CONN_STATUS_ERROR = 4,
        CONN_STATUS_SOCKET_ERROR = 5,
        CONN_STATUS_CLIENT_ERROR = 6,
        CONN_STATUS_RESET = 7,
        CONN_STATUS_UNREACHABLE = 8;

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
    public String request_plaintext;
    public String l7proto;
    public int uid;
    public int incr_id;
    public int status;
    private int tcp_flags;

    public ConnectionDescriptor(int _incr_id, int _ipver, int _ipproto, String _src_ip, String _dst_ip,
                                int _src_port, int _dst_port, int _uid, long when) {
        incr_id = _incr_id;
        ipver = _ipver;
        ipproto = _ipproto;
        src_ip = _src_ip;
        dst_ip = _dst_ip;
        src_port = _src_port;
        dst_port = _dst_port;
        uid = _uid;
        first_seen = last_seen = when;
    }

    public void processUpdate(ConnectionUpdate update) {
        if((update.update_type & ConnectionUpdate.UPDATE_STATS) != 0) {
            sent_bytes = update.sent_bytes;
            rcvd_bytes = update.rcvd_bytes;
            sent_pkts = update.sent_pkts;
            rcvd_pkts = update.rcvd_pkts;
            status = update.status;
            last_seen = update.last_seen;
            tcp_flags = update.tcp_flags;
        }
        if((update.update_type & ConnectionUpdate.UPDATE_INFO) != 0) {
            info = update.info;
            url = update.url;
            request_plaintext = update.request_plaintext;
            l7proto = update.l7proto;
        }
    }

    public String getStatusLabel(Context ctx) {
        int resid;

        if(status >= CONN_STATUS_CLOSED) {
            switch(status) {
                case CONN_STATUS_CLOSED:
                case CONN_STATUS_RESET:
                    resid = R.string.conn_status_closed;
                    break;
                case CONN_STATUS_UNREACHABLE:
                    resid = R.string.conn_status_unreachable;
                    break;
                default:
                    resid = R.string.error;
                    break;
            }
        } else
            resid = R.string.conn_status_open;

        return(ctx.getString(resid));
    }

    public boolean matches(AppsResolver res, String filter) {
        filter = filter.toLowerCase();
        AppDescriptor app = res.get(uid, 0);

        return(((info != null) && (info.contains(filter))) ||
                dst_ip.contains(filter) ||
                l7proto.toLowerCase().contains(filter) ||
                Integer.toString(uid).equals(filter) ||
                Integer.toString(dst_port).contains(filter) ||
                Integer.toString(src_port).equals(filter) ||
                ((app != null) && (app.getName().toLowerCase().contains(filter) ||
                        app.getPackageName().equals(filter)))
        );
    }

    public int getSentTcpFlags() {
        return (tcp_flags >> 8);
    }

    public int getRcvdTcpFlags() {
        return (tcp_flags & 0xFF);
    }

    @Override
    public @NonNull String toString() {
        return "[proto=" + ipproto + "/" + l7proto + "]: " + src_ip + ":" + src_port + " -> " +
                dst_ip + ":" + dst_port + " [" + uid + "] " + info;
    }
}
