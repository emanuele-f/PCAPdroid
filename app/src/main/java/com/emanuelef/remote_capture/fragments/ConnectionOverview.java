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
 * Copyright 2020-22 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.fragments;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.ConnectionsRegister;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.ConnectionDetailsActivity;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.activities.MenuActionHandler;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.haipq.android.flagkit.FlagImageView;

public class ConnectionOverview extends Fragment implements ConnectionDetailsActivity.ConnUpdateListener, MenuActionHandler {
    private static final String TAG = "ConnectionOverview";
    private ConnectionDetailsActivity mActivity;
    private ConnectionDescriptor mConn;
    private TableLayout mTable;
    private TextView mBytesView;
    private TextView mPayloadLen;
    private TextView mPacketsView;
    private TextView mDurationView;
    private TextView mBlockedPkts;
    private View mBlockedPktsRow;
    private TextView mStatus;
    private TextView mDecStatus;
    private ImageView mDecIcon;
    private TextView mFirstSeen;
    private TextView mLastSeen;
    //private TextView mTcpFlags;
    private TextView mError;
    private TextView mSocketErrno;
    private View mSocketErrnoRow;
    private View mSocketErrnoInfo;
    private ImageView mBlacklistedIp;
    private ImageView mBlacklistedHost;

    public static ConnectionOverview newInstance(int conn_id) {
        ConnectionOverview fragment = new ConnectionOverview();
        Bundle args = new Bundle();
        args.putInt("conn_id", conn_id);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mActivity = (ConnectionDetailsActivity) context;
        mActivity.addConnUpdateListener(this);
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mActivity.removeConnUpdateListener(this);
        mActivity = null;
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.connection_overview, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        TextView appLabel = view.findViewById(R.id.detail_app);
        TextView proto = view.findViewById(R.id.detail_protocol);
        TextView info_label = view.findViewById(R.id.detail_info_label);
        TextView info = view.findViewById(R.id.detail_info);
        TextView url = view.findViewById(R.id.detail_url);
        View url_row = view.findViewById(R.id.detail_url_row);
        View info_row = view.findViewById(R.id.detail_info_row);
        TextView source = view.findViewById(R.id.detail_source);
        TextView destination = view.findViewById(R.id.detail_destination);
        TextView country = view.findViewById(R.id.country_name);
        FlagImageView country_flag = view.findViewById(R.id.country_flag);
        TextView asn = view.findViewById(R.id.asn);
        mTable = view.findViewById(R.id.table);
        mPayloadLen = view.findViewById(R.id.detail_payload);
        mBytesView = view.findViewById(R.id.detail_bytes);
        mPacketsView = view.findViewById(R.id.detail_packets);
        mBlockedPkts = view.findViewById(R.id.blocked_pkts);
        mBlockedPktsRow = view.findViewById(R.id.blocked_row);
        mDurationView = view.findViewById(R.id.detail_duration);
        mStatus = view.findViewById(R.id.detail_status);
        mDecStatus = view.findViewById(R.id.detail_decryption_status);
        mDecIcon = view.findViewById(R.id.decryption_icon);
        mFirstSeen = view.findViewById(R.id.first_seen);
        mLastSeen = view.findViewById(R.id.last_seen);
        //mTcpFlags = view.findViewById(R.id.tcp_flags);
        mError = view.findViewById(R.id.error_msg);
        mSocketErrno = view.findViewById(R.id.detail_errno);
        mSocketErrnoRow = view.findViewById(R.id.error_row);
        mSocketErrnoInfo = view.findViewById(R.id.error_info);
        mBlacklistedIp = view.findViewById(R.id.blacklisted_ip);
        mBlacklistedHost = view.findViewById(R.id.blacklisted_host);

        Bundle args = getArguments();
        assert args != null;
        ConnectionsRegister reg = CaptureService.requireConnsRegister();

        mConn = reg.getConnById(args.getInt("conn_id"));
        if(mConn == null) {
            Utils.showToast(requireContext(), R.string.connection_not_found);
            mActivity.finish();
            return;
        }

        view.findViewById(R.id.whois_ip).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://search.arin.net/rdap/?query=" + mConn.dst_ip));
            Utils.startActivity(mActivity, intent);
        });

        if(mConn != null) {
            String l4proto = Utils.proto2str(mConn.ipproto);
            //if(l4proto.equals("TCP"))
            //    view.findViewById(R.id.tcp_flags_row).setVisibility(View.VISIBLE);

            if(!mConn.l7proto.equals(l4proto))
                proto.setText(String.format(getResources().getString(R.string.app_and_proto), mConn.l7proto, l4proto));
            else
                proto.setText(mConn.l7proto);

            CharSequence protoMsg = null;
            if (mConn.l7proto.equals("DNS"))
                protoMsg = getString(R.string.dns_conn_info);
            else if ((mConn.l7proto.equals("TLS")) || (mConn.l7proto.equals("HTTPS")))
                protoMsg = Utils.getText(view.getContext(), R.string.tls_conn_info, MainActivity.TLS_DECRYPTION_DOCS_URL);

            if (protoMsg != null) {
                final CharSequence msg = protoMsg;
                View protoInfo = view.findViewById(R.id.protocol_info);
                protoInfo.setVisibility(View.VISIBLE);

                protoInfo.setOnClickListener(view1 -> {
                    Context ctx = getContext();
                    if (ctx != null)
                        Utils.showHelpDialog(ctx, msg);
                });
            }

            if(l4proto.equals("ICMP")) {
                source.setText(mConn.src_ip);
                destination.setText(mConn.dst_ip);
            } else {
                if (mConn.ipver == 6) {
                    source.setText(String.format(getResources().getString(R.string.ipv6_and_port), mConn.src_ip, mConn.src_port));
                    destination.setText(String.format(getResources().getString(R.string.ipv6_and_port), mConn.dst_ip, mConn.dst_port));
                } else {
                    source.setText(String.format(getResources().getString(R.string.ip_and_port), mConn.src_ip, mConn.src_port));
                    destination.setText(String.format(getResources().getString(R.string.ip_and_port), mConn.dst_ip, mConn.dst_port));
                }
            }

            if((mConn.info != null) && (!mConn.info.isEmpty())) {
                if(mConn.l7proto.equals("DNS"))
                    info_label.setText(R.string.query);
                else if(mConn.l7proto.equals("HTTP"))
                    info_label.setText(R.string.host);
                info.setText(mConn.info);
            } else
                info_row.setVisibility(View.GONE);

            String uid_str = Integer.toString(mConn.uid);
            AppDescriptor app = (new AppsResolver(mActivity)).getAppByUid(mConn.uid, 0);
            if(app != null)
                appLabel.setText(String.format(getResources().getString(R.string.app_and_proto), app.getName(), uid_str));
            else
                appLabel.setText(uid_str);

            view.findViewById(R.id.decryption_status_row)
                    .setVisibility(CaptureService.isDecryptingTLS() ? View.VISIBLE : View.GONE);

            boolean has_scripts = (mConn.js_injected_scripts != null) && !mConn.js_injected_scripts.isEmpty();
            view.findViewById(R.id.injected_scripts_row)
                    .setVisibility(has_scripts ? View.VISIBLE : View.GONE);
            if(has_scripts)
                ((TextView)view.findViewById(R.id.injected_scripts)).setText(mConn.js_injected_scripts);

            if(!mConn.url.isEmpty())
                url.setText(mConn.url);
            else
                url_row.setVisibility(View.GONE);

            if(!mConn.country.isEmpty()) {
                country.setText(Utils.getCountryName(mActivity, mConn.country));
                country_flag.setCountryCode(mConn.country);
            } else
                view.findViewById(R.id.country_row).setVisibility(View.GONE);

            if(mConn.asn.isKnown())
                asn.setText(mConn.asn.toString());
            else
                view.findViewById(R.id.asn_row).setVisibility(View.GONE);

            if(mConn.ifidx > 0) {
                String ifname = CaptureService.getInterfaceName(mConn.ifidx);

                if(!ifname.isEmpty()) {
                    view.findViewById(R.id.interface_row).setVisibility(View.VISIBLE);
                    ((TextView) view.findViewById(R.id.capture_interface)).setText(ifname);
                }
            }

            connectionUpdated();
        }
    }

    @Override
    public boolean handleMenuAction(MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.copy_to_clipboard) {
            ClipboardManager clipboard = (ClipboardManager) mActivity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.connection_details), getContents());
            clipboard.setPrimaryClip(clip);

            Utils.showToast(mActivity, R.string.copied);
            return true;
        } else if(id == R.id.share) {
            Utils.shareText(mActivity, getString(R.string.connection_details), getContents());
            return true;
        }

        return false;
    }

    private String getContents() {
        if(mTable == null)
            return "";

        return Utils.table2Text(mTable);
    }

    @Override
    public void connectionUpdated() {
        Context context = mBytesView.getContext();

        mPayloadLen.setText(Utils.formatBytes(mConn.payload_length));
        mBytesView.setText(String.format(getResources().getString(R.string.rcvd_and_sent), Utils.formatBytes(mConn.rcvd_bytes), Utils.formatBytes(mConn.sent_bytes)));
        mPacketsView.setText(String.format(getResources().getString(R.string.rcvd_and_sent), Utils.formatIntShort(mConn.rcvd_pkts), Utils.formatIntShort(mConn.sent_pkts)));

        if(mConn.blocked_pkts > 0) {
            mBlockedPkts.setText(String.format(getResources().getString(R.string.n_pkts), Utils.formatIntShort(mConn.blocked_pkts)));
            mBlockedPktsRow.setVisibility(View.VISIBLE);
        }

        mDurationView.setText(Utils.formatDuration((mConn.last_seen - mConn.first_seen) / 1000));
        mFirstSeen.setText(Utils.formatEpochMillis(mActivity, mConn.first_seen));
        mLastSeen.setText(Utils.formatEpochMillis(mActivity, mConn.last_seen));
        mStatus.setText(mConn.getStatusLabel(mActivity));
        mDecStatus.setText(mConn.getDecryptionStatusLabel(mActivity));
        Utils.setDecryptionIcon(mDecIcon, mConn);
        //mTcpFlags.setText(Utils.tcpFlagsToStr(mConn.getRcvdTcpFlags()) + " <- " + Utils.tcpFlagsToStr(mConn.getSentTcpFlags()));
        mBlacklistedIp.setVisibility(mConn.isBlacklistedIp() ? View.VISIBLE : View.GONE);
        mBlacklistedHost.setVisibility(mConn.isBlacklistedHost() ? View.VISIBLE : View.GONE);

        if (mConn.error > 0) {
            mSocketErrnoRow.setVisibility(View.VISIBLE);

            Pair<Integer, Integer> errnoInfo = getSocketErrnoInfo(mConn.error);
            mSocketErrno.setText(context.getString(R.string.error_code_with_text,
                    context.getString((errnoInfo != null) ? errnoInfo.first : R.string.unknown_app),
                    mConn.error));

            if (errnoInfo != null) {
                final int msgId = errnoInfo.second;

                mSocketErrnoInfo.setOnClickListener(view -> {
                    Context ctx = getContext();
                    if (ctx != null)
                        Utils.showHelpDialog(ctx, msgId);
                });
            } else
                mSocketErrnoInfo.setVisibility(View.GONE);
        }

        if(mConn.decryption_error != null) {
            mError.setTextColor(ContextCompat.getColor(context, R.color.danger));
            mError.setText(mConn.decryption_error);
            mError.setVisibility(View.VISIBLE);
        } else if(mConn.is_blocked) {
            mError.setTextColor(ContextCompat.getColor(context, R.color.warning));
            mError.setText(context.getString(R.string.connection_blocked));
            mError.setVisibility(View.VISIBLE);
        } else if(!mConn.hasSeenStart()) {
            mError.setTextColor(ContextCompat.getColor(context, R.color.warning));
            mError.setText(context.getString(R.string.connection_start_not_seen));
            mError.setVisibility(View.VISIBLE);
        } else if(mConn.isPortMappingApplied()) {
            mError.setTextColor(ContextCompat.getColor(context, R.color.colorTabText));
            mError.setText(context.getString(R.string.connection_redirected_port_map));
            mError.setVisibility(View.VISIBLE);
        } else if(mConn.payload_length == 0) {
            mError.setTextColor(ContextCompat.getColor(context, R.color.warning));
            mError.setText(context.getString(R.string.warn_no_app_data));
            mError.setVisibility(View.VISIBLE);
        } else if(mConn.netd_block_missed) {
            mError.setTextColor(ContextCompat.getColor(context, R.color.warning));
            mError.setText(context.getString(R.string.netd_block_missed));
            mError.setVisibility(View.VISIBLE);
        } else if(mConn.getDecryptionStatus() == ConnectionDescriptor.DecryptionStatus.ENCRYPTED) {
            mError.setTextColor(ContextCompat.getColor(context, R.color.colorTabText));
            mError.setText(R.string.decryption_info_no_rule);
            mError.setVisibility(View.VISIBLE);
        } else if((mConn.getDecryptionStatus() == ConnectionDescriptor.DecryptionStatus.NOT_DECRYPTABLE)
                && mConn.l7proto.equals("QUIC") &&
                CaptureService.isDecryptingTLS()) {
            mError.setTextColor(ContextCompat.getColor(context, R.color.warning));
            mError.setText(R.string.decrypt_quic_notice);
            mError.setVisibility(View.VISIBLE);
        } else
            mError.setVisibility(View.GONE);
    }

    private Pair<Integer, Integer> getSocketErrnoInfo(int errno) {
        return switch (errno) {
            case 32 -> /* EPIPE */
                    new Pair<>(R.string.errno_epipe, R.string.errno_epipe_msg);
            case 100 -> /* ENETDOWN */
                    new Pair<>(R.string.errno_enetdown, R.string.errno_enetdown_msg);
            case 101 -> /* ENETUNREACH */
                    new Pair<>(R.string.errno_enetunreach, R.string.errno_enetunreach_msg);
            case 102 -> /* ENETRESET */
                    new Pair<>(R.string.errno_enetreset, R.string.errno_enetreset_msg);
            case 103 -> /* ECONNABORTED */
                    new Pair<>(R.string.errno_econnaborted, R.string.errno_econnaborted_msg);
            case 104 -> /* ECONNRESET */
                    new Pair<>(R.string.errno_econnreset, R.string.errno_econnreset_msg);
            case 110 -> /* ETIMEDOUT */
                    new Pair<>(R.string.errno_etimedout, R.string.errno_etimedout_msg);
            case 111 -> /* ECONNREFUSED */
                    new Pair<>(R.string.errno_econnrefused, R.string.errno_econnrefused_msg);
            case 113 -> /* EHOSTUNREACH */
                    new Pair<>(R.string.errno_ehostunreach, R.string.errno_ehostunreach_msg);
            default -> null;
        };
    }
}
