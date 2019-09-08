Remote Capture

Capture traffic on android devices and send the PCAP via UDP. No root privileges required.

Features:

  - Capture DNS traffic
  - Only capture traffic of a specific APP

TODO:

  - ICMP support
  - App icon and layout

## Receiving the PCAP

In order to receive the PCAP on the collector host, perform the following steps in order:

  1. Ensure that the Remote Capture VPN is not running (key icon is not shown)
  2. Run the PCAP collector program (e.g. wireshark) on the host
  3. Start the Remote Capture VPN via the start button

To start a new capture, stop the VPN and repeat the steps above.

### Examples

- Analyze the traffic in Wireshark:

```bash
socat -b 65535 - udp4-listen:1234 | wireshark -k -i -
```

- Write the traffic to a PCAP file:

```bash
socat -b 65535 - udp4-listen:1234 | tcpdump -w dump.pcap -r -
```

Note: the `-b` option of `socat` is required as the default UDP buffer size of 8192 B
of `nc` or `socat` is not enough to handle the encapsulated packets.
