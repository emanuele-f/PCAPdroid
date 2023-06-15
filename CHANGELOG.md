# Changelog

Releases available at https://github.com/emanuele-f/PCAPdroid/releases

## [1.6.4] - 2023-04-24
- Fix QR code activation crash on Android 32+
- Update translations

## [1.6.3] - 2023-04-23
- Add paid features activation via QR code for non-Play builds
- Fix firewall not working when loading duplicate domain rules
- Fix repeated local TCP connection attempts on some devices when private DNS is enabled
- Minor bug fixes

## [1.6.2] - 2023-03-31
- Update mitm addon to mitmproxy 9.0.1
- Add SOCKS5 authentication via username and password
- Add workaround for mitm addon connection failure
- Update German translation (credits: Mario Herrmann)

## [1.6.1] - 2023-02-10
- Fix Android TV navigation
- Fix minor crashes

## [1.6.0] - 2023-01-16
- Add firewall whitelist mode: block everything, unless whitelisted
- Add pcapng format: removes the need for a separate SSLKEYLOG (paid feature)
- Add port mapping: redirect traffic to given hosts/ports
- Custom rules can now be added from the UI
- DNS servers are now configurable
- Add TLS decryption whitelist
- Simplify PCAP dump to file
- Sort apps by total/sent/received bytes
- Full payload can now be disabled in TLS decryption mode
- Support custom mitmproxy options
- The application and the mitmproxy logs can now be viewed in-app
- Apps stats can now be reset

## [1.5.6] - 2022-09-24
- Extend STUN compatibility
- Fix Whatsapp calls being dropped (no-root)
- Add ability to skip TLS certificate installation
- Fix paid features docs URLs and appearance on first app start
- Fix capture stopped with always-on and start-on-boot

## [1.5.5] - 2022-08-25
- You can now buy unlock tokens to access paid features in non-Play builds
- Fix minor crashes and ANR
- Fix JNI leaks and prevent local references overflow
- Fix deprecations

## [1.5.4] - 2022-08-04
- Unblock newly installed apps via notification action (firewall)
- Fix context menu action possibly applied to wrong item
- Fix ICMPv6 not captured with root and app filter
- Fix package search with uppercase letters
- Fix PCAP file selection dialog sometimes not appearing

## [1.5.3] - 2022-07-13
- Add support for Android 13 Tiramisu
- Add VPN exemptions to exclude specific apps from the VPN
- Ability to set IPv6-only VPN mode
- Add filter to only show cleartext connections
- Ability to block newly installed apps (firewall)
- Ability to temporary unblock an app (firewall)
- Show scam-prevention messages
- Deny sending traffic to remote servers via CaptureCtrl to prevent scams
- Fix possible IllegalStateException in the status view

## [1.5.2] - 2022-06-22
- Add ability to only show the connections allowed by the firewall
- Monitor memory usage and disable full payload on low memory
- Fix possible SecurityException in Android 11
- Fix crash on tap on uninstalled app
- Fix ANR on first root capture start

## [1.5.1] - 2022-06-07
- Resolve correct apps details with work profiles in root mode
- Fix app filter icon not shown on capture start
- Fix crash on screen rotation in connection details
- Fix some occasional crashes

## [1.5.0] - 2022-06-02
- No-root firewall: block apps, domains and IP addresses (paid feature)
- Ability to decrypt TLS traffic, display decrypted data, export the SSLKEYLOGFILE
- Inspect HTTP requests and replies thanks to the built-in decoders (brotli, deflate, gzip)
- Uploads in VPN mode are now faster and less cpu intensive
- Inspect the full connections' payload as hexdump/text
- Add ability to import/export rules
- Fix uid resolution failing with root after some time
- Fix PCAP dump of invalid/unsupported packets in root
- Reduce APK size: geolocation db is now optional
- Intent-based API now reports traffic stats
- Add app on-boarding on first start
- Add ability to disable blocking of private DNS
- Add German translation (credits: Robin)
- Add Indonesian translation (credits: Reza Almanda)
- Add Turkish translation (credits: Oğuz Ersen)
- Minor fixes and improvements

## [1.4.8] - 2022-04-22
- Fix corrupted PCAP file on Android 10+ when overwriting existing file
- Start the capture by tapping "Ready"
- Allow inspecting connections after the capture is stopped
- Support UDP STUN in non-root mode
- Faster uid resolution and other improvements

