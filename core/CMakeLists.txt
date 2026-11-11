# Builds Kotomo's native libraries for the app (Gradle externalNativeBuild); each engine is its own
# shared library with hidden internal symbols (PLAN §4.2), and the mixed-input coordinator is a
# fourth that reaches them through function tables (ADR-008). Mozc's library is built by Bazel
# (scripts/build-mozc.sh) and packed as a prebuilt.
cmake_minimum_required(VERSION 3.22.1)
project(kotomo_core C CXX)

add_subdirectory(english)
add_subdirectory(rime)
add_subdirectory(mixed)
