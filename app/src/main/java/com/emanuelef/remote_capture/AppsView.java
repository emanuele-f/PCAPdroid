package com.emanuelef.remote_capture;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.List;

// TODO add searchbar
// https://stackoverflow.com/questions/31085086/how-to-implement-floating-searchwidget-android

class AppsView extends ListView {
    public interface OnSelectedAppListener {
        void onSelectedApp(AppDescriptor app);
    }

    public AppsView(Context context) {
        super(context);

        List<AppDescriptor> installedApps = getInstalledApps();
        AppAdapter installedAppAdapter = new AppAdapter(getContext(), installedApps);

        setAdapter(installedAppAdapter);
    }

    public void setSelectedAppListener(final OnSelectedAppListener listener) {
        setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                AppDescriptor app = ((AppDescriptor) getAdapter().getItem(i));
                listener.onSelectedApp(app);
            }
        });
    }

    private List<AppDescriptor> getInstalledApps() {
        PackageManager pm = getContext().getPackageManager();
        List<AppDescriptor> apps = new ArrayList<>();
        List<PackageInfo> packs = pm.getInstalledPackages(0);

        // Add the "No Filter" app
        apps.add(new AppDescriptor("", getResources().getDrawable(android.R.drawable.ic_menu_view), getResources().getString(R.string.no_filter), -1));

        for (int i = 0; i < packs.size(); i++) {
            PackageInfo p = packs.get(i);

            if((p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                String appName = p.applicationInfo.loadLabel(pm).toString();
                Drawable icon = p.applicationInfo.loadIcon(pm);
                String packages = p.applicationInfo.packageName;
                int uid = p.applicationInfo.uid;
                apps.add(new AppDescriptor(appName, icon, packages, uid));
            }
        }
        return apps;
    }
}
