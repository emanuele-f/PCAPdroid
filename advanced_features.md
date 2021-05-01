
## 4.1 DNS Server

When IPv6 is enabled via the PCAPdroid settings, the IPv6 DNS server `2001:4860:4860::8888` will be used.

For IPv4 connections, PCAPdroid tries to detect the DNS server used by the device and use it. This only works on Android Marshmallow and after.
If the detection fails or the primary data connection (e.g. 4G or WiFi) changes, the `8.8.8.8` DNS server is used. You can review the currently used IPv4
DNS server in the `Stats` page.

## 4.2 DoH Support

PCAPdroid detects and blocks DNS-over-HTTPS requests. This is necessary in order to extract and display the DNS query being sent. The DNS queries are then sent in
cleartext.

## 4.3 IPv6 Support

PCAPdroid supports IPv6 TCP/UDP connections. IPv6 must be manually enabled from the *VPN* settings.
By default, IPv6 is disabled, in which case no IPv6 internet connection will be established (or monitored) when PCAPdroid is active.
When enabled, all the IPv6 unicast traffic will be monitored by PCAPdroid.

In order to avoid getting a "Unreachable" connections errors, IPv6 should only be enabled when the primary data connection (e.g. 4G or WiFi) actually supports IPv6.
In general, you can safely disable IPv6 unless you actually want to monitor an IPv6 service.

## 4.4 Root Capture

Since version 1.3.6, it's possible to capture the network traffic directly from the network interface of the Android device without creating a VPNService. This allows PCAPdroid to run while other VPN apps are running. A rooted device is needed to use this feature.

In this mode, PCAPdroid performs a "raw" capture, meaning that real packets are captured as they appear on the network interface. This means that the limitations described in the [PCAP Reliability section](https://emanuele-f.github.io/PCAPdroid/quick_start#14-pcap-reliability) do not apply. It's important to note, however, that PCAPdroid will skip the Ethernet headers to provide the same PCAP format regardless of the network interface in use.

While common network tools like tcpdump require you to select a specific network interface, PCAPdroid automatically detects the internet-facing interface and it captures the packets from it. Moreover it automatically changes the capture interface when, for example, the device switches from WiFi to the mobile network.

The root capture feature can be enabled from the app settings. It is only displayed when root access is detected, so if you are using magisk hide you will need to whitelist the app. Root permissions will only be asked while starting the capture. PCAPdroid will spawn a daemon process as root and will communicate with it to capture the packets. The daemon will be stopped when the capture is stopped from the app.

Please note that the following limitations apply for this mode:

- The SOCKS5 proxy is not available
- Only IPv4 connections will be shown (no IPv6, no ICMP, no Ethernet)
- Domain names for DNS requests sent via DoH will not be visible. You need to disable/block DoH if you want to extract domain names.
- The connections status show in PCAPdroid does not currently reflect the actual status of the connections
- Some broadcast/multicast connections may have a wrong traffic direction
