# Changelog

Releases available at https://github.com/emanuele-f/PCAPdroid/releases

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
