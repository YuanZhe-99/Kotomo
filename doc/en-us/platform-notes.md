# Platform notes

| Platform | Status |
|---|---|
| Android phones (`arm64-v8a`) | P0 spikes: the keyboard with English (S4) and Chinese (S2) engines; not yet run on a device. |
| Android emulator (`x86_64`) | The development target for layouts and instrumentation tests where the host supports it; never evidence for latency or on-device AI. |
| Android tablets and foldables | Planned for P1 together with one-handed mode; layouts never assume a phone screen size. |
| Desktop hosts (Windows, Linux, macOS) | Build and test the engine core from P0; never a product target. |

## Repository

Verified on Windows 11 on ARM64 (2026-11-04; JDK 21.0.13) and on GitHub Actions (`ubuntu-latest`):

| Check | Result |
|---|---|
| Documentation mirror and links | `java tool/CheckDocs.java` passes |
| Unit tests (S1: layout and hit testing, latency percentiles; settings format: defaults, round trip, unknown keys, no file) | `./gradlew testDebugUnitTest`: 23 tests pass (the 6 settings-format tests have run on the Windows host only so far) |
| Lint | `./gradlew check` passes with no warnings |
| Release build | `./gradlew assembleRelease`: R8 on, signed with the release key (APK Signature Scheme v2) |

## Spikes

| Spike | Status | Evidence |
|---|---|---|
| S1 IME skeleton | PASS on a device (Galaxy Z Fold, SM-F971U1, Android 17, release build, 2026-11-04) | Press → key drawn p50 9–10 ms, p95 19–21 ms; press → candidates drawn p50 15–48 ms, p95 120–130 ms (English and Chinese engines, first call excepted); keyboard view → first frame 11–14 ms; Compose emoji panel first frame 73 ms; process memory 61–80 MB. Split layout on the unfolded screen, phone layout on the cover screen, keys above the system bar. |
| S2 librime | PASS on a device (same phone) and on the host | Chinese typing works with data compiled at build time (unpacked in about 120 ms on first start). Two device-only faults were found and fixed: the JNI string conversion dropped the last character of the data path, and on-device deploys could not create build/; the self-check on the diagnostics page shows the state. Host: 24 checks incl. prebuilt data and the key path. |
| S3 Mozc | PASS on the host; Android build NOT_RUN | Mozc 3.35.6354 and Kotomo's glue build in WSL (aarch64); host test 7 of 7: `わたしはにほんじん` → 私は日本人, pinned 池袋 + `にかいにいこう` → 池袋に買いに行こう, pinyin is not a Mozc reading. The Android libraries need `.github/workflows/mozc.yml` on x86_64 Linux, which has not run yet. |
| S4 English engine | PASS on the host; NOT_RUN on a device | `libkotomo_english.so` builds for arm64-v8a and x86_64 (about 360 KB stripped, 16 KB-aligned); the 2.5 MB dictionary builds reproducibly; `core/english/tests/run-host.sh` in WSL (Debian, aarch64): 13 of 13 cases (`helo` → hello, `teh` → the, near-key taps, completion, next-word prediction, negatives). Host timing 3.8 ms per call is not device latency. |
| S5 cross-language lexicon | PASS on the host | `java lexicon/CheckSamples.java`: 34 of 34 expected mappings at their rank (池袋 under `chidai`, 東京 under `dongjing`, コーヒー under `coffee`, …) and 0 of 8 negative cases (芸術 is not under `yunshu`). Pinyin → Japanese covers 99.97 % of Mozc's all-kanji entries. |
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

- English mode suggests corrections, completions and next words from the S4 engine; the typed word
  stays first and is what space commits. Chinese mode converts pinyin through librime (S2) with data
  compiled at build time; Mixed mode does the same until the mixed coordinator (S6) exists. Japanese
  mode converts romaji through Mozc (S3, libraries from Linux CI): the composition shows kana, the
  first candidate is the whole sentence, Enter keeps the kana; editing single segments comes in P2.
- No claim about latency, memory, APK size or on-device AI can be made before P0 measures it on a
  device.
