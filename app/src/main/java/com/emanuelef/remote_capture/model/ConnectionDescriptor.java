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
import androidx.annotation.Nullable;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.HTTPReassembly;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.R;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/* Holds the information about a single connection.
 * Equivalent of zdtun_conn_t from zdtun and pd_conn_t from pcapdroid.c .
 *
 * Connections are normally stored into the ConnectionsRegister. Concurrent access to the connection
 * fields can happen when a connection is updated and, at the same time, it is retrieved by the UI
 * thread. However this does not create concurrency problems as the update only increments counters
 * or sets a previously null field to a non-null value.
 */
public class ConnectionDescriptor {
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

    // This is an high level status which abstracts the zdtun_conn_status_t
    public enum Status {
        STATUS_INVALID,
        STATUS_ACTIVE,
        STATUS_CLOSED,
        STATUS_UNREACHABLE,
        STATUS_ERROR,
    }

    public enum DecryptionStatus {
        INVALID,
        ENCRYPTED,
        CLEARTEXT,
        DECRYPTED,
        NOT_DECRYPTABLE,
        WAITING_DATA,
        ERROR,
    }

    public enum FilteringStatus {
        INVALID,
        ALLOWED,
        BLOCKED
    }

    /* Metadata */
    public final int ipver;
    public final int ipproto;
    public final String src_ip;
    public final String dst_ip;
    public final int src_port;
    public final int dst_port;
    public final int local_port; // in VPN mode, this is the local port of the Internet connection

    /* Data */
    public long first_seen;
    public long last_seen;
    public long payload_length;
    public long sent_bytes;
    public long rcvd_bytes;
    public int sent_pkts;
    public int rcvd_pkts;
    public int blocked_pkts;
    public String info;
    public String url;
    public String l7proto;
    private final ArrayList<PayloadChunk> payload_chunks; // must be synchronized
    public final int uid;
    public final int ifidx;
    public final int incr_id;
    private final boolean mitm_decrypt; // true if the connection is under mitm for TLS decryption
    private boolean internal_decrypt;
    public int status;
    private int tcp_flags;
    private boolean blacklisted_ip;
    private boolean blacklisted_host;
    public boolean is_blocked;
    private boolean port_mapping_applied;
    private boolean decryption_ignored;
    public boolean netd_block_missed;
    private boolean payload_truncated;
    private boolean encrypted_l7;     // application layer is encrypted (e.g. TLS)
    public boolean encrypted_payload; // actual payload is encrypted (e.g. telegram - see Utils.hasEncryptedPayload)
    public String decryption_error;
    public String js_injected_scripts;
    public String country;
    public Geomodel.ASN asn;

    /* Internal */
    public boolean alerted;
    public boolean block_accounted;

    public ConnectionDescriptor(int _incr_id, int _ipver, int _ipproto, String _src_ip, String _dst_ip,
                                int _src_port, int _dst_port, int _local_port, int _uid, int _ifidx,
                                boolean _mitm_decrypt, long when) {
        incr_id = _incr_id;
        ipver = _ipver;
        ipproto = _ipproto;
        src_ip = _src_ip;
        dst_ip = _dst_ip;
        src_port = _src_port;
        dst_port = _dst_port;
        local_port = _local_port;
        uid = _uid;
        ifidx = _ifidx;
        first_seen = last_seen = when;
        l7proto = "";
        country = "";
        asn = new Geomodel.ASN();
        payload_chunks = new ArrayList<>();
        mitm_decrypt = _mitm_decrypt;
        internal_decrypt = false;
    }

