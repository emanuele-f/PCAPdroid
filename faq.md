**Does PCAPdroid support decrypting TLS 1.3?**

PCAPdroid relies on [mitmproxy](https://mitmproxy.org/) for the TLS decryption and it supports TLS 1.3.

**Can I decrypt TLS without mitmproxy?**

Currently no. Doing TLS decryption right takes a lot of effort and I don't known any open source libraries to integrate in PCAPdroid. This is why I have decided to modify mitmproxy rather than implementing the decryption directly into PCAPdroid.

**Why is PCAPdroid asking me to create a VPN? Will it steal my data?**

PCAPdroid uses the android [VpnService](https://developer.android.com/reference/android/net/VpnService) to be able to capture the packets without root. PCAPdroid processes the traffic locally so your data is in your hands.

**Does PCAPdroid expose me to data leakage?**

You should only run PCAPdroid on a trusted local network as PCAPdroid does not encrypt your traffic. When using the HTTP server mode, any device in your local network can connect to PCAPdroid to download the PCAP of the traffic! PCAPdroid can also be configured to send your traffic over the internet (by enabling the UDP Exporter mode and using a public IP address), however doing this is discouraged.

**Can I constantly monitor my device traffic?**

PCAPdroid is not optimized to reduce the battery consumtion. Do it at your own risk!

**Can PCAPdroid block connections/disable ADS in app**

While tecnically possible, PCAPdroid is not a firewall app. For this kind of stuff you can check out the wonderful [NetGuard](https://github.com/M66B/NetGuard) project.

**Can I switch network connection while running PCAPdroid?**

PCAPdroid currently does not support this. The connections will break if your main IP address changes, e.g. if you switch from the mobile network to the WiFi network.
