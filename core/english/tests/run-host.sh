#!/bin/sh
# Builds libkotomo_english.so for the Linux host and runs EnglishHostTest against the English
# dictionary (spike S4). Run from the repository root on Linux (or WSL) with CMake, Ninja, a C++17
# compiler and a JDK: core/english/tests/run-host.sh [dict]. The dictionary defaults to the output
# of `java tool/BuildEnglishDict.java`. Host results never stand in for device latency.
set -eu
dict=${1:-build/lexicon/english/main_en_us.dict}
out=build/host/english
cmake -S core/english -B "$out" -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$out"
java -Dkotomo.english.lib="$PWD/$out/libkotomo_english.so" -Dkotomo.english.dict="$dict" \
    core/english/tests/EnglishHostTest.java
