# Development

## Requirements

- JDK 21 or later. The documentation check and the Gradle build run on it.
- The Android SDK with the platform and build tools named in `build-lock.json` (currently
  platform 37.2 and build tools 37.0.0), found through `sdk.dir` in the ignored `local.properties`
  or `ANDROID_HOME`. Gradle itself comes from the wrapper, which checks the distribution's hash.
- From later P0 spikes: the Android NDK, and CMake and Ninja from the SDK; Bazelisk too, if spike
  S3 keeps Mozc's own Bazel build. Each is locked in `build-lock.json` by the commit that adds it.
- A real Android device for every claim about input latency, the IME lifecycle and on-device AI.
  An emulator is enough for layouts and most instrumentation tests where the host supports one;
  Google's Android Emulator does not run on Windows on ARM64 hosts.

## Commands

```bash
java tool/CheckDocs.java   # doc/en-us and doc/zh-cn mirror each other; local links resolve
./gradlew check            # unit tests and lint
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease  # R8 on; signed when key.properties exists, otherwise left unsigned
java -Xmx4g lexicon/CrossLexicon.java   # S5 lexicon into build/lexicon/; --offline uses .deps/ only
java lexicon/CheckSamples.java          # S5 sample check; non-zero exit on a failed line
java tool/BuildEnglishDict.java         # S4 dictionary; assembleDebug and assembleRelease run it too
java lexicon/EmojiData.java             # emoji asset into build/emoji/emoji.tsv; --offline; the app's emojiData task runs it
sh core/english/tests/run-host.sh       # S4 host test (Linux or WSL: CMake, Ninja, g++, JDK)
scripts/fetch-boost.sh                  # Boost headers for librime; needed before any native build
scripts/prebuild-rime.sh                # compiled Chinese data for the APK (Linux or WSL); CI requires it
scripts/fetch-mozc.sh                   # Mozc libraries and data pinned in build-lock.json (gh); CI requires them
scripts/build-mozc.sh --abi host        # S3 Mozc and glue host build and test (Linux or WSL, Bazelisk)
cmake -S core/rime -B build/host/rime -G Ninja && cmake --build build/host/rime --target kotomo_rime_test && ctest --test-dir build/host/rime -V   # S2 librime host test (Linux or WSL)
./gradlew --no-daemon check             # when Gradle finds no SDK packages: a sandboxed daemon of another agent was reused
```

Gradle runs on the Windows host (Git Bash or PowerShell), never in WSL; WSL builds only librime and
Mozc for the host. From Git Bash, call WSL with `MSYS_NO_PATHCONV=1` so paths under `/mnt/c` are
passed unchanged, and put multi-line commands in a script.

The conversion corpus runner joins this list with its first code; `PLAN.md` §17 lists the
interfaces the plan asks for that do not exist yet.

## Release signing

`assembleRelease` signs with the key that the ignored `key.properties` at the repository root
names. It holds four entries:

```properties
storeFile=/absolute/path/to/kotomo-release.jks
storePassword=…
keyAlias=kotomo
keyPassword=…
```

Use forward slashes in `storeFile`. Without the file, the release APK is left unsigned
(`app-release-unsigned.apk`); it never falls back to the debug key, so an installed release is
always signed by the release key. The keystore itself never enters the repository.

## Continuous integration

`.github/workflows/build.yml` runs on GitHub for every push to `main`, every pull request and every
`v*` tag: the documentation check, `./gradlew check`, and the debug and release builds, uploaded
as the `kotomo-arm64-v8a` and `kotomo-x86_64` artifacts. The repository secrets `KEYSTORE_BASE64`, `STORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD` become a temporary keystore and `key.properties`; without them (pull
requests from forks) the release APK stays unsigned. A `v*` tag also publishes the signed APK as a
GitHub Release. Dependabot proposes Gradle and Actions updates weekly.

The project was archived on 2026-11-04 and its GitHub repository deleted, with the CI artifacts
(including the prebuilt Mozc libraries that `scripts/fetch-mozc.sh` downloads) and the secrets.
The workflows run again once the repository is pushed to a new GitHub repository with the four
secrets; until then, build Mozc locally with `scripts/build-mozc.sh`.

## Testing principles

Tests protect product contracts and error-prone logic, preferably table-driven. Every fixed bug
gets a regression test, and every conversion bug a line in the conversion corpus. Each test names
the test ID (`T01`…) or gate it covers. Latency, memory and on-device AI results count only on a
real device and are reported with the device, the Android version and the build type (debug or
release with R8). Results are `PASS`, `FAIL`, `BLOCKED` or `NOT_RUN`.
