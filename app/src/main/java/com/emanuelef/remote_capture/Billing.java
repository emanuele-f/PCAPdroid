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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import androidx.preference.PreferenceManager;

import com.emanuelef.remote_capture.model.Prefs;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/* Billing stub */
public class Billing {
    private static final String TAG = "Billing";
    private static final String KEY = "ME4wEAYHKoZIzj0CAQYFK4EEACEDOgAE6cS1N1P0kaiuxq0g70OVVE0uIOD+t809" +
            "Etg3k2h11k8uNvfkx3mL1HTjQyzSfdueyY4DqTW7+sk=";
    private static final String PEER_SKU_KEY = "peer_skus";

    // SKUs
    public static final String SUPPORTER_SKU = "pcapdroid_supporter";
    public static final String UNLOCK_TOKEN_SKU = "unlock_code";
    public static final String MALWARE_DETECTION_SKU = "malware_detection";
    public static final String FIREWALL_SKU = "no_root_firewall";
    public static final String PCAPNG_SKU = "pcapng";
    public static final List<String> ALL_SKUS = Arrays.asList(
            SUPPORTER_SKU, UNLOCK_TOKEN_SKU, MALWARE_DETECTION_SKU, FIREWALL_SKU, PCAPNG_SKU
    );

    // Resources used in the play build, referenced here to avoid being marked as unused resources
    private static final int[] res_placeholder = {
            R.string.billing_connecting, R.string.pending_transaction,
            R.string.feature_not_available, R.string.show_me,
            R.string.loading, R.string.purchased,
            R.string.no_items_for_purchase, R.string.billing_failure,
            R.string.learn_more, R.string.buy_action,
            R.string.can_use_purchased_feature, R.drawable.ic_shopping_cart,
            R.string.firewall_summary, R.string.no_root_firewall,
            R.string.unlock_token, R.string.unlock_token_summary, R.string.unlock_token_error,
            R.string.license_service_unavailable, R.string.requesting_unlock_token, R.string.show_action, R.string.unlock_token_msg1,
            R.string.qr_license_confirm, R.string.qr_purchase_required, R.string.license_limit_reached,
            R.string.license_error, R.string.requesting_license
    };

    protected final Context mContext;
    protected SharedPreferences mPrefs;

    // this is initialized in MainActivity
    private static final HashSet<String> mPeerSkus = new HashSet<>();

    protected Billing(Context ctx) {
        mContext = ctx;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        // Load peer skus
        Set<String> peer_skus = mPrefs.getStringSet(PEER_SKU_KEY, null);
        if(peer_skus != null)
            mPeerSkus.addAll(peer_skus);
    }

    public static Billing newInstance(Context ctx) {
        return new Billing(ctx);
    }

    public boolean isAvailable(String sku) {
        return isPurchased(sku);
    }

    public boolean isPurchased(String sku) {
        if(mPeerSkus.contains(sku))
            return true;

        return !getLicense().isEmpty();
    }

    public boolean isPlayStore() {
        return false;
    }

    public String getLicense() {
        return mPrefs.getString("license", "");
    }

    public void connectBilling() {}
    public void disconnectBilling() {}

    public boolean setLicense(String license) {
        boolean valid = true;
        if(!isValidLicense(license)) {
            license = "";
            valid = false;
        }

        mPrefs.edit()
                .putString("license", license)
                .apply();

        return valid;
    }

    public boolean isValidLicense(String license) {
        if(license.isEmpty())
            return false;

        try {
            // license data provided by the user
            byte[] data = Utils.base32Decode(license);
            if((data.length != 60) || (data[0] != 'v') || (data[1] != '1'))
                return false;

            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            PublicKey pk = keyFactory.generatePublic(new X509EncodedKeySpec(android.util.Base64.decode(KEY, android.util.Base64.DEFAULT)));
            Signature sig = Signature.getInstance("SHA1withECDSA");
            sig.initVerify(pk);

            String msg = SUPPORTER_SKU + "@" + getInstallationId();
            sig.update(msg.getBytes(StandardCharsets.US_ASCII));
            return sig.verify(getASN1(data, 4));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException
                | SignatureException | IllegalArgumentException e) {
            Log.d(TAG, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public String getInstallationId() {
        // NOTE: On Android >= O, the ID is unique to each combination of package, key, user and device
        String installation_id = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                Settings.Secure.getString(mContext.getContentResolver(), Settings.Secure.ANDROID_ID) :
                Build.SERIAL;

        try {
            // Calculate the MD5 to provide a consistent output and to increase privacy on Android < O
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(installation_id.getBytes());
            installation_id = "M" + Utils.byteArrayToHex(digest, 8);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen
            e.printStackTrace();
            installation_id = "D" + installation_id;
        }

        return installation_id;
    }

    private byte[] getASN1(byte[] signature, int offset) {
        int r_len = 28;

        if((signature.length - offset) != 2*r_len)
            throw new IllegalArgumentException("invalid signature length");

        int r_extra = (signature[offset] < 0) ? 1 : 0;
        int n_extra = (signature[offset + r_len] < 0) ? 1 : 0;
        int tot_len = 2*r_len + 6 + r_extra + n_extra;
        byte[] rv = new byte[tot_len];
        int i = 0;

        rv[i++] = 0x30; rv[i++] = (byte)(tot_len - 2);

        rv[i++] = 0x02; rv[i++] = (byte)(r_len + r_extra);
        if(r_extra > 0) rv[i++] = 0x00;
        System.arraycopy(signature, offset, rv, i, r_len);
        i += 28;

        rv[i++] = 0x02; rv[i++] = (byte)(r_len + n_extra);
        if(n_extra > 0) rv[i++] = 0x00;
        System.arraycopy(signature, offset + r_len, rv, i, r_len);

        return rv;
    }

    public boolean isFirewallVisible() {
        if(!isPurchased(Billing.FIREWALL_SKU))
            return false;

        if(CaptureService.isServiceActive())
            return !CaptureService.isCapturingAsRoot() && !CaptureService.isReadingFromPcapFile();
        else
            return !Prefs.isRootCaptureEnabled(mPrefs);
    }

    public void handlePeerSkus(Set<String> skus) {
        if(skus.equals(mPeerSkus))
            return; // nothing changed

        mPeerSkus.clear();
        mPeerSkus.addAll(skus);

        Log.i(TAG, "Peer skus updated: " + skus);

        mPrefs.edit()
                .putStringSet(PEER_SKU_KEY, mPeerSkus)
                .apply();
    }

    public void clearPeerSkus() {
        handlePeerSkus(new HashSet<>());
    }
}
