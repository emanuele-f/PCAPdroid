## Running tests

Tests in PCAPdroid can be split in the following categories:

- [Java tests](https://github.com/emanuele-f/PCAPdroid/tree/master/app/src/test/java):
  they can be run via `./gradlew test`. They use the
  [robolectric framework](https://github.com/robolectric/robolectric)
  to mock the Android API, allowing them to be run locally (without an Android device).

- [Native tests](https://github.com/emanuele-f/PCAPdroid/tree/master/app/src/main/jni/tests):
  tests and fuzzing targets for native code. Check out their readme for more details.

The tests are executed on every push via the
[Github workflows](https://github.com/emanuele-f/PCAPdroid/tree/master/.github/workflows).

Apart from automatic tests, the following manual tests should be performed
before every release:

- Test on devices matching the `minSdkVersion` (currently Android SDK 21)
- Test on devices matching the `targetSdkVersion` (currently Android SDK 31)
- Rotate the device, put activity in background, clear from recent activities
- Java memory consumption tests via the [Memory Profiler](https://developer.android.com/studio/profile/memory-profiler)
- Manual malware detection test against `internetbadguys.com` and `0.0.0.1`

## VPN mode performance

Performance is essential in VPN mode, as PCAPdroid can become a bottleneck, reducing the max bandwidth. While testing performance, it's useful to measure both the bandwidth and the CPU usage. In fact, a 100% cpu usage on the PCAPdroid process indicates that the capture thread has reached its limit, whereas a lower usage indicates that there could be a problem in the handling of packets (e.g. with the TCP window size).

A basic setup for a vpn performance measurement requires:

- the `iperf3` package, installed in termux, to measure the bandwidth
- the `top` utility, run via `adb`, to get the pcapdroid cpu usage. USB connection is advided to avoid losing the connection on vpn start/stop
- an high throughput wifi connection, e.g. a 5 GHz. The network should not be the bottleneck for the test
- a phone with good wifi hw. Again, it should not be the bottleneck
- a pc connected to the same network via gigabit ethernet

Since the VPN mode only captures connections initiaded from the device, the iperf3 client must be run on the Android device. To run commands more easily, it's adviced to run an sshd daemon via termux and connect to it from the pc.

Here are the commands to run on the phone to perform the different tests with a given bandwidth, assuming iperf3 server running on 192.168.1.10.

- UDP upload: `iperf3 -u -c 192.168.1.10 -b 100M --length 1472`
- UDP download: `iperf3 -u -R -c 192.168.1.10 -b 100M --length 1472`
- TCP upload: `iperf3 -c 192.168.1.10 -b 100M`
- TCP download: `iperf3 -c -R 192.168.1.10 -b 100M`

The `-R` flag enables the reverse mode, which makes the iperf3 client receive data from the server. It's advided to first run the tests without PCAPdroid running to ensure that no bottlenecks are present.
