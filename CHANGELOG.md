# Changelog

Releases available at https://github.com/emanuele-f/PCAPdroid/releases

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
