# Function index

One row per module and tool: its role and the declarations to read first. Declarations carry the
Function Explanation Layer in their doc comments (first sentence: purpose; then inputs, returns,
side effects and notes where non-trivial).

| Module / file | Role | Start with |
|---|---|---|
| `tool/CheckDocs.java` | Documentation mirror and link check | `main`, `read` |
| `app/build.gradle.kts` | Android app build: SDK levels, version, R8, release signing from `key.properties` | `signingConfigs`, `buildTypes` |
| `build-lock.json` | Toolchain, upstream and data-source versions with download hashes | `toolchain` |
| `.github/workflows/build.yml` | CI: documentation check, Gradle check and build, signed release APK; GitHub Release on `v*` tags | `android`, `release` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/KotomoService.kt` | Input method service (S1): composition, placeholder candidates, direct typing in sensitive fields, Compose panel host | `onKey`, `isDirect`, `refresh` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Layout.kt` | Keyboard pages, key placement and hit testing (pure Kotlin, unit-tested) | `Layout.letters`, `Layout.place`, `Layout.hit` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/KeyboardView.kt` | Canvas keyboard as in the design (phone, and split halves of fixed width on wide screens), taps, Gboard-style long-press popups (slide to choose, release to type), backspace repeat; `PopupLayer` draws the popup | `onTouchEvent`, `onDraw`, `PopupLayer` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/CandidateBar.kt` | The row above the keys: candidate pills that scroll, the expand arrow, the toolbar when idle, status hints | `show`, `hint`, `onTouchEvent` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/EmojiPanel.kt` | Compose panels in the keyboard's place: sample emoji (S1's Compose check) and every candidate | `EmojiPanel`, `CandidatesPanel` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Probe.kt` | S1 measurements (timings and memory only) and their stored summary | `Probe`, `LatencyStats` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/MainActivity.kt` | Settings app shell: the home with the setup card and try-it fields, page navigation (one pane with a back stack; from 840 dp the navigation list beside the page), the Material 3 theme from Kotomo's tokens | `SettingsApp`, `Page`, `SetupCard` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/Pages.kt` | Settings pages (input modes, Mixed mode, Chinese, Japanese, English, layout and size, keys and gestures, privacy, the pages that arrive later) and the diagnostics page; the shared rows and controls | `MixedPage`, `LayoutPage`, `DiagnosticsPage`, `Section` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/About.kt` | About, open-source licenses, data sources and design acknowledgements; its credit lists mirror [third-party.md](../third-party.md) | `CODE`, `DATA`, `AboutPage` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/Settings.kt`, `SettingsStore.kt` | The settings model, its versioned Preferences DataStore file ([settings-format.md](../settings-format.md)) and the process-wide store with a snapshot readable without I/O | `Settings`, `SettingsFormat`, `SettingsStore.current` |
| `lexicon/CrossLexicon.java` | Cross-language lexicon pipeline (S5): fetches and verifies the data sources, builds pinyin → Japanese and English → Japanese tables into `build/lexicon/` | `main`, `SOURCES` |
| `lexicon/CheckSamples.java` | Checks the generated tables against `tests/lexicon/samples.tsv` (positive and negative lines) | `main` |
| `core/CMakeLists.txt` | Top-level native build for the app; one subdirectory per engine library | — |
| `core/english/` | English engine (S4): CMake build of the LatinIME core and Kotomo's JNI bridge; host test | `jni/english_jni.cpp`, `tests/run-host.sh` |
| `app/src/main/java/com/yuanzhe/kotomo/engine/English.kt` | Kotlin facade of `libkotomo_english.so` (engine thread only) | `English.open`, `suggestions` |
| `tool/BuildEnglishDict.java` | Builds the English dictionary from the pinned word list with AOSP's dicttool | `main` |
| `core/rime/` | Chinese engine (S2): CMake build of librime and its dependencies into `libkotomo_rime.so`, the bridge, the data staging and the host test | `jni/rime_bridge.h`, `stage-data.cmake`, `tests/rime_bridge_test.cc` |
| `app/src/main/java/com/yuanzhe/kotomo/engine/Rime.kt` | Kotlin facade of `libkotomo_rime.so` (engine thread only) | `initialize`, `deploy`, `processKey`, `setLearning` |
| `scripts/fetch-boost.sh` | Downloads and verifies the Boost headers librime needs | — |
| `core/mozc/` | Japanese engine glue (S3): session conversion and pinned-span conversion over Mozc, JNI, host test; built inside Mozc's Bazel workspace | `kotomo_mozc.h`, `tests/kotomo_mozc_test.cc` |
| `app/src/main/java/com/yuanzhe/kotomo/engine/Mozc.kt` | Kotlin facade of `libkotomo_mozc.so` (not used by the app until the Android library exists) | `sessionConvert`, `pinnedConvert` |
| `scripts/build-mozc.sh` | Builds Mozc and the glue for the host or for Android ABIs (Android needs x86_64 Linux or macOS) | `--abi` |
| `.github/workflows/mozc.yml` | CI: host tests and the Android Mozc libraries as a hash-listed artifact | `mozc` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/RimeDeploy.kt` | librime deploy in its own process into a staging directory, the file layout under `filesDir/rime`, the swap and the deploy report | `RimeDeployService`, `RimeFiles.promote` |
| `core/rime/tools/prebuild.cc`, `scripts/prebuild-rime.sh` | Compile librime's data on a Linux host into the shared data the APK ships | `main` |
