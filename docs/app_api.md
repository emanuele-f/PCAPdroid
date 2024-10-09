PCAPdroid exposes an API for other apps to configure and control the packet capture. This can be used to easily enable packet capture in your app, provided that the PCAPdroid app is also installed into the device.

## The API

The [CaptureCtrl.java](https://github.com/emanuele-f/PCAPdroid/blob/master/app/src/main/java/com/emanuelef/remote_capture/activities/CaptureCtrl.java) activity is the one exposed to allow you to control the PCAPdroid capture via Intents.

The activity can be easily invoked from the cli by running:

```bash
adb shell am start -e action [ACTION] -e [SETTINGS] -n com.emanuelef.remote_capture/.activities.CaptureCtrl
```

where ACTION is one of:
  - `start`: starts the capture with the specified parameters
  - `stop`: stops the capture
  - `get_status`: get the capture status

The capture parameters are specified via Intent extras, which are discussed below.

For example, you can use the following command to start the capture and write the traffic dump to the PCAP file `Download/PCAPdroid/traffic.pcap`:

```bash
adb shell am start -e action start -e pcap_dump_mode pcap_file -e pcap_name traffic.pcap -n com.emanuelef.remote_capture/.activities.CaptureCtrl
```

A common task is to capture the traffic of a specific app to analyze it into your app. This can be easily accomplished by running PCAPdroid in the
[UDP Exporter mode](https://emanuele-f.github.io/PCAPdroid/dump_modes#24-udp-exporter):

```bash
adb shell am start -e action start -e pcap_dump_mode udp_exporter -e collector_ip_address 127.0.0.1 -e collector_port 5123 -e app_filter org.mozilla.firefox -n com.emanuelef.remote_capture/.activities.CaptureCtrl
```

then your app can listen for UDP packets on port `5123` to handle the Firefox network packets.
Another interesting option is to enable the [pcapdroid_trailer](https://emanuele-f.github.io/PCAPdroid/advanced_features#45-pcapdroid-trailer) to be able to get the app UID/name into your app.

The Intent above can also be triggered programmatically from your app:

```java
class YourActivity extends Activity {
  private final ActivityResultLauncher<Intent> captureLauncher =
    registerForActivityResult(new StartActivityForResult(), this::handleCaptureResult);

  void startCapture() {
    Intent intent = new Intent(Intent.ACTION_VIEW);
    intent.setClassName("com.emanuelef.remote_capture", "com.emanuelef.remote_capture.activities.CaptureCtrl");

    intent.putExtra("action", "start");
    intent.putExtra("pcap_dump_mode", "udp_exporter");
    intent.putExtra("collector_ip_address", "127.0.0.1");
    intent.putExtra("collector_port", "5123");
    intent.putExtra("app_filter", "org.mozilla.firefox");

    captureLauncher.launch(intent);
  }

  void handleCaptureResult(final ActivityResult result) {
    if(result.getResultCode() == RESULT_OK) {
      // command executed successfully
    }
  }
}
```

The result code tells if the command succeded or not. Check out the [PCAPReceiver sample app](https://github.com/emanuele-f/PCAPReceiver) for a working example.

## User Consent

To prevent malicious apps from monitoring/hijacking the device traffic, PCAPdroid will ask for user consent every time a capture is started. If the user denies consent, then the request fails. After an app is granted start permission, subsequent requests from that app are automatically granted. 

From the permission dialog the user can choose to permanently grant or deny the capture permission to an app. The permanently granted/denied permissions can be edited from the `Control Permissions` entry in the PCAPdroid settings.

Applications interfacing with PCAPdroid should use the `startActivityForResult` (or the equivalent `ActivityResultLauncher`) when calling its API, rather than `startActivity`. This ensures that the package name of the calling app can be retrieved via [getCallingPackage](https://developer.android.com/reference/android/app/Activity#getCallingPackage()).

## Capture Settings

As shown above, the capture settings can be specified by using intent extras. The updated list of all the supported parameters is available in
[CaptureSettings.java](https://github.com/emanuele-f/PCAPdroid/blob/master/app/src/main/java/com/emanuelef/remote_capture/model/CaptureSettings.java).

| Parameter               | Type   | Ver | Mode | Value                                                              |
|-------------------------|--------|-----|------|--------------------------------------------------------------------|
| pcap_dump_mode          | string |     |      | none \| http_server \| udp_exporter \| pcap_file                   |
| app_filter              | string |     |      | package name of the app(s) to capture (73+: comma separated list)  |
| collector_ip_address    | string |     |      | the IP address of the collector in udp_exporter mode               |
| collector_port          | int    |     |      | the UDP port of the collector in udp_exporter mode                 |
| http_server_port        | int    |     |      | the HTTP server port in http_server mode                           |
| pcap_uri                | string |     |      | the URI for the PCAP dump in pcap_file mode (overrides pcap_name)  |
| socks5_enabled          | bool   |     | vpn  | true to redirect the TCP connections to a SOCKS5 proxy             |
| socks5_proxy_ip_address | string |     | vpn  | the SOCKS5 proxy IP address                                        |
| socks5_proxy_port       | int    |     | vpn  | the SOCKS5 proxy port                                              |
| root_capture            | bool   |     |      | true to capture packets in root mode, false to use the VPNService  |
| pcapdroid_trailer       | bool   |     |      | true to enable the PCAPdroid trailer                               |
| capture_interface       | string |     | root | @inet \| any \| ifname - network interface to use in root mode     |
| snaplen                 | int    |  43 |      | max size in bytes for each individual packet in the PCAP dump      |
| max_pkts_per_flow       | int    |  43 |      | only dump the first max_pkts_per_flow packets per flow             |
| max_dump_size           | int    |  43 |      | max size in bytes for the PCAP dump                                |
| tls_decryption          | bool   |  49 | vpn  | true to enable the built-in TLS decryption                         |
| auto_block_private_dns  | bool   |  51 | vpn  | true to detect and possibly block private DNS to inspect traffic   |
| ip_mode                 | string |  56 | vpn  | which IP addresses to use for the VPN: ipv4 \| ipv6 \| both        |
| mitmproxy_opts          | string |  62 |      | additional options to provide to mitmproxy in decryption mode      |
| pcap_name               | string |  62 |      | write the PCAP to Download/PCAPdroid/*pcap_name* in pcap_file mode |
| pcapng_format           | bool   |  62 |      | true to use the PCAPNG dump format (overrides pcapdroid_trailer)*  |
| socks5_username         | string |  64 | vpn  | username for the optional SOCKS5 proxy authentication              |
| socks5_password         | string |  64 | vpn  | password for the optional SOCKS5 proxy authentication              |
| block_quic              | string |  73 | vpn  | never \| always \| to_decrypt (matching the decryption whitelist)  |

\*: paid feature

The `Ver` column indicates the minimum PCAPdroid version required to use the given parameter. The PCAPdroid version can be queried via the `get_status` action as explained below.
The `Mode` column indicates if the option applies to any mode or only to the VPN or root mode.

*NOTE*: for security reasons, since version 1.5.3 you cannot specify a remote server IP address in `collector_ip_address` or in `socks5_proxy_ip_address`. If you really want to do this, you should first set such a remote IP address via the PCAPdroid gui and only then invoke the API.

*NOTE*: since version 1.6.0, the `pcap_uri` behavior is changed as described in the `Dumping PCAP to file` section below

## Query the Capture Status

It is possible to check if the capture is currently running by sending an Intent with the `get_status` action. The response Intent contains the following extras:

| Field               | Type   | Value                                                             |
|---------------------|--------|-------------------------------------------------------------------|
| version_name        | string | the PCAPdroid versionName (e.g. "1.4.5")                          |
| version_code        | int    | the PCAPdroid versionCode, an incremental number for the release  |
| running             | bool   | true if the capture is running                                    |

Other than via the API, the capture may be manually stopped by the user from the PCAPdroid app. In order to be notified when the capture is stopped, you can create a `BroadcastReceiver` and subscribe to the `com.emanuelef.remote_capture.CaptureStatus` action. Here is an example:

```xml
<receiver android:name=".MyBroadcastReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="com.emanuelef.remote_capture.CaptureStatus" />
    </intent-filter>
</receiver>
```

To tell PCAPdroid to send the Intent to your receiver, you must specify its class name in the `broadcast_receiver` extra of the start intent:

```java
intent.putExtra("action", "start");
intent.putExtra("broadcast_receiver", "com.emanuelef.pcap_receiver.MyBroadcastReceiver");
...
captureStartLauncher.launch(intent);
```

The receiver will get an intent with the `running` extra set to `false` when the capture is stopped.

## Capture Stats

In the result of the `stop` and `get_status` actions and in the broadcast of `CaptureStatus`, PCAPdroid provides the following capture stats in the form of Intent extras:

| Field               | Type   | Ver |  Value                                                             |
|---------------------|--------|-----|--------------------------------------------------------------------|
| bytes_sent          | long   |  50 | bytes sent (from the device to the Internet)                       |
| bytes_rcvd          | long   |  50 | bytes received (from the Internet to the device)                   |
| bytes_dumped        | long   |  50 | size of the PCAP dump                                              |
| pkts_sent           | int    |  50 | packets sent                                                       |
| pkts_rcvd           | int    |  50 | packets received                                                   |
| pkts_dropped        | int    |  50 | in root mode, number of packets not analyzed and not dumped        |
| ipv6_bytes_sent     | long   |  74 | IPv6 bytes sent (from the device to the Internet)                  |
| ipv6_bytes_recv     | long   |  74 | IPv6 bytes received (from the Internet to the device)              |

## Dumping PCAP to file

[Scoped storage](https://developer.android.com/about/versions/11/privacy/storage) restrictions apply to PCAPdroid, which limits in which paths the PCAP file can be stored and how
other apps can access it.

Since version 1.6.0, PCAPdroid dumps the PCAP file to the `Download/PCAPdroid` directory. By using the `pcap_name` parameter, you can get a predictable PCAP file path. The PCAP
file is overwritten if another file with the same `pcap_name` exists.

You can use the `pcap_uri` parameter to write the PCAP file to an arbitrary path. The URI specified in `pcap_uri`, however, is only used as an identifier, it's not the actual file
path. This choice has been made to cope with the scoped storage limitations. This means that, the first time the `pcap_uri` is set and every time it's changed, a file dialog is
displayed to the user to pick the actual file path. On subsequent runs, the same `pcap_uri` identifier can be used to write the selected file without the file dialog to be
presented to the user.
