package com.emanuelef.remote_capture;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class Utils {
    public static String formatBytes(long bytes) {
        long divisor;
        String suffix;
        if(bytes < 1024) return bytes + " B";

        if(bytes < 1024*1024)               { divisor = 1024;           suffix = "KB"; }
        else if(bytes < 1024*1024*1024)     { divisor = 1024*1024;      suffix = "MB"; }
        else                                { divisor = 1024*1024*1024; suffix = "GB"; }

        return String.format("%.1f %s", ((float)bytes) / divisor, suffix);
    }

    public static List<AppDescriptor> getInstalledApps(Context context) {
        PackageManager pm = context.getPackageManager();
        List<AppDescriptor> apps = new ArrayList<>();
        List<PackageInfo> packs = pm.getInstalledPackages(0);

        // Add the "No Filter" app
        apps.add(new AppDescriptor("", context.getResources().getDrawable(android.R.drawable.ic_menu_view), context.getResources().getString(R.string.no_filter), -1));

        for (int i = 0; i < packs.size(); i++) {
            PackageInfo p = packs.get(i);

            if((p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                String packages = p.applicationInfo.packageName;

                if(!packages.equals("com.emanuelef.remote_capture")) {
                    String appName = p.applicationInfo.loadLabel(pm).toString();
                    Drawable icon = p.applicationInfo.loadIcon(pm);
                    int uid = p.applicationInfo.uid;
                    apps.add(new AppDescriptor(appName, icon, packages, uid));

                    Log.d("APPS", appName + " - " + packages + " [" + uid + "]");
                }
            }
        }
        return apps;
    }

    public static long now() {
        Calendar calendar = Calendar.getInstance();
        return(calendar.getTimeInMillis() / 1000);
    }
}
