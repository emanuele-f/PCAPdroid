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

package com.emanuelef.remote_capture;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.activities.ErrorActivity;
import com.emanuelef.remote_capture.model.Blocklist;
import com.emanuelef.remote_capture.model.CtrlPermissions;
import com.emanuelef.remote_capture.model.MatchList;
import com.emanuelef.remote_capture.model.Prefs;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Set;

import cat.ereza.customactivityoncrash.config.CaocConfig;

/* The PCAPdroid app class.
 * This class is instantiated before anything else, and its reference is stored in the mInstance.
 * Global state is stored into this class via singletons. Contrary to static singletons, this does
 * not require passing the localized Context to the singletons getters methods.
 *
 * IMPORTANT: do not override getResources() with mLocalizedContext, otherwise the Webview used for ads will crash!
 * https://stackoverflow.com/questions/56496714/android-webview-causing-runtimeexception-at-webviewdelegate-getpackageid
 */
public class PCAPdroid extends Application {
    private static final String TAG = "PCAPdroid";
    private MatchList mVisMask;
    private MatchList mMalwareWhitelist;
    private MatchList mFirewallWhitelist;
    private MatchList mDecryptionList;
    private Blocklist mBlocklist;
    private Blacklists mBlacklists;
    private CtrlPermissions mCtrlPermissions;
    private Context mLocalizedContext;
    private boolean mIsDecryptingPcap = false;
    private static WeakReference<PCAPdroid> mInstance;
    protected static boolean isUnderTest = false;

    @Override
    public void onCreate() {
        super.onCreate();

        if(!isUnderTest())
            Log.init(getCacheDir().getAbsolutePath());

        Utils.BuildType buildtp = Utils.getVerifiedBuild(this);
        Log.i(TAG, "Build type: " + buildtp);

        CaocConfig.Builder builder = CaocConfig.Builder.create();
        if((buildtp == Utils.BuildType.PLAYSTORE) || (buildtp == Utils.BuildType.UNKNOWN)) {
            // Disabled to get reports via the Android system reporting facility and for unsupported builds
            builder.enabled(false);
        } else {
            builder.errorDrawable(R.drawable.ic_app_crash)
                    .errorActivity(ErrorActivity.class);
        }
        builder.apply();

        mInstance = new WeakReference<>(this);
        mLocalizedContext = createConfigurationContext(Utils.getLocalizedConfig(this));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String theme = prefs.getString(Prefs.PREF_APP_THEME, "");

        if("".equals(theme)) {
            if(Utils.isTv(this)) {
                // Use the dark theme by default on Android TV
                theme = "dark";
                prefs.edit().putString(Prefs.PREF_APP_THEME, theme).apply();
            } else
                theme = "system";
        }
        Utils.setAppTheme(theme);

        // Listen to package events
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) {
                    boolean newInstall = !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                    String packageName = intent.getData().getSchemeSpecificPart();
                    Log.d(TAG, "ACTION_PACKAGE_ADDED [new=" + newInstall + "]: " + packageName);

                    if(newInstall)
                        checkUidMapping(packageName);
                } else if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                    boolean isUpdate = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                    String packageName = intent.getData().getSchemeSpecificPart();
                    Log.d(TAG, "ACTION_PACKAGE_REMOVED [update=" + isUpdate + "]: " + packageName);

