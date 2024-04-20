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
 * Copyright 2020-24 - Emanuele Faranda
 */

package com.emanuelef.remote_capture.fragments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.MitmReceiver;
import com.emanuelef.remote_capture.activities.AppFilterActivity;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppState;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.interfaces.AppStateListener;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.model.CaptureStats;
import com.emanuelef.remote_capture.views.PrefSpinner;

import java.util.ArrayList;
import java.util.Set;

public class StatusFragment extends Fragment implements AppStateListener, MenuProvider {
    private static final String TAG = "StatusFragment";
    private Menu mMenu;
    private MenuItem mStartBtn;
    private MenuItem mStopBtn;
    private MenuItem mOpenPcap;
    private ImageView mFilterIcon;
    private MenuItem mMenuSettings;
    private TextView mInterfaceInfo;
    private View mCollectorInfoLayout;
    private TextView mCollectorInfoText;
    private ImageView mCollectorInfoIcon;
    private TextView mCaptureStatus;
    private View mQuickSettings;
    private MainActivity mActivity;
    private SharedPreferences mPrefs;
    private TextView mFilterDescription;
    private SwitchCompat mAppFilterSwitch;
    private Set<String> mAppFilter;
    private TextView mFilterRootDecryptionWarning;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mActivity = (MainActivity) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mActivity.setAppStateListener(null);
        mActivity = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        requireActivity().addMenuProvider(this, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
        return inflater.inflate(R.layout.status, container, false);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mInterfaceInfo = view.findViewById(R.id.interface_info);
        mCollectorInfoLayout = view.findViewById(R.id.collector_info_layout);
        mCollectorInfoText = mCollectorInfoLayout.findViewById(R.id.collector_info_text);
        mCollectorInfoIcon = mCollectorInfoLayout.findViewById(R.id.collector_info_icon);
        mCaptureStatus = view.findViewById(R.id.status_view);
        mQuickSettings = view.findViewById(R.id.quick_settings);
        mFilterRootDecryptionWarning = view.findViewById(R.id.app_filter_root_decryption_warning);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        mAppFilter = Prefs.getAppFilter(mPrefs);

        PrefSpinner.init(view.findViewById(R.id.dump_mode_spinner),
                R.array.pcap_dump_modes, R.array.pcap_dump_modes_labels, R.array.pcap_dump_modes_descriptions,
                Prefs.PREF_PCAP_DUMP_MODE, Prefs.DEFAULT_DUMP_MODE);

        mAppFilterSwitch = view.findViewById(R.id.app_filter_switch);
        View filterRow = view.findViewById(R.id.app_filter_text);
        TextView filterTitle = filterRow.findViewById(R.id.title);
        mFilterDescription = filterRow.findViewById(R.id.description);
        mFilterIcon = filterRow.findViewById(R.id.icon);

        filterTitle.setText(R.string.target_apps);

        mAppFilterSwitch.setOnClickListener((buttonView) -> {
            mAppFilterSwitch.setChecked(!mAppFilterSwitch.isChecked());
            openAppFilterSelector();
        });

        refreshFilterInfo();

        mCaptureStatus.setOnClickListener(v -> {
            if(mActivity.getState() == AppState.ready)
                mActivity.startCapture();
        });

        // Register for updates
        MitmReceiver.observeStatus(this, status -> refreshDecryptionStatus());
        CaptureService.observeStats(this, this::onStatsUpdate);

        // Make URLs clickable
        mCollectorInfoText.setMovementMethod(LinkMovementMethod.getInstance());

        /* Important: call this after all the fields have been initialized */
        mActivity.setAppStateListener(this);
        refreshStatus();
    }

    @Override
    public void onCreateMenu(@NonNull Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.main_menu, menu);

