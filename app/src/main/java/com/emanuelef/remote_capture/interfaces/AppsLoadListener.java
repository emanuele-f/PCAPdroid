package com.emanuelef.remote_capture.interfaces;

import com.emanuelef.remote_capture.model.AppDescriptor;

import java.util.Map;

public interface AppsLoadListener {
    // uid -> AppDescriptor
    void onAppsInfoLoaded(Map<Integer, AppDescriptor> apps);
    void onAppsIconsLoaded(Map<Integer, AppDescriptor> apps);
}
