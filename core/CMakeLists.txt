# Builds Kotomo's native libraries for the app (Gradle externalNativeBuild); each engine is its own
# shared library with hidden internal symbols (PLAN §4.2).
cmake_minimum_required(VERSION 3.22.1)
project(kotomo_core CXX)

add_subdirectory(english)
