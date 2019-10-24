package com.emanuelef.remote_capture;

import android.content.Context;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;

import java.util.List;

// TODO add searchbar
// https://stackoverflow.com/questions/31085086/how-to-implement-floating-searchwidget-android

class AppsView extends ListView {
    List<AppDescriptor> mInstalledApps;

    public interface OnSelectedAppListener {
        void onSelectedApp(AppDescriptor app);
    }

    public AppsView(Context context, List<AppDescriptor> installedApps) {
        super(context);

        mInstalledApps = installedApps;
        AppAdapter installedAppAdapter = new AppAdapter(getContext(), mInstalledApps);

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
}
