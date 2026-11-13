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
| ADR-007 | The network permissions ML Kit brings are accepted | accepted 2026-11-10; Kotomo's first own network feature confirmed 2026-11-11 (ADR-011) |
| ADR-008 | The coordinator is a fourth library that calls the engines through narrow C functions | default taken 2026-11-11, awaiting the user's confirmation |
| ADR-009 | Snapshots are a versioned compact binary format | default taken 2026-11-11, awaiting the user's confirmation |
| ADR-010 | Basic Mixed mode arrives with the coordinator; space and Enter during composition are chosen per mode | confirmed by the user 2026-11-11; space and Enter per mode decided 2026-11-12 |
| ADR-011 | Dictionaries are downloaded from their sources and converted on the device, replacing a hosted catalog | accepted 2026-11-11 (user decision) |
| ADR-012 | Optional password protection seals the person's dictionary files; librime's tables stay plain while usable | switch decided by the user 2026-11-11; the locking behavior awaits the user's confirmation |
| ADR-013 | Optional encryption of Mixed mode's learned readings with a Keystore key | switch decided by the user 2026-11-12; Keystore key, export and import confirmed 2026-11-13 |

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

## ADR-008 Where the coordinator lives

**Decision.** The mixed-input coordinator (`core/mixed/`) is C++ with no engine linked in. On the
device it is a fourth shared library, `libkotomo_core.so`, with the app's one JNI facade;
librime's, Mozc's and the English core's libraries each hold a static table of the few plain C
functions that answer the coordinator's questions and return its address through a JNI getter,
and the app hands the addresses to the coordinator's library (`engine-contract.md`). The
libraries still export nothing but JNI entry points: the first plan, exporting the C functions as
symbols for the coordinator's library to link, was dropped when the device side was built
(2026-11-11), because a table needs no symbol resolved across libraries, works unchanged in the
host JVM that runs the conversion corpus, and lets a build without Mozc leave its table out. This is option (b) of `PLAN.md`
§2.5 decision 4 (a fourth library, one JNI crossing per key), confirmed by the user on
2026-11-11; how the C functions are handed over is this record's own choice.

**Why.** A Kotlin coordinator (a) would cross JNI several times per key and put the lattice and
the decoder on the managed heap, against the one-crossing contract. One merged library (c) would
break the symbol isolation of ADR-004 and cannot include Mozc, which Bazel builds separately
(ADR-003). With (b) the engines stay separate libraries with hidden internals, one key is one JNI
crossing, and the same coordinator code runs on the host against the same five questions.

**Cost.** Each engine library carries a small C surface besides JNI, versioned per table, and
the app must close the coordinator before an engine whose handle it holds. On the host Mozc
cannot be linked into a CMake program, so the coordinator's own corpus runner reaches it through
a line service; device latency of the five calls is unmeasured.

**Reopens when.** The tables' functions cannot be called across the libraries on a device, or
their per-key cost breaks the latency budget.

## ADR-009 The snapshot format

**Decision.** Confirmed by the user on 2026-11-11. The engine answers every call with one
snapshot in a compact binary encoding of its own: a magic, the contract version, then fixed-width little-endian integers and length-prefixed
UTF-8 strings (`engine-contract.md`). The decoder checks every length and range and drops a
buffer that fails. A snapshot carries a generation and a stage, and replaces the one on screen
only when it is newer.

**Why.** The plan asked for a compact binary format or protobuf-lite, never JSON per key. The
snapshot has three record types and no optional fields, so a hand-written codec is about a
hundred lines on each side and adds no dependency; protobuf-lite would add a second copy of the
runtime next to the one hidden inside Mozc's library (ADR-004).

**Reopens when.** The snapshot grows optional or repeated structures that make the hand-written
codec error-prone; protobuf-lite is then the replacement, behind the same version number.

## ADR-010 Basic Mixed mode, the space key and Enter

**Decision.** Basic Mixed mode is not built as a separate word-level merge in Kotlin: Mixed mode
arrives with the coordinator's device integration, and gate G1 judges the three single-language
modes only (`PLAN.md` §2.5 decision 5, confirmed by the user on 2026-11-11). What the space key
and Enter do while a composition is open is a setting of each mode (user decision, 2026-11-12,
replacing the fixed rule of decision 6), with two small sets of choices and these defaults:

