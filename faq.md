**The client does not trust the proxy's certificate, how to fix?**

For most apps, you will need a rooted device to successfully decrypt TLS traffic. This is explained in depth in [this section](https://emanuele-f.github.io/PCAPdroid/tls_decryption#34-caveats-and-possible-solutions) of the PCAPdroid user guide, along with some other possible solutions.
<br/><br/>

**How can I extract the URLs from an app?**

In PCAPdroid you can tap on an HTTP connection to show its details, which includes the requested URL. However, most apps employ HTTPS, in which case it's necessary to decrypt the connections via mitm in order to extract the URL. Check out the [TLS Decryption section](https://emanuele-f.github.io/PCAPdroid/tls_decryption) for details. If the app provides a web version, instead of decrypting the connections, it's easier to open the app in a browser on a PC and inspect the connections data via the browser developer tools.
<br/><br/>

**Why is PCAPdroid asking me to create a VPN?**

In order to run without root, the app takes advantage of the Android [VpnService](https://developer.android.com/reference/android/net/VpnService) API to collect the packets on the device itself. No data leaves the device.
<br/><br/>

**Can I capture the traffic of other devices in the network?**

No. PCAPdroid only captures the traffic of the Android device where it is running.
<br/><br/>

**Can I capture the traffic of other VPN apps?**

Yes! You need a rooted device, check out the [user guide](https://emanuele-f.github.io/PCAPdroid/advanced_features#44-root-capture).
<br/><br/>

**Why I see connections with IP 10.215.173.1/.2 ?**

`10.215.173.1` is the IP address of the virtual interface created by PCAPdroid. As PCAPdroid acts like a proxy, all the connections have this source address.
`10.215.173.2` is virtual IP address used by PCAPdroid to capture the DNS traffic.
<br/><br/>

**Can I capture the hotspot/tethering traffic?**

This depends on your OS implementation. Usually, this is not possible without root. A detailed explanation is provided at https://github.com/emanuele-f/PCAPdroid/issues/20. There is a workaround to capture only the HTTP/S traffic, which is to install an HTTP proxy on the Android phone and configure the client device to use this proxy.
<br/><br/>
