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

package com.emanuelef.remote_capture.fragments;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.MitmReceiver;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppState;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.interfaces.AppStateListener;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.model.CaptureStats;
import com.emanuelef.remote_capture.views.AppSelectDialog;
import com.emanuelef.remote_capture.views.PrefSpinner;

public class StatusFragment extends Fragment implements AppStateListener, MenuProvider {
    private static final String TAG = "StatusFragment";
    private Handler mHandler;
    private Menu mMenu;
    private MenuItem mStartBtn;
    private MenuItem mStopBtn;
    private MenuItem mMenuSettings;
    private TextView mInterfaceInfo;
    private TextView mCollectorInfo;
    private TextView mCaptureStatus;
    private View mQuickSettings;
    private MainActivity mActivity;
    private SharedPreferences mPrefs;
    private TextView mFilterDescription;
    private SwitchCompat mAppFilterSwitch;
    private String mAppFilter;
    private TextView mFilterWarning;
    private AppSelectDialog mAppSelDialog;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        mActivity = (MainActivity) context;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        abortAppSelection();
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
        mHandler = new Handler(Looper.getMainLooper());
        mInterfaceInfo = view.findViewById(R.id.interface_info);
        mCollectorInfo = view.findViewById(R.id.collector_info);
        mCaptureStatus = view.findViewById(R.id.status_view);
        mQuickSettings = view.findViewById(R.id.quick_settings);
        mFilterWarning = view.findViewById(R.id.app_filter_warning);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        mAppFilter = Prefs.getAppFilter(mPrefs);

        PrefSpinner.init(view.findViewById(R.id.dump_mode_spinner),
                R.array.pcap_dump_modes, R.array.pcap_dump_modes_labels, R.array.pcap_dump_modes_descriptions,
                Prefs.PREF_PCAP_DUMP_MODE, Prefs.DEFAULT_DUMP_MODE);

        mAppFilterSwitch = view.findViewById(R.id.app_filter_switch);
        View filterRow = view.findViewById(R.id.app_filter_text);
        TextView filterTitle = filterRow.findViewById(R.id.title);
        mFilterDescription = filterRow.findViewById(R.id.description);

