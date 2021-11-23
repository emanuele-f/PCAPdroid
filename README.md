# PCAPdroid

PCAPdroid is an open source app which lets you monitor and export the network traffic of your device. The app simulates a VPN to achieve non-root capture but, contrary to a VPN, the traffic is processed locally into the device.

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/master/fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="190" />
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/master/fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="190" />
</p>

Features:

- Log and examine the connections made by user and system apps
- Extract the SNI, DNS query, HTTP request, HTTP URL and the remote IP address
- Create rules to filter out the good traffic and easily spot anomalies
- Dump the traffic into a PCAP file, download it from a browser, or stream it to a remote receiver for real time analysis (e.g. wireshark)
- Use the app in combination with mitmproxy to [decrypt HTTPS/TLS traffic](https://emanuele-f.github.io/PCAPdroid/tls_decryption) (technical knowledge required)
- On rooted devices, capture the traffic while other VPN apps are running

Paid Features:

- Detect malicious connections by using third-party blacklists

If you plan to use PCAPdroid to perform packet analysis, please check out <a href='https://emanuele-f.github.io/PCAPdroid/quick_start#14-packet-analysis'>the specific section</a> of the manual.

<a href="https://f-droid.org/packages/com.emanuelef.remote_capture">
    <img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
    alt="Get it on F-Droid"
    height="80">
</a> <a href='https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture'><img height="80" alt='Get it on Google Play' src='https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png'/></a>

<sub>Google Play and the Google Play logo are trademarks of Google LLC.</sub>

## User Guide

Check out the [quick start instructions](https://emanuele-f.github.io/PCAPdroid/quick_start) or the full [User Guide](https://emanuele-f.github.io/PCAPdroid).

## Sponsors

The PCAPdroid project is sponsored by [AVEQ GmbH](https://aveq.info).

If you are a business and want to sponsor this project, you can [reach me via email](mailto:black.silver@hotmail.it?subject=PCAPdroid%20sponsorship).

## Community

You can help the PCAPdroid project in many ways:

- Translate the app in your language
- Improve the app theme and layout
- Propose and discuss new features
- Open bug reports with detailed information
- [Make a donation](https://emanuele-f.github.io/PCAPdroid/donate)
- Star the project on github and on the [Play Store](https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture)
- Of course provide code pull requests!

You can [join the PCAPdroid community](https://t.me/PCAPdroid) on telegram.
The development of new features happens in the [dev branch](https://github.com/emanuele-f/PCAPdroid/tree/dev). Ensure to target this branch when making pull requests for new features. Here is the normal release cycle:

1. Changes are developed and pushed to the dev branch.
2. Once changes are stable enough, they are merged to the master branch. This is a good time to update translations.
3. After about 2 days (or more in case of a major update), the new version is released.

## Integrating into your APP

Some features of PCAPdroid can be integrated into a third-party app to provide packet capture capabilities.

- For rooted devices, the [pcapd daemon](https://github.com/emanuele-f/PCAPdroid/tree/master/app/src/main/jni/pcapd) can be directly integrated into your APK to capture network packets.
- For all the devices, PCAPdroid [exposes an API](https://github.com/emanuele-f/PCAPdroid/blob/master/docs/app_api.md) to control the packet capture and send the captured packets via UDP to your app. This requires to install PCAPdroid along with your app.

## Third Party

- [zdtun](https://github.com/emanuele-f/zdtun): TCP/UDP/ICMP connections proxy
- [nDPI](https://github.com/ntop/nDPI): deep packet inspection library, used to extract the connections metadata
- [nanohttpd](https://github.com/NanoHttpd/nanohttpd): tiny HTTP server

For the complete list of third party libraries and the corresponding licenses check out the "About" page in the app.

## Building

1. On Windows, install [gitforwindows](https://gitforwindows.org)
2. Clone this repo
3. Inside the repo dir, run `git submodule update --init`. The `submodules` directory should get populated.
4. Open the project in Android Studio, install the appropriate SDK and the NDK
5. Build the app

*Note*: If you get "No valid CMake executable was found", be sure to install the CMake version used by PCAPdroid (currently [3.18.1](https://github.com/emanuele-f/PCAPdroid/blob/master/app/build.gradle)) from the SDK manager

