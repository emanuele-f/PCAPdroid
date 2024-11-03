PCAPdroid offers different ways to export the captured traffic. This is called *dump mode* and can be changed from the app settings.

## 2.1 None

This mode does not export the traffic in any way. This is suitable to only perform traffic analysis directly from the *connections* view of PCAPdroid.

## 2.2 HTTP Server

This mode starts an HTTP server which can be accessed by any PC in the local network to download the PCAP file containing the captured traffic. This is the default mode of PCAPdroid as it does not require any further setup or specific OS.

After the capture is started, you can connect to the URL displayed by PCAPdroid in the "Status" view to start downloading the PCAP. It is normal if you don't see any download progress in your browser as the PCAP size is unknown. The PCAP file will be properly saved once you stop the capture from PCAPdroid.

On linux, you can also use this mode to analyze the PCAP in real time on Wireshark with the following command (replace `192.168.1.10:8080`):

```bash
curl -NLs http://192.168.1.10:8080 | wireshark -k -i -
```

Compared to the `UDP Exporter` mode, this has the advantage of using TCP as the transport protocol, which prevents packets drops/reordering.

## 2.3 PCAP File

This mode can be used to save a PCAP file into the device storage. The file name and path can be selected after clicking the start button. Some Android TV devices do not implement the file selection dialog; in such cases, a file name will be picked automatically and file will be saved to the Downloads directory.
After the capture is stopped, a dialog is displayed which offers the option to share the PCAP file, delete it or just keep it.

## 2.4 UDP Exporter

This advanced mode is specifically designed to provide a real time analysis of the traffic. In this mode PCAPdroid encapsulates the PCAP records in an UDP stream, sent to a remote UDP collector. The collector IP and port can be configured in the PCAPdroid settings.

**NOTE**: UDP is a unreliable transport protocol, which means that packets may be dropped or reordered, in particular over wifi, so this mode may produce a partial capture

To use this mode, you either need a linux system or a Windows system with Wireshark.

### Capturing on a linux system

Download the [udp_receiver.py](https://github.com/emanuele-f/PCAPdroid/blob/master/tools/udp_receiver.py) python script. This script will receive the UDP packets, decapsulate them, and print the raw PCAP records to the stdout. By piping it into a network monitoring program it is possible to analyze the captured packets in real time.

Here are some examples of how to combine this mode with some common tools:

- Analyze the traffic with [Wireshark](https://www.wireshark.org/) in real-time:

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

### Capturing on a Windows system

You can capture packets on Windows in real-time via Wireshark and the "UDP listener remote capture" interface (udpdump).

To do this, configure Wireshark as follows:

1. When installing Wireshark, ensure to select udpdump in the optional section
2. Copy the [pcapdroid.lua](https://github.com/emanuele-f/PCAPdroid/blob/master/tools/pcapdroid.lua) and the [pcapdroid_udpdump.lua](https://github.com/emanuele-f/PCAPdroid/blob/master/tools/pcapdroid_udpdump.lua) plugins to the [plugins directory](https://www.wireshark.org/docs/wsug_html_chunked/ChPluginFolders.html (usually `%APPDATA%\Wireshark\plugins`)
3. Restart Wireshark and in the About -> Plugins ensure that both the PCAPdroid plugins are listed
4. Start the Wireshark capture in UDP listener mode
5. In the PCAPdroid settings, set the UDP exporter IP address to the IP address of the Windows pc, and the port to 5555
6. From the PCAPdroid main screen, select the UDP dump mode and start the capture

You should now see the packets correctly decapsulated in Wireshark. If you see `127.0.0.1` as the destination IP and just a `Data` field without any dissection, double check that the plugins are correctly loaded.

**NOTE**: when capturing via `udpdump`, decrypting PCAPNG is currently not supported. Use the PCAP file dump instead
