Paid features are only available when the app is installed [via Google Play](https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture). If you are an F-Droid user and you have any suggestions on how to handle world-wide payments in the F-Droid build, please [reach me via email](mailto:black.silver@hotmail.it?subject=PCAPdroid).

## 5.1 Malware Detection

**DISCLAIMER**: *the malware detection feature of PCAPdroid is not a comprehensive solution for the security of your device. The author provides no guarantee on the malware detection capabilities or on the accuracy of PCAPdroid and he can not be held liable for any direct or indirect damage caused by its use.*

The malware detection feature enables PCAPdroid to detect malicious hosts by the means of third-party blacklists. The detection is only active when the capture is running. Since version 1.4.5, when running in the default VPN mode, PCAPdroid will also **block** all the traffic directed to and coming from the malicious hosts. Blocking *does not occur* when running in [root mode](https://emanuele-f.github.io/PCAPdroid/advanced_features#44-root-capture).

Today our devices are exposed to a variety of threats: phishing, online scams, ransomware and spyware to name a few. When it comes to security, precautions are never enough and no solution will fit all the needs. The malware detection feature of PCAPdroid can help detecting malicious connections as they happen, bringing the possible threat to the user attention, as well as preventing damage by blocking the communication. Here are some contexts where it finds applicability:

- The user browsers a known malicious website (e.g. phishing, scam)
- The user installs a malicious app or addon (e.g. spyware) which connects to a malicious domain/IP
- The device is exploited and a malware is installed (e.g. spyware, ransonware or C&C), which connects to a malicious domain/IP

Whenever PCAPdroid detects a malicious connection, it reports the event to the user via a notification.

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/gh-pages/images/malware_notification.jpg" width="300" />
</p>

You can manually test the detection by visiting the legit website [www.internetbadguys.com](http://www.internetbadguys.com). The notification reports the app which made the connection and the IP address or domain which triggered the detection. By clicking on the notification, it is possible to get the list of all the malicious connections made by the app.

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/gh-pages/images/malicious_connections.jpg" width="250" />
</p>

The "malicious" connection filter can be applied at any time from the "Edit Filter" dialog. The malicious connections data is lost once the capture is stopped. When a connection is blocked, a "ban" sign is shown next to the malicious indicator.

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/gh-pages/images/malware_blocked.jpg" width="300" />
</p>

In this case, PCAPdroid has blocked the communication with the malicious website.

PCAPdroid provides an overview on the status of the malware detection, which is available in the "Malware Detection" entry in the left drawer. The Status tab shows the current status of the malware detection.

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/gh-pages/images/malware_detection_status.jpg" width="250" />
</p>

A green checkmark indicates that no malicious connections have been detected during the capture. It is replaced by a red danger sign if malware traffic is detected.
The cards below the mark provide some indicators for the malware detection:

- The "Updated blacklists" reports the number of successfully updated blacklists over the total blacklists number. PCAPdroid updates the blacklists once a day to ensure that it can catch the newest threats. If some download fails, a manual update can be triggered from the "Blacklists" tab.
- The "Last update" reports the last time the blacklists were updated.
- The "IP" and "Domain" rules indicates the total number of unique rules that PCAPdroid is using to perfom the malware detection.

The "Blacklists" tab shows the status of each individual blacklist in use.

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/gh-pages/images/blacklists_status.jpg" width="250" />
</p>

The blacklists used by PCAPdroid contain a list of domains and IP addresses with a bad reputation. This includes scanners, brute-forcers and known sources of malware. The blacklists normally contain some static rules, which are based on the past infections data, and some dynamic rules, which are generated automatically, usually via honeypots.

Each blacklist entry specifies the number of unique rules in use. Duplicate rules are only counted once so some blacklists may show 0 rules if they are already part of another blacklist. The "Last update" field shows the last time a given blacklist was updated. By clicking on the blacklist title, it is possible to open the blacklist to manually review the rules it contains. By clicking on the refresh icon in the action bar it is possible to manually trigger a blacklists update.

Finally the "Whitelist" tab show the rules which are part of the whitelist. If the malware detection gets triggered by a false positive, you can long press a malicious connection and whitelist the genuine IP, domain or even the app.
