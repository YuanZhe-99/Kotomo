# Decisions

Architecture decision records: each states what was decided, why, and what would reopen it. A
decision changes only when the user decides; a new record replaces an old one by saying so, and the
old one stays with its status changed. Dates are when the decision took effect.

| ID | Decision | Status |
|---|---|---|
| ADR-001 | Engines: librime, Mozc and AOSP LatinIME's suggestion core | accepted 2026-11-04 |
| ADR-002 | librime is called through its session API, without a plugin | accepted 2026-11-04 |
| ADR-003 | Mozc is built with its own Bazel build into a separate library | accepted 2026-11-04, amended 2026-11-10 |
| ADR-004 | One shared library per engine, exporting only its JNI entry points | accepted 2026-11-04 |
| ADR-005 | The keyboard is a custom View; panels and the settings app use Compose | accepted 2026-11-04 |
| ADR-006 | Chinese data is compiled at build time; on-device deploys run in their own process | accepted 2026-11-04 |
| ADR-007 | The network permissions ML Kit brings are accepted | accepted 2026-11-10 |

## ADR-001 Engines

**Decision.** Chinese uses librime 1.17.0 with the `luna_pinyin` schema (rime-luna-pinyin,
LGPL-3.0); Japanese uses Mozc 3.35.6354 with Kotomo's own client and C++ glue; English uses AOSP
LatinIME's native suggestion core as HeliBoard 4.2 maintains it, with the aosp-dictionaries en_US
word list. Simplified and Traditional conversion uses OpenCC, shared with librime.

**Why.** Each is the mature open engine for its language and builds for Android (`upstream.md`).
Kotomo writes the mixed-input coordinator, not segmentation, kana-kanji conversion or spelling
correction (`PLAN.md` §1).

**Not compared.** The plan asked spike S2 to compare schemas and dictionaries on a Chinese corpus;
`luna_pinyin` was chosen without one. The comparison runs once the conversion corpus exists.

**Reopens when.** The corpus shows another schema or engine clearly better, or an upstream stops
being maintained.

## ADR-002 librime through its session API

**Decision.** The bridge drives librime sessions: `process_key` for keys, `set_input` for a span,
candidate iteration, `select_candidate` and `get_commit`. No librime plugin (`register_module`).

**Why.** Spike S2 measured about 0.2 ms per prefix with `set_input` and candidate iteration on the
host, enough for per-span use by the coordinator (`PLAN.md` §5.4).

**Cost.** Today Chinese typing crosses JNI five times per key (`Rime.kt`); the engine ↔ app
contract (`PLAN.md` §4.4) replaces that with one crossing per key.

**Reopens when.** The coordinator needs librime's lattice rather than its candidates, or device
latency per span is too high.

## ADR-003 Mozc's build

**Decision.** Mozc and Kotomo's glue (`core/mozc/`) build with Mozc's own Bazel build into
`libkotomo_mozc.so` and `mozc.data`, through `scripts/build-mozc.sh`. The glue is copied into a
work copy of Mozc, so the submodule stays unmodified; visibility is relaxed with
`--nocheck_visibility` instead of a patch. The app build packs the result from
`build/mozc-prebuilt/`.

**Why.** Mozc builds only with Bazel, and its converter layer (pinned-span conversion, ADR on the
coordinator to come) is reachable only from inside its workspace.

**Amended 2026-11-10.** The Android libraries were first built by GitHub Actions and pinned by
hash, because the development host was Windows on ARM64 and the NDK has no aarch64 Linux host
tools. That repository was deleted; the development host is now x86_64 Linux, and the libraries
are built there. The first local build matched the pinned hashes byte for byte; the hashes
move with every change to the glue (prediction and fixed boundaries were added the same day).

**Reopens when.** Mozc gains a supported way to embed its converter, or the build cost becomes a
problem for releases.

## ADR-004 One library per engine

**Decision.** `libkotomo_rime.so`, `libkotomo_mozc.so` and `libkotomo_english.so` are separate
shared libraries. Each exports only its JNI entry points (linker version scripts; `build-mozc.sh`
fails a Mozc build that exports more) and is 16 KB-aligned; checked with `llvm-readelf` on all six
libraries of the 2026-11-10 release build.

**Why.** librime brings Boost, Mozc brings protobuf and abseil; hiding everything but JNI keeps
their symbols from colliding, and Mozc's Bazel build cannot link into the Gradle CMake build.

**Consequence.** A C++ coordinator cannot call the engines in process as the libraries stand; how
it reaches them on the device is open (`PLAN.md` §2.5 decision 4).

## ADR-005 Views and Compose

**Decision.** The keyboard and the candidate bar are custom Views drawn on a Canvas. The keyboard's
panels (emoji, clipboard, quick settings) and the settings app use Jetpack Compose with Material 3.

**Why.** Spike S1 measured key drawing at p95 19–21 ms on the custom View, and the Compose emoji
panel's first frame at 73 ms (Galaxy Z Fold, Android 17, release), acceptable for a panel but not
for the key path. Compose inside the input method needs a `LifecycleOwner` and a
`SavedStateRegistryOwner` in the view tree, which `KotomoService` provides.

**Reopens when.** A measurement shows a panel or the keyboard missing its frame budget.

## ADR-006 Prebuilt Chinese data and a separate deploy process

**Decision.** The APK ships librime's data compiled on the build host (`scripts/prebuild-rime.sh`),
so the phone never compiles on first use. When the data must change on the device, librime
deploys in its own process (`:deploy`) into a staging directory while the keyboard keeps its
session on the previous build, then the keyboard swaps the new build in.

**Why.** A fresh deploy takes 5–15 s with a peak of about 294 MB on the host and refuses sessions
meanwhile (`upstream.md`); the keyboard must keep typing.

## ADR-007 Network permissions from ML Kit

**Decision.** The app's own manifest declares no permission, and the merged manifest keeps the
`INTERNET`, `ACCESS_NETWORK_STATE` and AICore binding permissions that ML Kit (writing help) brings.
Kotomo's own code opens no connection; the first feature of its own that does (dictionary
downloads) is confirmed with the user before it is added and goes into the privacy policy.

**Why.** The plan's default for `PLAN.md` §2.5 decision 10, recorded when development resumed.
`PRIVACY_POLICY.md` and `ai.md` already describe the permissions.

**Reopens when.** The user asks for a build without ML Kit (for example for F-Droid, which does not
accept it): writing help then moves into its own build variant.