## [1.4.7] - 2022-02-10
- Fix unsolicited capture start when swiping the app from recent apps
- Fix ForegroundServiceStartNotAllowedException in Android 12
- Fix ANR when stopping the capture
- Fix empty stats when there is no traffic
- Update to nDPI 4.2

## [1.4.6] - 2022-02-06
- Improve capture performance when PCAP dump is enabled
- Always-on VPN is now fully supported
- Add Norwegian translation (credits: Allan Nordhøy)
- Add French translation (credits: J. Lavoie and Maxime Leroy)
- Fix Android 11/12 crash when invoked via the Intent-based API
- Fix SecurityException crash on Android TV with PCAP file dump
- Fix packets truncated with root on devices with sw/hw offloading
- Fix use-after-free in root mode
- Fix memory leak with HTTP server PCAP dump when a client disconnects
- Add native code testing and fuzzing

## [1.4.5] - 2022-01-06
- Reduce the memory usage, in particular during the capture
- Malware detection now blocks malicious connections in non-root mode (paid feature)
- Add ability to filter connections by capture interface in root mode
- Add spanish translation (credits: sguinetti)
- Improve stability: add tests, bounds checks, synchronization
- Fix crashes when no activity is available to handle intents
- Fix crash in root mode when the internet interface goes down
- Workaround for crash due to IndexOutOfBoundsException in RecyclerView
- Fix long press on a connection which is being updated
- Re-enable always-on VPN support
- Improve Android TV support, support devices with no wifi

## [1.4.4] - 2021-11-23
- New malware detection overview: show blacklists and detection status
- Show destination country and ASN in the connection details
- Add ability to start the capture at boot
- Reduce packet processing delay during UI updates
- Fix no Internet when a custom private DNS is set
- Add ability to grant persistent capture control permissions

## [1.4.3] - 2021-10-30
- Implement blacklist-based malware detection with alerts (paid feature)
- Filter connections: only plaintext, state (e.g. only open), not hidden, malicious
- Fix a bug which, in rare cases, caused the connections view to stop being updated
- Fix some closed connections being marked with the Unknown protocol
- Improve netd app resolution with root capture
- Ability to copy host/IP/URL/plaintext on long press
- Added whois IP lookup button
- "Whitelist" is now called "Hidden Connections"

## [1.4.2] - 2021-09-11
- Ability to select the capture interface in root mode
- Ability to start/stop the capture from other apps via Intent
- Improve pcapd error reporting
- The copy action now also dumps the request plaintext

## [1.4.1] - 2021-07-31
- Fix crash in some devices with PCAPdroid trailer
- Add ability to build on Windows

## [1.4.0] - 2021-07-20
- Ability to export app name to Wireshark (PCAPdroid trailer)
- Add apps details page with app metadata and permissions
- Allow searching by source and destination port
- Packet capture optimizations
- Add in-app purchases to remove ads
- Add packet drops for root capture
- Add IPv6 and ICMP support for root capture
- Fix root capture stall in some cases
- Fix PCAP dump in root mode
- Fix truncated full size packets in root mode and handle fragments
- PCAP timestamps in root mode now correspond to the capture timestamps
- Fix ping to known DNS servers being blocked

## [1.3.9] - 2021-06-16
This release brings a set of new features to make it easier to spot unwanted connections!

- Long press a connection to whitelist it, making it easy to filter out background traffic
- Add ability to search connections
- Most netd DNS connections are now resolved into actual apps
- Add Brazilian translation (credits: mezysinc)
- Add Japanese translation (credits: Akihiro Nagai)

## [1.3.8] - 2021-06-03
- Fix monodirectional connections with LINUX_SLL in root mode
- Show plaintext request data (e.g. HTTP headers) in the connection details

## [1.3.7] - 2021-05-19
- Handle DLT_LINUX_SLL to fix some root daemon start issues

## [1.3.6] - 2021-05-01
- Implement root based capture to run with other VPN apps
- Fix bad URL when an HTTP proxy is used

## [1.3.5] - 2021-04-14
- Fix delays and slow downs with big uploads
- Add SOCKS5 client, allowing to use the official mitmproxy
- Implement MSS and TCP window scaling options

