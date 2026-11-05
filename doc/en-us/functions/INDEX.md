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
| `app/src/main/java/com/yuanzhe/kotomo/ime/KotomoService.kt` | Input method service: modes and engines on the engine thread, direct typing in sensitive fields, the settings in force (SettingsStore), Compose panel host | `onKey`, `isDirect`, `refresh` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Layout.kt` | Keyboard pages (letters with field-adapted bottom rows, symbols, phone and number pads, a bottom row split around a hinge), key placement and hit testing (pure Kotlin, unit-tested) | `Layout.letters`, `Layout.pad`, `Layout.place`, `Layout.hit` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Fields.kt` | Field adaptation and spacing rules (PLAN §8.7, §8.1): field kinds from the input type and hint, Enter styles, double-space full stop, smart space (pure Kotlin, unit-tested) | `Fields.of`, `Fields.enter`, `Fields.period` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Posture.kt` | Window size classes and fold posture for the layout (PLAN §8.10): when to split, the height limit in tabletop posture and short windows (unit-tested) | `Posture.split`, `Posture.heightLimit` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/KeyboardView.kt` | Canvas keyboard as in the design (phone, and split halves of fixed width on wide screens), taps, Gboard-style long-press popups (slide to choose, release to type), the key preview, dragging on space to move the cursor and left from backspace to delete words, backspace repeat, TalkBack nodes per key; `PopupLayer` draws the popup | `onTouchEvent`, `onDraw`, `PopupLayer` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/CandidateBar.kt` | The row above the keys: candidate pills that scroll, the expand arrow, the toolbar when idle, status hints, a candidate's details after a long press (reading, source, Forget for learned words) | `show`, `hint`, `details`, `onTouchEvent` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/EmojiPanel.kt` | Compose panels in the keyboard's place: the emoji panel (every emoji of the system font by group, Recents in app-private preferences only when the field allows learning, skin-tone picker on long press) and every candidate | `EmojiPanel`, `Recents`, `TonePicker`, `CandidatesPanel` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/EmojiIndex.kt` | The emoji asset in memory, loaded once off the main thread: groups, skin-tone variants, key lookup in English, Chinese, pinyin, Japanese, kana and romaji (pure Kotlin parse, unit-tested) | `parse`, `lookup`, `load` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/clipboard/ClipboardHistory.kt` | Clipboard history ([clipboard.md](../clipboard.md)): recording only where allowed (no sensitive fields, sensitive clips or incognito), dedupe, pins and quick phrases, retention and cap, the AES-GCM file with a Keystore key, an in-memory snapshot; clip classification | `onPrimaryClip`, `allowedIn`, `items`, `ClipFile`, `ClipKind.of` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/clipboard/VerificationCodes.kt` | Verification-code extraction from copied text: ranked codes with context, negatives for dates, prices, phone and order numbers (pure Kotlin, unit-tested) | `find`, `bare` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/clipboard/ClipboardPanel.kt`, `QuickPaste.kt` | The clipboard panel in the keyboard's place (tabs, two-column cards, footer, long-press actions) and the quick-paste strip above the keys with its offer rules (60 s, once per clip) | `ClipboardPanel`, `QuickPaste`, `QuickPasteStrip` |
| `lexicon/EmojiData.java` | Emoji data pipeline: fetches and verifies Unicode Emoji and CLDR data, builds `emoji.tsv` (groups, tones, keys with pinyin from CC-CEDICT and Unihan, kana from Mozc and Hepburn romaji); run by the app's `emojiData` task | `main`, `SOURCES`, `index` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Probe.kt` | S1 measurements (timings and memory only) and their stored summary | `Probe`, `LatencyStats` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/MainActivity.kt` | Settings app shell: the home with the setup card and try-it fields, page navigation (one pane with a back stack; from 840 dp the navigation list beside the page), the Material 3 theme from Kotomo's tokens | `SettingsApp`, `Page`, `SetupCard` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/Pages.kt` | Settings pages (input modes, Mixed mode, Chinese, Japanese, English, layout and size, keys and gestures, privacy with the clipboard settings, the pages that arrive later) and the diagnostics page; the shared rows and controls | `MixedPage`, `LayoutPage`, `DiagnosticsPage`, `Section` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/About.kt` | About, open-source licenses, data sources and design acknowledgements; its credit lists mirror [third-party.md](../third-party.md) | `CODE`, `DATA`, `AboutPage` |
| `design/` | Design drafts from the design agent: one HTML artboard per screen (phone, fold, tablet, settings) and the shared key-area component, frozen from the design canvas | `README.md`, `Keyboard.dc.html`, `canvas.json` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/Settings.kt`, `SettingsStore.kt` | The settings model, its versioned Preferences DataStore file ([settings-format.md](../settings-format.md)) and the process-wide store with a snapshot readable without I/O | `Settings`, `SettingsFormat`, `SettingsStore.current` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/Themes.kt`, `app/src/main/java/com/yuanzhe/kotomo/dictionary/Json.kt` | The keyboard theme as a versioned JSON file (`files/themes/current.json`, [settings-format.md](../settings-format.md)) and the pure-Kotlin JSON reader and writer it uses (unit-tested) | `ThemeFormat`, `ThemeFile`, `Json.parse` |
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
| `scripts/fetch-mozc.sh` | Fetches the Mozc libraries and data built by mozc.yml, pinned by run and SHA-256 in build-lock.json | — |
| `app/src/main/java/com/yuanzhe/kotomo/ime/QuickPanel.kt` | The toolbar's quick settings in the keyboard's place: one-handed side, split layout, Simplified/Traditional, incognito, settings app | `QuickPanel` |
| `app/src/main/java/com/yuanzhe/kotomo/ai/GenAi.kt` | Gemini Nano proofreading and rewriting through ML Kit GenAI ([ai.md](../ai.md)): status, user-started download, splitting long text, error mapping | `GenAi`, `AiAction`, `AiFailure` |
| `app/src/main/java/com/yuanzhe/kotomo/ai/AiActivity.kt`, `AiHandoff.kt` | The writing-help screen (language, action, suggestions, Replace, Copy) and the in-memory hand-off between it and the keyboard | `AiActivity`, `AiHandoff`, `AiRequest` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/KeyboardColors.kt` | The keyboard's palette for the chosen theme (system, light, dark, Material You) for views and Compose panels | `apply`, `color`, `kbColor` |
