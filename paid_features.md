Paid features are only available when the app is installed [via Google Play](https://play.google.com/store/apps/details?id=com.emanuelef.remote_capture). If you are an F-Droid user and have suggestions on how to handle payments in the F-Droid build, please [reach me via email](mailto:black.silver@hotmail.it?subject=PCAPdroid).

## 5.1 Malware Detection

When this feature is enabled, PCAPdroid detects connections to malicious hosts and alerts the user when such an event occurs. The detection is only active when the capture is running.

Malicious hosts are determined by using publicly available blacklists of malicious IPs and domains.
The blacklists can detect common Internet threats, such as C&C (botnets), ramsonware, brute-forcers and they have a specific focus on the Android-related malware and threat actors. Lists can contain false positives or can miss some threats so this feature is not a valid alternative to an antivirus.

PCAPdroid updates the blacklists once a day to ensure that it can catch the newest threats. The blacklists status is shown in the `Stats` page:

- Up-to-date: reports the number of up-to-date blacklists. In the event a blacklist update fails, the previous one is still used.
- Domain rules: reports the number of unique domain rules.
- IP rules: reports the number of IP/subnet rules. Some IP addresses may be already included into other rules, so this count is just an upper bound.
- Last update: reports the time of the last blacklists update.

When a malicious connection is detected, it is reported to the user via an Android notification. The notification reports the app which generated the connection and the IP address or domain which triggered the detection. To avoid polluting the notifications area, the notification only reports the latest malicious IP/domain for a given app.

By clicking on the notification, it is possible to get the list of all the malicious connections made by the given app.
The malicious connection filter can be applied at any time from the "Edit Filter" dialog. The malicious connections data is lost once the capture is stopped.