## [1.3.4] - 2021-03-30
- Add dark theme
- Android TV: fix file selection and other improvements
- Fix UDP broadcast connections

## [1.3.3] - 2021-03-25
- You can now donate! Check out the user guide for instructions
- Add IPv6 support
- Reduce background battery and RAM consumption
- Add russian translation (credits: rikishi)
- Add italian translation
- Add ability to switch between system language and english
- Improve TCP and DNS connections handling
- Connection details stats now automatically refresh
- Fix DNS resolution failures on main network change

## [1.3.2] - 2021-03-09
- Add ICMP connections support
- Reduce app icons load time and RAM usage
- Fix crash in Bitmap.createBitmap

## [1.3.1] - 2021-03-07
- Fix startup crash before Oreo

## [1.3.0] - 2021-03-05
- Retain the connections log during the whole capture
- Add Android TV support
- New app layout for easier interaction
- Export the connections log to a CSV file
- Filter the connections log by app
- New Apps view to review the total traffic and connections by app
- Clipboard copy and export of the connection details
- Add persistent notification while capture is running
- Apps can now conveniently searched via the searchbar

## [1.2.16] - 2021-02-23
- Fix non-selectable text in connections details
- Fix start button not working on first app run

## [1.2.15] - 2021-02-20
- Improve connections stability
- Report fatal native errors to the user

## [1.2.14] - 2021-02-14
- Fix DNS resolution not working when an IPv6 DNS server is configured

## [1.2.13] - 2021-02-12
- More sockets checks to prevent crashes
- Add stats view with traffic and debug information
- UI improvements: apps loading toast, start button position
- ViewPager2 migration (credits: TacoTheDank <SkytkRSfan3895@gmail.com>)

## [1.2.12] - 2021-01-26
- minSdkVersion is now 21 (Lollipop)
- Improve DNS traffic capture when an app filter is set

## [1.2.11] - 2021-01-25
- Fix empty apps list in Android 11

## [1.2.10] - 2021-01-23
- Ask confirmation when another VPNService is running
- Rework modules dependencies

## [1.2.9] - 2021-01-17
- Fix crash when app is stopped from the system VPN settings

## [1.2.8] - 2021-01-11
- Improve TCP connections stability with big packets
- Add chinese translation (credits: sr093906)
- Update nDPI and gradle dependencies
- Fix some crashes

## [1.2.7] - 2021-01-07
- Fix app ID not resolved on android >= Q
- Resolve system apps UIDs

## [1.2.6] - 2020-11-16
- Add support for TLS decryption via mitmproxy
- Fix occasional crash due to null ConnDescriptor in array
- Connections view consistency fixed when app filter is in use
- Update nDPI to 3.4 stable
- Add links: rate app, telegram channel, user guide

## [1.2.5] - 2020-09-20
- Fix crash on subsequent app runs

## [1.2.4] - 2020-09-18
- Traffic monitoring fix
- JNI fixes and optimizations

## [1.2.3] - 2020-02-19
- Add polish translation (Atrate <Atrate@protonmail.com>)
- Add more compact layout for screens lower than 480dp
- JNI crashes fixes

## [1.2.2] - 2020-01-11
- Fix crash while clicking on the HTTP server URL
- Improve local HTTP server IP detection

## [1.2.1] - 2020-01-06
- New embedded HTTP server to easily download the PCAP from multiple devices
- Choose how to dump packets: HTTP server, UDP exporter, none.
- App renamed to PCAPdroid, changed version scheme, added changelog

# Older Releases

Releases available at https://github.com/emanuele-f/RemoteCapture/releases

## [1.2] - 2020-01-01
- New tab to display the active connections, with server name and traffic volume
- Show server name, DNS query, L7 protocol information from nDPI
- Click on a connection to show its details
- Use the system DNS server (fallback to 8.8.8.8 only if not available)

## [1.1] - 2019-11-03
- Fix app state loading after resume
- Add option to enable/disable unknown app traffic capture
- Add UDP receiver python script to easily pause and resume capture

## [1.0] - 2019-10-26
- Capture apps traffic without root
- Send captured traffic via UDP
- Show captured traffic realtime statistics
- Apply a filter to only capture traffic for the selected app
