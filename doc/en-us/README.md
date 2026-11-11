# Kotomo documentation

**These docs are the authoritative description of the code.** `doc/en-us/` is the source;
`doc/zh-cn/` mirrors it file for file. Plans and open work are not documented here; while the plan
is active it lives only in the repository's `PLAN.md`.

Kotomo is an Android input method for Chinese, English and Japanese. Pinyin, English and Japanese
romaji are typed on one keyboard, and in Mixed mode one continuous string holding all three
converts as a whole sentence. Kotlin carries the input method service, the keyboard and the
settings app; a C++ engine reached through JNI carries the mixed-input coordinator over librime
(Chinese), Mozc (Japanese), AOSP LatinIME's suggestion engine (English) and OpenCC.

| Document | Covers |
|---|---|
| [decisions.md](decisions.md) | Architecture decision records: what was decided, why, and what would reopen it |
| [upstream.md](upstream.md) | Upstream contracts and quirks: the engines, the Android IME platform, ML Kit, the data sets |
| [settings-format.md](settings-format.md) | The settings file: keys, defaults, versioning and how the keyboard reads it |
| [ai.md](ai.md) | Writing help: proofreading and rewriting with Gemini Nano, the hand-off, the rules it follows |
| [clipboard.md](clipboard.md) | The clipboard history: what is recorded, its encrypted file, verification codes, the quick-paste strip |
| [dictionaries.md](dictionaries.md) | Dictionaries: sources and priority, readings, import and export, the versioned files, packs and the online catalog |
| [engine-contract.md](engine-contract.md) | The engine ↔ app contract: one facade, one JNI crossing per key, the versioned snapshot, stale updates, how the coordinator reaches the engines |
| [lexicon-format.md](lexicon-format.md) | The compiled cross-language lexicon: how it is built, its binary layout, how it is read and checked |
| [third-party.md](third-party.md) | Reused code, data and assets, licenses and obligations; design acknowledgements |
| [platform-notes.md](platform-notes.md) | What is implemented and verified on each device and Android version, gate status |
| [development.md](development.md) | Environment, commands, the dev loop |
| [translation-guide.md](translation-guide.md) | Mirror rules and the glossary |
| [version-history.md](version-history.md) | Releases and the upstream versions they used |
| [functions/INDEX.md](functions/INDEX.md) | Per-module and per-file roles and key declarations |

## Upstream versions

Kotomo follows the latest release of each upstream, pinned by commit; `build-lock.json` records
what each build used. The table shows the latest version at the last check (2026-11-04).

| Component | Policy | Latest at last check |
|---|---|---|
| librime | latest GitHub release | 1.17.0 |
| Mozc | latest tag (Mozc publishes no GitHub releases) | 3.35.6354 |
| OpenCC | latest GitHub release | 1.4.2 |
| English engine: AOSP LatinIME's native code in HeliBoard's maintained copy | latest HeliBoard release | HeliBoard 4.2 |
| Android platform | newest stable API as `compileSdk` and `targetSdk` | API 37 (Android 17) |
| Android Gradle Plugin, Gradle, Kotlin, NDK | latest stable | 9.5.1, 9.9.1, 2.4.21, r30 (30.0.16248370) |
| Compose BOM, Room, DataStore | latest stable | 2026.09.00, 2.8.5, 1.3.0 |
| ML Kit GenAI proofreading, rewriting and prompt; ML Kit translation | latest | 1.0.0-beta1, 1.0.0-beta1, 1.0.0-beta5; 17.0.3 |
| JMdict (through jmdict-simplified), Unihan, CLDR | latest published release | 3.6.2+20261109214828; Unicode 18.0.0; CLDR 49 |
| Noto Emoji, Twemoji | latest release | v2026-09-24-unicode18_0; 17.0.3 |
