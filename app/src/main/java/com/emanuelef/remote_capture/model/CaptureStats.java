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

public class CaptureStats implements Serializable {
    public String alloc_summary;
    public long bytes_sent;
    public long bytes_rcvd;
    public long ipv6_bytes_sent;
    public long ipv6_bytes_rcvd;
    public long pcap_dump_size;
    public int pkts_sent;
    public int pkts_rcvd;
    public int pkts_dropped;
    public int num_dropped_conns;
    public int num_open_sockets;
    public int max_fd;
    public int active_conns;
    public int tot_conns;
    public int num_dns_queries;

    /* Invoked by native code */
    public void setData(String _alloc_summary,
                        long _bytes_sent, long _bytes_rcvd,
                        long _ipv6_bytes_sent, long _ipv6_bytes_rcvd,
                        long _pcap_dump_size, int _pkts_sent, int _pkts_rcvd,
                        int _pkts_dropped, int _num_dropped_conns, int _num_open_sockets,
                        int _max_fd, int _active_conns, int _tot_conns, int _num_dns_queries) {
        alloc_summary = _alloc_summary;
        bytes_sent = _bytes_sent;
        bytes_rcvd = _bytes_rcvd;
        ipv6_bytes_sent = _ipv6_bytes_sent;
        ipv6_bytes_rcvd = _ipv6_bytes_rcvd;
        pcap_dump_size = _pcap_dump_size;
        pkts_sent = _pkts_sent;
        pkts_rcvd = _pkts_rcvd;
        pkts_dropped = _pkts_dropped;
        num_dropped_conns = _num_dropped_conns;
        num_open_sockets = _num_open_sockets;
        max_fd = _max_fd;
        active_conns = _active_conns;
        tot_conns = _tot_conns;
        num_dns_queries = _num_dns_queries;
    }
}
