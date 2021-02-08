**Does PCAPdroid support decrypting TLS 1.3?**

PCAPdroid relies on [mitmproxy](https://mitmproxy.org/) for the TLS decryption and it supports TLS 1.3.
<br/><br/>

**Can I decrypt TLS without mitmproxy?**

Currently no. Doing TLS decryption right takes a lot of effort and I don't known any open source libraries to integrate in PCAPdroid. This is why I decided to modify mitmproxy rather than implementing the decryption directly into PCAPdroid. If you are a developer and want to implement this feature, your contribution is welcome!
<br/><br/>

**Why I see connections originating from the IP 10.215.173.1?**

`10.215.173.1` is the IP address of the virtual interface created by PCAPdroid. As PCAPdroid acts like a proxy, all the connections have this source address.
<br/><br/>

**Why I see DNS requests to 10.215.173.2?**

`10.215.173.2` is virtual IP address used by PCAPdroid to capture the DNS traffic. During startup, PCAPdroid detects the DNS server in use by the device and proxies all the DNS requests to such address. Only UDP DNS queries as currently supported. TCP DNS queries are dropped.

**Why is PCAPdroid asking me to create a VPN? Will it steal my data?**

In order to run without root, the app takes advantage of the Android [VpnService](https://developer.android.com/reference/android/net/VpnService) API to collect the packets on the device. They are not sent to an external VPN server.
<br/><br/>

**Can I constantly monitor my device traffic?**

PCAPdroid is not optimized to reduce the battery consumtion. Do it at your own risk!
<br/><br/>

**Can PCAPdroid block connections/disable ADS in app**

While tecnically possible, PCAPdroid is not a firewall app. For this kind of stuff you can check out the wonderful [NetGuard](https://github.com/M66B/NetGuard) project.
<br/><br/>

**Can I capture the hotspot/tethering traffic?**

This is not possible without root. A detailed explanation is provided at https://github.com/emanuele-f/PCAPdroid/issues/20. There is a workaround to capture only the HTTP/S traffic, which is to install an HTTP proxy on the android phone and configure the client device to use this proxy.
<br/><br/>

**Does PCAPdroid expose me to data leakage?**

You should only run PCAPdroid on a trusted local network as PCAPdroid does not encrypt your traffic. When using the HTTP server mode, any device in your local network can connect to PCAPdroid to download the PCAP of the traffic! PCAPdroid can also be configured to send your traffic over the internet (by enabling the UDP Exporter mode and using a public IP address), however doing this is discouraged.
<br/><br/>
