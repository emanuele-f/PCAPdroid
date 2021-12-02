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
 * Copyright 2020-21 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.UiModeManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SearchView;
import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.interfaces.TextAdapter;
import com.emanuelef.remote_capture.model.AppDescriptor;
import com.emanuelef.remote_capture.model.Prefs;
import com.emanuelef.remote_capture.views.AppsListView;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.net.ssl.HttpsURLConnection;

public class Utils {
    public static final int UID_UNKNOWN = -1;
    public static final int UID_NO_FILTER = -2;
    private static Boolean rootAvailable = null;
    private static Locale primaryLocale = null;

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

    public static String getCountryName(Context context, String country_code) {
        Locale cur_locale = getPrimaryLocale(context);
        return(new Locale(cur_locale.getCountry(), country_code)).getDisplayCountry();
    }

    public static boolean isRTL(Context ctx) {
        Locale locale = getPrimaryLocale(ctx);
        final int direction = Character.getDirectionality(locale.getDisplayName().charAt(0));

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
    public static String getLocalWifiIpAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
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

                            if ((addr instanceof Inet4Address) && !sAddr.equals("0.0.0.0")) {
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

    // Splits the provided data into individual PCAP records. Intended to be used with data received
    // via CaptureService::dumpPcapData
    public static Iterator<Integer> iterPcapRecords(byte[] data) {
        final ByteBuffer buf = ByteBuffer.wrap(data);
        buf.order(ByteOrder.nativeOrder());

        return new Iterator<Integer>() {
            @Override
            public boolean hasNext() {
                // 16: sizeof(pcaprec_hdr_s)
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

    public static boolean hasVPNRunning(Context context) {
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

    public static void showToast(Context context, int id) {
        String msg = context.getResources().getString(id);
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
    }

    public static void showToastLong(Context context, int id) {
        String msg = context.getResources().getString(id);
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

    public static Dialog getAppSelectionDialog(Activity activity, List<AppDescriptor> appsData, AppsListView.OnSelectedAppListener listener) {
        View dialogLayout = activity.getLayoutInflater().inflate(R.layout.apps_selector, null);
        SearchView searchView = dialogLayout.findViewById(R.id.apps_search);
        AppsListView apps = dialogLayout.findViewById(R.id.apps_list);
        TextView emptyText = dialogLayout.findViewById(R.id.no_apps);

        apps.setApps(appsData);
        apps.setEmptyView(emptyText);
        searchView.setOnQueryTextListener(apps);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(R.string.app_filter);
        builder.setView(dialogLayout);

        final AlertDialog alert = builder.create();
        alert.setCanceledOnTouchOutside(true);

        apps.setSelectedAppListener(app -> {
            listener.onSelectedApp(app);

            // dismiss the dialog
            alert.dismiss();
        });

        return alert;
    }

    public static String getUniqueFileName(Context context, String ext) {
        Locale locale = getPrimaryLocale(context);
        final DateFormat fmt = new SimpleDateFormat("dd_MMM_HH_mm_ss", locale);
        return  "PCAPdroid_" + fmt.format(new Date()) + "." + ext;
    }

    public static String getUniquePcapFileName(Context context) {
        return(Utils.getUniqueFileName(context, "pcap"));
    }

    public static BitmapDrawable scaleDrawable(Resources res, Drawable drawable, int new_x, int new_y) {
        if((new_x == 0) || (new_y == 0))
            return null;

        Bitmap bitmap = Bitmap.createBitmap(new_x, new_y, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return new BitmapDrawable(res, bitmap);
    }

    // Converts a TableLayout (two columns, label and value) to a string which can be copied
    public static String table2Text(TableLayout table) {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < table.getChildCount(); i++) {
            View v = table.getChildAt(i);

            if ((v instanceof TableRow) && (v.getVisibility() == View.VISIBLE)
                    && (((TableRow) v).getChildCount() == 2)) {
                View label = ((TableRow) v).getChildAt(0);
                View value = ((TableRow) v).getChildAt(1);

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
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            String version = pInfo.versionName;
            boolean isRelease = version.contains(".");

            appver = isRelease ? ("v" + version) : version;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e("Utils", "Could not retrieve package version");
            appver = "";
        }

        return appver;
    }

    public static boolean supportsFileDialog(Context context, Intent intent) {
        // https://commonsware.com/blog/2017/12/27/storage-access-framework-missing-action.html
        ComponentName comp = intent.resolveActivity(context.getPackageManager());

        return((comp != null) && (!"com.google.android.tv.frameworkpackagestubs".equals(comp.getPackageName())));
    }

    @SuppressWarnings("deprecation")
    public static Uri getInternalStorageFile(Context context, String fname) {
        ContentValues values = new ContentValues();

        //values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fname);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Android Q+ cannot directly access the external dir. Must use RELATIVE_PATH instead.
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        } else {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    Log.w("getInternalStorageFile", "external storage permission was denied");
                    return(null);
                }
            }

            // NOTE: context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) returns an app internal folder
            String path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/" + fname;
            Log.d("getInternalStorageFile", path);
            values.put(MediaStore.MediaColumns.DATA, path);
        }

        return context.getContentResolver().insert(
                MediaStore.Files.getContentUri("external"), values);
    }

    public static String getUriFname(Context context, Uri uri) {
        Cursor cursor;
        String fname;

        try {
            String []projection = {OpenableColumns.DISPLAY_NAME};
            cursor = context.getContentResolver().query(uri, projection, null, null, null);
        } catch (Exception e) {
            return null;
        }

        if((cursor == null) || !cursor.moveToFirst())
            return null;

        try {
            int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            if(idx < 0)
                return null;
            fname = cursor.getString(idx);
        } finally {
            cursor.close();
        }

        return fname;
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
        ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(ctx.getString(R.string.stats), contents);
        clipboard.setPrimaryClip(clip);

        Utils.showToast(ctx, R.string.copied_to_clipboard);
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
    public static String getRootDomain(String domain) {
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

    public static boolean unzip(InputStream is, String dstpath) {
        try(ZipInputStream zipIn = new ZipInputStream(is)) {
            ZipEntry entry = zipIn.getNextEntry();

            while (entry != null) {
                File dst = new File(dstpath + File.separator + entry.getName());

                if (entry.isDirectory()) {
                    if(!dst.mkdirs()) {
                        Log.w("unzip", "Could not create directories");
                        return false;
                    }
                } else {
                    // Extract file
                    try(BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst))) {
                        byte[] bytesIn = new byte[4096];
                        int read = 0;
                        while ((read = zipIn.read(bytesIn)) != -1)
                            bos.write(bytesIn, 0, read);
                    }
                }

                zipIn.closeEntry();
                entry = zipIn.getNextEntry();
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

                    try(InputStream in = new BufferedInputStream(con.getInputStream())) {
                        byte[] bytesIn = new byte[4096];
                        int read = 0;
                        while ((read = in.read(bytesIn)) != -1) {
                            bos.write(bytesIn, 0, read);
                            has_contents |= (read > 0);
                        }
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
                (new File(path + ".tmp")).delete(); // if exists
            } catch (Exception e) {
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
}
