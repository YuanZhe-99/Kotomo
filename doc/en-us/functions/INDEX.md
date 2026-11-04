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
| `app/src/main/java/com/yuanzhe/kotomo/ime/KeyboardView.kt` | Canvas keyboard: drawing, taps, long presses, backspace repeat | `onTouchEvent`, `onDraw` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/CandidateBar.kt` | Candidate row: drawing and picks | `show`, `onTouchEvent` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/EmojiPanel.kt` | Compose emoji panel with sample emoji (S1's Compose check) | `EmojiPanel` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Probe.kt` | S1 measurements (timings and memory only) and their stored summary | `Probe`, `LatencyStats` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/MainActivity.kt` | Settings app: keyboard setup, try-it fields, diagnostics | `SettingsScreen` |
| `lexicon/CrossLexicon.java` | Cross-language lexicon pipeline (S5): fetches and verifies the data sources, builds pinyin → Japanese and English → Japanese tables into `build/lexicon/` | `main`, `SOURCES` |
| `lexicon/CheckSamples.java` | Checks the generated tables against `tests/lexicon/samples.tsv` (positive and negative lines) | `main` |
