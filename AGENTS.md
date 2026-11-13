# AGENTS.md

Operating guide for agents working on **Kotomo**. This file holds **only** rules about how to work
here. Everything describing what the code *is* or *does* lives in `doc/en-us/` — see
[Where to read what](#where-to-read-what).

Kotomo is an Android input method for Chinese, English and Japanese. Pinyin, English and Japanese
romaji are typed on one keyboard; in Mixed mode one continuous string may hold all three
(`chidai ni kai ni ikou` → 池袋に買いに行こう) and converts as a whole sentence. It is
local-first: Kotlin for the input method service, the keyboard and the settings app; C++ for the
composition engine, with librime for Chinese, Mozc for Japanese and AOSP LatinIME's suggestion
engine for English, reached through JNI. On-device Gemini Nano is optional, for proofreading,
translation and rewriting the person asks for. Treat the user's message as the change request:
plan, implement, verify, report.

## Reading order

When you need to understand code, read in this order and stop as soon as you have what you need:

1. **`doc/en-us/`** — start here, always. `functions/INDEX.md` to find the right module; the
   concept docs for behavior.
2. **`PLAN.md`** — while it is active, for anything not yet implemented: §0 first (the current
   state and the work queue), then the architecture, the composition engine, phases P0–P5, spikes
   S1–S8, gates G0–G5, the conversion corpus and acceptance.
3. **Comments in the source** — the Function Explanation Layer on each declaration.
4. **The implementation** — only when the docs and comments are insufficient, or when you must
   confirm actual behavior before changing it.

Do not jump straight to reading source bodies. Where docs and code disagree on something you are
about to change, verify against the code, then fix the docs in the same commit.

## Where to read what

| Question | Read |
|---|---|
| The current state, the one work queue and the open decisions (while the plan is active) | `PLAN.md` §0, §2.5 |
| Architecture, engine design, phases, spikes, gates, corpus, acceptance (while the plan is active) | `PLAN.md` |
| Verified upstream contracts (librime, Mozc, OpenCC, the English engine, ML Kit, the Android IME platform, data sets) and quirks | `doc/en-us/upstream.md` |
| Reused code, dictionary data, emoji assets and fonts: licenses and obligations; design acknowledgements | `doc/en-us/third-party.md` |
| What is implemented and verified per device and Android version, with gate status | `doc/en-us/platform-notes.md` |
| Environment, commands, dev loop | `doc/en-us/development.md` |
| English→Chinese terminology | `doc/en-us/translation-guide.md` |
| Past releases and the upstream versions they used | `doc/en-us/version-history.md` |
| Per-module and per-file roles | `doc/en-us/functions/INDEX.md` |
| Architecture decisions (ADRs): engines, builds, libraries, UI toolkit, permissions | `doc/en-us/decisions.md` |
| Toolchain, upstream commits, data-source versions and hashes each build uses | `build-lock.json` |
| The settings file: keys, defaults, versioning | `doc/en-us/settings-format.md` |
| The clipboard history: recording rules, encrypted file format, verification codes, quick paste | `doc/en-us/clipboard.md` |
| Backups and sync: what a backup holds, its file, restoring with preview and undo, automatic backups, sync and its conflicts | `doc/en-us/backup.md` |
| Writing help (on-device AI): rules, the keyboard ↔ AI screen hand-off, actions, failures | `doc/en-us/ai.md` |
| Dictionaries: sources and priority, readings, import formats and checks, the versioned files, packs and the online catalog | `doc/en-us/dictionaries.md` |
| The engine ↔ app contract: calls in, the versioned snapshot out, stale updates, the questions the coordinator asks the engines | `doc/en-us/engine-contract.md` |
| The compiled cross-language lexicon: build, binary layout, reading and checks | `doc/en-us/lexicon-format.md` |
| Screens, wording and tokens from the design agent (keyboard, candidate bar, panels, settings) | `design/` (start at `design/README.md`) |

The concept docs added from P0 on (the
architecture, the engine ↔ app contract, the composition engine, lexicon formats, ranking, data
formats, privacy, decisions) get their rows here in the commit that creates them.

## Division of work

Three kinds of agents work here, following `PLAN.md` §6; one agent may also do all of it, in the
same dependency order:

- The **engine agent** owns the C++ core (`core/`): composition, the candidate lattice, the
  decoder, the cross-language lexicon at run time and the JNI layer; the upstream engines
  (`third_party/`, `patches/`) and their builds; the lexicon pipeline (`lexicon/`) and its data
  sources; the conversion corpus (`tests/conversion/`); `build-lock.json`. It starts with spikes
  S2–S6 and S8.
- The **app agent** owns the Android app (`app/`): the input method service, keyboard views and
  layouts, the candidate bar and panels, settings, storage, the clipboard, backup and sync, the AI
  manager, and the instrumentation and benchmark tests. It starts with spikes S1 and S7.
- The **design agent** designs the keyboard, the candidate bar, the panels and the settings app as
  standalone screens in `design/`, with Gboard's interaction model as the keyboard reference and
  MyApps-UI's settings presentation as the settings reference. It delivers screens and wording; it
  does not write product code.
- The app reaches the engine only through the engine ↔ app contract (a Kotlin facade over
  `core/jni`): key events and commands in, versioned composition and candidate snapshots out. The
  engine agent defines it with the app agent and records it in `doc/en-us/` before the app depends
  on it; a change to it updates both sides and their tests in one change set.

## Required workflow

1. Treat the user's message as the modification request.
2. Before editing, fetch the remote(s) and check whether the local branch is behind. Resolve any
   divergence before starting.
3. Check the upstreams per [Upstream versions](#upstream-versions).
4. Read per [Reading order](#reading-order). Find the phase and gate (`PLAN.md` §6) the change
   belongs to; do not start work whose dependencies are not done, except where §6 allows it.
5. Keep changes scoped. Do not revert unrelated work in the tree.
6. Update documentation in the same change set — see
   [Documentation maintenance](#documentation-maintenance).
7. Verify — see [Verification](#verification).
8. Report briefly, in English and Chinese, with what `PLAN.md` §13 asks: what changed, the commands
   run, each spike, gate and corpus result as `PASS / FAIL / BLOCKED / NOT_RUN` with evidence
   (device, Android version, build type), what is still unverified, the next blocking issue, any
   deviation from the plan, the current version and the configured remotes.
9. Ask before pushing. The user decides when to release.

## Documentation maintenance

**Docs are the primary artifact. Update them first, and never let them drift.**

Any change to a behavior, a data format (user dictionary, learning data, lexicons, themes,
backups), the engine ↔ app contract, a ranking rule, a privacy rule, an upstream or data source,
or a user-visible feature updates, in the same commit, the affected concept docs and the
`functions/INDEX.md` row.

Every language directory under `doc/` (currently `en-us` and `zh-cn`) mirrors the others exactly —
same files, headings, tables, and code blocks. `doc/en-us/` is authoritative: any documentation
change updates **all** language directories in the same commit, translated per
`translation-guide.md`; new terms go into its glossary. `java tool/CheckDocs.java` must pass.

**Put explanation in the docs, not here.** This file is for agent instructions only.

**`PLAN.md` is the active plan** (written in Chinese with the user, from their draft) and is never
copied into `doc/`. Its design decisions change only when the user decides; record a deviation you
need as a question to the user, or as an ADR in `doc/en-us/decisions.md` where the plan asks for
one. When a phase is done, its long-lived contracts (the engine contract, lexicon formats, ranking
rules and data formats as built) move into the concept docs, and the plan's sections for it shrink
to a pointer. When the plan is complete, delete `PLAN.md`.

`PRIVACY_POLICY.md` (from the first release on) describes what the released app stores, learns and
sends; it changes in the same commit as anything that changes those.

Add a `doc/en-us/version-history.md` entry for each release. Documentation-only commits do not
bump versions or create tags.

## Authoring rules

**Function Explanation Layer.** Every function, method and significant type carries a doc comment
(KDoc in Kotlin; `///` on C++ declarations in headers; Javadoc in Java) whose first sentence states
its purpose ("Maps …"). Non-trivial declarations add short lines as needed:

- `Inputs:` important parameters only
- `Returns:` what the caller receives
- `Side effects:` state, file, database, `InputConnection`, process or UI effects
- `Notes:` assumptions, edge cases, the thread it runs on (main or engine), the plan rule a check
  enforces

Keep comments accurate in the same change as the code: they are the third layer of the reading
order, and a stale comment leads to a wrong change. Upstream code keeps its own comment style.

**Code guidelines** (shared by the MyApps projects):

- Do not over-scope: make only the changes that are requested or clearly necessary; no speculative
  features, flags or configurability, and no empty modules or directories waiting for later work.
  The switches `PLAN.md` requires (each gesture, each cross-language conversion) are product
  requirements, not speculation.
- Do not over-abstract: no interface with a single implementation, no builders for simple objects,
  no helpers for one-time operations; fewer cohesive files beat many thin wrappers; keep
  implementation details private. The engine ↔ app contract is the boundary the plan requires; the
  module map in `PLAN.md` §4 lists responsibilities, not classes to create.
- Do not over-defend: validate at true boundaries — JNI entry points, `EditorInfo` and
  `InputConnection` data from other apps, imported dictionaries, themes and backups, downloaded
  packages, files read from disk, AI responses — and trust internal callers.
- Use the current language versions idiomatically: the latest stable Kotlin (sealed types,
  exhaustive `when`, data classes, coroutines and `Flow`); C++ at the newest standard the upstream
  engines build with; Java only for tools.
- Name concisely; reuse existing helpers and conventions; when removing code, remove it completely.
- Choose data structures for their read/write ratio: lexicons are read-mostly and built ahead of
  time (tries, sorted arrays, precomputed indexes); nothing on the key path scans a dictionary
  linearly or allocates per key without need.

**Upstream code.** librime, Mozc, OpenCC and the English engine's native code (and any engine
added later) are git submodules under `third_party/`, pinned by commit and never edited in place. Changes to them are numbered patch
files in `patches/<upstream>/`, each stating its reason, applied by the build. Keep patches small;
proposing a change upstream is the user's call. Never rename, reformat or reorganize upstream code.

**The key path.** Touch → engine → candidate drawing never waits for I/O, the network, AI, a
database or a lock held by background work. One engine thread owns the native engine and nothing
else calls into it. First candidates and later candidates are separate updates, and a stale update
never replaces a newer one. The budgets are in `PLAN.md` §13; they are measured, not assumed.

**Tests.** The product requires tests and a conversion corpus (`PLAN.md` §13), which overrides the
MyApps default of writing tests only on request. Every corpus line has an ID, a mode, the input,
the acceptable outputs and the rank they must reach; every fixed conversion bug adds a line; every
capability has a positive and a negative case (input that must *not* convert). Name the test ID or
gate a test covers. No tests for wording or internal state details. Never tune the engine to one
corpus line at the expense of others: the whole corpus runs before and after a change.

Other conventions:

- Interface copy is written for the person typing. Never surface engine codes, scores, internal IDs
  or paths in the keyboard or the settings; a diagnostics page may show them.
- Interface strings go through Android string resources in English, Simplified Chinese,
  Traditional Chinese and Japanese; no string literals in views.
- Layout numbers and colors are named tokens of the theme system, not literals scattered through
  views.
- The product name is **Kotomo** everywhere (launcher, the system keyboard list, About, release
  material). The reference products (Madamada, Gboard, Microsoft Pinyin, Sogou Pinyin, Microsoft
  SwiftKey, Google Japanese Input) inspire interaction only: never copy their code, dictionaries,
  icons, sounds, images or themes, and never imply endorsement (`PLAN.md` §15).

## Behavior contract

Do not change these without the user explicitly deciding to. `PLAN.md` §1, §14 and §15 bind every
change.

- **Typing works offline.** Every input mode, conversion, correction, prediction and emoji
  suggestion works with no network, and nothing on the key path touches it.
- **What is typed stays on the device.** No telemetry, analytics or crash upload carries typed
  text, candidates, the composition, the clipboard or AI prompts, and logs never contain them
  (lengths and timings only). The network is used only for downloads the person starts
  (dictionaries, emoji packs, models), sync they turned on and an online AI source they enabled,
  each disclosed before first use.
- **Sensitive fields learn nothing.** In password and other sensitive input types, in fields with
  `IME_FLAG_NO_PERSONALIZED_LEARNING`, for clips marked sensitive and in incognito mode: no
  learning, no history, no clipboard recording, no AI, no personalized predictions.
- **AI is optional and never on the key path.** It is off by default and runs only when the person
  asks; every result is previewed and replaces text only on confirmation, with undo. Devices
  without Gemini Nano keep every input feature. An online AI source is used only after the person
  enabled it and accepted a notice naming the provider; it is never a fallback.
- **Original language first.** In Mixed mode, candidates in the language the input was typed in
  rank before cross-language candidates unless the chosen level or the person's history says
  otherwise. Recent language use may adjust the order but never decides alone: `ai` keeps 爱, AI,
  愛 and あい.
- **The person stays in control.** Every gesture and every cross-language conversion has its own
  switch. Autocorrect suggests first and replaces only where it is turned on, and backspace right
  after a replacement undoes it. Learned items can be seen, demoted, deleted or cleared. Changing
  one segment never silently rewrites segments the person already confirmed.
- **Untrusted dictionaries rank low.** Imported and LLM-generated dictionaries are validated
  (schema, readings, duplicates, encoding, weights) and previewed before use, and never enter
  high-priority ranking unvalidated.
- **Data formats are versioned.** The user dictionary, learning data, settings, themes and backups
  carry a format version, keep unknown fields, use UTC timestamps and migrate in a way that can be
  rolled back; a restore never silently overwrites newer data.
- **Licenses before assets.** Nothing ships without a verified license recorded in
  `doc/en-us/third-party.md` and on the in-app licenses page. Apple's emoji images never ship. The
  reference products are credited as design inspiration only.
- **Never claim more than was verified.** Each layer and gate is reported on its own, with the
  device it ran on; an emulator run never stands in for device latency or on-device AI.

## Upstream versions

**Kotomo follows its upstreams' latest releases, pinned by commit.** Every change set checks:

- librime, Mozc, OpenCC and the English engine's upstream (chosen in spike S4) at their latest
  release (`PLAN.md` §3.1 says how each one releases), moved by checking out the new commit in the
  submodule and rebasing its patches;
- the Android toolchain at its latest stable versions: Android Gradle Plugin, Kotlin, Gradle, the
  NDK, the newest platform as `compileSdk` and `targetSdk`, the AndroidX and Compose libraries, and
  the ML Kit artifacts;
- the data sources (JMdict, Unihan, CLDR, the Chinese and English word lists) at their latest
  published release.

Moving an engine, a lexicon or a data source re-runs the whole conversion corpus and the gates the
change touches; a release waits until they pass and never falls back to an older upstream.
`build-lock.json` records what each build used (commit, version, download hash). Add a
`version-history.md` line naming the versions; record "checked, unchanged" too.

## Verification

```bash
java tool/CheckDocs.java
```

- The Gradle checks, the host engine tests, the conversion corpus and the device suites join this
  list as their phase adds them (`doc/en-us/development.md`). "No tests executed" is never a pass.
- Run on a real device whenever a change touches the input method service, `InputConnection`
  handling, the key path, latency, memory, the native engines or on-device AI, and record the
  device, Android version and build type (`doc/en-us/platform-notes.md`). An emulator covers
  layouts and logic only.
- Run the whole conversion corpus whenever a change touches the engine, a lexicon, ranking or an
  upstream; compare it with the previous run and report regressions line by line.
- Test a release build (R8 on) on a device before a release; JNI and ML Kit classes are what R8
  breaks.
- Inspect the keyboard and the settings (screenshots) when a change touches layout or theming: light
  and dark, the four interface languages, the largest font scale. Screenshots never replace
  behavior assertions.
- **Never claim "verified" without an actual run;** use the report template of `PLAN.md` §13.

## Release, version, commit, tag, push

The version lives in `app/build.gradle.kts` (`versionName`; `versionCode` grows with every release)
once P0 creates it. The About page reads it from the package, never from a literal.

For ordinary work, do not bump versions or tag until the user confirms the release version and
confirms pushing. When the user confirms:

1. Set `versionName` and `versionCode`.
2. Bring the upstreams to their latest versions and re-run verification, including the corpus and
   a release build on a device.
3. Commit, including the `doc/en-us/version-history.md` entry (and its mirror).
4. Create an annotated tag `vX.Y.Z`.
5. Push **the commit first**, then the tag, to every configured remote.

Commits follow `PLAN.md` §18: one capability per commit with its test results; never one commit
mixing an upstream move, refactoring and new behavior.

Signing keys live only in CI secrets and the maintainer's offline copy; a local release build reads
them from the ignored `key.properties`.

## Agent co-author attribution

An agent that made a real, material contribution to a commit may add its own accurate
`Co-authored-by:` trailer. Attribution is per commit: do not add an agent merely because it
reviewed, observed, or continued another agent's work, and never copy a trailer automatically from
an earlier commit. When multiple agents materially contributed, include one accurate trailer each.
Use the agent's actual documented identity; never invent a provider, model, name, or email.
Approved examples are `Co-authored-by: Codex <noreply@openai.com>` and, for Claude Code,
`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` with the model name replaced by the actual
model that did the work.

## Remotes and secrets

- `origin` → `<local_gitea_address>` (private Gitea)

`origin` is the only copy. The project was archived on 2026-11-04 and resumed on 2026-11-10; the
public GitHub repository (where CI and GitHub Releases ran) was deleted and is not rebuilt for now.
`.github/` stays for a restart and does nothing until the repository is pushed to a new public
remote with the CI secrets (`PLAN.md` §0). Until then every build and test runs on the development
host (`doc/en-us/development.md`).

Determine the repository path from the runtime workspace; do not hardcode a machine-specific
absolute path here.

**Masking rule:** keep the `origin` URL written as `<local_gitea_address>` in every committed file,
including `.gitmodules`. Never write the underlying host or port anywhere in the repo. Treat the
repository as public: committed files never name private repositories, machines, servers, accounts
or where keys are kept.

**Never commit:** secrets, signing keys, `key.properties`, `local.properties`, `.dev/`, build
output, downloaded toolchains, upstream archives and raw data-source downloads (`.deps/`),
generated lexicons (they are build output), anything typed while testing (logs, learning
databases, clipboard dumps), or screenshots and assets of other keyboards (reference material stays
in `.dev/`).

## MyApps milestone releases

For each completed gate (G0–G5), increment the patch version by 0.0.1; the user decides when to
release. Documentation-only changes do not bump the version.

License: GPL-3.0 (`LICENSE`), as for the MyApps applications. Upstream code and data keep their own
licenses and notices (`THIRD_PARTY_NOTICES.md`, `doc/en-us/third-party.md`); releases ship the
license texts and the complete corresponding source. Generated lexicons keep the license of the
data they derive from.
