#!/usr/bin/env bash
# Builds Mozc and Kotomo's Mozc glue (core/mozc) with Mozc's own Bazel build.
#
#   scripts/build-mozc.sh --abi arm64-v8a [--abi x86_64] [options]
#   scripts/build-mozc.sh --abi host      # Linux host build and glue tests
#
# Options:
#   --abi <arm64-v8a|x86_64|host>  repeatable; required
#   --mozc <dir>     Mozc checkout (default: third_party/mozc)
#   --work <dir>     work directory (default: build/mozc); holds a copy of Mozc
#                    with core/mozc at src/kotomo, so the submodule stays untouched
#   --out <dir>      output directory (default: <work>/out)
#   --ndk <dir>      an existing Android NDK r29; otherwise Mozc's
#                    build_tools/update_deps.py downloads it and checks its hash
#   --bazelisk <f>   an existing Bazelisk; otherwise the pinned release below is
#                    downloaded into <work>/tools and checked against its hash
#   --jobs <n>       Bazel --jobs (default: Bazel's own choice)
#
# Host output: <out>/host/libkotomo_mozc.so and <out>/host/mozc.data for the
# conversion corpus (tests/conversion/run.sh).
# Android output: <out>/<abi>/libkotomo_mozc.so (16 KB-aligned, JNI symbols
# only), <out>/mozc.data and <out>/SHA256SUMS, installed into build/mozc-prebuilt
# for the app build and compared with build-lock.json. Runs on Linux (x86_64 for
# Android, since the NDK ships only x86_64 Linux host tools) or macOS; nothing
# is installed system-wide.

set -euo pipefail

readonly BAZELISK_VERSION="v1.30.0"
readonly BAZELISK_SHA256_LINUX_AMD64="a248ae2b2b62b5baebb748f6fba7203bff1a27e5d01b55399a8406744cb539e8"
readonly BAZELISK_SHA256_LINUX_ARM64="b2bcc040c213b44586b121bda6d983c35df121b917c3a7a66fb16f2fa0df1a4f"
readonly NDK_DIR_NAME="android-ndk-r29"

repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
abis=()
mozc="$repo/third_party/mozc"
work="$repo/build/mozc"
out=""
ndk=""
bazelisk=""
jobs=""

die() {
  echo "build-mozc: $*" >&2
  exit 1
}

# Runs a command and exits with its status and a message if it fails.
run() {
  "$@" || die "failed ($?): $*"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --abi) abis+=("${2:?--abi needs a value}"); shift 2 ;;
    --mozc) mozc="${2:?}"; shift 2 ;;
    --work) work="${2:?}"; shift 2 ;;
    --out) out="${2:?}"; shift 2 ;;
    --ndk) ndk="${2:?}"; shift 2 ;;
    --bazelisk) bazelisk="${2:?}"; shift 2 ;;
    --jobs) jobs="${2:?}"; shift 2 ;;
    -h|--help) sed -n '2,26p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

