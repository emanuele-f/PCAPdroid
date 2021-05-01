# PCAPdroid

PCAPdroid is an open source network monitoring and capture tool. It can capture an Android device traffic without rooting the device. The traffic can be sent to a remote receiver.

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/master/fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="190" />
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/master/fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="190" />
</p>

Features:

- Log and examine the connections made by the user and system apps
- Extract SNI, DNS query, URL and remote IP address
- Apply a filter to only capture the traffic of the selected app
- Easily download a PCAP file of the traffic thanks to the integrated HTTP server
- Stream the PCAP to a remote receiver for further analysis (e.g. wireshark)
- Decrypt HTTPS/TLS traffic via a remote mitmproxy

**Important**: the PCAP generated by PCAPdroid is not 100% accurate. Check out [PCAP Reliability](https://emanuele-f.github.io/PCAPdroid/quick_start#14-pcap-reliability) for more details.

PCAPdroid leverages the Android VpnService to receive all the traffic generated by the Android apps. No external VPN is actually created, the traffic is locally processed by the app.

<a href="https://f-droid.org/packages/com.emanuelef.remote_capture">
    <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">
</a> <a href='https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture'><img height="80" alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png'/></a>

<sub>Google Play and the Google Play logo are trademarks of Google LLC.</sub>

## User Guide

Check out the [quick start instructions](https://emanuele-f.github.io/PCAPdroid/quick_start) or the full [User Guide](https://emanuele-f.github.io/PCAPdroid).

## Community

You can help the PCAPdroid project in many ways:

- Translate the app in your language
- Improve the app theme and layout
- Propose and discuss new features
- Open bug reports with detailed information
- [Make a donation](https://emanuele-f.github.io/PCAPdroid/donate)
- Star the project on github and on the [Play Store](https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture)
- Of course provide code pull requests!

You can reach the PCAPdroid community on the [telegram group](https://t.me/PCAPdroid).

## What is the netd app

Many applications rely on the Android DNS resolution service `netd` in order to resolve names. In such cases PCAPdroid will be unable to determine the originating app and will instead mark the connection with a question mark. Nevertheless, it will properly capture the selected app DNS traffic when an app filter is set.

## Decrypting HTTPS/TLS Traffic

PCAPdroid supports decrypting TLS traffic by sending it to mitmproxy. Check out the [User Guide](https://emanuele-f.github.io/PCAPdroid/tls_decryption) for more details.

## Third Party

- [zdtun](https://github.com/emanuele-f/zdtun): TCP/UDP/ICMP connections proxy
- [nDPI](https://github.com/ntop/nDPI): deep packet inspection library, used to extract the connections metadata
- [nanohttpd](https://github.com/NanoHttpd/nanohttpd): tiny HTTP server
- [CustomActivityOnCrash](https://github.com/Ereza/CustomActivityOnCrash): handles app crashes gracefully and allows to copy the crash log

## Building

1. Clone this repo
2. Install the native dependencies: `autogen autoconf libtool pkg-config libpcap-dev libjson-c-dev`
3. Run `git submodule update --init`
4. Build the app