    public void processUpdate(ConnectionUpdate update) {
        // The "update_type" is used to limit the amount of data sent via the JNI
        if((update.update_type & ConnectionUpdate.UPDATE_STATS) != 0) {
            sent_bytes = update.sent_bytes;
            rcvd_bytes = update.rcvd_bytes;
            sent_pkts = update.sent_pkts;
            rcvd_pkts = update.rcvd_pkts;
            blocked_pkts = update.blocked_pkts;
            status = (update.status & 0x00FF);
            port_mapping_applied = (update.status & 0x2000) != 0;
            decryption_ignored = (update.status & 0x1000) != 0;
            netd_block_missed = (update.status & 0x0800) != 0;
            is_blocked = (update.status & 0x0400) != 0;
            blacklisted_host = (update.status & 0x0200) != 0;
            blacklisted_ip = (update.status & 0x0100) != 0;
            last_seen = update.last_seen;
            tcp_flags = update.tcp_flags; // NOTE: only for root capture

            // see MitmReceiver.handlePayload
            if((status == ConnectionDescriptor.CONN_STATUS_CLOSED) && (decryption_error != null))
                status = ConnectionDescriptor.CONN_STATUS_CLIENT_ERROR;

            // with mitm we account the TLS payload length instead
            if(!mitm_decrypt)
                payload_length = update.payload_length;
        }
        if((update.update_type & ConnectionUpdate.UPDATE_INFO) != 0) {
            info = update.info;
            url = update.url;
            l7proto = update.l7proto;
            encrypted_l7 = ((update.info_flags & ConnectionUpdate.UPDATE_INFO_FLAG_ENCRYPTED_L7) != 0);
        }
        if((update.update_type & ConnectionUpdate.UPDATE_PAYLOAD) != 0) {
            // Payload for decryptable connections should be received via the MitmReceiver
            assert(decryption_ignored || isNotDecryptable() || PCAPdroid.getInstance().isDecryptingPcap());

            // Some pending updates with payload may still be received after low memory has been
            // triggered and payload disabled
            if(!CaptureService.isLowMemory()) {
                synchronized (this) {
                    if(update.payload_chunks != null)
                        payload_chunks.addAll(update.payload_chunks);
                    payload_truncated = update.payload_truncated;
                    internal_decrypt = update.payload_decrypted;
                }
            }
        }
    }

    public InetAddress getDstAddr() {
        try {
            return InetAddress.getByName(dst_ip);
        } catch (UnknownHostException e) {
            e.printStackTrace();
            return null;
        }
    }

    public Status getStatus() {
        if(status >= CONN_STATUS_CLOSED) {
            switch(status) {
                case CONN_STATUS_CLOSED:
                case CONN_STATUS_RESET:
                    return Status.STATUS_CLOSED;
                case CONN_STATUS_UNREACHABLE:
                    return Status.STATUS_UNREACHABLE;
                default:
                    return Status.STATUS_ERROR;
            }
        }
        return Status.STATUS_ACTIVE;
    }

    public static String getStatusLabel(Status status, Context ctx) {
        int resid;

        switch(status) {
            case STATUS_ACTIVE: resid = R.string.conn_status_active; break;
            case STATUS_CLOSED: resid = R.string.conn_status_closed; break;
            case STATUS_UNREACHABLE: resid = R.string.conn_status_unreachable; break;
            default: resid = R.string.error;
        }

        return(ctx.getString(resid));
    }

    public String getStatusLabel(Context ctx) {
        return getStatusLabel(getStatus(), ctx);
    }

    public boolean matches(AppsResolver res, String filter) {
        filter = filter.toLowerCase();
        AppDescriptor app = res.getAppByUid(uid, 0);

        return(((info != null) && (info.contains(filter))) ||
                dst_ip.contains(filter) ||
                l7proto.toLowerCase().equals(filter) ||
                Integer.toString(uid).equals(filter) ||
                Integer.toString(dst_port).contains(filter) ||
                Integer.toString(src_port).equals(filter) ||
                ((app != null) && (app.matches(filter, true)))
        );
    }

    public DecryptionStatus getDecryptionStatus() {
        if(isCleartext())
            return DecryptionStatus.CLEARTEXT;
        else if(decryption_error != null)
            return DecryptionStatus.ERROR;
        else if(isNotDecryptable())
            return DecryptionStatus.NOT_DECRYPTABLE;
        else if(decryption_ignored || (PCAPdroid.getInstance().isDecryptingPcap() && !internal_decrypt))
            return DecryptionStatus.ENCRYPTED;
        else if(isDecrypted())
            return DecryptionStatus.DECRYPTED;
        else
            return DecryptionStatus.WAITING_DATA;
    }

