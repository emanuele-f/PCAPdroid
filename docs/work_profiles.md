## Running in work profiles

Work profiles allows users to isolate apps. They also allow running multiple VPN at the same time, as each work profile can run a VPNService.

When PCAPdroid runs in a work profile in VPN mode, it can only capture the traffic of the work profile.

You can install [Island](https://play.google.com/store/apps/details?id=com.oasisfeng.island) to manage work profiles

## Root support

When running with root, PCAPdroid still captures the traffic of the whole device, regardless if it's installed in the main profile or in the work profile.
To properly map UIDs of apps installed into different profiles, it uses the [INTERACT_ACROSS_USERS permission](https://source.android.com/devices/tech/admin/multiuser-apps),
which is granted on the first root capture start. Apps installed in both the main profile and a work profile will have two different UIDs, and are reported
by PCAPdroid as two different apps.

In order to properly grant root privileges to PCAPdroid when installed in a work profile, follow these steps:

1. In the Magisk manager settings, set Multiuser-mode to "User independent"
2. Clone the Magisk manager app to the work profile
3. Start the root capture and grant the root request from magisk dialog

## Debugging

To easily debug PCAPdroid in work profile, create a new run configuration which installs and runs the app into the specific work profile.

First get the work profile user ID from `adb shell pm list users` (e.g. 10). Then add the `--user 10` parameter to the "Install flags" and "Launch flags" of the new configuration.
