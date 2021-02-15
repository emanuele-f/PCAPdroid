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
 * Copyright 2020 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.app.Service;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.format.Formatter;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class Utils {
    static String formatBytes(long bytes) {
        long divisor;
        String suffix;
        if(bytes < 1024) return bytes + " B";

        if(bytes < 1024*1024)               { divisor = 1024;           suffix = "KB"; }
        else if(bytes < 1024*1024*1024)     { divisor = 1024*1024;      suffix = "MB"; }
        else                                { divisor = 1024*1024*1024; suffix = "GB"; }

        return String.format("%.1f %s", ((float)bytes) / divisor, suffix);
    }

    static String formatPkts(long pkts) {
        long divisor;
        String suffix;
        if(pkts < 1000) return Long.toString(pkts);

        if(pkts < 1000*1000)               { divisor = 1000;           suffix = "K"; }
        else if(pkts < 1000*1000*1000)     { divisor = 1000*1000;      suffix = "M"; }
        else                               { divisor = 1000*1000*1000; suffix = "G"; }

        return String.format("%.1f %s", ((float)pkts) / divisor, suffix);
    }

    static String formatNumber(Context context, long num) {
        Locale locale = context.getResources().getConfiguration().locale;
        return String.format(locale, "%,d", num);
    }

    static String formatDuration(long seconds) {
        if(seconds == 0)
            return "< 1 s";
        else if(seconds < 60)
            return String.format("%d s", seconds);
        else if(seconds < 3600)
            return String.format("> %d m", seconds / 60);
        else
            return String.format("> %d h", seconds / 3600);
    }

    static List<AppDescriptor> getInstalledApps(Context context) {
        PackageManager pm = context.getPackageManager();
        List<AppDescriptor> apps = new ArrayList<>();
        List<PackageInfo> packs = pm.getInstalledPackages(0);

        // Add the "No Filter" app
        Drawable icon = ContextCompat.getDrawable(context, android.R.color.transparent);
        apps.add(new AppDescriptor("", icon, context.getResources().getString(R.string.no_filter), -1, false));

        Log.d("APPS", "num apps (system+user): " + packs.size());
        long tstart = now();

        for (int i = 0; i < packs.size(); i++) {
            PackageInfo p = packs.get(i);
            boolean is_system = (p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

            String package_name = p.applicationInfo.packageName;

            if(!package_name.equals("com.emanuelef.remote_capture")) {
                String appName = p.applicationInfo.loadLabel(pm).toString();

                // NOTE: this call is expensive
                icon = p.applicationInfo.loadIcon(pm);

                int uid = p.applicationInfo.uid;
                apps.add(new AppDescriptor(appName, icon, package_name, uid, is_system));

                Log.d("APPS", appName + " - " + package_name + " [" + uid + "]" + (is_system ? " - SYS" : " - USR"));
            }
        }

        Log.d("APPS", packs.size() + " apps loaded in " + (now() - tstart) +" seconds");
        return apps;
    }

    static String proto2str(int proto) {
        switch(proto) {
            case 6:     return "TCP";
            case 17:    return "UDP";
            case 1:     return "ICMP";
            default:    return(Integer.toString(proto));
        }
    }

    static String getDnsServer(Context context) {
        ConnectivityManager conn = (ConnectivityManager) context.getSystemService(Service.CONNECTIVITY_SERVICE);

        if(conn != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Network net = conn.getActiveNetwork();

                if (net != null) {
                    LinkProperties props = conn.getLinkProperties(net);

                    if(props != null) {
                        List<InetAddress> dns_servers = props.getDnsServers();

                        for(InetAddress addr : dns_servers) {
                            // Get the first IPv4 DNS server
                            if(addr instanceof Inet4Address) {
                                return addr.getHostAddress();
                            }
                        }
                    }
                }
            }
        }

        // Fallback
        return "8.8.8.8";
    }

    // https://gist.github.com/mathieugerard/0de2b6f5852b6b0b37ed106cab41eba1
    static String getLocalWifiIpAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        WifiInfo connInfo = wifiManager.getConnectionInfo();

        if(connInfo != null) {
            int ipAddress = connInfo.getIpAddress();

            if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
                ipAddress = Integer.reverseBytes(ipAddress);
            }

            byte[] ipByteArray = BigInteger.valueOf(ipAddress).toByteArray();

            String ipAddressString;
            try {
                ipAddressString = InetAddress.getByAddress(ipByteArray).getHostAddress();
            } catch (UnknownHostException ex) {
                return(null);
            }

            return ipAddressString;
        }

        return(null);
    }

    static String getLocalIPAddress(Context context) {
        InetAddress vpn_ip;

        try {
            vpn_ip = InetAddress.getByName(CaptureService.VPN_IP_ADDRESS);
        } catch (UnknownHostException e) {
            return "";
        }

        // try to get the WiFi IP address first
        String wifi_ip = getLocalWifiIpAddress(context);

        if(wifi_ip != null) {
            Log.d("getLocalIPAddress", "Using WiFi IP: " + wifi_ip);
            return wifi_ip;
        }

        // otherwise search for other network interfaces
        // https://stackoverflow.com/questions/6064510/how-to-get-ip-address-of-the-device-from-code
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if(!intf.isVirtual()) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress()
                                && addr.isSiteLocalAddress() /* Exclude public IPs */
                                && !addr.equals(vpn_ip)) {
                            String sAddr = addr.getHostAddress();
                            boolean isIPv4 = sAddr.indexOf(':') < 0;

                            if (isIPv4) {
                                Log.d("getLocalIPAddress", "Using interface '" + intf.getName() + "' IP: " + sAddr);
                                return sAddr;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) { }

        // Fallback
        Log.d("getLocalIPAddress", "Using fallback IP");
        return "127.0.0.1";
    }

    // returns current timestamp in seconds
    public static long now() {
        Calendar calendar = Calendar.getInstance();
        return(calendar.getTimeInMillis() / 1000);
    }

    static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    static boolean hasVPNRunning(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if(cm != null) {
            Network[] networks = cm.getAllNetworks();

            for(Network net : networks) {
                NetworkCapabilities cap = cm.getNetworkCapabilities(net);

                if ((cap != null) && cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    Log.d("hasVPNRunning", "detected VPN connection: " + net.toString());
                    return true;
                }
            }
        }

        return false;
    }

    static void showToast(Context context, int id) {
        String msg = context.getResources().getString(id);
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }
}
