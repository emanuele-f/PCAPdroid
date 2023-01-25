## 3.1 Introduction

PCAPdroid can decrypt the TLS traffic and display the decrypted payload directly into the app. Moreover, it can [generate a pcapng file](paid_features#53-pcapng-format), which you can load in tools like Wireshark to analyze the decrypted traffic.

Most apps today employ TLS to secure their data against inspection and tampering. Such connections are reported in PCAPdroid with either the TLS or the HTTPS protocol. Decryption can be useful in the following contexts:

- Check if an app transmits sensitive information or unhashed passwords
- Reverse engineer the protocol of an app, e.g. to extract the REST API endpoints
- Debug protocol-related issues without disabling the TLS encryption

**Note**: before decrypting, you should check the app ToS to see if this is allowed

**Note**: running TLS decryption will weaken the security of your app. You should only do this if you know what you are doing and for a limited amount of time

Current limitations:

- TLS decryption is not available with the root capture
- Decrypting QUIC traffic [is not supported yet](https://github.com/mitmproxy/mitmproxy/issues/4170). In PCAPdroid, you can enable blocking of QUIC, which causes some apps [to fallback to TLS](https://www.ietf.org/archive/id/draft-ietf-quic-applicability-09.html#section-2), thus making them decryptable
- Decrypting STARTTLS [is not supported yet](https://github.com/mitmproxy/mitmproxy/issues/4215)
- There are some protocol-specific limitations, check out [the mitmproxy docs](https://docs.mitmproxy.org/stable/concepts-protocols/#protocols) for more details

TLS decryption on Android is not an easy task, technical knowledge and familiarity with the topic is required. There are many caveats and which are be discussed below. A rooted device will help you being successful in most circumstances.


## 3.2 Initial setup

TLS decryption can be enabled from the PCAPdroid settings.

         In PCAPdroid settings (top right ⚙️ icon), toggle **TLS decryption** switch under **Traffic inspection** menu.

The first time decryption is enabled, a setup wizard will help you properly setting up decryption. It includes the following steps:

1. Download and installation of the [PCAPdroid-mitm addon](https://github.com/emanuele-f/PCAPdroid-mitm). The actual decryption is performed by [mitmproxy](https://github.com/mitmproxy/mitmproxy), which is bundled into the addon
2. Permission to control the mitm addon. This is a security measure to prevent other apps from controlling the addon
3. Installation of the PCAPdroid [CA certificate](https://docs.mitmproxy.org/stable/concepts-certificates). The CA certificate is what allows PCAPdroid to decrypt the app data, and to do so, it must first be added to the certificate store. To increase security, a unique CA is generated at runtime by PCAPdroid

Before proceeding, check if your device has [Autostart](https://www.vivo.com/en/support/questionByTitle?title=How%20to%20turn%20on/off%20Autostart%20for%20my%20apps) or similar software which prevents background services excecution, in which case you will need to whitelist the mitm addon, otherwise decryption will refuse to start.


## 3.3 Decrypting

Before starting the decryption, you will need to select a target app. Decryption is an invasive operation which can break the connectivity of some apps due to certificate trust problems, so it's a good practice to only limit it to a single app.

The first test you should do to verify that decryption works is to choose an app which is easy to decrypt. It turns out Google Chrome is a good candidate. Enable decryption, select Google Chrome as the target app and then start the capture in PCAPdroid. In chrome, open a new tab and a new HTTPS website (or just clear the browser cache) and you should start seeing decrypted connections in PCAPdroid. These are marked with a green open lock.

The lock icon and color indicates the decryption status, which is also reported into the connection details:

- *green*: decryption successful
- *red*: decryption failed. If you tap on the connection, you should get an error message explaining why it failed
- *orange*: decryption is not supported for this protocol/app (e.g. for QUIC and some specific apps, see below)
- *gray open lock*: the connection is not encrypted (e.g. plain DNS)
- *gray closed lock*: decryption not available yet (e.g. waiting for application data)

You can easily show decrypted or decryption-failed connections via the "Edit Filter" dialog under the filter icon.
If you tap on a decrypted connection, the payload and the HTTP tabs will show the decrypted payload data.

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/gh-pages/images/decryption_1.jpg" width="250" />
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/gh-pages/images/decryption_2.jpg" width="250" />
</p>

If the PCAP dump is enabled, after you stop the capture you will be prompted to save the `SSLKEYLOGFILE`, which you can load in Wireshark [to decrypt](https://wiki.wireshark.org/TLS#tls-decryption) the PCAP file. Alternatively, to simplify the process, you can embed the keylog [directly into the pcapng file](paid_features#53-pcapng-format).


## 3.4 Caveats and possible solutions

Google chrome is a relatively easy app to decrypt. If you try to decrypt other apps you will soon face some problems, which can mostly addressed with a rooted device and the right tools.

### 3.4.1 The client does not trust the mitm certificate

This decryption error may occur for different reasons:

- Android > 7 and app target SDK > 23
- App uses a custom certificates trust store
- [Certificate pinning](https://developer.android.com/training/articles/security-ssl#Pinning) enabled on the app

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/gh-pages/images/tls_cert_not_trusted.jpg" width="250" />
</p>

If you are on Android 7 or newer and the app you are decrypting has target SDK > 23, which is usually the case, the mitm certificate will be rejected, as apps do not trust user certificates anymore. In order to overcome this issue, you either need to:

- If you have the app source code and can build the app, refer to the [the Android guide](https://developer.android.com/training/articles/security-config.html) to trust the PCAPdroid CA. In the network security config xml, you can specify TLDs, for example `<domain includeSubdomains="true">com</domain>` to use the CA to mitm any `.com` domain. To specify the certificate, rename the PCAPdroid CA certificate you exported during the TLS decryption setup to `pcapdroid.crt` and place it under the `raw` resources folder. Please also note that some libraries may use a custom trust store, refer to their documentation on this subject
- On a device rooted with magisk, you can install the [MagiskTrustUserCerts plugin](https://github.com/NVISOsecurity/MagiskTrustUserCerts), which adds the user certs to the system store via a filesystem overlay. This is the suggested solution if you have magisk
- On any rooted device, you can install the certificate [into the system store](https://docs.mitmproxy.org/stable/howto-install-system-trusted-ca-android/#3-insert-certificate-into-system-certificate-store), by mounting the system partition as `rw`
- You can use [apktool](https://ibotpeaches.github.io/Apktool) to decompile the app, lower its target SDK to 23, and rebuild it
- You can use [VirtualXposed](https://github.com/android-hacker/VirtualXposed) to virtualize your app, making it run as it was SDK 23 (Android 11 and later  [currently not supported](https://github.com/android-hacker/VirtualXposed/issues/1073)). To do so, open VirtualXposed, select "Add App" and install the target application that you want to decrypt (use the "virtualxposed" method). Then in PCAPdroid, select VirtualXposed as the target app for the decryption. Virtualization is quite unreliable, so expect crashes

Additionally, some apps (mainly browsers) implement a custom certificate trust store, separate from the system store. You should check if they have an option to disable it, for example, in Firefox [you can do this](https://support.mozilla.org/en-US/questions/1304237) via `about:config`. If such option is not available, you will need to patch the app.

If the client still refuses to connect, then the app may employ certificate pinning, which means that the app actively performs certificate verification against a whitelist. To bypass this, you either need to:

- On a device rooted with magisk, install the [LSposed](https://github.com/LSPosed/LSPosed) module. Then install the [sslunpinning](https://github.com/Xposed-Modules-Repo/io.github.tehcneko.sslunpinning/releases) module
- You can use [apk-mitm](https://github.com/shroudedcode/apk-mitm) to rebuild a patched apk with pinning disabled
- If none of the above works, then the app may use custom pinning logic, in which case you will need to unpack, reverse engineer, and patch it

### 3.4.2 Certificate transparency

When decrypting a browser traffic, the browser may refuse to connect to websites giving you an error about certificate transparency. With [Certificate transparency](https://en.wikipedia.org/wiki/Certificate_Transparency), custom system CA are normally rejected. To fix this, you either need to:

- in some browsers, you can disable certificate transparency, e.g. in bromite via `chrome://flags`
- uninstall the PCAPdroid CA from the system store or simply temporary disable it from the Android security settings
- if the PCAPdroid system CA is installed via the `MagiskTrustUserCerts` plugin, then you can use *magisk hide* on the browser to make it see the PCAPdroid CA as a non-system CA

### 3.4.3 Traffic is still encrypted

After decrypting the TLS traffic, the decrypted payload may still be encrypted with a another protocol. This occurs, in particular, with Telegram and Whatsapp, which use a custom encrypted protocol. Such protocols require the development of custom tools for the decryption, which are out of the scope of PCAPdroid.

Moreover, beware that the result of decryption may produce a *binary* protocol, which is not in a human-readable form. It's important to understand that a *binary* protocol does not necessarily means that the protocol is encrypted. For example, DNS is a binary protocol but it's not encrypted.


## 3.5 Decrypting via an external mitmproxy

For better flexibility, e.g. to modify the traffic or use an upstream proxy, you can perform the TLS decryption on a third-party SOCKS5 proxy, possibly located into another device. Here is an example on how to configure `mitmproxy` for this.

On a PC, you can install `mitmproxy` by following the [official installation guide](https://docs.mitmproxy.org/stable/overview-installation). Both the Android device and the PC should be connected to the same network for this to work. As an alternative, you can install `mitmproxy` directly on the Android device in `termux`. After installing the `termux` app, open it and run the following commands:

```bash
pkg update
pkg install python openssl-1.1-static
python3 -m pip install --upgrade pip

CRYPTOGRAPHY_DONT_BUILD_RUST=1 CRYPTOGRAPHY_SUPPRESS_LINK_FLAGS=1 \
  LDFLAGS="$PREFIX/lib/openssl-1.1/libssl.a $PREFIX/lib/openssl-1.1/libcrypto.a" \
  CFLAGS="-I$PREFIX/include/openssl-1.1" \
  pip install mitmproxy==7.0.4
```

This installs `mitmproxy` 7.0.4, which is the latest version to not require `rust`. If you want to install version 8+, refer [to these instructions](https://t.me/PCAPdroid/10071).

**Note**: when installed on the Android device via termux, it's essential to set an app filter in PCAPdroid to only capture a specific app traffic, otherwise the termux mitmproxy traffic would run in a loop, breaking the phone internet connectivity.

After installing `mitmproxy`, you need to perform the following steps:

1. Run `mitmproxy` without options to generate the mitm certificate. Install the certificate (usually `~/.mitmproxy/mitmproxy-ca-cert.cer`) in the Android phone. It may be needed to change the extension to `.crt` to install it
2. Open the PCAPdroid settings
3. Toggle "Enable SOCKS5 Proxy"
4. Set the IP address and port of the remote mitmproxy instance (port 8050 in this example)
5. Run mitmproxy in SOCKS5 mode, e.g. via `mitmproxy --mode socks5 --listen-port 8050`

PCAPdroid will now redirect all the TCP traffic to the mitmproxy server, which will proxy the connections and decrypt the TLS traffic. Please note that the PCAP generated by PCAPdroid will still contain the encrypted traffic with the original IP destinations and ports.
