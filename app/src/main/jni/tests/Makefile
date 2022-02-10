.PHONY: clean run_tests fuzz fuzz_pcapd fuzz_capture

clean:
	rm -rf build /tmp/pcapdroid_test*

run_tests:
	mkdir -p build/test
	cd build/test && \
		cmake -DCMAKE_C_COMPILER=clang -DCMAKE_C_FLAGS="-fsanitize=address,leak -fno-omit-frame-pointer -O1" ../.. && \
		$(MAKE) run_tests

fuzz:
	# Note: corpus should be specific of a single target
	mkdir -p build/${FUZZ_TARGET}
	cd build/${FUZZ_TARGET} && \
		cmake -DFUZZING=1 -DCMAKE_C_COMPILER=clang ../.. && \
		$(MAKE) ${FUZZ_TARGET}
	cd build/${FUZZ_TARGET}/fuzz && \
		mkdir -p CORPUS && \
		./${FUZZ_TARGET} CORPUS ../../../../../../../../submodules/nDPI/tests/pcap ../../../pcap ${FUZZ_OPTS}

fuzz_pcapd:
	$(MAKE) FUZZ_TARGET=fuzz_pcapd fuzz

fuzz_capture:
	$(MAKE) FUZZ_TARGET=fuzz_capture fuzz
