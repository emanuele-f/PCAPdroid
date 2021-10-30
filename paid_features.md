Paid features are only available when the app is installed [via Google Play](https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture). If you are an F-Droid user and you have any suggestions on how to handle payments in the F-Droid build, please [reach me via email](mailto:black.silver@hotmail.it?subject=PCAPdroid).

## 5.1 Malware Detection

**DISCLAIMER**: *the malware detection feature of PCAPdroid is not a comprehensive solution for the security of your device. The author provides no guarantee on the malware detection capabilities or accuracy of PCAPdroid and he can not be held liable for any direct or indirect damage caused by its use.*

The malware detection feature enables PCAPdroid to detect malicious hosts by the means of third-party blacklists. The detection is only active when the capture is running.

Today our devices are exposed to a variety of threats: phishing, online scams, ransomware and spyware to name a few. When it comes to security, precautions are never enough and no solution will fit all the needs. The malware detection feature of PCAPdroid can help detecting malicious connections as they happen, bringing the possible threat to the user attention.

Here are some contexts where it finds applicability:

- The user browsers a known malicious website (e.g. phishing, scam)
- The user installs a malicious app or addon (e.g. spyware) which connects to a known threat actor
- The device is exploited and a malware is installed (e.g. spyware, ransonware or C&C), which connects to a known threat actor

The blacklists used by PCAPdroid contain a list of domains and IP addresses with a bad reputation, being them scanners, brute-forcers or actors performing other malicious activities. These blacklists normally contain some static rules, which are based on the past infections data, and some dynamic rules, which are generated automatically via honeypots. PCAPdroid updates the blacklists once a day to ensure that it can catch the newest threats.

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/gh-pages/images/blacklists_status.jpg" width="250" />
</p>

The blacklists status is shown in the `Stats` page:

- *Up-to-date*: reports the number of up-to-date blacklists. In the event a blacklist update fails, the previous one is still used.
- *Domain rules*: reports the number of unique domain rules.
- *IP rules*: reports the number of IP/subnet rules. Some IP addresses may be already included into other rules, so this count is just an upper bound.
- *Last update*: reports the time of the last blacklists update.

When a malicious connection is detected, it is reported to the user via a notification. You can manually test the detection by visiting the legit website [www.internetbadguys.com](http://www.internetbadguys.com).

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/gh-pages/images/malware_notification.jpg" width="300" />
</p>

The notification reports the app which made the connection and the IP address or domain which triggered the detection. To avoid polluting the notifications area, the notification only reports the latest malicious contact for a given app.

By clicking on the notification, it is possible to get the list of all the malicious connections made by the app.

<p align="center">
<img src="https://raw.githubusercontent.com/emanuele-f/PCAPdroid/gh-pages/images/malicious_connections.jpg" width="250" />
</p>

The malicious connection filter can be applied at any time from the "Edit Filter" dialog. The malicious connections data is lost once the capture is stopped.

If the malware detection gets triggered by a false positive, you can long press a malicious connection and whitelist the genuine IP, domain or even the app. New connections matching any whitelist rule won't be detected as malware and the existing ones will be re-evaluated. You an edit the malware whitelist from the left drawer.
