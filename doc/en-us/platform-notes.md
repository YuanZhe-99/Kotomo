# Platform notes

| Platform | Status |
|---|---|
| Android phones (`arm64-v8a`) | Run on a Galaxy Z Fold (Android 17, release build): S1 and S2 pass there. P1's device checklist is not recorded line by line yet; only what appears on the screen could be checked. |
| Android emulator (`x86_64`) | Android 17 (API 37.2) Google Play image with 16 KB pages on the development host: the release APK installs, Kotomo is selected as the keyboard and Japanese mode converts through Mozc (2026-11-10). For layouts and logic only; never evidence for latency, memory or on-device AI. |
| Android tablets and foldables | P1 code in place (not yet run on a device): window size classes, a centred maximum width, keys below the hinge when half-folded, split halves and two space bars beside a vertical hinge (unit-tested). |
| Desktop hosts (Linux, macOS) | Build and test the engines and the app; the development host is x86_64 Linux (Debian 13). Never a product target. |

## Repository

Verified on the development host, x86_64 Linux (Debian 13, JDK 21.0.13), from a fresh clone on 2026-11-10:

| Check | Result |
|---|---|
| Documentation mirror and links | `java tool/CheckDocs.java` passes |
| Unit tests (layout and hit testing, latency percentiles, fields, posture, emoji index; settings format and theme file; clipboard: recording rules, the encrypted file, expiry, refused clips, verification codes, quick paste; the AI hand-off) | `./gradlew check` on 2026-11-10 (debug): 87 tests, 87 pass, none skipped |
| Lint | `./gradlew check` on 2026-11-10: no errors |
| Conversion corpus (T01–T07) | `tests/conversion/run.sh` on 2026-11-10 (x86_64 Linux, host engines): 47 of 68 lines pass. T01 Chinese 19/19, T02 English 10/10, T03 Japanese 14/15 (T03-001 私羽日本人, the particle は typed wa); T04–T07 Mixed 4/24, because Mixed mode is Chinese mode until the coordinator exists. The run is the baseline in `tests/conversion/baseline.tsv`. |
| Instrumentation tests (T12, T13, PLAN §2.3, §8.6, §8.8, §8.10) | `./gradlew connectedDebugAndroidTest` on 2026-11-11 (emulator, Android 17, x86_64, 16 KB pages, debug): `FieldTypingTest` 10 of 10. Typing `nihao` and space on Kotomo's keys gives 你好 in an ordinary field and in a field with `IME_FLAG_NO_PERSONALIZED_LEARNING`; a password field receives `nihao` as typed; in Japanese mode (fixed for the app) `nihon` and space gives 日本, and Enter keeps にほん; `nihon` typed in Chinese and moved to Japanese by a mode switch commits にほん with Enter, and `nihao` typed in Japanese and moved to Chinese gives 你好 with space; the emoji panel's search types `cat` into its own query (the field stays empty) and Enter sends the first result; after clearing the learning data while the keyboard runs, `nihao` still gives 你好; one-handed on the right at the width kept for the emulator's screen and orientation moves q past a quarter of the screen, and full width brings it back to the edge |
| Release build | `./gradlew assembleRelease` on 2026-11-10 with `KOTOMO_REQUIRE_MOZC=1` and `KOTOMO_REQUIRE_RIME_PREBUILT=1`: R8 on, signed with the release key (APK Signature Scheme v2, verified with `apksigner`); arm64-v8a 27.4 MB, x86_64 27.5 MB, each with librime, Mozc (2.9 MB library, 19 MB data) and the English engine |

## Spikes