        mMenu = menu;
        mStartBtn = mMenu.findItem(R.id.action_start);
        mStopBtn = mMenu.findItem(R.id.action_stop);
        mMenuSettings = mMenu.findItem(R.id.action_settings);
        mOpenPcap = mMenu.findItem(R.id.open_pcap);
        refreshStatus();
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        return false;
    }

    private void recheckFilterWarning() {
        boolean hasFilter = ((mAppFilter != null) && (!mAppFilter.isEmpty()));

        mFilterRootDecryptionWarning.setVisibility((Prefs.getTlsDecryptionEnabled(mPrefs) &&
                Prefs.isRootCaptureEnabled(mPrefs)
                && !hasFilter) ? View.VISIBLE : View.GONE);
    }

    private void refreshDecryptionStatus() {
        MitmReceiver.Status proxy_status = CaptureService.getMitmProxyStatus();
        Context ctx = getContext();

        if((proxy_status == MitmReceiver.Status.START_ERROR) && (ctx != null))
            Utils.showToastLong(ctx, R.string.mitm_addon_error);

        mInterfaceInfo.setText((proxy_status == MitmReceiver.Status.RUNNING) ? R.string.mitm_addon_running : R.string.mitm_addon_starting);
    }

    private void refreshFilterInfo() {
        Context context = getContext();
        if(context == null)
            return;

        if((mAppFilter == null) || (mAppFilter.isEmpty())) {
            mFilterDescription.setText(R.string.capture_all_apps);
            mFilterIcon.setVisibility(View.GONE);
            mAppFilterSwitch.setChecked(false);
            return;
        }

        mAppFilterSwitch.setChecked(true);

        Pair<String, Drawable> pair = getAppFilterTextAndIcon(context);

        mFilterDescription.setText(pair.first);

        if (pair.second != null) {
            mFilterIcon.setImageDrawable(pair.second);
            mFilterIcon.setVisibility(View.VISIBLE);
        }
    }

    private void onStatsUpdate(CaptureStats stats) {
        Log.d("MainReceiver", "Got StatsUpdate: bytes_sent=" + stats.pkts_sent + ", bytes_rcvd=" +
                stats.bytes_rcvd + ", pkts_sent=" + stats.pkts_sent + ", pkts_rcvd=" + stats.pkts_rcvd);
        mCaptureStatus.setText(Utils.formatBytes(stats.bytes_sent + stats.bytes_rcvd));
    }

    private Pair<String, Drawable> getAppFilterTextAndIcon(@NonNull Context context) {
        Drawable icon = null;
        String text = "";

        if((mAppFilter != null) && (!mAppFilter.isEmpty())) {
            if (mAppFilter.size() == 1) {
                // only a single app is selected, show its image and text
                String package_name = mAppFilter.iterator().next();
                AppDescriptor app = AppsResolver.resolveInstalledApp(requireContext().getPackageManager(), package_name, 0);

                if((app != null) && (app.getIcon() != null)) {
                    icon = app.getIcon();
                    text = app.getName() + " (" + app.getPackageName() + ")";
                }
            } else {
                // multiple apps, show default icon and comprehensive text
                icon = ContextCompat.getDrawable(context, R.drawable.ic_image);
                ArrayList<String> parts = new ArrayList<>();

                for (String package_name: mAppFilter) {
                    AppDescriptor app = AppsResolver.resolveInstalledApp(requireContext().getPackageManager(), package_name, 0);
                    String tmp = package_name;

                    if (app != null)
                        tmp = app.getName();

                    parts.add(tmp);
                }

                text = Utils.shorten(String.join(", ", parts), 48);
            }
        }

        return new Pair<>(text, icon);
    }

    private void refreshPcapDumpInfo(Context context) {
        String info = "";

        Prefs.DumpMode mode = CaptureService.getDumpMode();

        switch (mode) {
        case NONE:
            info = getString(R.string.no_dump_info);
            break;
        case HTTP_SERVER:
            info = String.format(getResources().getString(R.string.http_server_status),
                    Utils.getLocalIPAddress(mActivity), CaptureService.getHTTPServerPort());
            break;
        case PCAP_FILE:
            info = getString(R.string.pcap_file_info);

            String pcapFname = CaptureService.getPcapFname();
            if(pcapFname != null)
                info = pcapFname;
            break;
        case UDP_EXPORTER:
            info = String.format(getResources().getString(R.string.collector_info),
                    CaptureService.getCollectorAddress(), CaptureService.getCollectorPort());
            break;
        }

        mCollectorInfoText.setText(info);

        // Check if a filter is set
        Drawable drawable = null;
        if((mAppFilter != null) && (!mAppFilter.isEmpty())) {
            Pair<String, Drawable> pair = getAppFilterTextAndIcon(context);
            drawable = pair.second;
        }

        if (drawable != null) {
            mCollectorInfoIcon.setImageDrawable(drawable);
            mCollectorInfoIcon.setVisibility(View.VISIBLE);
        } else
            mCollectorInfoIcon.setVisibility(View.GONE);
    }

    @Override
    public void appStateChanged(AppState state) {
        Context context = getContext();
        if(context == null)
            return;

        if(mMenu != null) {
            if((state == AppState.running) || (state == AppState.stopping)) {
                mStartBtn.setVisible(false);
                mStopBtn.setEnabled(true);
                mStopBtn.setVisible(!CaptureService.isAlwaysOnVPN());
                mMenuSettings.setEnabled(false);
                mOpenPcap.setEnabled(false);
            } else { // ready || starting
                mStopBtn.setVisible(false);
                mStartBtn.setEnabled(true);
                mStartBtn.setVisible(!CaptureService.isAlwaysOnVPN());
                mMenuSettings.setEnabled(true);
                mOpenPcap.setEnabled(true);
            }
        }

        switch(state) {
            case ready:
                mCaptureStatus.setText(R.string.ready);
                mCollectorInfoLayout.setVisibility(View.GONE);
                mInterfaceInfo.setVisibility(View.GONE);
                mQuickSettings.setVisibility(View.VISIBLE);
                mAppFilter = Prefs.getAppFilter(mPrefs);
                refreshFilterInfo();
                break;
            case starting:
                if(mMenu != null)
                    mStartBtn.setEnabled(false);
                break;
            case stopping:
                if(mMenu != null)
                    mStopBtn.setEnabled(false);
                break;
            case running:
                mCaptureStatus.setText(Utils.formatBytes(CaptureService.getBytes()));
                mCollectorInfoLayout.setVisibility(View.VISIBLE);
                mQuickSettings.setVisibility(View.GONE);
                CaptureService service = CaptureService.requireInstance();

                if(CaptureService.isDecryptingTLS()) {
                    refreshDecryptionStatus();
                    mInterfaceInfo.setVisibility(View.VISIBLE);
                } else if(CaptureService.isCapturingAsRoot()) {
                    String capiface = service.getCaptureInterface();

                    if(capiface.equals("@inet"))
                        capiface = getString(R.string.internet);
                    else if(capiface.equals("any"))
                        capiface = getString(R.string.all_interfaces);

                    mInterfaceInfo.setText(String.format(getResources().getString(R.string.capturing_from), capiface));
                    mInterfaceInfo.setVisibility(View.VISIBLE);
                } else if(service.getSocks5Enabled() == 1) {
                    mInterfaceInfo.setText(String.format(getResources().getString(R.string.socks5_info),
                            service.getSocks5ProxyAddress(), service.getSocks5ProxyPort()));
                    mInterfaceInfo.setVisibility(View.VISIBLE);
                } else
                    mInterfaceInfo.setVisibility(View.GONE);

                mAppFilter = CaptureService.getAppFilter();
                refreshPcapDumpInfo(context);
                break;
            default:
                break;
        }
    }

    private void refreshStatus() {
        if(mActivity != null)
            appStateChanged(mActivity.getState());
        recheckFilterWarning();
    }

    private void openAppFilterSelector() {
        Intent intent = new Intent(requireContext(), AppFilterActivity.class);
        startActivity(intent);
    }
}
