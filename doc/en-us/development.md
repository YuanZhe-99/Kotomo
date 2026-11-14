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
scripts/build-mozc.sh --abi host        # S3 Mozc and glue host build and test; also the line service the S6 host prototype uses
scripts/build-mozc.sh --abi arm64-v8a --abi x86_64   # Mozc for the APK into build/mozc-prebuilt, compared with build-lock.json
cmake -S core/rime -B build/host/rime -G Ninja && cmake --build build/host/rime && ctest --test-dir build/host/rime -V   # S2 librime host test
./gradlew connectedDebugAndroidTest    # instrumentation tests on a running emulator or device (debug build)
tests/conversion/run.sh                 # conversion corpus and the host fuzzy-pinyin test on the host, compared with tests/conversion/baseline.tsv; --update accepts the run
KOTOMO_PACKS=1 tests/conversion/run.sh  # also converts every downloadable dictionary from the pinned files in .deps/ (DictionaryPacksTest)
cmake -S core/mixed -B build/host/mixed -G Ninja && cmake --build build/host/mixed && ctest --test-dir build/host/mixed   # the coordinator: unit tests on fake engines, libkotomo_core.so for the host JVM, the lexicon compiler, the corpus runner
build/host/mixed/kotomo_lexicon_compile build/lexicon/pinyin-ja.tsv build/lexicon/en-ja.tsv build/lexicon/cross.klx   # the compiled cross-language lexicon the APK packs (lexicon-format.md)
build/host/mixed/kotomo_mixed_corpus . --convert "chidai ni kai ni ikou"   # one composition through the coordinator and the three host engines, with costs and pieces
./gradlew --no-daemon check             # when Gradle finds no SDK packages: a sandboxed daemon of another agent was reused
```

A build from a fresh clone runs, in order: `git submodule update --init --recursive`,
`scripts/fetch-boost.sh`, `scripts/prebuild-rime.sh`, the Android `scripts/build-mozc.sh` line,
`java -Xmx4g lexicon/CrossLexicon.java` and the two `core/mixed` lines that build and run
`kotomo_lexicon_compile`, then Gradle with `KOTOMO_REQUIRE_MOZC=1`, `KOTOMO_REQUIRE_RIME_PREBUILT=1`
and `KOTOMO_REQUIRE_LEXICON=1`, so an APK never ships without Japanese, without compiled Chinese
data or without the cross-language lexicon. `scripts/fetch-mozc.sh` downloads the same
libraries from a GitHub Actions run instead; the run it pins was deleted with the repository, and it
works again only once `build-lock.json` names a run of the new CI whose artifact has not expired.

The conversion corpus (`tests/conversion/corpus.tsv`) runs through the app's own engine facades
and candidate assembly in a host JVM: `run.sh` builds the host librime and English libraries and
the coordinator's (`libkotomo_core.so`), compiles the cross-language lexicon, then runs
`ConversionCorpusTest` with `KOTOMO_CORPUS=1`. Mixed lines are typed key by key through `Core`,
so they cross the same JNI boundary and the same function tables as on a phone. It needs the
prebuilt Chinese data, the host Mozc build, the English dictionary, the emoji asset and the
lexicon's two tables (`CrossLexicon.java`). A line that passed in the baseline
and fails now is a regression and fails the run; lines that fail in the baseline too are known
gaps. Run it before and after every change to an engine, a lexicon or ranking, and accept a new
baseline (`--update`) only after reading the report. A Chinese or Mixed line can name fuzzy-pinyin
rules after its mode (`chinese+fuzzy:z_zh,n_l`, `chinese+fuzzy:all`): the runner then deploys the
rule file the app generates on the prebuilt data and converts on that build. A Chinese line may
also name a double-pinyin scheme (`chinese+double:xiaohe`) or an output script
(`chinese+script:taiwan`, `hong_kong`, `traditional`), set as the keyboard sets them, regional
ranking included (T11), or be typed on the 9-key layout (`chinese+nine`, keypad digits). `run.sh` then runs the
same corpus through the coordinator alone (`core/mixed/host/`): `kotomo_mixed_corpus` converts
the Mixed lines and, as a baseline, the single-language lines among the candidates of their
language, writes `build/conversion/mixed-report.txt` with a gap class for every failed line and
compares the Mixed lines with `tests/conversion/baseline-mixed.tsv`. It needs the host Mozc
service besides. The two runs are reported side by side: the app's shows what the keyboard would
show (emoji and the letters as typed included), the coordinator's shows costs and pieces. Mozc's Bazel build needs a JDK with its
`include/` directory: the JetBrains runtime inside Android Studio has none, so leave `JAVA_HOME`
unset or point it at a full JDK for `build-mozc.sh`. `run.sh` also runs
`FuzzyRimeTest` (T08: the private schema, the staged swap, going back to the prebuilt data). and the dictionary tests `DictionaryRimeTest` and `DictionaryPacksTest`; with `KOTOMO_PACKS=1` the latter converts every downloadable dictionary from the files `build-lock.json` pins (they must be in `.deps/`) with the app's own converters and prints counts, time and memory. `DictionaryDownloadTest` (instrumentation) downloads from the real sources and is skipped, not passed, when they cannot be reached. `PLAN.md` §17 lists the interfaces the plan
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

`.github/workflows/build.yml` runs the build from a fresh clone above on every push to `main`, every
pull request, every `v*` tag and by hand (`workflow_dispatch`). Its jobs:

| Job | What it runs |
| --- | --- |
| `docs` | `java tool/CheckDocs.java` |
| `mozc` | `.github/workflows/mozc.yml`: `scripts/build-mozc.sh --abi host --abi arm64-v8a --abi x86_64`, the glue tests included; its output is compared with `build-lock.json` and kept as the run's `kotomo-mozc` artifact |
| `data` | `scripts/fetch-boost.sh`, `scripts/prebuild-rime.sh`, `BuildEnglishDict.java`, `EmojiData.java`, `CrossLexicon.java`, `CheckSamples.java` and `kotomo_lexicon_compile`, kept as the `kotomo-data` artifact |
| `host` | the librime host tests (`ctest`), the English host test (`run-host.sh`), then `tests/conversion/run.sh` with the coordinator's unit tests; one line that regressed against either baseline fails the job |
| `android` | `./gradlew check assembleDebug assembleRelease` with `KOTOMO_REQUIRE_MOZC=1`, `KOTOMO_REQUIRE_RIME_PREBUILT=1` and `KOTOMO_REQUIRE_LEXICON=1`; one APK artifact per ABI |
| `release` | on a `v*` tag: the signed release APKs on a GitHub release |

Mozc comes only from the same run: Bazel takes an hour or more, so `mozc.yml` caches its output
under the Mozc submodule commit and the hashes of `core/mozc/`, `patches/mozc/`,
`scripts/build-mozc.sh` and the workflow, and a run with the same key builds nothing. The first run
on a new repository builds in full; no artifact has to exist beforehand. A difference from the
hashes `build-lock.json` records (those of the development host's build) is a warning, not a
failure, since another distribution may not build byte for byte the same. The release APK is signed
when the secrets `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` exist and is
left unsigned otherwise, with the workflow still passing; only the `release` job requires them. The
pinned downloads in `.deps/` are cached under the pins themselves and saved even when the `data` job
fails, because CC-CEDICT is published only as the current file: once MDBG replaces it, a fresh
download no longer matches its pin, the job fails with "Pinned data source changed", and the pin
has to be moved on purpose (with `CheckSamples` and the whole corpus run again).

The workflows run on the public GitHub repository (the `github` remote), recreated on 2026-11-14
after the first one was deleted with its runs, artifacts and secrets when the project was archived
on 2026-11-04. `main` is pushed to both remotes. A run that misses the Mozc cache takes about
45 minutes (Mozc about 22); with the cache, 15 to 22 (`android` 13 to 19, `host` 7 to 8, `data`
2 to 4). `platform-notes.md` records what the runs verified. The full build still works on
the development host alone.

## Testing principles

Tests protect product contracts and error-prone logic, preferably table-driven. Every fixed bug
gets a regression test, and every conversion bug a line in the conversion corpus. Each test names
the test ID (`T01`…) or gate it covers. Latency, memory and on-device AI results count only on a
real device and are reported with the device, the Android version and the build type (debug or
release with R8). Results are `PASS`, `FAIL`, `BLOCKED` or `NOT_RUN`.
