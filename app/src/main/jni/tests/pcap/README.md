## metadata.pcap

Contains HTTP, TLS and DNS connections for both IPv4 and IPv6, suitable to test DPI.

Connections:

```
[UDP4] 192.168.1.10:48037 -> 8.8.8.8:53 [example.org]
[UDP4] 192.168.1.10:38793 -> 8.8.8.8:53 [www.google.com]
[TCP4] 192.168.1.10:36922 -> 216.58.208.164:80 [www.google.com]
[UDP4] 192.168.1.10:48772 -> 8.8.8.8:53 [www.google.com]
[TCP6] 2001:db8:1234::1:49936 -> 385d:1ee:e3c9:9c5f::2004:80 [www.google.com]
[UDP4] 192.168.1.10:51080 -> 8.8.8.8:53 [google.it]
[TCP6] 2001:db8:1234::1:44904 -> 2c9b:a9b9:83dd:d9d1::2003:443 []
[TCP4] 192.168.1.10:51588 -> 142.250.180.131:443 [google.it]
[UDP4] 192.168.1.10:42218 -> 8.8.8.8:53 [www.google.it]
[TCP6] 2001:db8:1234::1:59424 -> 3a5d:15fe:e3cb:9c5f::2003:443 [www.google.it]
[ICMP4] 192.168.1.10:4 -> 1.1.1.1:0 []
[UDP4] 192.168.1.10:47987 -> 8.8.8.8:53 [www.internetbadguys.com]
[TCP4] 192.168.1.10:46312 -> 146.112.255.155:80 [www.internetbadguys.com]
[UDP4] 192.168.1.10:51165 -> 8.8.8.8:53 [www.internetbadguys.com]
[UDP4] 192.168.1.10:52176 -> 8.8.8.8:53 [example.org]
[TCP6] 2001:db8:1234::1:45226 -> 2ed5:9050:81e9:4b68:248:1893:25c8:1946:443 [example.org]
[TCP4] 192.168.1.10:43453 -> 8.8.8.8:53 [f-droid.org]
[UDP4] 192.168.1.10:41011 -> 8.8.8.8:53 [f-droid.org]
[TCP4] 192.168.1.10:52782 -> 149.202.95.241:80 [f-droid.org]
```
