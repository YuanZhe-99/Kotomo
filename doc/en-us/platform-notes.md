# Platform notes

| Platform | Status |
|---|---|
| Android phones (`arm64-v8a`) | Scaffold only: no app, engine or dictionary code yet. |
| Android emulator (`x86_64`) | The development target for layouts and instrumentation tests from P0; never evidence for latency or on-device AI. |
| Android tablets and foldables | Planned for P1 together with one-handed mode; layouts never assume a phone screen size. |
| Desktop hosts (Windows, Linux, macOS) | Build and test the engine core from P0; never a product target. |

## Repository

Verified on Windows 11 x64 (2026-11-04; JDK 21.0.13):

| Check | Result |
|---|---|
| Documentation mirror and links | `java tool/CheckDocs.java` passes |

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

- Nothing can be typed yet: no engine, app or dictionary exists.
- No claim about latency, memory, APK size or on-device AI can be made before P0 measures it on a
  device.