| Mode | Space (`<mode>.space`) | Enter (`<mode>.enter`) |
|---|---|---|
| Chinese | `commit_first`; also `next` | `shown` (the letters); also `first` |
| Japanese | `next`, as Japanese keyboards do; also `commit_first` | `shown` (the kana); also `first` |
| Mixed | `separate`; also `commit_first`, `next` | `first`; also `shown` (the letters) |

`separate` marks a boundary inside the composition and commits nothing. `commit_first` commits
the first candidate. `next` converts: the first press shows the first candidate in the field
without committing it, each further press the next one (after the last, the first again); Enter
then commits the candidate shown under either Enter choice, backspace returns to the composition
as typed, and a character commits the candidate and is typed after it. Enter otherwise commits
the composition as the field shows it (`shown`) or the first candidate (`first`).

**Why.** The coordinator reads a space as a boundary hint, not as a character
(`chidai ni kai ni ikou` and `chidainikainiikou` convert alike), so Mixed mode keeps `separate`
as its default: a space that committed would make the plan's own sentence examples untypable.
Japanese mode follows what people who type Japanese expect from the space bar. Chinese mode
keeps what it shipped with. The choices are a fixed short list, not a key mapping: each one is a
behavior that is tested.

**Cost.** A boundary inside the composition exists only in Mixed mode: Chinese mode marks one
with the apostrophe key and Japanese mode converts through Mozc's session, which has none; a
`separate` stored for them reads as the mode's default. Under `next` a later, fuller list of a
long Mixed composition is not drawn while a candidate is chosen, since it would move the choice.

**Reopens when.** The user decides a point differently, or Japanese mode moves its conversion to
the coordinator (a boundary could then be offered there too).

## ADR-011 Dictionaries straight from their sources

**Decision.** Kotomo does not host dictionary packs. The settings list the approved sources that
convert well on a phone (`dictionaries.md`); a download fetches the source's own files from where
its authors publish them and converts them on the device with the same Kotlin code the host tests
run (`dictionary/Sources.kt`), into the entry model and pack files of PLAN §10.5. A GitHub release
asset is checked against the SHA-256 digest GitHub publishes for it; a file whose source publishes
no checksum is protected by HTTPS only, and the settings say so. Only HTTPS is accepted, redirects
included. Before the first download a notice names the hosts and says that nothing typed is sent;
it is accepted once per notice version (`state.json` `online_accepted`). This replaces the hosted
catalog with its own SHA-256 list and signatures (`PLAN.md` §2.5 decisions 2 and 9); the pinned
hashes in `build-lock.json` are for host builds only.

**Why.** The user decided it (2026-11-11): no hosting, no release to sign, and the dictionaries
stay as current as their sources. Converting on the device is feasible for the Chinese and English
word lists measured in Q6; it is not for sources that need heavy tools or are too large, which the
settings leave out.

**Cost.** Only GitHub releases come with a published checksum; plain files (THUOCL, rime-ice's
character list) are trusted as far as HTTPS goes. A source that changes its layout breaks its
download until the converter follows. Conversion uses the phone's CPU and memory for seconds to
tens of seconds in the background.