        // Needed to update the filter icon after mFilterDescription is measured
        final ViewTreeObserver vto = mFilterDescription.getViewTreeObserver();
        if(vto.isAlive()) {
            vto.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    refreshFilterInfo();

                    final ViewTreeObserver vto = mFilterDescription.getViewTreeObserver();

                    if(vto.isAlive()) {
                        vto.removeOnGlobalLayoutListener(this);
                        Log.d(TAG, "removeOnGlobalLayoutListener called");
                    }
                }
            });
        }

        filterTitle.setText(R.string.app_filter);

        mAppFilterSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if(isChecked) {
                if((mAppFilter == null) || (mAppFilter.isEmpty()))
                    openAppFilterSelector();
            } else
                setAppFilter(null);
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
        mCollectorInfo.setMovementMethod(LinkMovementMethod.getInstance());

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
        refreshStatus();
    }

    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        return false;
    }

    private void recheckFilterWarning() {
        boolean hasFilter = ((mAppFilter != null) && (!mAppFilter.isEmpty()));
        mFilterWarning.setVisibility((Prefs.getTlsDecryptionEnabled(mPrefs) && !hasFilter) ? View.VISIBLE : View.GONE);
    }

    private void refreshDecryptionStatus() {
        MitmReceiver.Status proxy_status = CaptureService.getMitmProxyStatus();
        Context ctx = getContext();

        if((proxy_status == MitmReceiver.Status.START_ERROR) && (ctx != null))
            Utils.showToastLong(ctx, R.string.mitm_addon_error);

        mInterfaceInfo.setText((proxy_status == MitmReceiver.Status.RUNNING) ? R.string.tls_decryption_running : R.string.tls_decryption_starting);
    }

    private void refreshFilterInfo() {
        Context context = getContext();
        if(context == null)
            return;

        if((mAppFilter == null) || (mAppFilter.isEmpty())) {
            mFilterDescription.setText(R.string.capture_all_apps);
            mFilterDescription.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            mAppFilterSwitch.setChecked(false);
            return;
        }

        mAppFilterSwitch.setChecked(true);

        AppDescriptor app = AppsResolver.resolveInstalledApp(context.getPackageManager(), mAppFilter, 0);
        String description;

        if(app == null)
            description = mAppFilter;
        else {
            description = app.getName() + " (" + app.getPackageName() + ")";
            int height = mFilterDescription.getMeasuredHeight();

            if((height > 0) && (app.getIcon() != null)) {
                Drawable drawable = Utils.scaleDrawable(context.getResources(), app.getIcon(), height, height);

                if(drawable != null)
                    mFilterDescription.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null);
            }
        }

        mFilterDescription.setText(description);
    }

    private void setAppFilter(AppDescriptor filter) {
        SharedPreferences.Editor editor = mPrefs.edit();
        mAppFilter = (filter != null) ? filter.getPackageName() : "";

        editor.putString(Prefs.PREF_APP_FILTER, mAppFilter);
        editor.apply();
        refreshFilterInfo();
        recheckFilterWarning();
    }

    private void onStatsUpdate(CaptureStats stats) {
        Log.d("MainReceiver", "Got StatsUpdate: bytes_sent=" + stats.pkts_sent + ", bytes_rcvd=" +
                stats.bytes_rcvd + ", pkts_sent=" + stats.pkts_sent + ", pkts_rcvd=" + stats.pkts_rcvd);

        mCaptureStatus.setText(Utils.formatBytes(stats.bytes_sent + stats.bytes_rcvd));
    }

    private void refreshPcapDumpInfo() {
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

            if(mActivity != null) {
                String fname = mActivity.getPcapFname();

                if(fname != null)
                    info = fname;
            }
            break;
        case UDP_EXPORTER:
            info = String.format(getResources().getString(R.string.collector_info),
                    CaptureService.getCollectorAddress(), CaptureService.getCollectorPort());
            break;
        }

        mCollectorInfo.setText(info);

        // Check if a filter is set
        if((mAppFilter != null) && (!mAppFilter.isEmpty())) {
            AppDescriptor app = AppsResolver.resolveInstalledApp(requireContext().getPackageManager(), mAppFilter, 0);

            if((app != null) && (app.getIcon() != null)) {
                // Rendering after mCollectorInfo.setText is deferred, so getMeasuredHeight must be postponed
                mHandler.post(() -> {
                    if(getContext() == null)
                        return;

                    int height = mCollectorInfo.getMeasuredHeight();
                    Drawable drawable = Utils.scaleDrawable(getResources(), app.getIcon(), height, height);

                    if(drawable != null)
                        mCollectorInfo.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null);
                });
            } else
                mCollectorInfo.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        } else
            mCollectorInfo.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
    }

    @Override
    public void appStateChanged(AppState state) {
        if(getContext() == null)
            return;

        if(mMenu != null) {
            if((state == AppState.running) || (state == AppState.stopping)) {
                mStartBtn.setVisible(false);
                mStopBtn.setEnabled(true);
                mStopBtn.setVisible(!CaptureService.isAlwaysOnVPN());
                mMenuSettings.setEnabled(false);
            } else { // ready || starting
                mStopBtn.setVisible(false);
                mStartBtn.setEnabled(true);
                mStartBtn.setVisible(!CaptureService.isAlwaysOnVPN());
                mMenuSettings.setEnabled(true);
            }
        }

        switch(state) {
            case ready:
                mCaptureStatus.setText(R.string.ready);
                mCollectorInfo.setVisibility(View.GONE);
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
                mCollectorInfo.setVisibility(View.VISIBLE);
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
                refreshPcapDumpInfo();
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
        mAppSelDialog = new AppSelectDialog((AppCompatActivity) requireActivity(), R.string.app_filter,
                new AppSelectDialog.AppSelectListener() {
            @Override
            public void onSelectedApp(AppDescriptor app) {
                abortAppSelection();
                setAppFilter(app);
            }

            @Override
            public void onAppSelectionAborted() {
                abortAppSelection();
                setAppFilter(null);
            }
        });
    }

    private void abortAppSelection() {
        if(mAppSelDialog != null) {
            mAppSelDialog.abort();
            mAppSelDialog = null;
        }
    }
}