    public static String getDecryptionStatusLabel(DecryptionStatus status, Context ctx) {
        int resid;

        switch (status) {
            case CLEARTEXT: resid = R.string.not_encrypted; break;
            case NOT_DECRYPTABLE: resid = R.string.not_decryptable; break;
            case DECRYPTED: resid = R.string.decrypted; break;
            case ENCRYPTED: resid = R.string.status_encrypted; break;
            case WAITING_DATA: resid = R.string.waiting_application_data; break;
            default: resid = R.string.error;
        }

        return(ctx.getString(resid));
    }

    public String getDecryptionStatusLabel(Context ctx) {
        return getDecryptionStatusLabel(getDecryptionStatus(), ctx);
    }

    public int getSentTcpFlags() {
        return (tcp_flags >> 8);
    }

    public int getRcvdTcpFlags() {
        return (tcp_flags & 0xFF);
    }

    public boolean isBlacklistedIp() { return blacklisted_ip; }
    public boolean isBlacklistedHost() { return blacklisted_host; }
    public boolean isBlacklisted() {
        return isBlacklistedIp() || isBlacklistedHost();
    }

    public void setPayloadTruncatedByAddon() {
        // only for the mitm addon
        assert(!isNotDecryptable());
        payload_truncated = true;
    }

    public boolean isPayloadTruncated() { return payload_truncated; }
    public boolean isPortMappingApplied() { return port_mapping_applied; }

    public boolean isNotDecryptable()   { return !decryption_ignored && (encrypted_payload || !mitm_decrypt) && !PCAPdroid.getInstance().isDecryptingPcap(); }
    public boolean isDecrypted()        { return !decryption_ignored && !isNotDecryptable() && (mitm_decrypt || internal_decrypt) && (getNumPayloadChunks() > 0); }
    public boolean isCleartext()        { return !encrypted_payload && !encrypted_l7; }

    public synchronized int getNumPayloadChunks() { return payload_chunks.size(); }

    public synchronized @Nullable PayloadChunk getPayloadChunk(int idx) {
        if(getNumPayloadChunks() <= idx)
            return null;
        return payload_chunks.get(idx);
    }

    public synchronized void addPayloadChunkMitm(PayloadChunk chunk) {
        payload_chunks.add(chunk);
        payload_length += chunk.payload.length;
    }

    public synchronized void dropPayload() {
        payload_chunks.clear();
    }

    private synchronized boolean hasHttp(boolean is_sent) {
        for(PayloadChunk chunk: payload_chunks) {
            if(chunk.is_sent == is_sent)
                return (chunk.type == PayloadChunk.ChunkType.HTTP);
        }

        return false;
    }
    public boolean hasHttpRequest() { return hasHttp(true); }
    public boolean hasHttpResponse() { return hasHttp(false); }

    private synchronized String getHttp(boolean is_sent) {
        if(getNumPayloadChunks() == 0)
            return "";

        // Need to wrap the String to set it from the lambda
        final AtomicReference<String> rv = new AtomicReference<>();

        HTTPReassembly reassembly = new HTTPReassembly(CaptureService.getCurPayloadMode() == Prefs.PayloadMode.FULL,
                chunk -> rv.set(new String(chunk.payload, StandardCharsets.UTF_8))
        );

        // Possibly reassemble/decode the request
        for(PayloadChunk chunk: payload_chunks) {
            if(chunk.is_sent == is_sent)
                reassembly.handleChunk(chunk);

            // Stop at the first reassembly/chunk
            if(rv.get() != null)
                break;
        }

        return rv.get();
    }
    public String getHttpRequest() { return getHttp(true); }
    public String getHttpResponse() { return getHttp(false); }

    public boolean hasSeenStart() {
        if((ipproto != 6 /* TCP */) || !CaptureService.isCapturingAsRoot())
            return true;

        return (getSentTcpFlags() & 0x2) != 0; // SYN
    }

    @Override
    public @NonNull String toString() {
        return "[proto=" + ipproto + "/" + l7proto + "]: " + src_ip + ":" + src_port + " -> " +
                dst_ip + ":" + dst_port + " [" + uid + "] " + info;
    }
}
