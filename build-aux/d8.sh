#!/bin/sh
# d8.sh R8_JAR OUTPUT.jar INPUT.jar: dex INPUT.jar with D8 into OUTPUT.jar. The dex files are packed
# with `jar`, so the output has a manifest like dx's (meson's install reads META-INF/MANIFEST.MF of
# every installed .jar). --min-api 24: lambdas are desugared, default and static interface methods kept.
set -e
r8=$1 out=$2 in=$3
case $out in /*) ;; *) out="$PWD/$out" ;; esac
tmp="$out.d8"
rm -rf "$tmp"
mkdir -p "$tmp"
java -cp "$r8" com.android.tools.r8.D8 --min-api 24 --output "$tmp" "$in"
rm -f "$out"
(cd "$tmp" && jar cf "$out" classes*.dex)
rm -rf "$tmp"
