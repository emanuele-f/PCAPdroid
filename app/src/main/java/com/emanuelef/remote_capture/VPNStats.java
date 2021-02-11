package com.emanuelef.remote_capture;

import java.io.Serializable;

public class VPNStats implements Serializable {
    int num_dropped_conns;
    int num_open_sockets;
    int max_fd;
    int active_conns;
    int tot_conns;
    int num_dns_queries;

    /* Invoked by native code */
    public void setData(int _num_dropped_conns, int _num_open_sockets, int _max_fd,
                        int _active_conns, int _tot_conns, int _num_dns_queries) {
        num_dropped_conns = _num_dropped_conns;
        num_open_sockets = _num_open_sockets;
        max_fd = _max_fd;
        active_conns = _active_conns;
        tot_conns = _tot_conns;
        num_dns_queries = _num_dns_queries;
    }
}
