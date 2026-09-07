#!/bin/bash
#
# Copyright 2026 - Emanuele Faranda
#
# Verifies the signature of a PCAPdroid APK and prints its build type.
# The known fingerprints must be kept in sync with Utils.getVerifiedBuild.
#

set -u

usage() {
	echo "Usage: $(basename "$0") <file.apk>"
	echo
	echo "Prints the PCAPdroid build type of the given APK, based on its signing certificate."
	echo "The Android SDK is searched via \$ANDROID_HOME / \$ANDROID_SDK_ROOT or in standard locations."
	exit 1
}

build_type() {
	case "$1" in
		511140392BFF2CFB4BD825895DD6510CE1807F6D) echo "DEBUG" ;;
		EE953D4F988C8AC17575DFFAA1E3BBCE2E29E81D) echo "PLAYSTORE" ;;
		77DA81218F6E0D91220700317B2BCB906F4D4255) echo "GITHUB" ;;
		72777D6939EF150099219BBB68C17220DB28EA8E) echo "FDROID" ;;
		*) echo "" ;;
	esac
}

find_apksigner() {
	if command -v apksigner >/dev/null 2>&1; then
		command -v apksigner
		return 0
	fi

	local sdk_dirs=(
		"${ANDROID_HOME:-}"
		"${ANDROID_SDK_ROOT:-}"
		"${ANDROID_SDK_HOME:-}"
		"$HOME/Android/Sdk"
		"$HOME/Library/Android/sdk"
		"/opt/android-sdk"
		"/usr/lib/android-sdk"
	)

	local sdk
	for sdk in "${sdk_dirs[@]}"; do
		[ -n "$sdk" ] || continue
		[ -d "$sdk/build-tools" ] || continue

		# use the most recent build-tools
		local tool
		tool=$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 -name apksigner -type f 2>/dev/null | sort -V | tail -n1)
		if [ -n "$tool" ]; then
			echo "$tool"
			return 0
		fi
	done

	return 1
}

get_fingerprints() {
	local apk="$1"

	if [ -n "$APKSIGNER" ]; then
		"$APKSIGNER" verify --print-certs "$apk" 2>/dev/null |
			sed -n 's/^Signer .* certificate SHA-1 digest: \([0-9a-fA-F]*\)$/\1/p' |
			tr 'a-f' 'A-F'
	else
		# fallback, only works with APKs having a v1 (JAR) signature
		keytool -printcert -jarfile "$apk" 2>/dev/null |
			sed -n 's/^[[:space:]]*SHA1:[[:space:]]*\([0-9a-fA-F:]*\)$/\1/p' |
			tr -d ':' | tr 'a-f' 'A-F'
	fi
}

check_apk() {
	local apk="$1"

	if [ ! -f "$apk" ]; then
		echo "Error: $apk: file not found" >&2
		return 1
	fi

	local fingerprints
	fingerprints=$(get_fingerprints "$apk")

	if [ -z "$fingerprints" ]; then
		echo "Error: could not extract the signing certificate (unsigned APK?)" >&2
		return 1
	fi

	local fingerprint
	for fingerprint in $fingerprints; do
		local btype
		btype=$(build_type "$fingerprint")

		if [ -n "$btype" ]; then
			echo "$btype"
			return 0
		fi
	done

	echo "UNKNOWN signature ($(echo $fingerprints | tr ' ' ','))" >&2
	return 1
}

[ $# -eq 1 ] || usage
case "$1" in
	-h|--help) usage ;;
esac

APKSIGNER=$(find_apksigner) || APKSIGNER=""

if [ -z "$APKSIGNER" ]; then
	if ! command -v keytool >/dev/null 2>&1; then
		echo "Error: neither apksigner nor keytool found. Set \$ANDROID_HOME to the Android SDK path." >&2
		exit 2
	fi

	echo "Warning: apksigner not found, falling back to keytool (only detects v1 signatures)" >&2
fi

check_apk "$1"
