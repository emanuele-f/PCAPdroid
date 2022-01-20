Tests in PCAPdroid can be split in the following categories:

- [Java tests](https://github.com/emanuele-f/PCAPdroid/tree/dev/app/src/test/java):
  they can be run via `./gradlew test`. They use the
  [robolectric framework](https://github.com/robolectric/robolectric)
  to mock the Android API, allowing them to be run locally (without an emulator)

- [Native tests](https://github.com/emanuele-f/PCAPdroid/tree/dev/app/src/main/jni/tests):
  they can be run with `./run_tests.sh` on a linux host. They are built
  with the [AddressSanitizer](https://clang.llvm.org/docs/AddressSanitizer.html)
  to detect memory issues and leaks

The tests are executed on every push via the
[Github workflows](https://github.com/emanuele-f/PCAPdroid/tree/dev/.github/workflows)

Apart from automatic tests, the following manual tests should be performed
before every release:

- Test on devices matching the `minSdkVersion` (currently Android SDK 21)
- Test on devices matching the `targetSdkVersion` (currently Android SDK 31)
- Rotate the device, put activity in background, clear from recent activities
- Java memory consumption tests via the [Memory Profiler](https://developer.android.com/studio/profile/memory-profiler)
- Manual malware detection test against `internetbadguys.com` and `0.0.0.1`
