#!/bin/sh
# sign-framework-res.sh KEYTOOL JARSIGNER KEYSTORE INPUT.apk OUTPUT.apk
#
# Signs framework-res.apk (the framework package "android") with ATL's platform key, as AOSP signs
# its framework with the platform key: apps and libraries look at the framework package's
# signature, and PackageManager reports it (GET_SIGNATURES, signingInfo, checkSignatures).
#
# KEYSTORE is the platform key. When it does not exist, a new one is generated there (RSA 2048,
# self-signed): the build directory's own key, made once per build directory. Nothing outside this
# runtime trusts it and only its self-consistency matters, so a fresh key per build is fine; pass
# -Dplatform_keystore=PATH to sign with a key of your own (PKCS12, alias "platform", store password
# in the environment variable ATL_PLATFORM_KEYSTORE_PASS, else the default below). Never commit a
# keystore.
#
# The signature is a JAR (v1) signature, which ATL's PackageParser.collectCertificates reads for
# system packages.
set -eu
keytool=$1 jarsigner=$2 keystore=$3 in=$4 out=$5
pass=${ATL_PLATFORM_KEYSTORE_PASS:-android-translation-layer-platform}

if [ ! -f "$keystore" ]; then
	mkdir -p "$(dirname "$keystore")"
	"$keytool" -genkeypair -noprompt \
		-keystore "$keystore" -storetype PKCS12 -storepass "$pass" \
		-alias platform -keyalg RSA -keysize 2048 -validity 10000 \
		-dname "CN=Android Translation Layer platform, O=Android Translation Layer" >/dev/null
fi

rm -f "$out.tmp"
cp "$in" "$out.tmp"
"$jarsigner" -keystore "$keystore" -storetype PKCS12 -storepass "$pass" \
	-sigalg SHA256withRSA -digestalg SHA-256 "$out.tmp" platform >/dev/null
mv "$out.tmp" "$out"
