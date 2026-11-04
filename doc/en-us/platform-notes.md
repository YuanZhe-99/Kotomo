# Platform notes

| Platform | Status |
|---|---|
| Android phones (`arm64-v8a`) | Spike S1 skeleton: a keyboard with placeholder candidates and no conversion engine yet; not yet run on a device. |
| Android emulator (`x86_64`) | The development target for layouts and instrumentation tests where the host supports it; never evidence for latency or on-device AI. |
| Android tablets and foldables | Planned for P1 together with one-handed mode; layouts never assume a phone screen size. |
| Desktop hosts (Windows, Linux, macOS) | Build and test the engine core from P0; never a product target. |

## Repository

Verified on Windows 11 on ARM64 (2026-11-04; JDK 21.0.13) and on GitHub Actions (`ubuntu-latest`):

| Check | Result |
|---|---|
| Documentation mirror and links | `java tool/CheckDocs.java` passes |
| Unit tests (S1: layout and hit testing, latency percentiles) | `./gradlew testDebugUnitTest`: 11 tests pass |
| Lint | `./gradlew check` passes with no warnings |
| Release build | `./gradlew assembleRelease`: R8 on, signed with the release key (APK Signature Scheme v2) |

## Spikes

| Spike | Status | Evidence |
|---|---|---|
| S1 IME skeleton | NOT_RUN on a device | Builds and passes unit tests. The diagnostics page in the settings app records press → key drawn, press → candidates drawn, cold start → first frame, the Compose emoji panel's first frame and memory, ready for a device run. |
| S5 cross-language lexicon | PASS on the host | `java lexicon/CheckSamples.java`: 28 of 28 expected mappings at their rank (池袋 under `chidai`, 東京 under `dongjing`, コーヒー under `coffee`, …) and 0 of 10 negative cases (芸術 is not under `yunshu`). Pinyin → Japanese covers 98.9 % of Mozc's all-kanji entries. |
| S4 English engine | PASS on the host; NOT_RUN on a device | `libkotomo_english.so` builds for arm64-v8a and x86_64 (about 360 KB stripped, 16 KB-aligned); the 2.5 MB dictionary builds reproducibly; `core/english/tests/run-host.sh` in WSL (Debian, aarch64): 13 of 13 cases (`helo` → hello, `teh` → the, near-key taps, completion, next-word prediction, negatives). Host timing 3.8 ms per call is not device latency. |
| S2, S3, S6–S8 | NOT_RUN | — |

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
  stays first and is what space commits. The other modes show placeholder candidates (the letters as
  typed, capitalized and in capitals); no Chinese or Japanese conversion exists yet.
- The keyboard view has no accessibility support (touch exploration) yet.
- No claim about latency, memory, APK size or on-device AI can be made before P0 measures it on a
  device.
