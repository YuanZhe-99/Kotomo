#!/usr/bin/env bash
# Fetches the prebuilt Mozc libraries and data that .github/workflows/mozc.yml built, as pinned in
# build-lock.json (upstreams.mozc.prebuilt: the workflow run and each file's SHA-256), into
# build/mozc-prebuilt/ for the app build (PLAN §2.5: Mozc's Android build needs Linux or macOS).
#
# Usage: scripts/fetch-mozc.sh
# Needs: gh (authenticated; GH_TOKEN in CI), node (to read build-lock.json), sha256sum.
# Side effects: replaces build/mozc-prebuilt/ with jniLibs/<abi>/libkotomo_mozc.so and
#   assets/mozc.data; fails without touching it when a hash does not match.
set -euo pipefail

die() { echo "fetch-mozc: $*" >&2; exit 1; }
[[ -f build-lock.json ]] || die "run from the repository root"

lock() { node -e "const p=require('./build-lock.json').upstreams.mozc.prebuilt; console.log(p$1)"; }
run=$(lock ".run") || die "no upstreams.mozc.prebuilt.run in build-lock.json"
repo=$(lock ".repository")

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
gh run download "$run" -R "$repo" -n kotomo-mozc -D "$tmp" || die "download of run $run failed"

for file in arm64-v8a/libkotomo_mozc.so x86_64/libkotomo_mozc.so mozc.data; do
  want=$(lock ".sha256['$file']")
  got=$(sha256sum "$tmp/$file" | cut -d' ' -f1)
  [[ "$want" == "$got" ]] || die "$file: sha256 $got, build-lock.json pins $want"
done

rm -rf build/mozc-prebuilt
mkdir -p build/mozc-prebuilt/jniLibs/arm64-v8a build/mozc-prebuilt/jniLibs/x86_64 build/mozc-prebuilt/assets
cp "$tmp/arm64-v8a/libkotomo_mozc.so" build/mozc-prebuilt/jniLibs/arm64-v8a/
cp "$tmp/x86_64/libkotomo_mozc.so" build/mozc-prebuilt/jniLibs/x86_64/
cp "$tmp/mozc.data" build/mozc-prebuilt/assets/
echo "fetch-mozc: build/mozc-prebuilt from run $run"
