#!/usr/bin/env bash
# Stages librime's shared data and prebuilds it (compiles the schemas and dictionaries) on a
# Linux host, so the app ships compiled data and the phone never deploys on first use.
#
# Usage: scripts/prebuild-rime.sh [--out <dir>] [--work <dir>]
#   --out   the shared data directory to produce (default build/rime-prebuilt); the app build
#           packs it as the rime assets when it exists
#   --work  the host build directory for librime (default build/host/rime)
# Needs: Linux or WSL with CMake, Ninja and a C++ compiler; the submodules and the Boost headers
#   (scripts/fetch-boost.sh). Run from the repository root.
# Side effects: writes only <work> and <out>; <out> is replaced.
set -euo pipefail

out=build/rime-prebuilt
work=build/host/rime
while [[ $# -gt 0 ]]; do
  case "$1" in
    --out) out="${2:?}"; shift 2 ;;
    --work) work="${2:?}"; shift 2 ;;
    *) echo "prebuild-rime: unknown argument $1" >&2; exit 2 ;;
  esac
done

die() { echo "prebuild-rime: $*" >&2; exit 1; }
[[ -f core/rime/stage-data.cmake ]] || die "run from the repository root"

cmake -S core/rime -B "$work" -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null || die "configure failed"
cmake --build "$work" --target kotomo_rime_prebuild || die "build failed"

rm -rf "$out"
cmake "-DKOTOMO_ROOT=$PWD" "-DOUT=$out" -P core/rime/stage-data.cmake || die "staging failed"
"$work/kotomo_rime_prebuild" "$out" "$work/prebuild-user" || die "prebuild failed"
echo "prebuild-rime: $out ($(du -sh "$out" | cut -f1))"
