package com.emanuelef.remote_capture.activities;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.emanuelef.remote_capture.AppsLoader;
import com.emanuelef.remote_capture.R;
import com.emanuelef.remote_capture.fragments.AppsFragment;
import com.emanuelef.remote_capture.fragments.ConnectionsFragment;
import com.emanuelef.remote_capture.interfaces.AppsLoadListener;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class InspectorActivity extends AppCompatActivity implements AppsLoadListener {
    private static final String TAG = "InspectorActivity";
    private ViewPager2 mPager;
    private TabLayout mTabLayout;
    private Map<Integer, AppDescriptor> mInstalledApps;
    private List<AppsLoadListener> mAppsListeners;

    private static final int POS_APPS = 0;
    private static final int POS_CONNECTIONS = 1;
    private static final int TOTAL_COUNT = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.inspector_activity);

        mTabLayout = findViewById(R.id.inspector_tablayout);
        mPager = findViewById(R.id.inspector_pager);
        mAppsListeners = new ArrayList<>();

        setupTabs();

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

    private static class MyStateAdapter extends FragmentStateAdapter {
        MyStateAdapter(final FragmentActivity fa) { super(fa); }

        @NonNull
        @Override
        public Fragment createFragment(int position) {
            Log.d(TAG, "createFragment");

            switch (position) {
                default: // Deliberate fall-through to status tab
                case POS_APPS:
                    return new AppsFragment();
                case POS_CONNECTIONS:
                    return new ConnectionsFragment();
            }
        }

        @Override
        public int getItemCount() {  return TOTAL_COUNT;  }
    }

    private void setupTabs() {
        final MyStateAdapter stateAdapter = new MyStateAdapter(this);
        mPager.setAdapter(stateAdapter);

        new TabLayoutMediator(mTabLayout, mPager, (tab, position) -> {
            switch (position) {
                default: // Deliberate fall-through to status tab
                case POS_APPS:
                    tab.setText(R.string.apps);
                    break;
                case POS_CONNECTIONS:
                    tab.setText(R.string.connections_view);
                    break;
            }
        }).attach();
    }

    public void filterByUid(int uid) {
        Bundle bundle = new Bundle();
        bundle.putInt("uid", uid);
        getSupportFragmentManager().setFragmentResult("appFilter", bundle);

        // Switch to the connections view
        mPager.setCurrentItem(POS_CONNECTIONS);
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
