/*
 * The MIT License
 *
 * Copyright (c) 2013 Edin Dazdarevic (edin.dazdarevic@gmail.com)

 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:

 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.

 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * */

package com.emanuelef.remote_capture;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * A class that enables to get an IP range from CIDR specification. It supports
 * both IPv4 and IPv6.
 */
public class Cidr {
    private static final String TAG = "CIDR";
    private final InetAddress inetAddress;
    private final String stringCidr;
    private final int prefixLength;
    private InetAddress startAddress;
    private InetAddress endAddress;
    private BigInteger startAddressBigInt;
    private BigInteger endAddressBigInt;

    public Cidr(String cidr) throws UnknownHostException, IllegalArgumentException {
        stringCidr = cidr;

        /* split CIDR to address and prefix part */
        if (cidr.contains("/")) {
            int index = cidr.indexOf("/");
            String addressPart = cidr.substring(0, index);
            String networkPart = cidr.substring(index + 1);

            inetAddress = InetAddress.getByName(addressPart);
            prefixLength = Integer.parseInt(networkPart);

            calculate();
        } else {
            Log.w(TAG, "Invalid CIDR: " + cidr);
            throw new IllegalArgumentException("not an valid CIDR format!");
        }
    }

    private void calculate() throws UnknownHostException {
        ByteBuffer maskBuffer;
        int targetSize;
        if (inetAddress.getAddress().length == 4) {
            maskBuffer =
                    ByteBuffer
                            .allocate(4)
                            .putInt(-1);
            targetSize = 4;
        } else {
            maskBuffer = ByteBuffer.allocate(16)
                    .putLong(-1L)
                    .putLong(-1L);
            targetSize = 16;
        }

        BigInteger mask = (new BigInteger(1, maskBuffer.array())).not().shiftRight(prefixLength);

        ByteBuffer buffer = ByteBuffer.wrap(inetAddress.getAddress());
        BigInteger ipVal = new BigInteger(1, buffer.array());

        BigInteger startIp = ipVal.and(mask);
        BigInteger endIp = startIp.add(mask.not());

        byte[] startIpArr = toBytes(startIp.toByteArray(), targetSize);
        byte[] endIpArr = toBytes(endIp.toByteArray(), targetSize);

        this.startAddress = InetAddress.getByAddress(startIpArr);
        this.startAddressBigInt = new BigInteger(1, this.startAddress.getAddress());

        this.endAddress = InetAddress.getByAddress(endIpArr);
        this.endAddressBigInt = new BigInteger(1, this.endAddress.getAddress());

    }

    private byte[] toBytes(byte[] array, int targetSize) {
        int counter = 0;
        List<Byte> newArr = new ArrayList<Byte>();
        while (counter < targetSize && (array.length - 1 - counter >= 0)) {
            newArr.add(0, array[array.length - 1 - counter]);
            counter++;
        }

        int size = newArr.size();
        for (int i = 0; i < (targetSize - size); i++) {

            newArr.add(0, (byte) 0);
        }

        byte[] ret = new byte[newArr.size()];
        for (int i = 0; i < newArr.size(); i++) {
            ret[i] = newArr.get(i);
        }
        return ret;
    }

    public String getNetworkAddress() {
        return this.startAddress.getHostAddress();
    }

    public String getBroadcastAddress() {
        return this.endAddress.getHostAddress();
    }

    public boolean isInRange(String ipAddress) throws UnknownHostException {
        InetAddress address = InetAddress.getByName(ipAddress);
        return isInRange(address);
    }

    public boolean isInRange(InetAddress address) {
        BigInteger start = this.startAddressBigInt;
        BigInteger end = this.endAddressBigInt;
        BigInteger target = new BigInteger(1, address.getAddress());

        if (start.compareTo(target) > 0){
            return false; //start is higher than address -> is not in range
        }
        return end.compareTo(target) >= 0; // end is higher or equal -> is in range
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof Cidr other))
            return super.equals(obj);

        return (other.startAddress.equals(startAddress)) &&
                (other.endAddress.equals(endAddress));
    }

    @NonNull
    @Override
    public String toString() {
        return stringCidr;
    }
}