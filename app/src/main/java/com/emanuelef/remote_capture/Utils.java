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
 * Copyright 2020-22 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.InetAddresses;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.StyleSpan;
import android.util.Patterns;
import android.view.Display;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.ImageView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SearchView;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.text.HtmlCompat;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.interfaces.TextAdapter;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.ConnectionDescriptor;
import com.emanuelef.remote_capture.model.Prefs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.net.ssl.HttpsURLConnection;

public class Utils {
    static final String TAG = "Utils";
    public static final String INTERACT_ACROSS_USERS = "android.permission.INTERACT_ACROSS_USERS";
    public static final String PCAPDROID_WEBSITE = "https://pcapdroid.org";
    public static final int PER_USER_RANGE = 100000;
    public static final int UID_UNKNOWN = -1;
    public static final int UID_NO_FILTER = -2;
    public static final int LOW_HEAP_THRESHOLD = 10485760 /* 10 MB */;
    private static Boolean rootAvailable = null;
    private static Locale primaryLocale = null;
    private static String[] l7Protocols = null;

    public enum BuildType {
        UNKNOWN,
        DEBUG,
        GITHUB,     // Github release
        FDROID,     // F-droid release
        PLAYSTORE,  // Google play release
    }

    public enum PrivateDnsMode {
        DISABLED,
        OPPORTUNISTIC,
        STRICT;

        @NonNull
        @Override
        public String toString() {
            return super.toString().toLowerCase();
        }
    }

    public static String[] list2array(List<String> l) {
        return l.toArray(new String[0]);
    }

    public static String formatBytes(long bytes) {
        long divisor;
        String suffix;
        if(bytes < 1024) return bytes + " B";

        if(bytes < 1024*1024)               { divisor = 1024;           suffix = "KB"; }
        else if(bytes < 1024*1024*1024)     { divisor = 1024*1024;      suffix = "MB"; }
        else                                { divisor = 1024*1024*1024; suffix = "GB"; }

        return String.format("%.1f %s", ((float)bytes) / divisor, suffix);
    }

    public static String formatIntShort(long val) {
        long divisor;
        String suffix;
        if(val < 1000) return Long.toString(val);

        if(val < 1000*1000)                { divisor = 1000;           suffix = "K"; }
        else if(val < 1000*1000*1000)      { divisor = 1000*1000;      suffix = "M"; }
        else                               { divisor = 1000*1000*1000; suffix = "G"; }

        return String.format("%.1f %s", ((float)val) / divisor, suffix);
    }

    @SuppressWarnings("deprecation")
    public static Locale getPrimaryLocale(Context context) {
        if(primaryLocale == null) {
            Configuration config = context.getResources().getConfiguration();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                primaryLocale = config.getLocales().get(0);
            else
                primaryLocale = config.locale;
        }

        return primaryLocale;
    }

