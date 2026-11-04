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
sh core/english/tests/run-host.sh       # S4 host test (Linux or WSL: CMake, Ninja, g++, JDK)
```

The host engine tests and the conversion corpus runner join this list with their first code;
`PLAN.md` §17 lists the intended commands.

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
as the `kotomo-apk` artifact. The repository secrets `KEYSTORE_BASE64`, `STORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD` become a temporary keystore and `key.properties`; without them (pull
requests from forks) the release APK stays unsigned. A `v*` tag also publishes the signed APK as a
GitHub Release. Dependabot proposes Gradle and Actions updates weekly.

## Testing principles

Tests protect product contracts and error-prone logic, preferably table-driven. Every fixed bug
gets a regression test, and every conversion bug a line in the conversion corpus. Each test names
the test ID (`T01`…) or gate it covers. Latency, memory and on-device AI results count only on a
real device and are reported with the device, the Android version and the build type (debug or
release with R8). Results are `PASS`, `FAIL`, `BLOCKED` or `NOT_RUN`.
