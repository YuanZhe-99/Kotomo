# Upstream contracts

What Kotomo relies on from its upstreams, and how far each point has been checked. The upstreams
follow their latest releases, so every point is re-checked when its upstream moves. The points
below were read in the upstreams' repositories, source and documentation on 2026-11-04, without
building or running anything; P0 re-checks each one and replaces "read" with the result.

## librime

| Point | Consequence | Checked |
|---|---|---|
| BSD-3-Clause; CMake and C++17; needs Boost ≥ 1.74, LevelDB, marisa, OpenCC ≥ 1.0.2 and yaml-cpp ≥ 0.5 (glog optional) | built from source as a submodule with the NDK | read |
| The embedding API `rime_api.h`: sessions, `process_key`, `set_input`, `get_context` (preedit, cursor, candidate page), `get_commit`, candidate selection and iteration, `set_option`, `register_module` | a pinyin span converts through `set_input` and `get_context`; deeper access goes through a module | read |
| Fuzzy pinyin and double pinyin are `speller/algebra` rules in the schema | changing a fuzzy-pinyin switch rewrites the schema and redeploys it, so deployment time matters | read |
| Trime builds librime with Gradle's CMake integration (NDK 30.0.16248370); fcitx5-android links static libraries from a separate prebuilder | both build routes are available | read |

## Mozc

| Point | Consequence | Checked |
|---|---|---|
| Google's code is BSD-3-Clause; the dictionary data carries its own notices (IPAdic under the NAIST and ICOT terms, a public-domain Okinawa dictionary, Google's additions) | the notices ship with the data | read |
| It builds only with Bazel (Bazelisk); the Android build (`docs/build_mozc_for_android.md`) produces 16 KB-aligned `libmozc.so` on macOS or Linux hosts only | Mozc is built on Linux, macOS or CI; Windows machines use pinned prebuilt output | read |
| The stock JNI registers on Google's class `com.google.android.apps.inputmethod.libs.mozc.session.MozcJni` and exposes only `evalCommand` (a serialized `commands::Command`), `onPostLoad` and `getDataVersion`; the Android client is not open source | Kotomo writes its own client and C++ glue | read |
| `Segment::SegmentType` has `FREE`, `FIXED_BOUNDARY`, `FIXED_VALUE`, `SUBMITTED` and `HISTORY`; `ImmutableConverter::Resegment` keeps a `FIXED_VALUE` segment's value, lid and rid, for conversion requests only | a cross-language span can be pinned inside a Japanese conversion from converter-level glue; predictions cannot carry pinned spans | read |
| Releases are tags only (`3.35.6354` at the last check) | the submodule follows the latest tag | read |

## OpenCC

| Point | Consequence | Checked |
|---|---|---|
| Apache-2.0; configurations for Simplified ↔ Traditional, Taiwan and Hong Kong with phrases (`s2twp`, `s2hk`, …) and Japanese Shinjitai (`t2jp`, `jp2t`, supported "to a limited extent") | regional conversion uses it directly; its Shinjitai tables are only one input of the lexicon pipeline | read |

## English engine

| Point | Consequence | Checked |
|---|---|---|
| AOSP LatinIME (Apache-2.0) has native C++ for binary dictionaries, key-proximity correction and suggestions; its gesture decoder is an empty stub; `main` last changed on 2025-02-26 | the default English engine, built in spike S4 | read |
| HeliBoard (GPL-3.0) maintains that native code; its dictionaries carry per-file licenses (the English experimental lists are CC BY 4.0); gesture typing needs Google's closed library | the maintained copy is S4's reference; gesture typing waits for an open decoder | read |

## Android input method platform

