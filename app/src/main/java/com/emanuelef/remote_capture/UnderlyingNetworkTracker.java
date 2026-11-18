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
 * Copyright 2026 - Emanuele Faranda
 */

package com.emanuelef.remote_capture;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/* Tracks the network underlying the capture, which provides the system DNS server. This is the
 * active network or, when the active network is a VPN, the non-VPN network underneath it. */
public class UnderlyingNetworkTracker {
    private static final String TAG = "UnderlyingNetworkTracker";
    private static final int UPDATE_DELAY_MS = 200;
    private final ConnectivityManager mCm;
    private final Handler mHandler;
    private final boolean mSkipVpn;
    private final Runnable mUpdateNotifier = this::notifyUpdate;
    private Listener mListener;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private ConnectivityManager.NetworkCallback mBestNetworkCallback;
    private Network mBestNetwork;

    public interface Listener {
        // Invoked on the main thread when the underlying network, or its properties, may have changed
        void onNetworkUpdate(@Nullable Network network);
    }

    // skipVpn: when the active network is a VPN, use its underlying network
    public UnderlyingNetworkTracker(Context ctx, boolean skipVpn) {
        mCm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        mHandler = new Handler(Looper.getMainLooper());
        mSkipVpn = skipVpn;
    }

    public @Nullable Network getNetwork() {
        Network net = mCm.getActiveNetwork();

        // The active network may be our VPN, or another VPN (e.g. another PCAPdroid build) which is
        // going to be replaced by our VPN. Their DNS server is not reachable, so use the underlying network
        if((net != null) && mSkipVpn && Utils.isVpnNetwork(mCm, net)) {
            // The best network is only known since Android S, and only after the network callback
            // reports it, so guess it otherwise
            Network underlying = (mBestNetwork != null) ? mBestNetwork : getNonVpnInternetNetwork(mCm, net);
            Log.d(TAG, "Active network " + net + " is a VPN, using underlying network " + underlying);
            net = underlying;
        }

        return net;
    }

    // Starts monitoring the network changes, which are reported to the listener. Returns false
    // if the monitoring is not available
    public boolean start(Listener listener) {
        if(mNetworkCallback != null)
            return true;

        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                Log.d(TAG, "onAvailable " + network);
                scheduleUpdate();
            }

            @Override
            public void onLost(@NonNull Network network) {
                Log.d(TAG, "onLost " + network);
                scheduleUpdate();
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
                // the validation state determines the preferred network
                scheduleUpdate();
            }

            @Override
            public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties linkProperties) {
                Log.d(TAG, "onLinkPropertiesChanged " + network);
                scheduleUpdate();
            }
        };

        try {
            Log.d(TAG, "registerNetworkCallback");

            // VPN networks are included, as in root mode another VPN may be the active network
            mCm.registerNetworkCallback(
                    new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                    mNetworkCallback);
        } catch (SecurityException e) {
            // this is a bug in Android 11 - https://issuetracker.google.com/issues/175055271?pli=1
            e.printStackTrace();

            Log.w(TAG, "registerNetworkCallback failed");
            mNetworkCallback = null;
            return false;
        }

        mListener = listener;

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            registerBestNetworkCallback();

        return true;
    }

    public void stop() {
        if(mNetworkCallback == null)
            return;

        unregisterCallback(mNetworkCallback);
        mNetworkCallback = null;

        if(mBestNetworkCallback != null) {
            unregisterCallback(mBestNetworkCallback);
            mBestNetworkCallback = null;
            mBestNetwork = null;
        }

        mListener = null;
        mHandler.removeCallbacks(mUpdateNotifier);
    }

    // Tracks the network that Android would pick as the default one when no VPN is active, which
    // is the one underlying a VPN
    @RequiresApi(api = Build.VERSION_CODES.S)
    private void registerBestNetworkCallback() {
        mBestNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                Log.d(TAG, "Best network: " + network);
                mBestNetwork = network;
                scheduleUpdate();
            }

            @Override
            public void onLost(@NonNull Network network) {
                if(network.equals(mBestNetwork)) {
                    Log.d(TAG, "Best network lost: " + network);
                    mBestNetwork = null;
                    scheduleUpdate();
                }
            }
        };

        // NOT_VPN is part of the default request capabilities. The callback runs on the main
        // thread, which is the only one accessing mBestNetwork
        Log.d(TAG, "registerBestMatchingNetworkCallback");
        mCm.registerBestMatchingNetworkCallback(
                new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                mBestNetworkCallback, mHandler);
    }

    private void unregisterCallback(ConnectivityManager.NetworkCallback callback) {
        try {
            Log.d(TAG, "unregisterNetworkCallback");
            mCm.unregisterNetworkCallback(callback);
        } catch(IllegalArgumentException e) {
            Log.w(TAG, "unregisterNetworkCallback failed: " + e);
        }
    }

    private void scheduleUpdate() {
        // Coalesce the bursts of network events, and let the connectivity state settle (e.g. the
        // lost network may still be reported as available right after onLost)
        mHandler.removeCallbacks(mUpdateNotifier);
        mHandler.postDelayed(mUpdateNotifier, UPDATE_DELAY_MS);
    }

    private void notifyUpdate() {
        // mNetworkCallback runs on a ConnectivityManager thread, so it may schedule an update
        // after stop() is called
        if(mListener == null)
            return;

        mListener.onNetworkUpdate(getNetwork());
    }

    // Returns the non-VPN network with Internet access which is most likely the default one, i.e.
    // the one underlying the given VPN network. Since Android P, the VPN carries the transports of
    // its underlying network, which is the most reliable hint. Otherwise, it mimics the Android
    // preference for validated networks and for wifi/ethernet over cellular.
    @SuppressWarnings("deprecation")
    private static Network getNonVpnInternetNetwork(ConnectivityManager cm, Network vpn) {
        Network best = null;
        int bestScore = -1;

        try {
            NetworkCapabilities vpnCap = cm.getNetworkCapabilities(vpn);

            for(Network net : cm.getAllNetworks()) {
                NetworkCapabilities cap = cm.getNetworkCapabilities(net);
                if((cap == null) || cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                    continue;

                // Since P, the transports indicate the underlying network type
                int score = (((vpnCap != null) && hasCommonTransport(cap, vpnCap)) ? 4 : 0)
                        + (cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 2 : 0)
                        + (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? 0 : 1);
                if(score > bestScore) {
                    best = net;
                    bestScore = score;
                }
            }
        } catch (SecurityException e) {
            // this is a bug in Android 11 - https://issuetracker.google.com/issues/175055271?pli=1
            e.printStackTrace();
        }

        return best;
    }

    private static boolean hasCommonTransport(NetworkCapabilities a, NetworkCapabilities b) {
        final int[] transports = {
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_ETHERNET,
                NetworkCapabilities.TRANSPORT_BLUETOOTH
        };

        for(int transport : transports) {
            if(a.hasTransport(transport) && b.hasTransport(transport))
                return true;
        }

        return false;
    }
}
