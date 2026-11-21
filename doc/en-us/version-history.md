# Version history

## 0.1.3 (2026-11-20)

- Live segment strip and grouped segment panel: Mixed and Japanese modes display a live segment strip (`SegmentStrip`) above the candidate bar; tapping a segment opens the segment panel with source-marked chips, two-column language/reading-grouped candidate cards, and a three-column kana & original row (`CandidateDetailsBar` and strip buttons fit at 1.5x font scale).
- Chinese/Japanese kanji script switching (Mixed engine contract version 10, command `14` lock text, candidate flag bit `5` `sharedZhJa`): shared Chinese/Japanese kanji carry a `中日` mark; the two-row `CandidateDetailsBar` switches candidates between Chinese and Japanese kanji forms via bundled OpenCC tables alongside Forget and Forms without overlapping.
- Symbol-page punctuation and autofill popup fix: Mixed, Chinese and Japanese letter pages keep full-width comma and period while both symbol pages' bottom row types half-width `,` and `.` (with full-width first on long press); top-row long-press popups and key previews are no longer covered by pinned inline autofill chips, and `KeyboardView` pointer tracking follows `pointerId`.
- Upstreams: Android Gradle Plugin bumped from 9.5.1 to 9.5.2 (PR #1); other upstreams same as the 0.1.2 baseline (`0.1.1` check on 2026-11-19).
- Tagged on 2026-11-20 (`versionCode` 4). Verified on `1a22596` (before merging PR #1) on the development host: `ctest core/mixed` (11/11), conversion corpus (`312/313`, 0 regressed, Mixed lines `138/138` through the coordinator), `./gradlew check assembleDebug assembleRelease assembleDebugAndroidTest`, and on the emulator (Android 17, API 37, debug): `InlineAutofillTest`, `FieldTypingTest`, `MixedTypingTest` and `CandidateBarTest` (73/73). Not re-run after merging PR #1 (AGP 9.5.2) per user instruction; not verified on a physical device in this release cycle.

## 0.1.2 (2026-11-19)

- Ordinary text fields no longer switch to code mode: multi-line, no-suggestion and code-hinted fields keep the current input mode and full-width symbols; English with half-width symbols applies only where the person turned on code mode for the app, and URL fields follow the current mode while `modes.url_english` is off.
- Long-press: the popup opens anchored over the pressed key, so the corner hint (`o` → `9`, `i` → `8`) is selected without sliding even on edge keys; on the letter page's bottom row and both symbol pages, long-pressing a full-width symbol selects its half-width form first.
- Modern Chinese vocabulary: rime-ice 2026.06.30 (`8105`, `base`, `ext`) is merged into the bundled pinyin dictionary at build time; the user dictionary and dictionary packs also apply in Mixed mode; the downloadable `zh-ice-base` and `zh-ice-ext` packs are compiled in the background.
- Clipboard images: copied PNG, JPEG, WebP and GIF images are recorded under the same rules as text (never in sensitive fields, incognito mode or excluded apps), stored encrypted on the device, shown as thumbnails in the clipboard panel, the clipboard settings page and the quick-paste strip, and pasted into fields that accept images.
- Mixed-mode refinement stops reading prefix candidates from librime and stale refinements are cancelled (`zhongguo` 2,475 ms → 65 ms on the host); the keyboard stays bottom-anchored when the quick-paste strip collapses.
- Upstreams: same as the 2026-11-19 check of 0.1.1, unchanged; rime-ice 2026.06.30 newly bundled.
- Released on 2026-11-19 (`versionCode` 3). Verified on the development host: `java tool/CheckDocs.java`, `java lexicon/CheckSamples.java` (40/40), host engine tests (English, rime `2/2`, Mixed `11/11`), the whole conversion corpus (`312/313`, 0 regressed, Mixed lines `138/138` through the coordinator), `./gradlew check assembleDebug assembleRelease assembleDebugAndroidTest`, and on the emulator (Android 17, x86_64, debug): `FieldTypingTest` 31/31, `MixedTypingTest` 31/31, `ClipboardAppTest` 4/4, `DictionaryKeyboardTest` 3/3, `FuzzySettingsTest` 4/4, plus light/dark, four-language and font-scale 1.5 screenshots. Not verified on a physical device in this release cycle.

## 0.1.1 (2026-11-19)

- Mixed mode and English → Japanese matching: JMdict glosses with parenthesized qualifiers whose remaining word is a single token now index into `cross.klx` (`power` → `パワー`), dependent POS senses (`suf`, `pref`, `ctr`, `aux*`, `prt`, `conj`, `int`) are down-weighted below nouns and loanwords (`system` → `システム`), English inflections (`-s`, `-es`, `-ed`, `-ing`) query their stems, unknown echoed kana and trailing Latin characters carry penalties so loanwords outrank kana echoes, and typed uppercase is preserved on English proposals in Mixed mode (S5 samples 40/40, counter-examples 0/8).
- Mixed-mode Pinyin prefix matching and partial commit (engine contract version 9): multi-syllable pinyin inputs offer syllable-aligned phrase prefixes (2–3 syllables) and first-syllable single-character candidates below the top whole-input sentences without cutting inside a syllable; picking a prefix candidate commits the prefix and keeps the remaining composition active (keeping expanded panels open and refreshing for the remainder).
- Expanded candidate panels: long-pressing a candidate in `CandidatesPanel` or `SegmentPanel` displays its reading, dictionary source, and Mozc part of speech / base form / inflections in the candidate bar above the panel.
- Mixed-mode candidate language badges: candidates in `CandidateBar`, `CandidatesPanel`, and `SegmentPanel` display a small `中` / `EN` / `日` badge controlled by the `mixed.language_tags` setting (default `true`, configurable under Settings → Mixed mode).
- Upstreams checked on 2026-11-19, unchanged: librime 1.17.0, Mozc 3.35.6354, OpenCC 1.4.2, HeliBoard 4.2, Gradle 9.9.1, Android Gradle Plugin 9.5.1, Kotlin 2.4.21, Compose BOM 2026.09.00, ML Kit GenAI 1.0.0-beta1, ML Kit translate 17.0.3, NDK r30, platform 37.2, build tools 37.0.0, CC-CEDICT 2026-11-14T02:25:17Z, jmdict-simplified 3.6.2+20261109214828, Unicode 18.0 and cldr-json 49.0.0.
- Released on 2026-11-19 (`versionCode` 2). Verified on the development host: `java tool/CheckDocs.java`, `java lexicon/CheckSamples.java` (40/40), host C++ Mixed tests (`11/11`), the whole conversion corpus (`301/302`, 0 regressed, Mixed lines `133/133` through the coordinator), `./gradlew check assembleDebug assembleRelease`, and on the emulator (Android 17, x86_64, 16 KB pages, debug): `CandidateBarTest` 3/3, `MixedTypingTest` 31/31, `FieldTypingTest` 29/29, plus light/dark, four-language and font-scale 2.0 screenshots. Not verified on a physical device in this release cycle.

## 0.1.0 (2026-11-14)

- Repository scaffold: working conventions (`AGENTS.md`), the plan (`PLAN.md`, from the user's
  draft 2.0), mirrored documentation and the documentation check (`tool/CheckDocs.java`).
- Upstreams read on 2026-11-04, nothing built yet: librime 1.17.0, Mozc 3.35.6354, OpenCC 1.4.2;
  Android API 37, Android Gradle Plugin 9.5.0, Kotlin 2.4.21, NDK r30.
- Emoji data (P1), checked 2026-11-04: Unicode Emoji 18.0 and CLDR 49.0.0 annotations, the latest
  releases; pinyin and kana readings from the Unihan, CC-CEDICT and Mozc pins above.
- Upstreams checked 2026-11-10 when development resumed on an x86_64 Linux host: Gradle 9.8.2 →
  9.9.1 and Android Gradle Plugin 9.5.0 → 9.5.1; CC-CEDICT moved to the 2026-11-10T08:36:22Z file
  and jmdict-simplified to 3.6.2+20261109214828 (JMdict 2026-11-09). Checked, unchanged: librime
  1.17.0, Mozc 3.35.6354, OpenCC 1.4.2, HeliBoard 4.2, Kotlin 2.4.21, Compose BOM 2026.09.00, the
  AndroidX libraries, ML Kit GenAI 1.0.0-beta1, NDK r30, Unicode 18.0, CLDR 49.0.0, the rime data
  repositories and Bazelisk 1.30.0.
- Dictionary-pack sources pinned 2026-11-11 at their latest releases (`build-lock.json`; nothing
  generated or shipped yet): rime-ice 2026.06.30, rime-frost 1.0.4, fcitx5-pinyin-zhwiki 0.3.1
  (dump 20261018), THUOCL `a30ce79d`, SudachiDict 20261015, mecab-ipadic-neologd seed 20200910,
  mozcdic-ut 2026-11-03, SCOWLv2 rel-2026.02.25.
- Upstreams checked 2026-11-11: CC-CEDICT moved to the 2026-11-11T02:50:39Z file (S5 samples 34/34,
  counter-examples 0/8). Checked, unchanged: librime 1.17.0, Mozc 3.35.6354, OpenCC 1.4.2, HeliBoard
  4.2, Gradle 9.9.1, Android Gradle Plugin 9.5.1, Kotlin 2.4.21, Compose BOM 2026.09.00, the AndroidX
  libraries, ML Kit GenAI 1.0.0-beta1, NDK r30, platform 37.2, build tools 37.0.0, jmdict-simplified
  3.6.2+20261109214828, Unicode 18.0, CLDR 49.0.0, the rime data repositories and Bazelisk 1.30.0.
- Dictionary sources checked again 2026-11-11 for Q6, unchanged: rime-ice 2026.06.30, rime-frost
  1.0.4, fcitx5-pinyin-zhwiki 0.3.1, THUOCL `a30ce79d`, SudachiDict 20261015, mozcdic-ut 2026-11-03,
  SCOWLv2 rel-2026.02.25. From now on the app downloads the offered ones (THUOCL, rime-ice
  `en_dicts.zip` and `8105`, the zhwiki web slang) from their sources at their current version;
  the pins only fix what the host tests convert.
- Upstreams checked again on 2026-11-11 for the coordinator's device integration (Q7), unchanged:
  librime 1.17.0, Mozc 3.35.6354, OpenCC 1.4.2 and HeliBoard 4.2 by their tags (HeliBoard's only
  newer tag is the pre-release 4.3-beta1); the toolchain and data sources as in the line above.
  Kotomo's Mozc glue gained the coordinator's function table, so both Android libraries were
  rebuilt and their hashes recorded in `build-lock.json`; `mozc.data` is unchanged.
- Source audit (S8) on 2026-11-12, `third-party.md`: latest releases read from the feeds: SudachiDict v20261015, Noto Emoji v2026-09-24-unicode18_0, Twemoji v17.0.3, OpenMoji 17.0.0, jmdict-simplified 3.6.2+20261109214828, cldr-json 49.0.0 (all unchanged or newly recorded); THUOCL unchanged.
- Upstreams checked on 2026-11-13 for translation (Q9): ML Kit `translate` 17.0.3 added at its latest release; ML Kit GenAI proofreading and rewriting 1.0.0-beta1, librime 1.17.0, Mozc 3.35.6354, OpenCC 1.4.2, HeliBoard 4.2 (newer only 4.3-beta1), AGP 9.5.1 and Kotlin 2.4.21 (2.5.0 is a release candidate) unchanged.
- Upstreams checked on 2026-11-14: CC-CEDICT moved to the 2026-11-14T02:25:17Z file, because MDBG replaced the current file and the earlier pin can no longer be downloaded (S5 samples 34/34, counter-examples 0/8; three rows of the pinyin → Japanese table changed, `emoji.tsv` is unchanged; the conversion corpus has no regression). Checked, unchanged: librime 1.17.0, Mozc 3.35.6354, OpenCC 1.4.2, HeliBoard 4.2 (newer only 4.3-beta1), Gradle 9.9.1, Android Gradle Plugin 9.5.1, Kotlin 2.4.21 (2.5.0 is a release candidate), Compose BOM 2026.09.00, jmdict-simplified 3.6.2+20261109214828 and cldr-json 49.0.0.
- Released on 2026-11-14 (`versionCode` 1), the first release. The upstreams are those of the
  2026-11-14 check above. Verified on the development host: the documentation check, the Gradle
  checks, the host engine tests, the whole conversion corpus with no regression (Mixed lines 115
  of 115 through the coordinator) and the instrumentation suite on the emulator (Android 17,
  x86_64, 16 KB pages; 107 tests on `e3871fe`: 105 pass, 1 skipped, and 1 failed once and passed
  three times alone, cause not confirmed);
  the signed `arm64-v8a` release build (R8 on) was installed and used on the user's phone over four
  rounds of `tests/device/checklist.md`, the last on the build of `4e60561`; the release differs
  from that build only in documentation and the CI workflow. Not verified: 115 checklist lines are
  not recorded, the last round's retest lines among them; gates G0–G5 are not judged; latency and
  memory were never measured on a device; on-device AI (Gemini Nano) was never run; the five
  `MixedTypingTest` failures of the CI emulator run (`platform-notes.md`) are not explained.
