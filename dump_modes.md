PCAPdroid offers different ways to export the captured traffic. This is called *dump mode* and can be changed from the app settings.

## 2.1 None

This mode does not export the traffic in any way. This is suitable to only perform traffic analysis directly from the *connections* view of PCAPdroid.

## 2.2 HTTP Server

This mode starts an HTTP server which can be accessed by any PC in the local network to download the PCAP file containing the captured traffic. This is the default mode of PCAPdroid as it does not require any further setup or specific OS.

In order to properly download the PCAP, you need to follow these steps:

1. Ensure that the HTTP server mode is selected
2. Start the capture from the app
3. From another device, open the URL displayed in the app. This will start the PCAP download. It's ok IF no download progress is shown, as the browser does not know the actual PCAP size.
4. Stop the capture from the app. This will terminate the active downloads and the PCAP file will be properly saved.

## 2.3 PCAP File

This mode can be used to save a PCAP file into the device storage. The file name and path can be selected after clicking the start button. Some Android TV devices do not implement the file selection dialog; in such cases, a file name will be picked automatically and file will be saved to the Downloads directory.
After the capture is stopped, a dialog is displayed which offers the option to share the PCAP file, delete it or just keep it.

## 2.4 UDP Exporter

This advanced mode is specifically designed to provide a real time analysis of the traffic. It requires the [udp_receiver.py](https://github.com/emanuele-f/PCAPdroid/blob/master/tools/udp_receiver.py) python script and a linux PC. In this mode PCAPdroid encapsulates the PCAP records into an UDP stream and sends the stream to the remote UDP collector. The collector IP and port must be configured through the settings.

The udp_receiver.py script will receive the UDP packets on the specified port, decapsulate them, and print the raw PCAP records to the stdout. By piping it into a network monitoring program it is possible to analyze the captured packets in real time.

Here are some examples of the applicability of this mode:

- Analyze the traffic with [wireshark](https://www.wireshark.org/):

```bash
udp_receiver.py -p 1234 | wireshark -k -i -
```

- Monitor the active connections and their peers in [ntopng](https://github.com/ntop/ntopng):

```bash
udp_receiver.py -p 1234 | ntopng -m “10.215.173.0/24” -i -
```

- Write the traffic to a PCAP file:

```bash
udp_receiver.py -p 1234 | tcpdump -w dump.pcap -r -
```

As a note, it is possible to use `socat` in place of the udp_receiver.py script in a similar way. However, the `-b` option must be specified as follows:

```bash
socat -b 65535 - udp4-listen:1234
```

Using `nc` will not work as bigger packets will be truncated.
