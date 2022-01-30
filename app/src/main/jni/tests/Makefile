.PHONY: clean run_tests fuzz fuzz_pcapd

clean:
	rm -rf build /tmp/pcapdroid_test*

run_tests:
	mkdir -p build/test
	cd build/test && \
		cmake -DCMAKE_C_COMPILER=clang -DCMAKE_C_FLAGS="-fsanitize=address -fno-omit-frame-pointer -O1" ../.. && \
		$(MAKE) run_tests

fuzz:
	# Note: corpus should be specific of a single target
	mkdir -p build/${FUZZ_TARGET}
	cd build/${FUZZ_TARGET} && \
		cmake -DFUZZING=1 -DCMAKE_C_COMPILER=clang ../.. && \
		make -j$(nproc) ${FUZZ_TARGET}
	cd build/${FUZZ_TARGET}/fuzz && \
		mkdir -p CORPUS && \
		./${FUZZ_TARGET} CORPUS ../../../../../../../../submodules/nDPI/tests/pcap ../../../pcap >/dev/null

fuzz_pcapd:
	$(MAKE) FUZZ_TARGET=fuzz_pcapd fuzz

fuzz_capture:
	$(MAKE) FUZZ_TARGET=fuzz_capture fuzz
