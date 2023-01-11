# PCAPdroid

PCAPdroid is a privacy-friendly open source app which lets you track, analyze and block the connections made by the other apps in your device. It also allows you to export a PCAP dump of the traffic, inspect HTTP, decrypt TLS traffic and much more!

PCAPdroid simulates a VPN in order to capture the network traffic without root. It does not use a remote VPN server, instead data is processed locally on the device.

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/master/fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="190" />
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/master/fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="190" />
</p>

Features:

- Log and examine the connections made by user and system apps
- Extract the SNI, DNS query, HTTP URL and the remote IP address
- Inspect HTTP requests and replies thanks to the built-in decoders
- Inspect the full connections payload as hexdump/text
- [Decrypt the HTTPS/TLS traffic](https://emanuele-f.github.io/PCAPdroid/tls_decryption) and export the SSLKEYLOGFILE
- Dump the traffic to a PCAP file, download it from a browser, or stream it to a remote receiver for real-time analysis (e.g. Wireshark)
- Create rules to filter out the good traffic and easily spot anomalies
- Identify the country and ASN of remote server via offline DB lookups
- On rooted devices, capture the traffic while other VPN apps are running

Paid features:

- [Firewall](https://emanuele-f.github.io/PCAPdroid/paid_features#51-firewall): create rules to block individual apps, domains and IP addresses
- [Malware detection](https://emanuele-f.github.io/PCAPdroid/paid_features#52-malware-detection): detect malicious connections by using third-party blacklists

If you plan to use PCAPdroid to perform packet analysis, please check out <a href='https://emanuele-f.github.io/PCAPdroid/quick_start#14-packet-analysis'>the specific section</a> of the manual.

<a href="https://f-droid.org/packages/com.emanuelef.remote_capture">
    <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">
</a> <a href='https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture'><img height="80" alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png'/></a>

You can test the latest features before the official release by adding the [Beta repository](https://pcapdroid.org/fdroid/repo/) to the F-Droid app.

## User Guide

Check out the [quick start instructions](https://emanuele-f.github.io/PCAPdroid/quick_start) or the full [User Guide](https://emanuele-f.github.io/PCAPdroid).

## Sponsors

The PCAPdroid project is sponsored by [AVEQ GmbH](https://aveq.info).

If you want to sponsor this project [drop me an email](mailto:black.silver@hotmail.it?subject=PCAPdroid%20sponsorship).

## Community

You can help the PCAPdroid project in many ways:

- [Make a donation](https://emanuele-f.github.io/PCAPdroid/donate)
- Translate the app on [Weblate](https://hosted.weblate.org/engage/pcapdroid/)
<a href="https://hosted.weblate.org/engage/pcapdroid/">
  <img src="https://hosted.weblate.org/widgets/pcapdroid/-/app/multi-auto.svg" alt="Translation status" />
</a>

- Improve the app theme and layout
- Star the project on Github and on [Google Play](https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture)
- Of course provide code pull requests!

Join the international PCAPdroid community [on Telegram](https://t.me/PCAPdroid) or [on Matrix](https://matrix.to/#/#pcapdroid:matrix.org).

## Integrating into your APP

Some features of PCAPdroid can be integrated into a third-party app to provide packet capture capabilities.

- For rooted devices, the [pcapd daemon](https://github.com/emanuele-f/PCAPdroid/tree/master/app/src/main/jni/pcapd) can be directly integrated into your APK to capture network packets.
- For all the devices, PCAPdroid [exposes an API](https://github.com/emanuele-f/PCAPdroid/blob/master/docs/app_api.md) to control the packet capture and send the captured packets via UDP to your app. This requires to install PCAPdroid along with your app.

## Third Party

- [zdtun](https://github.com/emanuele-f/zdtun): TCP/UDP/ICMP connections proxy
- [nDPI](https://github.com/ntop/nDPI): deep packet inspection library, used to extract the connections metadata
- [mitmproxy](https://github.com/mitmproxy/mitmproxy): a local proxy used to perform TLS decryption

For the complete list of third party libraries and the corresponding licenses check out the "About" page in the app.

## Building

1. On Windows, install [gitforwindows](https://gitforwindows.org)
2. Clone this repo
3. Inside the repo dir, run `git submodule update --init`. The `submodules` directory should get populated.
4. Open the project in Android Studio, install the appropriate SDK and the NDK
5. Build the app

*Note*: If you get "No valid CMake executable was found", be sure to install the CMake version used by PCAPdroid (currently [3.22.1](https://github.com/emanuele-f/PCAPdroid/blob/master/app/build.gradle)) from the SDK manager