**Reopens when.** A source moves or changes format, a source starts publishing checksums or
signatures, or a large source (Japanese dictionaries once Mozc's user dictionary reads them) needs
preconversion after all.

## ADR-012 Password protection of the person's dictionary

**Decision.** An optional switch, off by default (user decision, 2026-11-11), encrypts the
person's dictionary files (`user.kenc`, `user.prev.kenc`) and exports with a key derived from a
password they choose: PBKDF2-HMAC-SHA256 with 600,000 rounds and a random salt, AES-256-GCM per
file with a versioned header (`dictionaries.md`). The password and the key are never written;
a forgotten password cannot be recovered. librime can only read plain tables, so: after a
process start the dictionary is locked (nothing shown or changed) but the tables written at the
last unlock stay, and the keyboard keeps typing the words; Lock now empties the two tables made
from the person's words until the next unlock.

**Why.** It protects what matters most at rest, the full list with its sources and the previous
version, and every export or backup made from it, without making the keyboard ask for a
password after each restart of its process (Android restarts input methods often). The
plain tables in the app's private storage are what an input method needs to type the words; they
hold only word, pinyin and weight, never Japanese or English entries or the sources.

**Cost.** While the keyboard can use the words, they are readable in the tables by anyone who can
read the app's private storage (root, a debugger on a debug build). Protecting them as well would
mean emptying the tables at every process start, so the person's words would be missing until
they unlock in the settings.

**Reopens when.** The user decides that a restart should lock the keyboard too (tables emptied
at every start), that the keyboard should ask for the password, or that the Keystore should hold
a key instead of a password.

## ADR-013 Optional encryption of the learned readings

**Decision.** A switch, off by default (user decisions, 2026-11-12: "optional encryption";
2026-11-13: a Keystore key, with export and import), keeps
Mixed mode's learned readings sealed in `learned.kenc` instead of the plain `learned.json`
(`settings-format.md`): AES-256-GCM with the Android Keystore key `kotomo_learned`, the same
container layout as the clipboard history. Turning it on or off rewrites the file in the other
form and deletes the old one only after the new one is on disk. What is learned, and where
nothing is learned (sensitive fields, fields that ask for no learning, incognito mode), does not
change. The learned readings page exports them as the plain versioned JSON, also while the file
on the device is encrypted, and imports such a file by merging it: a reading both have keeps the
copy used later.

**Why.** `PLAN.md` §14 names a Keystore key for learning data, and the keyboard must read the
readings on its own: Android restarts an input method's process often, and with a password
(ADR-012's scheme for the dictionary) the readings would be locked after every restart until
the person opened the settings and typed it, so nothing learned would be used or recorded most
of the time. A Keystore key protects the file at rest (a copy of the app's storage without the
device's Keystore cannot be read) and needs nothing from the person.

**Cost.** It does not protect against someone who can run code as the app on the unlocked device
(root, a debugger on a debug build): the Keystore decrypts for the app. If the Keystore loses the
key, the sealed readings are lost (the file is moved aside, learning starts empty). The key
cannot leave the device, so an export is not encrypted; the page says so.

**Reopens when.** The user wants a password for the learned readings as for the dictionary, or
the dictionary moves to a Keystore key too.

## ADR-014 Handwriting with ML Kit's digital ink recognition

**Decision.** Chinese and Japanese handwriting (PLAN §12) uses ML Kit's on-device digital ink
recognition (`digital-ink-recognition`), the same vendor and terms as the translation Kotomo
already ships (ADR-007). A model per language (mainland, Taiwan and Hong Kong Chinese, Japanese;
about 20 MB each) is downloaded from Google only when the person taps Download under a notice that
names Google and the size, and can be deleted in the settings. The strokes stay on the device and
nothing written is learned.

**Why.** It recognizes whole lines of Chinese and Japanese, takes the text before the cursor as
context, needs no data in the APK and adds no new license: ML Kit's terms already apply. The
open-source alternative, Zinnia with the Tegaki or Tomoe models, would ship about 10–20 MB of
models in the APK under their own licenses, recognizes one character at a time and has had no
release for years; it was not audited.

**Cost.** Closed source, like the rest of ML Kit; no model until the person downloads one (no
handwriting offline before that); ML Kit may send its own diagnostics (`upstream.md`).

**Reopens when.** The user wants handwriting without Google's code (for example a build without
ML Kit, decision 10), or an open recognizer with Chinese and Japanese models of known license is
found.

## ADR-015 Voice typing through Android's on-device speech recognition

**Decision.** Voice typing in Chinese (mainland or Taiwan Mandarin, by the person's script),
English and Japanese (PLAN §12) uses only the system's on-device recognizer
(`SpeechRecognizer.createOnDeviceSpeechRecognizer`, Android 12 and later). Kotomo asks for the
microphone (`RECORD_AUDIO`) only when the person opens voice typing, through a screen that shows
nothing but the system's dialog; it never records audio itself. A missing language model is
downloaded by the system's speech service (Android 13 and later) only after the person taps
Download under a notice saying so. Nothing heard is stored, logged or learned; voice typing is not
offered in password, other sensitive or direct fields or in incognito mode.

**Why.** It adds no library, model or license to Kotomo, runs offline once the model is on the
device, and keeps audio inside the system's own recognition service. The default
`SpeechRecognizer` may stream audio to remote servers (its documentation says so), which the
behavior contract forbids without a disclosed, person-started use; an open recognizer (Vosk,
whisper.cpp, sherpa-onnx with Chinese, English and Japanese models) would ship 50–500 MB of models
whose sources and licenses need the user's choice.

**Cost.** Devices without an on-device recognizer (most before Android 12, and many without
Google's speech services) show that voice typing is unavailable. Which provider runs the
recognizer and where its model comes from is the device's choice, not Kotomo's.

**Reopens when.** The user picks an open recognizer and its models, or wants a network recognizer
as an option the person enables with a notice.
