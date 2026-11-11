# Development

## Requirements

- An x86_64 Linux host (the maintainer's is Debian 13): Mozc's Android build needs x86_64 Linux or
  macOS, since the NDK ships no aarch64 Linux host tools. Everything else builds there too.
- JDK 21 or later. The documentation check and the Gradle build run on it; Mozc's Bazel build needs
  `JAVA_HOME` (derived from `java` on `PATH` when unset).
- The Android SDK with the platform and build tools named in `build-lock.json` (currently
  platform 37.2 and build tools 37.0.0), the NDK and CMake it names, found through `ANDROID_HOME`
  (or `sdk.dir` in the ignored `local.properties`). Gradle itself comes from the wrapper, which
  checks the distribution's hash.
- CMake, Ninja, a C++ compiler, Python 3, `rsync` and `curl` for the host builds. Mozc's build
  downloads its pinned Bazelisk and NDK r29 itself and installs nothing system-wide.
- The Android Emulator with an API 37 x86_64 image with 16 KB pages for layouts, logic and
  instrumentation tests. A real device for every claim about input latency, memory, the IME
  lifecycle and on-device AI.

## Commands

```bash
java tool/CheckDocs.java   # doc/en-us and doc/zh-cn mirror each other; local links resolve
./gradlew check            # unit tests and lint
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-<abi>-debug.apk
./gradlew assembleRelease  # R8 on; signed when key.properties exists, otherwise left unsigned
java -Xmx4g lexicon/CrossLexicon.java   # S5 lexicon into build/lexicon/; --offline uses .deps/ only
java lexicon/CheckSamples.java          # S5 sample check; non-zero exit on a failed line
java tool/BuildEnglishDict.java         # S4 dictionary; assembleDebug and assembleRelease run it too
java lexicon/EmojiData.java             # emoji asset into build/emoji/emoji.tsv; --offline; the app's emojiData task runs it
sh core/english/tests/run-host.sh       # S4 host test; needs the dictionary from BuildEnglishDict
scripts/fetch-boost.sh                  # Boost headers for librime; needed before any native build
scripts/prebuild-rime.sh                # compiled Chinese data for the APK
scripts/build-mozc.sh --abi host        # S3 Mozc and glue host build and test
scripts/build-mozc.sh --abi arm64-v8a --abi x86_64   # Mozc for the APK into build/mozc-prebuilt, compared with build-lock.json
cmake -S core/rime -B build/host/rime -G Ninja && cmake --build build/host/rime && ctest --test-dir build/host/rime -V   # S2 librime host test
./gradlew connectedDebugAndroidTest    # instrumentation tests on a running emulator or device (debug build)
tests/conversion/run.sh                 # conversion corpus and the host fuzzy-pinyin test on the host, compared with tests/conversion/baseline.tsv; --update accepts the run
./gradlew --no-daemon check             # when Gradle finds no SDK packages: a sandboxed daemon of another agent was reused
```

A build from a fresh clone runs, in order: `git submodule update --init --recursive`,
`scripts/fetch-boost.sh`, `scripts/prebuild-rime.sh`, the Android `scripts/build-mozc.sh` line,
then Gradle with `KOTOMO_REQUIRE_MOZC=1` and `KOTOMO_REQUIRE_RIME_PREBUILT=1`, so an APK never ships
without Japanese or without compiled Chinese data. `scripts/fetch-mozc.sh` downloads the same
libraries from a GitHub Actions run instead; the run it pins was deleted with the repository and it
works again only once CI exists.

The conversion corpus (`tests/conversion/corpus.tsv`) runs through the app's own engine facades
and candidate assembly in a host JVM: `run.sh` builds the host librime and English libraries,
then runs `ConversionCorpusTest` with `KOTOMO_CORPUS=1`. It needs the prebuilt Chinese data, the
host Mozc build, the English dictionary and the emoji asset. A line that passed in the baseline
and fails now is a regression and fails the run; lines that fail in the baseline too are known
gaps. Run it before and after every change to an engine, a lexicon or ranking, and accept a new
baseline (`--update`) only after reading the report. A Chinese or Mixed line can name fuzzy-pinyin
rules after its mode (`chinese+fuzzy:z_zh,n_l`, `chinese+fuzzy:all`): the runner then deploys the
rule file the app generates on the prebuilt data and converts on that build. `run.sh` also runs
`FuzzyRimeTest` (T08: the private schema, the staged swap, going back to the prebuilt data). `PLAN.md` §17 lists the interfaces the plan
asks for that do not exist yet.

## Emulator

```bash
emulator -avd <avd> -no-snapshot &                       # an API 37 x86_64 16 KB-page image
adb install -r app/build/outputs/apk/release/app-x86_64-release.apk
adb shell ime enable com.yuanzhe.kotomo/.ime.KotomoService
adb shell ime set com.yuanzhe.kotomo/.ime.KotomoService
adb shell settings put secure show_ime_with_hard_keyboard 1
```

The emulator reports a hardware keyboard, so Android hides every on-screen keyboard until
`show_ime_with_hard_keyboard` is set. Emulator runs cover layouts and logic only; they are never
evidence for latency, memory or on-device AI.

## Release signing

`assembleRelease` signs with the key that the ignored `key.properties` at the repository root
names. It holds four entries:

```properties
storeFile=/absolute/path/to/kotomo-release.p12
storePassword=…
keyAlias=…
keyPassword=…
```

Use forward slashes in `storeFile`. Without the file, the release APK is left unsigned
(`app-release-unsigned.apk`); it never falls back to the debug key, so an installed release is
always signed by the release key. The keystore itself never enters the repository.

## Continuous integration

`.github/workflows/build.yml` and `.github/workflows/mozc.yml` ran on GitHub until the project was
archived on 2026-11-04 and its GitHub repository deleted, with the CI artifacts and the secrets.
They stay for a restart and do nothing until the repository is pushed to a new GitHub repository
with the secrets `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Until then
every build and test runs on the development host.

## Testing principles

Tests protect product contracts and error-prone logic, preferably table-driven. Every fixed bug
gets a regression test, and every conversion bug a line in the conversion corpus. Each test names
the test ID (`T01`…) or gate it covers. Latency, memory and on-device AI results count only on a
real device and are reported with the device, the Android version and the build type (debug or
release with R8). Results are `PASS`, `FAIL`, `BLOCKED` or `NOT_RUN`.