| Point | Consequence | Checked |
|---|---|---|
| Android 17 (API 37) is the newest stable platform; Google Play requires targeting API 36 since 2026-08-31; apps targeting API 35 or later must support 16 KB pages, and Play blocks updates that do not from 2027-02-01 | `compileSdk` and `targetSdk` 37; every native library is 16 KB aligned | read |
| `IME_FLAG_NO_PERSONALIZED_LEARNING` is a request, not a guarantee; password fields use the `TYPE_TEXT_VARIATION_*PASSWORD` variations and `TYPE_NUMBER_VARIATION_PASSWORD` | Kotomo treats both as hard rules | read |
| Inline autofill suggestions (API 30), stylus handwriting callbacks (API 33 and later) and, for apps targeting API 37, `TextAttribute.Builder.setTextSuggestionSelected()` for CJK conversions | used by the phases that need them | read |
| Android 17 limits app memory by device RAM and no longer restores keyboard visibility after an unhandled rotation | memory is measured on Android 17 | read |
| Only the default IME and the focused app read the clipboard (Android 10+); the default IME gets no access toast (Android 12+); sensitive clips carry `ClipDescription.EXTRA_IS_SENSITIVE`; the system clears the clipboard at most 60 minutes after the last access (Android 13+) | Kotomo keeps its own clipboard history and never records sensitive clips | read |
| `ComposeView` needs a `LifecycleOwner` and a `SavedStateRegistryOwner` in the view tree; there is no official guide for input methods | spike S1 decides whether panels use Compose | read |

## ML Kit

| Point | Consequence | Checked |
|---|---|---|
| GenAI inference runs only while the app is the top foreground application; a foreground service gets `BACKGROUND_USE_BLOCKED`; per-app quotas return `BUSY` and `PER_APP_BATTERY_USE_QUOTA_EXCEEDED`. Developers report the same block from an `InputMethodService` | AI runs in a Kotomo screen the person opens; spike S7 verifies it on a device | read |
| Proofreading and rewriting (beta) take English, Japanese, French, German, Italian, Spanish and Korean, not Chinese, under 256 tokens, on API 26 or later, never on an unlocked bootloader; the Prompt API (beta) takes under 4,000 input tokens | English and Japanese use these APIs; Chinese gets no proofreading or rewriting, and the Prompt API is not used | read |
| On-device translation covers Chinese, Japanese and English (about 30 MB per language, downloaded on demand); pairs without English pivot through English. It is a standard ML Kit API, not GenAI, and its documentation states no foreground restriction | translation runs in the keyboard itself (spike S7 confirms); Chinese ↔ Japanese quality is evaluated before it is offered | read |

## Data sets

| Point | Consequence | Checked |
|---|---|---|
| JMdict and KANJIDIC2 are CC BY-SA 4.0 (EDRDG); apps credit them on a separate screen reached from a menu and keep the data updated; priority tags mark common words and loanwords (`gai1`) | the English → Japanese mapping; a sources screen under About | read |
| GPLv3 is a one-way compatible license for CC BY-SA 4.0 (Creative Commons, 2015) | CC BY-SA 4.0 data ships in the GPL-3.0 app, with its attribution | read |
| Unihan (Unicode License v3) gives `kMandarin` per character; Unicode 18.0 adds `kJapaneseOldVariant` and `kJapaneseNewVariant`; a Shinjitai form can collide with an unrelated Chinese character (芸 yún against 藝 yì) | the pinyin → Japanese mapping maps Shinjitai to the old form first | read |
| CLDR 49 annotations (Unicode License v3) give emoji keywords and names in en, zh, zh-Hant and ja | the emoji keyword lexicon | read |
| Noto Emoji: font OFL-1.1, images Apache-2.0, Unicode 18.0; Twemoji (jdecked): graphics CC BY 4.0, Emoji 17.0; Apple's emoji may not be redistributed or included in third-party keyboards | emoji style packs; never Apple's images | read |
| Chinese schema data: rime-ice GPL-3.0 only, rime_wanxiang CC BY 4.0, rime-luna-pinyin and rime-essay LGPL-3.0. Japanese: SudachiDict Apache-2.0; alt-cannadic GPL-2.0 only | chosen in spikes S2 and S8; GPL-2.0-only data is never used | read |
