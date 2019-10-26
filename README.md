# Remote Capture

Remote Capture captures the android apps traffic to analyze it remotely (e.g. via Wireshark). The traffic is sent live via an UDP socket and can be easily captured remotely with:

<img src="https://raw.githubusercontent.com/emanuele-f/RemoteCapture/master/playstore/screenshots/capturing.jpg" width="200" />

Features:

- Capture apps traffic without root
- Send captured traffic via UDP
- Show captured traffic realtime statistics
- Apply a filter to only capture traffic for the selected app

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

## Building

In order to build the app, you need to clone https://github.com/emanuele-f/zdtun beside the RemoteCapture directory