                    if(!isUpdate) {
                        checkUidMapping(packageName);
                        removeUninstalledAppsFromAppFilter();
                    }
                }
            }
        }, filter);

        removeUninstalledAppsFromAppFilter();
    }

    public static @NonNull PCAPdroid getInstance() {
        return mInstance.get();
    }

    public static boolean isUnderTest() {
        return isUnderTest;
    }

    public MatchList getVisualizationMask() {
        if(mVisMask == null)
            mVisMask = new MatchList(mLocalizedContext, Prefs.PREF_VISUALIZATION_MASK);

        return mVisMask;
    }

    public Blacklists getBlacklists() {
        if(mBlacklists == null)
            mBlacklists = new Blacklists(mLocalizedContext);
        return mBlacklists;
    }

    public MatchList getMalwareWhitelist() {
        if(mMalwareWhitelist == null)
            mMalwareWhitelist = new MatchList(mLocalizedContext, Prefs.PREF_MALWARE_WHITELIST);
        return mMalwareWhitelist;
    }

    public Blocklist getBlocklist() {
        if(mBlocklist == null)
            mBlocklist = new Blocklist(mLocalizedContext);
        return mBlocklist;
    }

    // use some safe defaults to guarantee basic services
    private void initFirewallWhitelist() {
        mFirewallWhitelist.addApp(0 /* root */);
        mFirewallWhitelist.addApp(1000 /* android */);
        mFirewallWhitelist.addApp(getPackageName() /* PCAPdroid */);

        // see also https://github.com/microg/GmsCore/issues/1508#issuecomment-876269198
        mFirewallWhitelist.addApp("com.google.android.gms" /* Google Play Services */);
        mFirewallWhitelist.addApp("com.google.android.gsf" /* Google Services Framework (push notifications) */);
        mFirewallWhitelist.addApp("com.google.android.ims" /* Carrier Services */);
        mFirewallWhitelist.addApp("com.sec.spp.push" /* Samsung Push Service */);
        mFirewallWhitelist.save();
    }

    private void checkUidMapping(String pkg) {
        if(mVisMask != null)
            mVisMask.uidMappingChanged(pkg);

        // When an app is installed/uninstalled, recheck the UID mappings.
        // In particular:
        //  - On app uninstall, invalidate any package_name -> UID mapping
        //  - On app install, add the new package_name -> UID mapping
        if((mMalwareWhitelist != null) && mMalwareWhitelist.uidMappingChanged(pkg))
            CaptureService.reloadMalwareWhitelist();

        if((mFirewallWhitelist != null) && mFirewallWhitelist.uidMappingChanged(pkg)) {
            if(CaptureService.isServiceActive())
                CaptureService.requireInstance().reloadFirewallWhitelist();
        }

        if((mDecryptionList != null) && mDecryptionList.uidMappingChanged(pkg))
            CaptureService.reloadDecryptionList();

        if((mBlocklist != null) && mBlocklist.uidMappingChanged(pkg)) {
            if(CaptureService.isServiceActive())
                CaptureService.requireInstance().reloadBlocklist();
        }
    }

    private void removeUninstalledAppsFromAppFilter() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        Set<String> filter = Prefs.getAppFilter(prefs);
        ArrayList<String> to_remove = new ArrayList<>();
        PackageManager pm = getPackageManager();

        for (String package_name: filter) {
            try {
                Utils.getPackageInfo(pm, package_name, 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.i(TAG, "Package " + package_name + " uninstalled, removing from app filter");
                to_remove.add(package_name);
            }
        }

        if (!to_remove.isEmpty()) {
            filter.removeAll(to_remove);
            prefs.edit()
                    .putStringSet(Prefs.PREF_APP_FILTER, filter)
                    .apply();
        }
    }

    public MatchList getFirewallWhitelist() {
        if(mFirewallWhitelist == null) {
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
            mFirewallWhitelist = new MatchList(mLocalizedContext, Prefs.PREF_FIREWALL_WHITELIST);

            if(!Prefs.isFirewallWhitelistInitialized(prefs)) {
                initFirewallWhitelist();
                Prefs.setFirewallWhitelistInitialized(prefs);
            }
        }
        return mFirewallWhitelist;
    }

    public MatchList getDecryptionList() {
        if(mDecryptionList == null)
            mDecryptionList = new MatchList(mLocalizedContext, Prefs.PREF_DECRYPTION_LIST);

        return mDecryptionList;
    }

    public CtrlPermissions getCtrlPermissions() {
        if(mCtrlPermissions == null)
            mCtrlPermissions = new CtrlPermissions(this);
        return mCtrlPermissions;
    }

    public void setIsDecryptingPcap(boolean val) {
        mIsDecryptingPcap = val;
    }

    public boolean isDecryptingPcap() {
        return mIsDecryptingPcap;
    }
}
