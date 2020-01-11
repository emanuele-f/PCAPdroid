# Changelog

Releases available at https://github.com/emanuele-f/PCAPdroid/releases

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
