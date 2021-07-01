package com.emanuelef.remote_capture.interfaces;

import java.io.IOException;

/** A dumper implements the ability to dump PCAP data.
 * It has the following lifecycle:
 *
 * startDumper -> ... dumpData ... -> stopDumper
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

    /**
     * Dump an unspecified number of PCAP records. The dumper must check if this is the first data
     * sent, in which case it should send the Utils.PCAP_HEADER bofore the PCAP records data.
     * @throws IOException
     */
    void dumpData(byte[] data) throws IOException;
}