| Spike | Status | Evidence |
|---|---|---|
| S1 IME skeleton | PASS on a device (Galaxy Z Fold, SM-F971U1, Android 17, release build, 2026-11-04) | Press → key drawn p50 9–10 ms, p95 19–21 ms; press → candidates drawn p50 15–48 ms, p95 120–130 ms (English and Chinese engines, first call excepted); keyboard view → first frame 11–14 ms; Compose emoji panel first frame 73 ms; process memory 61–80 MB. Split layout on the unfolded screen, phone layout on the cover screen, keys above the system bar. |
| S2 librime | PASS on a device (same phone) and on the host | Chinese typing works with data compiled at build time (unpacked in about 120 ms on first start). Two device-only faults were found and fixed: the JNI string conversion dropped the last character of the data path, and on-device deploys could not create build/; the self-check on the diagnostics page shows the state. Host: 35 checks incl. prebuilt data, the key path and the runtime tables, plus 9 UTF conversion checks (`ctest`, 2 of 2, x86_64 Linux, 2026-11-10). |
| S3 Mozc | PASS on the host; Android build PASS; NOT_RUN on a device | Mozc 3.35.6354 and Kotomo's glue; host test 11 of 11 (x86_64 Linux, 2026-11-10): `わたしはにほんじん` → 私は日本人, pinned 池袋 + `にかいにいこう` → 池袋に買いに行こう, pinyin is not a Mozc reading, a fixed boundary keeps わたし one segment, ありがと predicts ありがとう. `scripts/build-mozc.sh` builds the arm64-v8a and x86_64 libraries (16 KB-aligned, 6 exported symbols, all JNI) and the data set; the first local build matched the deleted CI run's hashes byte for byte, and `build-lock.json` now records the libraries with the newer glue. On the emulator (Android 17, x86_64, debug) Japanese mode converts `watashiwanihonjin` to 私羽日本人 first, the known particle gap of T03-001, and `watashihanihonjin` to 私は日本人; in the segment panel 私は shrinks to 私 + は and choosing 渡し for 私 leaves は and 日本人 as they were. No device result is recorded. |
| S4 English engine | PASS on the host; NOT_RUN on a device | `libkotomo_english.so` builds for arm64-v8a and x86_64 (about 360 KB stripped, 16 KB-aligned); the 2.5 MB dictionary builds reproducibly; `core/english/tests/run-host.sh` on x86_64 Linux (2026-11-10): 13 of 13 cases (`helo` → hello, `teh` → the, near-key taps, completion, next-word prediction, negatives). Host timing 4.7 ms per call is not device latency. |
| S5 cross-language lexicon | PASS on the host | `java lexicon/CheckSamples.java` (2026-11-10, CC-CEDICT 2026-11-10, JMdict 2026-11-09): 34 of 34 expected mappings at their rank (池袋 under `chidai`, 東京 under `dongjing`, コーヒー under `coffee`, …) and 0 of 8 negative cases (芸術 is not under `yunshu`). Pinyin → Japanese covers 99.97 % of Mozc's all-kanji entries. |
| S6–S8 | NOT_RUN | — |

## Gates

| Gate | Status |
|---|---|
| G0 feasibility | NOT_RUN |
| G1 basic input | NOT_RUN |
| G2 mixed input | NOT_RUN |
| G3 complete input | NOT_RUN |
| G4 AI and personalization | NOT_RUN |
| G5 1.0 | NOT_RUN |

## Known limitations

- A reading of the code on 2026-11-09 found P1 incomplete: Mixed mode types exactly as Chinese
  mode. `PLAN.md` §8 lists every gap.

- English mode suggests corrections, completions and next words from the S4 engine, with each
  letter's tap position on its key, so a tap near a neighbouring key weighs that key too; the typed
  word stays first and is what space commits. Chinese mode offers the letters as typed after the
  two best Chinese candidates. Chinese mode converts pinyin through librime (S2) with data
  compiled at build time; Mixed mode does the same until the mixed coordinator (S6) exists. Japanese
  mode converts romaji through Mozc (S3, libraries built with `scripts/build-mozc.sh`): the composition shows kana, the
  first candidate is the whole sentence, followed by up to three completions predicted from the
  reading, the first segment's alternatives and the kana; Enter keeps the kana. The expand arrow
  opens the segments: choosing one's candidate pins it and converts the rest again, Shorter and
  Longer move its end, and Enter then commits the conversion as shown. A build without Mozc's data
  says so in the candidate bar.
- P1's gestures, field adaptation, Taiwan and Hong Kong output, candidate details, Direct Boot and the API 37 composition attribute are unit- or host-tested only. The P1 checklist (`tests/device/p1-checklist.md`) is judged from the screen, and no line has been recorded yet, so G1 stays NOT_RUN. Before the first unlock the keyboard types with the defaults and built-in data only: Chinese with nothing learned, English, no Japanese (Mozc needs its data in credential-protected storage), no clipboard, AI or emoji recents.
- No claim about latency, memory, APK size or on-device AI can be made before P0 measures it on a
  device.
