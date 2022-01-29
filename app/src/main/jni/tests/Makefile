.PHONY: clean run_tests fuzz fuzz_pcapd

clean:
	rm -rf build

run_tests:
	mkdir -p build/tests
	cd build && cmake .. && $(MAKE) run_tests

fuzz:
	mkdir -p build/${FUZZ_TARGET}
	( cd build/${FUZZ_TARGET} && cmake ../.. -DFUZZING=1 && make -j$(nproc) ${FUZZ_TARGET} )
	( cd build/${FUZZ_TARGET} && mkdir -p ../CORPUS && fuzz/${FUZZ_TARGET} ../CORPUS ../../../../../../../submodules/nDPI/tests/pcap ../../pcap >/dev/null )

fuzz_pcapd:
	$(MAKE) FUZZ_TARGET=fuzz_pcapd fuzz
