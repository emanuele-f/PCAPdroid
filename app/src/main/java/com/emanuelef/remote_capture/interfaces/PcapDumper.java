package com.emanuelef.remote_capture.interfaces;

import java.io.IOException;

/** A dumper implements the ability to dump PCAP data.
 * It has the following lifecycle:
 *
 * startDumper -> ... dumpData ... -> stopDumper
 *
 * In order to avoid monitoring the dumper traffic (which would cause a loop), a dumper must implement
 * the following policy:
 *  - for root capture, the getBpf method must return a BPF filter to exclude the traffic. This will
 *    be set at the start of the catpure.
 *  - for non-root capture, the dumper must pass each socket it opens to the CaptureService.protect
 *    method.
 */
public interface PcapDumper {
    /**
     * Starts the dumper.
     * @throws IOException
     */
    void startDumper() throws IOException;

    /**
     * Terminates the dumper.
     * @throws IOException
     */
    void stopDumper() throws IOException;

    /** Get a BPF to use to ignore the connections made by the dumper.
     *
     * @return the BPF string
     */
    String getBpf();

    /**
     * Dump an unspecified number of PCAP records. The dumper must check if this is the first data
     * sent, in which case it should send the Utils.PCAP_HEADER bofore the PCAP records data.
     * @throws IOException
     */
    void dumpData(byte[] data) throws IOException;
}
