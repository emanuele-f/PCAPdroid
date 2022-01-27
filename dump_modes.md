PCAPdroid offers different ways to export the captured traffic. This is called *dump mode* and can be changed from the app settings.

## 2.1 None

This mode does not export the traffic in any way. This is suitable to only perform traffic analysis directly from the *connections* view of PCAPdroid.

## 2.2 HTTP Server

This mode starts an HTTP server which can be accessed by any PC in the local network to download the PCAP file containing the captured traffic. This is the default mode of PCAPdroid as it does not require any further setup or specific OS.

After the capture is started, you can connect to the URL displayed by PCAPdroid in the "Status" view to start downloading the PCAP. It is normal if you don't see any download progress in your browser as the PCAP size is unknown. The PCAP file will be properly saved once you stop the capture from PCAPdroid.

On linux, you can also use this mode to analyze the PCAP in real time on Wireshark with the following command (replace `192.168.1.10:8080`):

```bash
curl -NLs http://192.168.1.10:8080 --output - | wireshark -k -i -
```

Compared to the `UDP Exporter` mode, this has the advantage of using TCP as the transport, which will prevent packet drops/reordering.

## 2.3 PCAP File

This mode can be used to save a PCAP file into the device storage. The file name and path can be selected after clicking the start button. Some Android TV devices do not implement the file selection dialog; in such cases, a file name will be picked automatically and file will be saved to the Downloads directory.
After the capture is stopped, a dialog is displayed which offers the option to share the PCAP file, delete it or just keep it.

## 2.4 UDP Exporter

This advanced mode is specifically designed to provide a real time analysis of the traffic. It requires the [udp_receiver.py](https://github.com/emanuele-f/PCAPdroid/blob/master/tools/udp_receiver.py) python script and a PC. In this mode PCAPdroid encapsulates the PCAP records into an UDP stream and sends the stream to the remote UDP collector. The collector IP and port must be configured through the settings.

**NOTE**: UDP is a unreliable transport protocol, which means that packets may be dropped or they may be reordered, in particular over wifi. This dump mode is not appropriate if you want to produce a full capture.

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
