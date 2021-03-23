
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
