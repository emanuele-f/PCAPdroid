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
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.AppsLoader;
import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.adapters.PrefSpinnerAdapter;
import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.AppState;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.activities.MainActivity;
import com.emanuelef.remote_capture.activities.StatsActivity;
import com.emanuelef.remote_capture.interfaces.AppStateListener;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.model.VPNStats;
import com.emanuelef.remote_capture.views.AppsListView;
import com.emanuelef.remote_capture.views.PrefSpinner;

import java.util.ArrayList;
import java.util.List;

public class StatusFragment extends Fragment implements AppStateListener, AppsLoadListener {
    private static final String TAG = "StatusFragment";
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
    private BroadcastReceiver mReceiver;
    private TextView mFilterDescription;
    private SwitchCompat mAppFilterSwitch;
    private String mAppFilter;
    private TextView mEmptyAppsView;
    private TextView mFilterWarning;
    AppsListView mOpenAppsList;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);

        mActivity = (MainActivity) context;
    }

    @Override
    public void onDestroy() {
        mActivity.setAppStateListener(null);
        mActivity = null;
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshStatus();

        /* Register for stats update */
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                processStatsUpdateIntent(intent);
            }
        };

        LocalBroadcastManager.getInstance(requireContext())
                .registerReceiver(mReceiver, new IntentFilter(CaptureService.ACTION_STATS_DUMP));
    }

    @Override
    public void onPause() {
        super.onPause();

        if(mReceiver != null) {
            LocalBroadcastManager.getInstance(requireContext())
                    .unregisterReceiver(mReceiver);
            mReceiver = null;
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        setHasOptionsMenu(true);
        return inflater.inflate(R.layout.status, container, false);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
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

        PrefSpinner.init(view.findViewById(R.id.payload_mode),
                R.array.payload_modes, R.array.payload_modes_labels, R.array.payload_modes_descriptions,
                Prefs.PREF_PAYLOAD_MODE, Prefs.DEFAULT_PAYLOAD_MODE);

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

        // Make URLs clickable
        mCollectorInfo.setMovementMethod(LinkMovementMethod.getInstance());

        /* Important: call this after all the fields have been initialized */
        mActivity.setAppStateListener(this);
        refreshStatus();
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.main_menu, menu);

        mMenu = menu;
        mStartBtn = mMenu.findItem(R.id.action_start);
        mStopBtn = mMenu.findItem(R.id.action_stop);
        mMenuSettings = mMenu.findItem(R.id.action_settings);
        refreshStatus();
    }

    private void recheckFilterWarning() {
        boolean hasFilter = ((mAppFilter != null) && (!mAppFilter.isEmpty()));
        mFilterWarning.setVisibility((Prefs.getTlsDecryptionEnabled(mPrefs) && !hasFilter) ? View.VISIBLE : View.GONE);
    }

    private void refreshFilterInfo() {
        if((mAppFilter == null) || (mAppFilter.isEmpty())) {
            mFilterDescription.setText(R.string.capture_all_apps);
            mFilterDescription.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            mAppFilterSwitch.setChecked(false);
            return;
        }

        mAppFilterSwitch.setChecked(true);

        AppDescriptor app = AppsResolver.resolve(requireContext().getPackageManager(), mAppFilter, 0);
        String description;

        if(app == null)
            description = mAppFilter;
        else {
            description = app.getName() + " (" + app.getPackageName() + ")";
            int height = mFilterDescription.getMeasuredHeight();

            if((height > 0) && (app.getIcon() != null)) {
                Drawable drawable = Utils.scaleDrawable(getResources(), app.getIcon(), height, height);

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

    private void processStatsUpdateIntent(Intent intent) {
        VPNStats stats = (VPNStats) intent.getSerializableExtra("value");

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
            AppDescriptor app = AppsResolver.resolve(requireContext().getPackageManager(), mAppFilter, 0);

            if((app != null) && (app.getIcon() != null)) {
                int height = mCollectorInfo.getMeasuredHeight();
                Drawable drawable = Utils.scaleDrawable(getResources(), app.getIcon(), height, height);

                if(drawable != null)
                    mCollectorInfo.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null);
            } else
                mCollectorInfo.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        } else
            mCollectorInfo.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
    }

    @Override
    public void appStateChanged(AppState state) {
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

                if(CaptureService.isCapturingAsRoot()) {
                    CaptureService service = CaptureService.requireInstance();
                    String capiface = service.getCaptureInterface();

                    if(capiface.equals("@inet"))
                        capiface = getString(R.string.internet);
                    else if(capiface.equals("any"))
                        capiface = getString(R.string.all_interfaces);

                    mInterfaceInfo.setText(String.format(getResources().getString(R.string.capturing_from), capiface));
                    mInterfaceInfo.setVisibility(View.VISIBLE);
                }

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
        Dialog dialog = Utils.getAppSelectionDialog(mActivity, new ArrayList<>(), this::setAppFilter);
        dialog.setOnCancelListener(dialog1 -> setAppFilter(null));
        dialog.setOnDismissListener(dialog1 -> {
            mOpenAppsList = null;
            mEmptyAppsView = null;
        });

        dialog.show();

        // NOTE: run this after dialog.show
        mOpenAppsList = (AppsListView) dialog.findViewById(R.id.apps_list);
        mEmptyAppsView = dialog.findViewById(R.id.no_apps);
        mEmptyAppsView.setText(R.string.loading_apps);

        (new AppsLoader((AppCompatActivity) requireActivity()))
                .setAppsLoadListener(this)
                .loadAllApps();
    }

    @Override
    public void onAppsInfoLoaded(List<AppDescriptor> installedApps) {
        if(mOpenAppsList == null)
            return;

        mEmptyAppsView.setText(R.string.no_apps);

        // Load the apps/icons
        Log.d(TAG, "loading " + installedApps.size() +" apps in dialog, icons=" + installedApps);
        mOpenAppsList.setApps(installedApps);
    }
}
