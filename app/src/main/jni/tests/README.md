Tests and fuzzers for the native code of PCAPdroid.

The tests are built with the [AddressSanitizer](https://clang.llvm.org/docs/AddressSanitizer.html) to detect memory issues and leaks. They are run as part of the Github workflow.

The fuzzers use [LibFuzzer](https://llvm.org/docs/LibFuzzer.html).

The targets can be run with the provided `Makefile` as follows:

```bash
# Run the tests
make run_tests

# Fuzz the pcapd daemon
make fuzz_pcap
```