    @SuppressWarnings("deprecation")
    public static int getSmallerDisplayDimension(Context ctx) {
        WindowManager manager = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics windowMetrics = manager.getCurrentWindowMetrics();
            return Math.min(windowMetrics.getBounds().width(), windowMetrics.getBounds().width());
        } else {
            Display display = manager.getDefaultDisplay();
            Point point = new Point();
            display.getSize(point);
            return Math.min(point.x, point.y);
        }
    }

    public static String getCountryName(Context context, String country_code) {
        Locale cur_locale = getPrimaryLocale(context);
        return(new Locale(cur_locale.getCountry(), country_code)).getDisplayCountry();
    }

    public static boolean isRTL(Context ctx) {
        String locale_name = getPrimaryLocale(ctx).getDisplayName();
        if(locale_name.isEmpty())
            return false;

        final int direction = Character.getDirectionality(locale_name.charAt(0));
        return direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
    }

    public static String formatNumber(Context context, long num) {
        Locale locale = getPrimaryLocale(context);
        return String.format(locale, "%,d", num);
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

    public static String formatEpochShort(Context context, long epoch) {
        if(epoch == 0)
            return "-";

        long now = Utils.now();
        Locale locale = getPrimaryLocale(context);

        if((now - epoch) < (24 * 3600)) {
            final DateFormat fmt = new SimpleDateFormat("HH:mm:ss", locale);
            return fmt.format(new Date(epoch * 1000));
        }

        DateFormat fmt = new SimpleDateFormat("dd MMM, HH:mm:ss", locale);
        return fmt.format(new Date(epoch * 1000));
    }

    public static String formatEpochMin(Context context, long epoch) {
        if(epoch == 0)
            return "-";

        long now = Utils.now();
        Locale locale = getPrimaryLocale(context);

        if((now - epoch) < (24 * 3600)) {
            final DateFormat fmt = new SimpleDateFormat("HH:mm", locale);
            return fmt.format(new Date(epoch * 1000));
        }

        DateFormat fmt = new SimpleDateFormat("dd MMM", locale);
        return fmt.format(new Date(epoch * 1000));
    }

    public static String formatEpochFull(Context context, long epoch) {
        Locale locale = getPrimaryLocale(context);
        DateFormat fmt = new SimpleDateFormat("MM/dd/yy HH:mm:ss", locale);

        return fmt.format(new Date(epoch * 1000));
    }

    public static String formatMillisIso8601(Context context, long millis) {
        Locale locale = getPrimaryLocale(context);

        String pattern;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N)
            pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
        else
            pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

        DateFormat fmt = new SimpleDateFormat(pattern, locale);
        String rv = fmt.format(new Date(millis));

        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            // convert RFC 822 (+0100) -> ISO 8601 timezone (+01:00)
            int l = rv.length();
            if ((l > 5) && (rv.charAt(l - 5) == '+'))
                rv = rv.substring(0, l - 2) + ":" + rv.substring(l - 2);
        }

        return rv;
    }

    public static String formatEpochMillis(Context context, long millis) {
        Locale locale = getPrimaryLocale(context);
        DateFormat fmt = new SimpleDateFormat("MM/dd/yy HH:mm:ss.SSS", locale);

        return fmt.format(new Date(millis));
    }

    public static String formatInteger(Context context, int val) {
        Locale locale = getPrimaryLocale(context);
        return String.format(locale, "%d", val);
    }

    public static Configuration getLocalizedConfig(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        Configuration config = context.getResources().getConfiguration();

        if(!Prefs.useEnglishLanguage(prefs))
            return config;

        Locale locale = new Locale("en");
        Locale.setDefault(locale);
        config.setLocale(locale);

        return config;
    }

    public static void setAppTheme(String theme) {
        if(theme.equals("light"))
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        else if(theme.equals("dark"))
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        else
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public static String proto2str(int proto) {
        switch(proto) {
            case 6:     return "TCP";
            case 17:    return "UDP";
            case 1:     return "ICMP";
            default:    return(Integer.toString(proto));
        }
    }

    public static String[] getL7Protocols() {
        if(l7Protocols == null) {
            List<String> protos = CaptureService.getL7Protocols();
            Collections.sort(protos, String.CASE_INSENSITIVE_ORDER);
            l7Protocols = protos.toArray(new String[0]);
        }

        return l7Protocols;
    }

    public static String getDnsServer(ConnectivityManager cm, Network net) {
        LinkProperties props = cm.getLinkProperties(net);

        if(props != null) {
            List<InetAddress> dns_servers = props.getDnsServers();

            for(InetAddress addr : dns_servers) {
                // Get the first IPv4 DNS server
                if(addr instanceof Inet4Address) {
                    return addr.getHostAddress();
                }
            }
        }

        return null;
    }

    // https://gist.github.com/mathieugerard/0de2b6f5852b6b0b37ed106cab41eba1
    // API level 31 requires building a NetworkRequest, which in turn requires an asynchronous callback.
    // Using the deprecated API instead to keep things simple.
    // https://developer.android.com/reference/android/net/wifi/WifiManager#getConnectionInfo()
    @SuppressWarnings("deprecation")
    public static String getLocalWifiIpAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if(wifiManager == null)
            return(null);

        WifiInfo connInfo = wifiManager.getConnectionInfo();
        if(connInfo != null) {
            int ipAddress = connInfo.getIpAddress();

            if(ipAddress == 0)
                return(null);

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

    public static String getLocalIPAddress(Context context) {
        InetAddress vpn_ip;

        try {
            vpn_ip = InetAddress.getByName(CaptureService.VPN_IP_ADDRESS);
        } catch (UnknownHostException e) {
            return "";
        }

        // try to get the WiFi IP address first
        String wifi_ip = getLocalWifiIpAddress(context);

        if((wifi_ip != null) && (!wifi_ip.equals("0.0.0.0"))) {
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

                            if ((sAddr != null) && (addr instanceof Inet4Address) && !sAddr.equals("0.0.0.0")) {
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

    public static boolean subnetContains(InetAddress subnet, int prefix, InetAddress address) {
        int addrlen = subnet.getAddress().length;
        ByteBuffer maskBuf = ByteBuffer.allocate(addrlen);

        for(int i=0; i<addrlen / 4; i++)
            maskBuf.putInt(-1);

        // 0xFFFFF...0000000
        BigInteger mask = ((new BigInteger(1, maskBuf.array())).shiftRight(prefix)).not();

        BigInteger start = new BigInteger(1, subnet.getAddress()).and(mask);
        BigInteger end = start.add(mask.not());
        BigInteger toCheck = new BigInteger(1, address.getAddress());

        return((toCheck.compareTo(start) >= 0) && (toCheck.compareTo(end) <= 0));
    }

    public static boolean subnetContains(String subnet, int prefix, String address) {
        try {
            return subnetContains(InetAddress.getByName(subnet), prefix, InetAddress.getByName(address));
        } catch (UnknownHostException ignored) {
            return false;
        }
    }

    public static boolean isLocalNetworkAddress(InetAddress checkAddress) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if(!intf.isVirtual()) {
                    List<InterfaceAddress> addrs = intf.getInterfaceAddresses();
                    for (InterfaceAddress addr : addrs) {
                        if(subnetContains(addr.getAddress(), addr.getNetworkPrefixLength(), checkAddress))
                            return true;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }

    public static boolean isLocalNetworkAddress(String checkAddress) {
        // this check is necessary as otherwise host resolution would be triggered on the main thread
        if(!validateIpAddress(checkAddress))
            return false;

        try {
            return isLocalNetworkAddress(InetAddress.getByName(checkAddress));
        } catch (UnknownHostException ignored) {
            return false;
        }
    }

    // returns current timestamp in seconds
    public static long now() {
        Calendar calendar = Calendar.getInstance();
        return(calendar.getTimeInMillis() / 1000);
    }

    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    // https://stackoverflow.com/questions/9655181/how-to-convert-a-byte-array-to-a-hex-string-in-java
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String byteArrayToHex(byte[] bytes, int size) {
        char[] hexChars = new char[size * 2];

        for (int j = 0; j < size; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    // Adapted from https://gist.github.com/jen20/906db194bd97c14d91df
    public static String hexdump(byte[] array, int offset, int length) {
        final int width = 16;
        final int half = width / 2;

        StringBuilder builder = new StringBuilder();

        for (int rowOffset = offset; rowOffset < offset + length; rowOffset += width) {
            for (int index = 0; index < width; index++) {
                if(index == half)
                    builder.append(" ");

                if (rowOffset + index < length)
                    builder.append(String.format("%02x ", array[rowOffset + index]));
                else
                    builder.append("   ");
            }

            if (rowOffset < length) {
                int asciiWidth = Math.min(width, length - rowOffset);
                builder.append(" ");

                builder.append(new String(array, rowOffset, asciiWidth,
                        StandardCharsets.US_ASCII).replaceAll("[^ -~]", "."));
            }

            builder.append("\n");
        }

        return builder.toString();
    }

    public static String hexdump(byte[] array) {
        return hexdump(array, 0, array.length);
    }

    // Splits the provided data into individual PCAP/PCAPNG records. Intended to be used with data received
    // via CaptureService::dumpPcapData
    public static Iterator<Integer> iterPcapRecords(byte[] data, boolean pcapng_format) {
        final ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.nativeOrder());

        if(pcapng_format) {
            // PCAPNG
            return new Iterator<Integer>() {
                @Override
                public boolean hasNext() {
                    // 12: min block size
                    return(buf.remaining() >= 12);
                }

                @Override
                public Integer next() {
                    int total_len = buf.getInt(buf.position() + 4);
                    buf.position(buf.position() + total_len);
                    return total_len;
                }
            };
        } else {
            // PCAP
            return new Iterator<Integer>() {
                @Override
                public boolean hasNext() {
                    // 16: sizeof(pcap_rec)
                    return(buf.remaining() > 16);
                }

                @Override
                public Integer next() {
                    int rec_len = buf.getInt(buf.position() + 8) + 16;
                    buf.position(buf.position() + rec_len);
                    return rec_len;
                }
            };
        }
    }

    // API level 31 requires building a NetworkRequest, which in turn requires an asynchronous callback.
    // Using the deprecated API instead to keep things simple.
    // https://developer.android.com/reference/android/net/ConnectivityManager#getAllNetworks()
    @SuppressWarnings("deprecation")
    public static Network getRunningVpn(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm != null) {
            try {
                Network[] networks = cm.getAllNetworks();

                for(Network net : networks) {
                    NetworkCapabilities cap = cm.getNetworkCapabilities(net);

                    if ((cap != null) && cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        Log.d("hasVPNRunning", "detected VPN connection: " + net.toString());
                        return net;
                    }
                }
            } catch (SecurityException e) {
                // this is a bug in Android 11 - https://issuetracker.google.com/issues/175055271?pli=1
                e.printStackTrace();
            }
        }

        return null;
    }

    public static void showToast(Context context, int id, Object... args) {
        String msg = context.getResources().getString(id, (Object[]) args);
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }

    public static void showToastLong(Context context, int id, Object... args) {
        String msg = context.getResources().getString(id, (Object[]) args);
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show();
    }

    public static void showHelpDialog(Context context, int id){
        String msg = context.getResources().getString(id);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.hint);
        builder.setMessage(msg);
        builder.setCancelable(true);
        builder.setNeutralButton(R.string.ok,
                (dialog, id1) -> dialog.cancel());

        AlertDialog alert = builder.create();
        alert.show();
    }

    public static String getUniqueFileName(Context context, String ext) {
        Locale locale = getPrimaryLocale(context);
        final DateFormat fmt = new SimpleDateFormat("dd_MMM_HH_mm_ss", locale);
        return  "PCAPdroid_" + fmt.format(new Date()) + "." + ext;
    }

    public static String getUniquePcapFileName(Context context, boolean pcapng_format) {
        return(Utils.getUniqueFileName(context, pcapng_format ? "pcapng" : "pcap"));
    }

    public static @Nullable BitmapDrawable scaleDrawable(Resources res, Drawable drawable, int new_x, int new_y) {
        if((new_x <= 0) || (new_y <= 0))
            return null;

        try {
            Bitmap bitmap = Bitmap.createBitmap(new_x, new_y, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            // may throw OutOfMemoryError
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);

            return new BitmapDrawable(res, bitmap);
        } catch (Exception e) {
            Log.w(TAG, "scaleDrawable(" + new_x + ", " + new_y + ") failed: " + e.getMessage());
            return null;
        }
    }

    // Converts a TableLayout (two columns, label and value) to a string which can be copied
    // If value is a ViewGroup, extract the first TextView from it
    public static String table2Text(TableLayout table) {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < table.getChildCount(); i++) {
            View v = table.getChildAt(i);

            if ((v instanceof TableRow) && (v.getVisibility() == View.VISIBLE)
                    && (((TableRow) v).getChildCount() == 2)) {
                View label = ((TableRow) v).getChildAt(0);
                View value = ((TableRow) v).getChildAt(1);

                if(value instanceof ViewGroup) {
                    // Try to find first TextView child
                    ViewGroup group = (ViewGroup) value;
                    for(int c=0; c<group.getChildCount(); c++) {
                        View view = group.getChildAt(c);

                        if(view instanceof TextView) {
                            value = view;
                            break;
                        }
                    }
                }

                if((label instanceof TextView) && (value instanceof TextView)) {
                    builder.append(((TextView) label).getText());
                    builder.append(": ");
                    builder.append(((TextView) value).getText());
                    builder.append("\n");
                }
            }
        }

        return builder.toString();
    }

    public static String adapter2Text(TextAdapter adapter) {
        StringBuilder builder = new StringBuilder();

        for(int i=0; i< adapter.getCount(); i++) {
            String text = adapter.getItemText(i);

            builder.append(text);
            builder.append("\n");
        }

        return builder.toString();
    }

    public static boolean isTv(Context context) {
        UiModeManager uiModeManager = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);

        if(uiModeManager == null)
            return false;

        return(uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION);
    }

    public static String getAppVersion(Context context) {
        String appver;

        try {
            PackageInfo pInfo = Utils.getPackageInfo(context.getPackageManager(), context.getPackageName(), 0);
            String version = pInfo.versionName;
            boolean isRelease = version.contains(".");

            appver = isRelease ? ("v" + version) : version;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not retrieve package version");
            appver = "";
        }

        return appver;
    }

    public static boolean supportsFileDialog(Context context, Intent intent) {
        // https://commonsware.com/blog/2017/12/27/storage-access-framework-missing-action.html
        ComponentName comp = intent.resolveActivity(context.getPackageManager());

        return((comp != null) && (!"com.google.android.tv.frameworkpackagestubs".equals(comp.getPackageName())));
    }

    public static boolean supportsFileDialog(Context context) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        return supportsFileDialog(context, intent);
    }

    public static boolean launchFileDialog(Context context, Intent intent, ActivityResultLauncher<Intent> launcher) {
        if(Utils.supportsFileDialog(context, intent)) {
            try {
                launcher.launch(intent);
                return true;
            } catch (ActivityNotFoundException ignored) {}
        }

        Utils.showToastLong(context, R.string.no_activity_file_selection);
        return false;
    }

    // Get a URI to write a file into the downloads folder, into a folder named "PCAPdroid"
    // If the file exists, it's overwritten
    public static Uri getDownloadsUri(Context context, String fname) {
        ContentValues values = new ContentValues();

        //values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);
        String selectQuery = "";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Android Q+ cannot directly access the external dir. Must use RELATIVE_PATH instead.
            // Important: trailing "/" required for the selectQuery
            String relPath = Environment.DIRECTORY_DOWNLOADS + "/PCAPdroid/";
            selectQuery = MediaStore.MediaColumns.RELATIVE_PATH + "='" + relPath + "' AND " +
                MediaStore.MediaColumns.DISPLAY_NAME + "='" + fname + "'";
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        } else {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    Utils.showToastLong(context, R.string.external_storage_perm_required);
                    return(null);
                }
            }

            // NOTE: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) returns an app internal folder
            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File folder = new File(downloadsDir + "/PCAPdroid");
            try {
                folder.mkdirs();
            } catch (Exception ignored) {}
            if(!folder.exists())
                folder = downloadsDir;

            String path = folder + "/" + fname;
            Log.d(TAG, "getDownloadsUri: path=" + path);
            selectQuery = MediaStore.MediaColumns.DATA + "='" + path + "'";
            values.put(MediaStore.MediaColumns.DATA, path);
        }

        Uri externalUri = MediaStore.Files.getContentUri("external");

        // if the file with given name already exists, overwrite it
        try (Cursor cursor = context.getContentResolver().query(externalUri, new String[]{MediaStore.MediaColumns._ID}, selectQuery, null, null)) {
            if ((cursor != null) && cursor.moveToFirst()) {
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                Uri existingUri = ContentUris.withAppendedId(externalUri, id);

                Log.d(TAG, "getDownloadsUri: overwriting file " + existingUri);
                return existingUri;
            }
        } catch (Exception ignored) {}

        try {
            Uri newUri = context.getContentResolver().insert(externalUri, values);
            Log.d(TAG, "getDownloadsUri: new file " + newUri);
            return newUri;
        } catch (Exception e) {
            // On some devices, it may trigger "IllegalArgumentException: Volume external_primary not found"
            Log.e(TAG, "getDownloadsUri failed:" + e.getMessage());
            Utils.showToastLong(context, R.string.write_ext_storage_failed);
            return(null);
        }
    }

    public static boolean isRootAvailable() {
        if(rootAvailable == null) {
            String path = System.getenv("PATH");
            rootAvailable = false;

            if(path != null) {
                Log.d("isRootAvailable", "PATH = " + path);

                for(String part : path.split(":")) {
                    File f = new File(part + "/su");

                    if(f.exists()) {
                        Log.d("isRootAvailable", "'su' binary found at " + f.getAbsolutePath());
                        rootAvailable = true;
                        break;
                    }
                }
            }
        }

        return rootAvailable;
    }

    public static void copyToClipboard(Context ctx, String contents) {
        try {
            ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(ctx.getString(R.string.stats), contents);
            clipboard.setPrimaryClip(clip);

            // Only show a toast for Android 12 and lower
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2)
                Utils.showToast(ctx, R.string.copied);
        } catch (Exception e) {
            Log.e(TAG, "copyToClipboard failed: " + e.getMessage());
            Utils.showToastLong(ctx, R.string.error);
        }
    }

    public static void shareText(Context ctx, String subject, String contents) {
        Intent intent = new Intent(android.content.Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(android.content.Intent.EXTRA_SUBJECT, subject);
        intent.putExtra(android.content.Intent.EXTRA_TEXT, contents);

        startActivity(ctx, Intent.createChooser(intent, ctx.getResources().getString(R.string.share)));
    }

    // Formats a string resource like "text: %1s" by applying the specified style to the "text:" and "value" ("%1s")
    public static SpannableString formatTextValue(Context ctx, StyleSpan textStyle, StyleSpan valStyle, int resid, String value) {
        String fmt = ctx.getResources().getString(resid);
        String textAndValue = String.format(fmt, value);
        SpannableString s = new SpannableString(textAndValue);
        int valOffset = fmt.length() - 4;

        if(!isRTL(ctx)) {
            if (textStyle != null)
                s.setSpan(textStyle, 0, valOffset, 0);
            if (valStyle != null)
                s.setSpan(valStyle, valOffset, textAndValue.length(), 0);
        } else {
            if (textStyle != null)
                s.setSpan(textStyle, textAndValue.length() - valOffset, textAndValue.length(), 0);
            if (valStyle != null)
                s.setSpan(valStyle, 0, textAndValue.length() - valOffset, 0);
        }

        return s;
    }

    // www.example.org -> example.org
    public static String cleanDomain(String domain) {
        if(domain.startsWith("www."))
            domain = domain.substring(4);
        return domain;
    }

    // a.example.org -> example.org
    public static String getSecondLevelDomain(String domain) {
        int tldPos = domain.lastIndexOf(".");

        if(tldPos <= 0)
            return domain;

        int rootPos = domain.substring(0, tldPos).lastIndexOf(".");

        if(rootPos <= 0)
            return domain;

        return domain.substring(rootPos + 1);
    }

    public static String tcpFlagsToStr(int flags) {
        final String []flags_s = {"FIN", "SYN", "RST", "PSH", "ACK", "URG", "ECN", "CWR"};
        final StringBuilder builder = new StringBuilder();
        boolean first = true;

        for(int i=0; i<flags_s.length; i++) {
            if((flags & (1 << i)) != 0) {
                if(!first)
                    builder.append(" ");
                builder.append(flags_s[i]);
                first = false;
            }
        }

        return builder.toString();
    }

    public static int getIntentFlags(int flags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    public static boolean ungzip(InputStream is, String dst) {
        try(GZIPInputStream gis = new GZIPInputStream(is)) {
            try(BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst))) {
                byte[] bytesIn = new byte[4096];
                int read;
                while ((read = gis.read(bytesIn)) != -1)
                    bos.write(bytesIn, 0, read);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean downloadFile(String _url, String path) {
        boolean has_contents = false;

        try (FileOutputStream out = new FileOutputStream(path + ".tmp")) {
            try (BufferedOutputStream bos = new BufferedOutputStream(out)) {
                URL url = new URL(_url);

                HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
                try {
                    // Necessary otherwise the connection will stay open
                    con.setRequestProperty("Connection", "Close");
                    con.setConnectTimeout(5000);
                    con.setReadTimeout(5000);

                    try(InputStream in = new BufferedInputStream(con.getInputStream())) {
                        byte[] bytesIn = new byte[4096];
                        int read;
                        while ((read = in.read(bytesIn)) != -1) {
                            bos.write(bytesIn, 0, read);
                            has_contents |= (read > 0);
                        }
                    } catch (SocketTimeoutException _ignored) {
                        Log.w(TAG, "Timeout while fetching " + _url);
                    }
                } finally {
                    con.disconnect();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if(!has_contents) {
            try {
                //noinspection ResultOfMethodCallIgnored
                (new File(path + ".tmp")).delete(); // if exists
            } catch (Exception ignored) {
                // ignore
            }
            return false;
        }

        // Only write the target path if it was successful
        return (new File(path + ".tmp")).renameTo(new File(path));
    }

    public static String shorten(String s, int maxlen) {
        if(s.length() > maxlen)
            s = s.substring(0, maxlen - 1) + "…";

        return s;
    }

    // NOTE: base32 padding not supported
    public static byte[] base32Decode(String s) {
        s = s.toUpperCase().replace("\n", "");
        byte[] rv = new byte[s.length() * 5 / 8];
        int i = 0;
        int bitsRemaining = 8;
        byte curByte = 0;

        for(int k=0; k<s.length(); k++) {
            int val;
            char c = s.charAt(k);

            if((c >= '2') && (c <= '7'))
                val = 26 + (c - '2');
            else if((c >= 'A') && (c <= 'Z'))
                val = (c - 'A');
            else
                throw new IllegalArgumentException("invalid BASE32 string or unsupported padding");

            // https://stackoverflow.com/questions/641361/base32-decoding
            if(bitsRemaining > 5) {
                int mask = val << (bitsRemaining - 5);
                curByte = (byte)(curByte | mask);
                bitsRemaining -= 5;
            } else {
                int mask = val >> (5 - bitsRemaining);
                curByte = (byte)(curByte | mask);
                rv[i++] = curByte;
                curByte = (byte)(val << (3 + bitsRemaining));
                bitsRemaining += 3;
            }
        }

        if(i < rv.length)
            rv[i] = curByte;

        return rv;
    }

    public static void startActivity(Context ctx, Intent intent) {
        try {
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            showToastLong(ctx, R.string.no_intent_handler_found);
        }
    }

    // Runs the specified runnable now if on the UI thread, otherwise enqueue it to the Handler
    public static void runOnUi(Runnable r, Handler h) {
        if(Looper.getMainLooper().getThread() == Thread.currentThread())
            r.run();
        else
            h.post(r);
    }

    public static void safeClose(Closeable obj) {
        if(obj == null)
            return;

        try {
            obj.close();
        } catch (IOException e) {
            Log.w(TAG, e.getLocalizedMessage());
        }
    }

    // Returns true on the playstore branch
    public static boolean isPlaystore() {
        return false;
    }

    @SuppressWarnings("deprecation")
    public static BuildType getVerifiedBuild(Context ctx, String package_name) {
        try {
            Signature[] signatures;

            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // NOTE: PCAPdroid does not use multiple signatures
                PackageInfo pInfo = Utils.getPackageInfo(ctx.getPackageManager(), package_name, PackageManager.GET_SIGNING_CERTIFICATES);
                signatures = (pInfo.signingInfo == null) ? null : pInfo.signingInfo.getSigningCertificateHistory();
            } else {
                @SuppressLint("PackageManagerGetSignatures")
                PackageInfo pInfo = Utils.getPackageInfo(ctx.getPackageManager(), package_name, PackageManager.GET_SIGNATURES);
                signatures = pInfo.signatures;
            }

            // can be null in robolectric tests
            if((signatures == null) || (signatures.length < 1))
                return BuildType.UNKNOWN;

            MessageDigest sha1 = MessageDigest.getInstance("SHA");
            sha1.update(signatures[0].toByteArray());

            // keytool -printcert -jarfile file.apk
            String hex = byteArrayToHex(sha1.digest(), sha1.getDigestLength());
            switch(hex) {
                case "511140392BFF2CFB4BD825895DD6510CE1807F6D":
                    return BuildType.DEBUG;
                case "EE953D4F988C8AC17575DFFAA1E3BBCE2E29E81D":
                    return isPlaystore() ? BuildType.PLAYSTORE : BuildType.GITHUB;
                case "72777D6939EF150099219BBB68C17220DB28EA8E":
                    return BuildType.FDROID;
            }
        } catch (PackageManager.NameNotFoundException | NoSuchAlgorithmException e) {
            Log.e(TAG, "Could not determine the build type");
        }
        return BuildType.UNKNOWN;
    }

    public static BuildType getVerifiedBuild(Context ctx) {
        return getVerifiedBuild(ctx, ctx.getPackageName());
    }

    public static X509Certificate x509FromPem(String pem) {
        int begin = pem.indexOf('\n') + 1;
        int end = pem.indexOf('-', begin);

        if((begin > 0) && (end > begin)) {
            String cert64 = pem.substring(begin, end);
            //Log.d(TAG, "Cert: " + cert64);
            try {
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                byte[] cert_data = android.util.Base64.decode(cert64, android.util.Base64.DEFAULT);
                return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(cert_data));
            } catch (CertificateException e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    public static boolean isCAInstalled(X509Certificate ca_cert) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidCAStore");
            ks.load(null, null);
            return ks.getCertificateAlias(ca_cert) != null;
        } catch (KeyStoreException | CertificateException | IOException | NoSuchAlgorithmException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean isCAInstalled(String ca_pem) {
        if(ca_pem == null)
            return false;

        X509Certificate ca_cert = x509FromPem(ca_pem);
        if(ca_cert == null)
            return false;

        return isCAInstalled(ca_cert);
    }

    // Like Files.copy(src.toPath(), out);
    public static void copy(File src, OutputStream out) throws IOException {
        try(FileInputStream in = new FileInputStream(src)) {
            byte[] bytesIn = new byte[4096];
            int read;
            while((read = in.read(bytesIn)) != -1)
                out.write(bytesIn, 0, read);
        }
    }

    public static void copy(InputStream in, File dst) throws IOException {
        try(FileOutputStream out = new FileOutputStream(dst)) {
            byte[] bytesIn = new byte[4096];
            int read;
            while((read = in.read(bytesIn)) != -1)
                out.write(bytesIn, 0, read);
        }
    }

    public static boolean hasEncryptedPayload(AppDescriptor app, ConnectionDescriptor conn) {
        return(
            // Telegram
            app.getPackageName().equals("org.telegram.messenger") ||

            // Whatsapp
            ((conn.info != null) && conn.info.equals("g.whatsapp.net") && !conn.l7proto.equals("DNS")) ||

            // Google GCM
            // https://stackoverflow.com/questions/15571576/which-port-and-protocol-does-google-cloud-messaging-gcm-use
            ((app.getUid() == 1000) && (conn.dst_port >= 5228) && (conn.dst_port <= 5230)) ||

            // Google APN
            // https://keabird.com/blogs/2014/09/19/ports-to-be-whitelisted-for-iosandroid-push-notification/
            ((app.getUid() == 1000) && ((conn.dst_port == 2195) || (conn.dst_port == 2196) || (conn.dst_port == 5223)))
        );
    }

    /* Detects and returns the end of the HTTP request/response headers. 0 is returned if not found. */
    public static int getEndOfHTTPHeaders(byte[] buf) {
        for(int i = 0; i <= (buf.length - 4); i++) {
            if((buf[i] == '\r') && (buf[i+1] == '\n') && (buf[i+2] == '\r') && (buf[i+3] == '\n'))
                return i+4;
        }
        return 0;
    }

    public static String genRandomString(int length) {
        String charset = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        StringBuilder sb = new StringBuilder(length);
        Random rnd = new Random();

        for(int i = 0; i < length; i++)
            sb.append(charset.charAt(rnd.nextInt(charset.length())));

        return sb.toString();
    }

    public static void setDecryptionIcon(ImageView icon, ConnectionDescriptor conn) {
        int color;

        switch(conn.getDecryptionStatus()) {
            case DECRYPTED:
                color = R.color.ok;
                break;
            case NOT_DECRYPTABLE:
                color = R.color.warning;
                break;
            case ERROR:
                color = R.color.danger;
                break;
            default:
                color = R.color.lightGray;
        }

        Context context = icon.getContext();
        int resid = (conn.isCleartext() || conn.isDecrypted()) ? R.drawable.ic_lock_open : R.drawable.ic_lock;

        icon.setColorFilter(ContextCompat.getColor(context, color));
        icon.setImageDrawable(ContextCompat.getDrawable(context, resid));
    }

    public static boolean isPrintable(byte c) {
        return ((c >= 32) && (c <= 126)) || (c == '\r') || (c == '\n') || (c == '\t');
    }

    // Get a CharSequence which properly displays clickable links obtained by formatting a parametric
    // string resource with the provided args. See setTextUrls
    // https://stackoverflow.com/questions/23503642/how-to-use-formatted-strings-together-with-placeholders-in-android
    public static CharSequence getText(Context context, int resid, String... args) {
        for(int i = 0; i < args.length; ++i)
            args[i] = TextUtils.htmlEncode(args[i]);

        String htmlOnly = String.format(HtmlCompat.toHtml(new SpannedString(context.getText(resid)),
                HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE), (Object[]) args);
        //Log.d(TAG, htmlOnly);
        return HtmlCompat.fromHtml(htmlOnly, HtmlCompat.FROM_HTML_MODE_LEGACY);
    }

    // Format a resource containing URLs and display it in a TextView, making URls clickable
    public static void setTextUrls(TextView tv, int resid, String... args) {
        CharSequence text = getText(tv.getContext(), resid, args);
        tv.setText(text);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
    }

    public static int getPCAPdroidUid(Context context) {
        // NOTE: when called from a work profile, it correctly returns the work profile UID
        AppDescriptor app = AppsResolver.resolveInstalledApp(context.getPackageManager(), BuildConfig.APPLICATION_ID, 0);
        if(app != null)
            return app.getUid();
        return Utils.UID_UNKNOWN;
    }

    // returns the user ID of a given app uid
    public static int getUserId(int uid) {
        return  uid / PER_USER_RANGE;
    }

    @SuppressLint("DefaultLocale")
    public static boolean rootGrantPermission(Context context, String perm) {
        return CaptureService.rootCmd("pm", String.format("grant --user %d %s %s", getUserId(getPCAPdroidUid(context)), BuildConfig.APPLICATION_ID, perm)) == 0;
    }

    // Returns the available dalvik vm heap size for this app. Exceeding this size will result into
    // an OOM exception
    public static long getAvailableHeap() {
        Runtime runtime = Runtime.getRuntime();

        // maxMemory: max memory which can be allocated on this app vm (should correspond to getMemoryClass)
        // totalMemory: currently allocated memory (used/unused) by the vm
        // freeMemory: free portion of the totalMemory
        long unallocated = runtime.maxMemory() - runtime.totalMemory();
        return unallocated + runtime.freeMemory();
    }

    public static String trimlvl2str(int lvl) {
        switch (lvl) {
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:         return "TRIM_MEMORY_UI_HIDDEN";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:  return "TRIM_MEMORY_RUNNING_MODERATE";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:       return "TRIM_MEMORY_RUNNING_LOW";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:  return "TRIM_MEMORY_RUNNING_CRITICAL";
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:        return "TRIM_MEMORY_BACKGROUND";
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:          return "TRIM_MEMORY_MODERATE";
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:          return "TRIM_MEMORY_COMPLETE";
            default:                                                return "TRIM_UNKNOWN";
        }
    }

    public static String getMemoryStats(Context context) {
        // This accounts system-wide limits
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);

        ActivityManager.RunningAppProcessInfo memState = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(memState);

        // This accounts app-specific limits (dalvik heap)
        Runtime runtime = Runtime.getRuntime();
        long heapAvailable = getAvailableHeap();
        boolean heapLow = heapAvailable <= LOW_HEAP_THRESHOLD;

        return "[Runtime] free: " + Utils.formatBytes(runtime.freeMemory()) + ", max: " + Utils.formatBytes(runtime.maxMemory()) + ", allocated: " + Utils.formatBytes(runtime.totalMemory()) + ", available: " + Utils.formatBytes(heapAvailable) + ", low=" + heapLow +
                "\n[MemoryState] pid: " + memState.pid + ", trimlevel: " + trimlvl2str(memState.lastTrimLevel) +
                "\n[MemoryInfo] available: " + Utils.formatBytes(memoryInfo.availMem) + ", total: " + Utils.formatBytes(memoryInfo.totalMem) + ", lowthresh: " + Utils.formatBytes(memoryInfo.threshold) + ", low=" + memoryInfo.lowMemory +
                "\n[MemoryClass] standard: " + activityManager.getMemoryClass() + " MB, large: " + activityManager.getLargeMemoryClass() + " MB";
    }

    public static void sendImportantNotification(Context context, int id, Notification notification) {
        NotificationManagerCompat man = NotificationManagerCompat.from(context);

        if(!man.areNotificationsEnabled()) {
            String title = notification.extras.getString(Notification.EXTRA_TITLE);
            String description = notification.extras.getString(Notification.EXTRA_TEXT);
            String text = title + " - " + description;

            Log.w(TAG, "Important notification not sent because notifications are disabled: " + text);

            // Try with toast (will only work if PCAPdroid is in the foreground)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        } else
            man.notify(id, notification);
    }

    // Set the SearchView query and expand it
    public static void setSearchQuery(SearchView searchView, MenuItem searchItem, String query) {
        searchView.setIconified(false);
        searchItem.expandActionView();

        searchView.setIconified(false);
        searchItem.expandActionView();

        // Delay otherwise the query won't be set when the activity is just started
        searchView.post(() -> searchView.setQuery(query, true));
    }

    public static boolean backHandleSearchview(SearchView searchView) {
        if((searchView != null) && !searchView.isIconified()) {
            // Required to close the SearchView when the search submit button was not pressed
            searchView.setIconified(true);
            return true;
        }

        return false;
    }

    public static String getDeviceModel() {
        if(Build.MODEL.startsWith(Build.MANUFACTURER))
            return Build.MANUFACTURER;
        else
            return Build.MANUFACTURER + " " + Build.MODEL;
    }

    public static String getOsVersion() {
        return "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
    }

    public static String getBuildInfo(Context ctx) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        boolean rooted = Utils.isRootAvailable();

        return "Build type: " + Utils.getVerifiedBuild(ctx).toString().toLowerCase() + "\n" +
                "Build version: " + BuildConfig.VERSION_NAME + "\n" +
                "Build date: " + dateFormat.format(new Date(BuildConfig.BUILD_TIME)) + "\n" +
                "Current date: " + dateFormat.format(new Date()) + "\n" +
                "Device: " + getDeviceModel() + (rooted ? " (rooted)" : "") + "\n" +
                "OS version: " + getOsVersion() + "\n";
    }

    // https://stackoverflow.com/questions/16704597/how-do-you-get-the-user-defined-device-name-in-android
    public static @Nullable String getDeviceName(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S)
                // NOTE: on Android 32+ this generates a security exception
                return Settings.Secure.getString(ctx.getContentResolver(), "bluetooth_name");

            return Settings.Global.getString(ctx.getContentResolver(), Settings.Global.DEVICE_NAME);
        } catch (Exception e) {
            Log.d(TAG, "getDeviceName failed: " + e);
            return null;
        }
    }

    public static String getAppVersionString() {
        return "PCAPdroid v" + BuildConfig.VERSION_NAME;
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    public static @Nullable <T extends Serializable> T getSerializableExtra(Intent intent, String key, Class<T> clazz) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return intent.getSerializableExtra(key, clazz);
        else {
            try {
                return (T)intent.getSerializableExtra(key);
            } catch (ClassCastException unused) {
                return null;
            }
        }
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    public static @Nullable <T extends Serializable> T getSerializable(Bundle bundle, String key, Class<T> clazz) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return bundle.getSerializable(key, clazz);
        else {
            try {
                return (T)bundle.getSerializable(key);
            } catch (ClassCastException unused) {
                return null;
            }
        }
    }

    @SuppressWarnings({"deprecation"})
    public static PackageInfo getPackageInfo(PackageManager pm, String package_name, int flags) throws PackageManager.NameNotFoundException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return pm.getPackageInfo(package_name, PackageManager.PackageInfoFlags.of(flags));
        else
            return pm.getPackageInfo(package_name, flags);
    }

    @SuppressWarnings({"deprecation"})
    public static int getPackageUid(PackageManager pm, String package_name, int flags) throws PackageManager.NameNotFoundException {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return pm.getPackageUid(package_name, PackageManager.PackageInfoFlags.of(flags));
        else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            return pm.getPackageUid(package_name, 0);
        else
            return pm.getApplicationInfo(package_name, 0).uid;
    }

    @SuppressLint({"QueryPermissionsNeeded"})
    @SuppressWarnings({"deprecation"})
    public static List<PackageInfo> getInstalledPackages(PackageManager pm, int flags) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            return pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags));
        else
            return pm.getInstalledPackages(flags);
    }

    public static boolean validatePort(String value) {
        try {
            int val = Integer.parseInt(value);
            return((val > 0) && (val < 65535));
        } catch(NumberFormatException e) {
            return false;
        }
    }

    // from bouncycastle
    private static boolean isValidIPv6(String address) {
        if (address.length() == 0)
            return false;

        char firstChar = address.charAt(0);
        if (firstChar != ':' && Character.digit(firstChar, 16) < 0)
            return false;

        int segmentCount = 0;
        String temp = address + ":";
        boolean doubleColonFound = false;

        int pos = 0, end;
        while (pos < temp.length() && (end = temp.indexOf(':', pos)) >= pos)  {
            if (segmentCount == 8)
                return false;

            if (pos != end)  {
                String value = temp.substring(pos, end);

                if (end == temp.length() - 1 && value.indexOf('.') > 0) {
                    // add an extra one as address covers 2 words.
                    if (++segmentCount == 8)
                        return false;
                    if (!validateIpv4Address(value))
                        return false;
                }
                else if (!isParseableIPv6Segment(temp, pos, end))
                    return false;
            } else {
                if (end != 1 && end != temp.length() - 1 && doubleColonFound)
                    return false;
                doubleColonFound = true;
            }

            pos = end + 1;
            ++segmentCount;
        }

        return segmentCount == 8 || doubleColonFound;
    }

    private static boolean isParseableIPv6Segment(String s, int pos, int end) {
        return isParseable(s, pos, end, 16, 4, true, 0x0000, 0xFFFF);
    }

    private static boolean isParseable(String s, int pos, int end, int radix,
                                       int maxLength, boolean allowLeadingZero,
                                       int minValue, int maxValue) {
        int length = end - pos;
        if (length < 1 | length > maxLength)
            return false;

        boolean checkLeadingZero = length > 1 & !allowLeadingZero;
        if (checkLeadingZero && Character.digit(s.charAt(pos), radix) <= 0)
            return false;

        int value = 0;
        while (pos < end) {
            char c = s.charAt(pos++);
            int d = Character.digit(c, radix);
            if (d < 0)
            {
                return false;
            }

            value *= radix;
            value += d;
        }

        return value >= minValue & value <= maxValue;
    }

    @SuppressWarnings("deprecation")
    public static boolean validateIpAddress(String value) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            return (InetAddresses.isNumericAddress(value));
        else {
            Matcher matcher = Patterns.IP_ADDRESS.matcher(value);
            return(matcher.matches());
        }
    }

    // https://mkyong.com/regular-expressions/how-to-validate-ip-address-with-regular-expression/
    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^(([0-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5])(\\.(?!$)|$)){4}$");

    public static boolean validateIpv4Address(String s) {
        Matcher matcher = IPV4_PATTERN.matcher(s);
        return matcher.matches();
    }

    public static boolean validateIpv6Address(String s) {
        return isValidIPv6(s) && !validateIpv4Address(s);
    }

    // rough validation
    public static boolean validateHost(String host) {
        int len = host.length();
        if((len < 2) || (len > 67))
            return false;
        if((host.charAt(0) == '-') || (host.charAt(len-1) == '-'))
            return false;
        if(host.matches(".*[A-Z\\s?!=`@].*"))
            return false;
        return true;
    }

    public static String uriToFilePath(Context ctx, Uri uri) {
        // https://gist.github.com/r0b0t3d/492f375ec6267a033c23b4ab8ab11e6a
        if (isExternalStorageDocument(uri)) {
            final String docId = DocumentsContract.getDocumentId(uri);
            final String[] split = docId.split(":");
            final String type = split[0];

            if ("primary".equalsIgnoreCase(type))
                return Environment.getExternalStorageDirectory() + "/" + split[1];
        } else if(isDownloadsDocument(uri)) {
            return downloadsUriToPath(ctx, uri);
        } else if("content".equalsIgnoreCase(uri.getScheme()))
            return mediastoreUriToPath(ctx, uri);
        else if ("file".equalsIgnoreCase(uri.getScheme()))
            return uri.getPath();
        return null;
    }

    private static String mediastoreUriToPath(Context ctx, Uri uri) {
        String[] proj = { MediaStore.Files.FileColumns.DATA };
        try(Cursor cursor = ctx.getContentResolver().query(uri, proj, null, null, null)) {
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
            if(cursor.moveToFirst())
                return cursor.getString(column_index);
        } catch (Exception ignored) {}

        return null;
    }

    private static String downloadsUriToPath(Context ctx, Uri uri) {
        final String id = DocumentsContract.getDocumentId(uri);
        if(id == null)
            return null;

        // Starting with Android O, this "id" is not necessarily a long (row number),
        // but might also be a "raw:/some/file/path" URL
        if (id.startsWith("raw:/")) {
            return Uri.parse(id).getPath();
        } else {
            long id_long;
            try {
                id_long = Long.parseLong(id);
            } catch (NumberFormatException ignored) {
                return null;
            }

            String[] contentUriPrefixesToTry = new String[]{
                    "content://downloads/public_downloads",
                    "content://downloads/my_downloads"
            };
            for (String contentUriPrefix : contentUriPrefixesToTry) {
                final Uri contentUri = ContentUris.withAppendedId(
                        Uri.parse(contentUriPrefix), id_long);
                String path = mediastoreUriToPath(ctx, contentUri);
                if(path != null)
                    return path;
            }
        }

        return null;
    }

    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    public static class UriStat {
        public String name;
        public long size;
    }

    // NOTE: in Android 7, the file size of a newly written file can be 0/less than actual size
    public static UriStat getUriStat(Context ctx, Uri uri) {
        String uri_str = uri.toString();
        File uriFile = null;

        // in some devices, cursor.isNull(sizeIndex) is true, which causes size to be 0
        // try to resolve the original path and access it as a file
        String fpath = uriToFilePath(ctx, uri);
        if(fpath != null) {
            Log.d(TAG, "getUriStat: resolved to file " + fpath);
            uriFile = new File(fpath);
        } else if(uri_str.startsWith("file://"))
            uriFile = new File(uri_str.substring(7));

        if((uriFile != null) && (uriFile.exists())) {
            // retrieve via file
            UriStat info = new UriStat();
            info.name = uriFile.getName();
            info.size = uriFile.length();
            return info;
        }

        // retrieve via content uri
        // https://developer.android.com/training/secure-file-sharing/retrieve-info.html#RetrieveFileInfo
        try(Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if((cursor == null) || !cursor.moveToFirst())
                return null;

            UriStat info = new UriStat();

            int sizeIndex = cursor.getColumnIndexOrThrow(OpenableColumns.SIZE);
            int idx = cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME);
            info.name = (idx >= 0) ? cursor.getString(idx) : "*unknown*";
            info.size = !cursor.isNull(sizeIndex) ? cursor.getLong(sizeIndex) : -1;

            return info;
        } catch (Exception e) {
            return null;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.P)
    public static PrivateDnsMode getPrivateDnsMode(@NonNull LinkProperties linkProperties) {
        if(linkProperties.getPrivateDnsServerName() != null)
            return PrivateDnsMode.STRICT;
        else if(linkProperties.isPrivateDnsActive())
            return PrivateDnsMode.OPPORTUNISTIC;
        else
            return PrivateDnsMode.DISABLED;
    }

    public static @NonNull Enumeration<NetworkInterface> getNetworkInterfaces() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if(ifs != null)
                return ifs;
        } catch (SocketException | NullPointerException e) {
            // NullPointerException can be thrown on Android < 31 with virtual interface without a
            // parent interface
            e.printStackTrace();
        }

        return Collections.enumeration(new ArrayList<>());
    }

    public static boolean isReadable(String path) {
        try(FileInputStream ignored = new FileInputStream(path)) {
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
