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
| Release build | `./gradlew assembleRelease` on 2026-11-10 with `KOTOMO_REQUIRE_MOZC=1` and `KOTOMO_REQUIRE_RIME_PREBUILT=1`: R8 on, signed with the release key (APK Signature Scheme v2, verified with `apksigner`); arm64-v8a 27.4 MB, x86_64 27.5 MB, each with librime, Mozc (2.9 MB library, 19 MB data) and the English engine |

## Spikes

| Spike | Status | Evidence |
|---|---|---|
| S1 IME skeleton | PASS on a device (Galaxy Z Fold, SM-F971U1, Android 17, release build, 2026-11-04) | Press → key drawn p50 9–10 ms, p95 19–21 ms; press → candidates drawn p50 15–48 ms, p95 120–130 ms (English and Chinese engines, first call excepted); keyboard view → first frame 11–14 ms; Compose emoji panel first frame 73 ms; process memory 61–80 MB. Split layout on the unfolded screen, phone layout on the cover screen, keys above the system bar. |
| S2 librime | PASS on a device (same phone) and on the host | Chinese typing works with data compiled at build time (unpacked in about 120 ms on first start). Two device-only faults were found and fixed: the JNI string conversion dropped the last character of the data path, and on-device deploys could not create build/; the self-check on the diagnostics page shows the state. Host: 35 checks incl. prebuilt data, the key path and the runtime tables, plus 9 UTF conversion checks (`ctest`, 2 of 2, x86_64 Linux, 2026-11-10). |
| S3 Mozc | PASS on the host; Android build PASS; NOT_RUN on a device | Mozc 3.35.6354 and Kotomo's glue; host test 7 of 7 (x86_64 Linux, 2026-11-10): `わたしはにほんじん` → 私は日本人, pinned 池袋 + `にかいにいこう` → 池袋に買いに行こう, pinyin is not a Mozc reading. `scripts/build-mozc.sh` builds the arm64-v8a and x86_64 libraries (16 KB-aligned, 5 exported symbols, all JNI) and the data set with the same SHA-256 as the deleted CI run in `build-lock.json`. On the emulator (Android 17, x86_64, release) Japanese mode converts `watashiwanihonjin` to 私羽日本人 first, the known particle gap of T03-001. No device result is recorded. |
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
  mode, Japanese mode has no segment editing or prediction, English mode does not pass touch
  positions to its engine, and fields that ask for no personalized learning accept only direct
  ASCII typing. `PLAN.md` §8 lists every gap.

- English mode suggests corrections, completions and next words from the S4 engine; the typed word
  stays first and is what space commits. Chinese mode converts pinyin through librime (S2) with data
  compiled at build time; Mixed mode does the same until the mixed coordinator (S6) exists. Japanese
  mode converts romaji through Mozc (S3, libraries built with `scripts/build-mozc.sh`): the composition shows kana, the
  first candidate is the whole sentence, Enter keeps the kana; editing single segments comes in P2.
- P1's gestures, field adaptation, Taiwan and Hong Kong output, candidate details, Direct Boot and the API 37 composition attribute are unit- or host-tested only. The P1 checklist (`tests/device/p1-checklist.md`) is judged from the screen, and no line has been recorded yet, so G1 stays NOT_RUN. Before the first unlock the keyboard types with the defaults and built-in data only: Chinese with nothing learned, English, no Japanese (Mozc needs its data in credential-protected storage), no clipboard, AI or emoji recents.
- No claim about latency, memory, APK size or on-device AI can be made before P0 measures it on a
  device.
