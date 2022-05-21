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
import android.util.Log;

import androidx.preference.PreferenceManager;

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
import java.util.List;

/* Billing stub */
public class Billing {
    private static final String TAG = "Billing";
    private static final String KEY = "ME4wEAYHKoZIzj0CAQYFK4EEACEDOgAE6cS1N1P0kaiuxq0g70OVVE0uIOD+t809" +
            "Etg3k2h11k8uNvfkx3mL1HTjQyzSfdueyY4DqTW7+sk=";

    // SKUs
    public static final String SUPPORTER_SKU = "pcapdroid_supporter";
    public static final String MALWARE_DETECTION_SKU = "malware_detection";
    public static final String FIREWALL_SKU = "no_root_firewall";
    public static final List<String> ALL_SKUS = Arrays.asList(
            SUPPORTER_SKU, MALWARE_DETECTION_SKU, FIREWALL_SKU
    );

    // Resources used in the play build, referenced here to avoid being marked as unused resources
    private static final int[] res_placeholder = {
            R.string.billing_connecting, R.string.pending_transaction,
            R.string.feature_not_available, R.string.show_me,
            R.string.loading, R.string.purchased,
            R.string.no_items_for_purchase, R.string.billing_failure,
            R.string.learn_more, R.string.buy_action,
            R.string.can_use_purchased_feature, R.drawable.ic_shopping_cart,
    };

    protected final Context mContext;
    protected SharedPreferences mPrefs;

    protected Billing(Context ctx) {
        mContext = ctx;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public static PlayBilling newInstance(Context ctx) {
        return new PlayBilling(ctx);
    }

    public boolean isAvailable(String sku) {
        return isPurchased(sku);
    }

    public boolean isPurchased(String sku) {
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

    public void setLicense(String license) {
        if(!isValidLicense(license))
            license = "";

        mPrefs.edit()
                .putString("license", license)
                .apply();
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

            String msg = SUPPORTER_SKU + "@" + getSystemId();
            sig.update(msg.getBytes(StandardCharsets.US_ASCII));
            return sig.verify(getASN1(data, 4));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException | InvalidKeyException
                | SignatureException | IllegalArgumentException e) {
            Log.d(TAG, e.getMessage());
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    public String getSystemId() {
        // NOTE: On Android >= O, the ID is unique to each combination of package, key, user and device
        String system_id = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                Settings.Secure.getString(mContext.getContentResolver(), Settings.Secure.ANDROID_ID) :
                Build.SERIAL;

        try {
            // Calculate the MD5 to provide a consistent output and to increase privacy on Android < O
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest(system_id.getBytes());
            system_id = "M" + Utils.byteArrayToHex(digest, 8);
        } catch (NoSuchAlgorithmException e) {
            // Should never happen
            e.printStackTrace();
            system_id = "D" + system_id;
        }

        return system_id;
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

    public boolean canUseFirewall() {
        return isPurchased(Billing.FIREWALL_SKU) && !CaptureService.isCapturingAsRoot();
    }
}