[[ ${#abis[@]} -gt 0 ]] || die "give at least one --abi"
for abi in "${abis[@]}"; do
  case "$abi" in
    arm64-v8a|x86_64|host) ;;
    *) die "unsupported ABI: $abi" ;;
  esac
done
[[ -f "$mozc/src/MODULE.bazel" ]] || die "not a Mozc checkout: $mozc (run git submodule update --init third_party/mozc)"
for tool in rsync python3 sha256sum curl; do
  command -v "$tool" >/dev/null || die "missing tool: $tool"
done

run mkdir -p "$work"
work="$(cd "$work" && pwd)"
mozc="$(cd "$mozc" && pwd)"
out="${out:-$work/out}"
case "$work/" in
  "$mozc"/*|"$repo/") die "the work directory must be outside the Mozc checkout and not the repository root" ;;
esac

# Bazel's rules_java needs a JDK home; derive it from java on PATH when unset.
if [[ -z "${JAVA_HOME:-}" ]] && command -v java >/dev/null; then
  JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
  export JAVA_HOME
fi

# Bazelisk: pinned release, hash-checked.
if [[ -z "$bazelisk" ]]; then
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) asset="bazelisk-linux-amd64"; sha="$BAZELISK_SHA256_LINUX_AMD64" ;;
    Linux-aarch64) asset="bazelisk-linux-arm64"; sha="$BAZELISK_SHA256_LINUX_ARM64" ;;
    *) die "no pinned Bazelisk for $(uname -s)-$(uname -m); pass --bazelisk" ;;
  esac
  bazelisk="$work/tools/bazelisk-$BAZELISK_VERSION"
  if [[ ! -x "$bazelisk" ]]; then
    run mkdir -p "$work/tools"
    run curl -fsSL -o "$bazelisk.part" \
      "https://github.com/bazelbuild/bazelisk/releases/download/$BAZELISK_VERSION/$asset"
    echo "$sha  $bazelisk.part" | sha256sum -c - >/dev/null || die "Bazelisk hash mismatch"
    run chmod +x "$bazelisk.part"
    run mv "$bazelisk.part" "$bazelisk"
  fi
fi

# Work copy: Mozc as checked out, plus the glue at src/kotomo. Only paths inside
# the work copy are replaced; Bazel's convenience links and the NDK are kept.
src="$work/mozc/src"
run mkdir -p "$work/mozc"
run rsync -a --delete \
  --exclude=/.git --exclude='/src/bazel-*' --exclude=/src/third_party/ndk \
  --filter='P /src/third_party/' \
  --exclude=/src/third_party_cache --exclude=/src/kotomo \
  "$mozc/" "$work/mozc/"
run rsync -a --delete "$repo/core/mozc/" "$src/kotomo/"
if [[ -d "$repo/patches/mozc" ]]; then
  for patch in "$repo"/patches/mozc/*.patch; do
    [[ -e "$patch" ]] || continue
    run patch -d "$work/mozc" -p1 --forward --batch -i "$patch"
  done
fi

bazel_flags=(--config release_build --nocheck_visibility)
[[ -n "$jobs" ]] && bazel_flags+=("--jobs=$jobs")

# The NDK must exist before the first Bazel command of this run: Bazel evaluates the NDK module
# extension once per server, so an NDK fetched after a host build leaves no Android toolchain.
ndk_dest="$src/third_party/ndk/$NDK_DIR_NAME"
if [[ " ${abis[*]} " != " host " ]]; then
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64|Darwin-*) ;;
    *) die "$abi needs an x86_64 Linux or a macOS host: the NDK has no $(uname -s)-$(uname -m) tools" ;;
  esac
  if [[ ! -d "$ndk_dest" ]]; then
    if [[ -n "$ndk" ]]; then
      run mkdir -p "$src/third_party/ndk"
      run ln -s "$(cd "$ndk" && pwd)" "$ndk_dest"
    else
      (cd "$src" && python3 build_tools/update_deps.py \
        --noqt --noninja --nollvm --nomsys2 --nowix) || die "NDK download failed"
    fi
  fi
  [[ -d "$ndk_dest" ]] || die "no NDK at $ndk_dest"
fi

for abi in "${abis[@]}"; do
  if [[ "$abi" == "host" ]]; then
    (cd "$src" && "$bazelisk" test --config oss_linux "${bazel_flags[@]}" \
      --test_output=all //kotomo:kotomo_mozc_test) || die "host tests failed"
    (cd "$src" && "$bazelisk" build --config oss_linux "${bazel_flags[@]}" \
      //kotomo:libkotomo_mozc.so) || die "host library build failed"
    host_lib="$src/bazel-bin/kotomo/libkotomo_mozc.so"
    # Every exported (global or weak) symbol must be a JNI entry point.
    exported="$(nm -D --defined-only "$host_lib" | awk '$2 ~ /^[A-Z]$/ {print $3}')" \
      || die "nm failed"
    if grep -v '^Java_com_yuanzhe_kotomo_engine_Mozc_' <<<"$exported" | grep -q .; then
      die "host library exports more than its JNI functions"
    fi
    # The conversion corpus (tests/conversion) loads this library and the data set into a host JVM.
    run install -D -m 0755 "$host_lib" "$out/host/libkotomo_mozc.so"
    run install -D -m 0644 "$src/bazel-bin/data_manager/oss/mozc.data" "$out/host/mozc.data"
    echo "host: tests passed; $(wc -l <<<"$exported") exported symbols, all JNI"
    continue
  fi


  # The data set is the same for every ABI and incompatible with the Android platforms, so it is
  # built for the host; its bazel-bin path is valid until the next build changes the config.
  if [[ -z "${data_built:-}" ]]; then
    (cd "$src" && "$bazelisk" build --config oss_linux "${bazel_flags[@]}" \
      //data_manager/oss:mozc_dataset_for_oss) || die "data set build failed"
    run mkdir -p "$out"
    run install -m 0644 "$src/bazel-bin/data_manager/oss/mozc.data" "$out/mozc.data"
    data_built=1
  fi
  (cd "$src" && "$bazelisk" build --config oss_android "${bazel_flags[@]}" \
    "//kotomo:android_$abi") || die "Android build failed for $abi"
  lib="$(cd "$src" && "$bazelisk" cquery --config oss_android "${bazel_flags[@]}" \
    --output=files "//kotomo:android_$abi" 2>/dev/null)" || die "cannot locate the $abi library"
  run mkdir -p "$out/$abi"
  run install -m 0755 "$src/$lib" "$out/$abi/libkotomo_mozc.so"

  # 16 KB pages: every PT_LOAD segment must be aligned to at least 0x4000.
  readelf="$(find "$ndk_dest/toolchains/llvm/prebuilt" -name llvm-readelf -type f -o -name llvm-readelf -type l | head -n1)"
  [[ -n "$readelf" ]] || die "llvm-readelf not found in the NDK"
  aligns="$("$readelf" -lW "$out/$abi/libkotomo_mozc.so" | awk '$1 == "LOAD" {print $NF}')" \
    || die "readelf failed"
  [[ -n "$aligns" ]] || die "no LOAD segments in $abi library"
  while read -r align; do
    (( align >= 0x4000 )) || die "$abi: LOAD segment aligned to $align, not 16 KB"
  done <<<"$aligns"
  exported="$("$readelf" --dyn-syms -W "$out/$abi/libkotomo_mozc.so" \
    | awk '($5 == "GLOBAL" || $5 == "WEAK") && $7 != "UND" {print $8}')" || die "readelf failed"
  extra="$(grep -v '^Java_com_yuanzhe_kotomo_engine_Mozc_' <<<"$exported" || true)"
  if [[ -n "$extra" ]]; then
    echo "$abi exports beyond the JNI functions:" >&2
    head -n 40 <<<"$extra" >&2
    die "$abi library exports more than its JNI functions ($(wc -l <<<"$extra") symbols)"
  fi
  echo "$abi: 16 KB-aligned, $(wc -l <<<"$exported") exported symbols, all JNI"
done

if [[ -d "$out" ]]; then
  (cd "$out" && find . -type f ! -name SHA256SUMS -print0 | sort -z \
    | xargs -0 sha256sum > SHA256SUMS) || die "hashing failed"
  cat "$out/SHA256SUMS"
fi

# Installs the Android outputs where the app build picks them up and compares them with the
# hashes build-lock.json records; a difference is reported, since moving Mozc changes them.
if [[ -f "$out/mozc.data" ]]; then
  prebuilt="$repo/build/mozc-prebuilt"
  run install -D -m 0644 "$out/mozc.data" "$prebuilt/assets/mozc.data"
  for abi in "${abis[@]}"; do
    [[ "$abi" == "host" ]] && continue
    run install -D -m 0755 "$out/$abi/libkotomo_mozc.so" "$prebuilt/jniLibs/$abi/libkotomo_mozc.so"
  done
  python3 - "$repo/build-lock.json" "$out" "${abis[@]}" <<'EOF' || die "build-lock.json comparison failed"
import hashlib, json, pathlib, sys
pinned = json.load(open(sys.argv[1]))["upstreams"]["mozc"]["prebuilt"]["sha256"]
out = pathlib.Path(sys.argv[2])
files = ["mozc.data"] + [f"{abi}/libkotomo_mozc.so" for abi in sys.argv[3:] if abi != "host"]
for name in files:
    got = hashlib.sha256((out / name).read_bytes()).hexdigest()
    want = pinned.get(name)
    print(f"{name}: {'matches build-lock.json' if got == want else f'differs from build-lock.json ({want}); record {got}'}")
EOF
  echo "installed into $prebuilt"
fi
