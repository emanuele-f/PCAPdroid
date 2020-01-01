package com.emanuelef.remote_capture;

import android.app.Service;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.util.Log;

import java.net.InetAddress;
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

    public static String formatPkts(long pkts) {
        long divisor;
        String suffix;
        if(pkts < 1000) return Long.toString(pkts);

        if(pkts < 1000*1000)               { divisor = 1000;           suffix = "K"; }
        else if(pkts < 1000*1000*1000)     { divisor = 1000*1000;      suffix = "M"; }
        else                               { divisor = 1000*1000*1000; suffix = "G"; }

        return String.format("%.1f %s", ((float)pkts) / divisor, suffix);
    }

    public static String formatDuration(long seconds) {
        if(seconds == 0)
            return "< 1 s";
        else if(seconds < 60)
            return String.format("%d s", seconds);
        else if(seconds < 3600)
            return String.format("> %d m", seconds / 60);
        else
            return String.format("> %d h", seconds / 3600);
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

    public static String proto2str(int proto) {
        switch(proto) {
            case 6:     return "TCP";
            case 17:    return "UDP";
            case 1:     return "ICMP";
            default:    return(Integer.toString(proto));
        }
    }

    public static String getDnsServer(Context context) {
        ConnectivityManager conn = (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Network net = conn.getActiveNetwork();

            if(net != null) {
                List<InetAddress> dns_server = conn.getLinkProperties(net).getDnsServers();

                for(InetAddress server : dns_server) {
                    return server.getHostAddress();
                }
            }
        }

        // Fallback
        return "8.8.8.8";
    }

    public static long now() {
        Calendar calendar = Calendar.getInstance();
        return(calendar.getTimeInMillis() / 1000);
    }
}
