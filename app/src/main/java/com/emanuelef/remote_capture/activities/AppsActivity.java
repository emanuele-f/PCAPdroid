package com.emanuelef.remote_capture.activities;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.emanuelef.remote_capture.AppsLoader;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.model.AppDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AppsActivity extends AppCompatActivity implements AppsLoadListener {
    private static final String TAG = "AppsActivity";
    private Map<Integer, AppDescriptor> mInstalledApps;
    private List<AppsLoadListener> mAppsListeners;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAppsListeners = new ArrayList<>();

        setContentView(R.layout.apps_activity);

        new AppsLoader(this)
                .setAppsLoadListener(this)
                .loadAllApps();
    }

    @Override
    public void onAppsInfoLoaded(Map<Integer, AppDescriptor> apps) {
        mInstalledApps = apps;

        for(AppsLoadListener listener: mAppsListeners)
            listener.onAppsInfoLoaded(apps);
    }

    @Override
    public void onAppsIconsLoaded(Map<Integer, AppDescriptor> apps) {
        mInstalledApps = apps;

        for(AppsLoadListener listener: mAppsListeners)
            listener.onAppsIconsLoaded(apps);
    }

    public void addAppLoadListener(AppsLoadListener l) {
        mAppsListeners.add(l);
    }

    public void removeAppLoadListener(AppsLoadListener l) {
        mAppsListeners.remove(l);
    }

    public Map<Integer, AppDescriptor> getApps() {
        return mInstalledApps;
    }
}
