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

package com.emanuelef.remote_capture.activities;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.AppsResolver;
import com.emanuelef.remote_capture.Billing;
import com.emanuelef.remote_capture.BuildConfig;
import com.emanuelef.remote_capture.CaptureHelper;
import com.emanuelef.remote_capture.CaptureService;
import com.emanuelef.remote_capture.Log;
import com.emanuelef.remote_capture.PCAPdroid;
import com.emanuelef.remote_capture.PersistableUriPermission;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.Utils;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.CaptureSettings;
import com.emanuelef.remote_capture.model.CtrlPermissions;
import com.emanuelef.remote_capture.model.CaptureStats;
import com.emanuelef.remote_capture.model.Prefs;

import java.util.HashSet;

public class CaptureCtrl extends AppCompatActivity {
    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_STATUS = "get_status";
    public static final String ACTION_PEER_INFO = "get_peer_info";
    public static final String ACTION_NOTIFY_STATUS = "com.emanuelef.remote_capture.CaptureStatus";
    private static final String TAG = "CaptureCtrl";
    private static AppDescriptor mStarterApp = null; // the app which started the capture, may be unknown
    private static String mReceiverClass = null;
    private CaptureHelper mCapHelper;
    private CtrlPermissions mPermissions;

    private PersistableUriPermission persistableUriPermission;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Important: calls must occur in the following order:
        //  requestWindowFeature -> setContentView -> getInsetsController()
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.ctrl_consent);

        // define here since it calls registerForActivityResult
        persistableUriPermission = new PersistableUriPermission(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final WindowInsetsController insetsController = getWindow().getInsetsController();
            if (insetsController != null)
                insetsController.hide(WindowInsets.Type.statusBars());
        } else {
            getWindow().setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
        }
        getWindow().addFlags(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        super.onCreate(savedInstanceState);

        mCapHelper = new CaptureHelper(this, false);
        mCapHelper.setListener(success -> {
            setResult(success ? RESULT_OK : RESULT_CANCELED, null);
            finish();
        });

        Intent intent = getIntent();
        String action = intent.getStringExtra("action");

        if(action == null) {
            Log.e(TAG, "no action provided");
            abort();
            return;
        }

        if(action.equals(ACTION_PEER_INFO)) {
            getPeerInfo();
            return;
        }

        // Check if a control permission rule was set
        mPermissions = PCAPdroid.getInstance().getCtrlPermissions();
        AppDescriptor app = getCallingApp();
        if(app != null) {
            CtrlPermissions.ConsentType consent = mPermissions.getConsent(app.getPackageName());

            if(consent == CtrlPermissions.ConsentType.ALLOW) {
                processRequest(intent, action);
                return;
            } else if(consent == CtrlPermissions.ConsentType.DENY) {
                abort();
                return;
            }
        }

        if(isControlApp(action)) {
            processRequest(intent, action);
            return;
        }

        // Show authorization window
        findViewById(R.id.allow_btn).setOnClickListener(v -> controlAction(intent, action, true));
        findViewById(R.id.deny_btn).setOnClickListener(v -> controlAction(intent, action, false));

        if(app != null) {
            ((TextView)findViewById(R.id.app_name)).setText(app.getName());
            ((TextView)findViewById(R.id.app_package)).setText(app.getPackageName());
            ((ImageView)findViewById(R.id.app_icon)).setImageDrawable(app.getIcon());
        } else
            findViewById(R.id.caller_app).setVisibility(View.GONE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    Button btn = findViewById(R.id.allow_btn);
                    btn.setTextColor(0xFF0099CC);
                    btn.setEnabled(true);
                }, 1500);
    }

    private AppDescriptor getCallingApp() {
        String callp = getCallingPackage();
        return (callp != null) ? AppsResolver.resolveInstalledApp(getPackageManager(), callp, 0) : null;
    }

    private void controlAction(Intent intent, String action, boolean allow) {
        AppDescriptor app = getCallingApp();
        if(app != null) {
            boolean is_forever = ((RadioButton)findViewById(R.id.choice_forever)).isChecked();
            if(is_forever) {
                Log.d(TAG, (allow ? "Grant" : "Deny") + " forever to " + app.getPackageName());
                mPermissions.add(app.getPackageName(), allow ? CtrlPermissions.ConsentType.ALLOW : CtrlPermissions.ConsentType.DENY);
            }
        }

        if(!allow)
            abort();
        else
            processRequest(intent, action);
    }

    @Override
    protected void onDestroy() {
        mCapHelper = null;
        super.onDestroy();
    }

    private boolean isControlApp(@NonNull String action) {
        // By default, only the app which started the capture can perform other actions
        return !action.equals(ACTION_START) && (mStarterApp != null)
                && (mStarterApp.getPackageName().equals(getCallingPackage()));
    }

    @Override
    public void onStart() {
        super.onStart();
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        abort();
        super.onBackPressed();
    }

    private void abort(boolean show_toast) {
        if(show_toast)
            Utils.showToast(this, R.string.ctrl_consent_denied);
        setResult(RESULT_CANCELED, null);
        finish();
    }

    private void abort() {
        abort(true);
    }

    // Check if the capture is requesting to send traffic to a remote server.
    // For security reasons, this is only allowed if such server is already configured by
    // the user in the app prefs.
    // see also MainActivity.showRemoteServerAlert
    private String checkRemoteServerNotAllowed(CaptureSettings settings) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if((settings.dump_mode == Prefs.DumpMode.UDP_EXPORTER) &&
                !Utils.isLocalNetworkAddress(settings.collector_address) &&
                !Prefs.getCollectorIp(prefs).equals(settings.collector_address))
            return settings.collector_address;

        if(settings.socks5_enabled &&
                !Utils.isLocalNetworkAddress(settings.socks5_proxy_address) &&
                !Prefs.getSocks5ProxyHost(prefs).equals(settings.socks5_proxy_address))
            return settings.socks5_proxy_address;

        // ok
        return null;
    }

    private void processRequest(Intent req_intent, @NonNull String action) {
        Intent res = new Intent();
        Utils.showToast(this, R.string.ctrl_consent_allowed);

        if(action.equals(ACTION_START)) {
            mStarterApp = getCallingApp();
            mReceiverClass = req_intent.getStringExtra("broadcast_receiver");
            Log.d(TAG, "Starting capture, caller=" + mStarterApp);

            CaptureSettings settings = new CaptureSettings(this, req_intent);
            String disallowedServer = checkRemoteServerNotAllowed(settings);
            if(disallowedServer != null) {
                Utils.showToastLong(this, R.string.remote_server_warning, disallowedServer);
                abort();
                return;
            }

            if(!settings.pcap_uri.isEmpty()) {
                persistableUriPermission.checkPermission(settings.pcap_uri, settings.pcapng_format, granted_uri -> {
                    Log.d(TAG, "persistable uri granted? " + granted_uri);

                    if(granted_uri != null) {
                        settings.pcap_uri = granted_uri.toString();
                        mCapHelper.startCapture(settings);
                    } else
                        abort();
                });
            } else
                // will call the mCapHelper listener
                mCapHelper.startCapture(settings);
            return;
        } else if(action.equals(ACTION_STOP)) {
            Log.d(TAG, "Stopping capture");

            CaptureService.stopService();
            mStarterApp = null;

            // stopService returns immediately, need to wait for capture stop
            CaptureService.waitForCaptureStop();

            putStats(res, CaptureService.getStats());
        } else if(action.equals(ACTION_STATUS)) {
            Log.d(TAG, "Returning status");

            res.putExtra("running", CaptureService.isServiceActive());
            res.putExtra("version_name", BuildConfig.VERSION_NAME);
            res.putExtra("version_code", BuildConfig.VERSION_CODE);

            putStats(res, CaptureService.getStats());
        } else {
            Log.e(TAG, "unknown action: " + action);
            abort();
            return;
        }

        setResult(RESULT_OK, res);
        finish();
    }

    public static void notifyCaptureStopped(Context ctx, CaptureStats stats) {
        if(stats != null)
            Log.d(TAG, "notifyCaptureStopped: " + (stats.pkts_sent + stats.pkts_rcvd) + " pkts");

        if((mStarterApp != null) && (mReceiverClass != null)) {
            Log.d(TAG, "Notifying receiver");

            Intent intent = new Intent(ACTION_NOTIFY_STATUS);
            intent.putExtra("running", false);
            if(stats != null)
                putStats(intent, stats);
            intent.setComponent(new ComponentName(mStarterApp.getPackageName(), mReceiverClass));

            try {
                ctx.sendBroadcast(intent);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        mStarterApp = null;
        mReceiverClass = null;
    }

    private static void putStats(Intent intent, CaptureStats stats) {
        intent.putExtra("bytes_sent", stats.bytes_sent);
        intent.putExtra("bytes_rcvd", stats.bytes_rcvd);
        intent.putExtra("bytes_dumped", stats.pcap_dump_size);
        intent.putExtra("pkts_sent", stats.pkts_sent);
        intent.putExtra("pkts_rcvd", stats.pkts_rcvd);
        intent.putExtra("pkts_dropped", stats.pkts_dropped);
    }

    // A request sent from a debug build of PCAPdroid to a non-debug one
    private void getPeerInfo() {
        // Verify the peer app
        String package_name = getCallingPackage();
        if((package_name == null) || !package_name.equals(BuildConfig.APPLICATION_ID + ".debug")) {
            Log.w(TAG, "getPeerInfo: package name mismatch");
            abort(false);
            return;
        }

        Billing billing = Billing.newInstance(this);
        billing.setLicense(billing.getLicense());

        Intent res = new Intent();
        HashSet<String> purchased = new HashSet<>();

        for(String sku: Billing.ALL_SKUS) {
            if(billing.isPurchased(sku))
                purchased.add(sku);
        }

        res.putExtra("skus", purchased);

        setResult(RESULT_OK, res);
        finish();
    }
}
