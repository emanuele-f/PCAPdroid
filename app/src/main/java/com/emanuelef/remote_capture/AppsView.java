package com.emanuelef.remote_capture;

import android.content.Context;
import android.view.View;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

// TODO add searchbar
// https://stackoverflow.com/questions/31085086/how-to-implement-floating-searchwidget-android

class AppsView extends RecyclerView {
    List<AppDescriptor> mInstalledApps;

    public interface OnSelectedAppListener {
        void onSelectedApp(AppDescriptor app);
    }

    public AppsView(Context context, List<AppDescriptor> installedApps) {
        super(context);

        mInstalledApps = installedApps;
        setLayoutManager(new LinearLayoutManager(context));
        setHasFixedSize(true);
    }

    public void setSelectedAppListener(final OnSelectedAppListener listener) {
        AppAdapter installedAppAdapter = new AppAdapter(getContext(), mInstalledApps, new OnClickListener() {
            @Override
            public void onClick(View view) {
                int itemPosition = getChildLayoutPosition(view);
                AppDescriptor app = mInstalledApps.get(itemPosition);
                listener.onSelectedApp(app);
            }
        });

        setAdapter(installedAppAdapter);
    }
}
