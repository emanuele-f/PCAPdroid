# PCAPdroid

PCAPdroid is an android network tool to capture the device traffic and export it remotely for further analysis (e.g. via Wireshark). The traffic can be easily downloaded from a remote device thanks to the integrated HTTP server, or streamed to a remote UDP receiver.

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/master/fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="200" />
</p>

Realtime Traffic Analysis:

```bash
tools/udp_receiver.py -p 1234 | wireshark -k -i -
```

Features:

- Capture apps traffic without root
- Easily download a PCAP file thanks to the integrated HTTP server
- Stream the PCAP on UDP to a remote receiver
- Show captured traffic realtime statistics
- Apply a filter to only capture traffic for the selected app
- Get active connections details, including server name, DNS query and URL
- Decrypt HTTPS/TLS traffic via a remote mitmproxy

## Download:

[Releases](https://github.com/emanuele-f/PCAPdroid/releases)

[F-Droid](https://f-droid.org/en/packages/com.emanuelef.remote_capture)

[Play Store](https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture)

## User Guide

Check out the [quick start instructions](https://emanuele-f.github.io/PCAPdroid/quick_start) or the full [User Guide](https://emanuele-f.github.io/PCAPdroid).

## Community

You can help the PCAPdroid project in many ways:

- Translate the app in your language
- Improve the app theme and layout
- Propose and discuss new features
- Open bug reports with detailed information
- Star the project on github and on the [Play Store](https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture)
- Of course provide code pull requests!

You can reach the PCAPdroid developers and community on the [telegram group](https://t.me/PCAPdroid).

## DNS Traffic has unknown app

Many applications rely on the Android DNS resolution service `netd` in order to resolve names. In such cases PCAPdroid will be unable to determine the originating app and will instead mark the connection with a question mark. Nevertheless, it will properly capture the selected app DNS traffic when an app filter is set.

## How it Works

In order to run without root, the app takes advantage of the Android VPNService API to collect the packets on the device (they are *not* sent to an external VPN server). The [zdtun](https://github.com/emanuele-f/zdtun) connections proxy library is used to route the packets back to their original destination. Here are some diagrams of how it works:
  
<p align="center">
  <img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/master/assets/handshake.png" width="250" />
  <img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/master/assets/send_recv.png" width="250" />
</p>

## Decrypting HTTPS/TLS Traffic

PCAPdroid supports decrypting TLS traffic by sending it to a customized version of mitmproxy. Check out the [User Guide](https://emanuele-f.github.io/PCAPdroid/tls_decryption) for more details.

## Third Party

PCAPdroid integrates [nDPI](https://github.com/ntop/nDPI) to detect the application protocol of the network connections and extract the SNI from TLS connections.

## Building

1. Clone this repo
2. Install the native dependencies: `autogen autoconf libtool pkg-config libpcap-dev libjson-c-dev`
3. Run `git submodule update --init`
4. Build the app
