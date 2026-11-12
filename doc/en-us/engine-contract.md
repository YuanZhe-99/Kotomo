# Engine ↔ app contract

How the app and the native engine talk: what goes in, what comes out, and how the mixed-input
coordinator (`core/mixed/`) reaches the three engines. This is **contract version 3**
(`kContractVersion` in `core/mixed/snapshot.h`, `Snapshot.VERSION` in `engine/Snapshot.kt`). Both
sides exist: the engine side is `libkotomo_core.so` (`core/mixed/session.h`, `jni/core_jni.cc`),
the app side is the facade `engine/Core.kt` with the decoder `engine/Snapshot.kt`. Mixed mode
runs on it; Chinese, Japanese and English mode still call their engine's own facade
(`engine/Rime.kt`, `Mozc.kt`, `English.kt`). The decisions behind it are ADR-008 and ADR-009
(`decisions.md`).

## Shape

- One Kotlin facade over one native library, called only on the engine thread. The coordinator is
  that library; it reaches librime, Mozc and the English core through plain C functions of those
  libraries, so one key is one JNI crossing whatever the number of engines it asks.
- In: one request, a key or a command with the generation the app counts, encoded as bytes. Out:
  one snapshot, encoded as bytes. No JSON and no per-candidate JNI calls.
- The engine holds the composition between requests: the letters and delimiters typed, where each
  key was touched, the options and the context.
- Nothing in the contract learns. A selection only says what to commit; learning happens on
  commit, in a later call, and never for a sensitive field.

## Input: the request

All integers are little-endian. A string is a `u16` byte length followed by UTF-8 bytes.

| Field | Type | Meaning |
|---|---|---|
| magic | 2 bytes | `KR` |
| version | u16 | the contract version; the engine refuses any other |
| generation | u64 | the app's count of keys and commands; the snapshot returns it |
| command | u8 | one of the codes below |
| fields | | the command's own, in the order listed |

| Code | Command | Fields | Effect |
|---|---|---|---|
| 0 | set | string: the composition | replaces the whole composition (a mode switch hands its letters over); cut at 256 bytes |
| 1 | key | u32 code point; i16 x, i16 y: the touch in the English engine's 1000 × 600 layout space, −1 for none | appends a letter, or a space or apostrophe as a boundary hint; any other character and a key past 256 bytes change nothing |
| 2 | backspace | — | deletes the last character |
| 3 | clear | — | drops the composition without committing |
| 4 | options | u8 level (0 conservative, 1 balanced, 2 aggressive); u8 switches; u8 targets; i8 × 3 weights for Chinese, Japanese, English | the switches' bits: 0 pinyin → Japanese, 1 English → Japanese kanji words, 2 English → katakana, 3 cross-language words inside a longer composition (off: only as the whole composition), 4 the field must leave no trace (Mozc converts incognito). The targets' bits name the languages candidates may be written in: 0 Chinese, 1 Japanese, 2 English; Mixed mode sets all three, Japanese mode only Japanese and takes the cross-language candidates (version 3). A weight says how far the person moved that language's weight from its default, −100…100; each point is worth 40 cost units for that language's candidates |
| 5 | context | string: the text before the cursor | kept for ranking by context; nothing reads it yet. The app never sends it for a sensitive field |
| 6 | select | u64: the generation of the snapshot on screen; u16: a candidate's index in it | commits that candidate: the snapshot's commit field holds its text and the composition is empty. When the engine's last snapshot is another one, or has no such candidate, nothing is committed |

Every request is answered with a snapshot of the composition it leaves. A request the engine
cannot decode (another version, an unknown command, level or switch bit, a weight out of range, a
truncated or overlong buffer) gets no snapshot and changes nothing. A composition the coordinator
cannot convert (no letters, or characters other than letters and delimiters) gets a snapshot with
the composition and no candidates.

Not in version 3: choosing or locking one piece, committing part of a composition, undoing a
conversion and restoring the raw input (the snapshot already carries the pieces those commands
address), and moving the cursor inside the composition. Each arrives with a new contract version.

## Output: the snapshot

One snapshot describes what the app shows after one request: text to commit first, the
composition, the cursor and the candidates, best first. Every candidate covers the whole
composition and keeps its pieces: the span of the composition each piece stands for, its text,
its reading, the language the span was typed in and where the text came from. The pieces are
what a later edit locks or changes, so the other pieces are never rewritten.

| Field | Type | Meaning |
|---|---|---|
| magic | 2 bytes | `KS` |
| version | u16 | the contract version; a decoder refuses any other |
| generation | u64 | the request's generation |
| stage | u8 | 0 for the first candidates of a generation, 1 and up for later stages |
| input | string | the composition as typed |
| cursor | u16 | byte offset of the cursor in the composition |
| commit | string | text this request commits to the field; empty for most requests |
| candidate count | u16 | then that many candidates |
| candidate: text | string | what choosing it commits |
| candidate: target | u8 | the language it is written in: 0 Chinese, 1 Japanese, 2 English |
| candidate: cost | i32 | the engine's cost, lower first; comparable only within one snapshot |
| candidate: flags | u8 | bit 0 cross-language, bit 1 fuzzy or corrected, bit 2 emoji, bit 3 whole composition |
| candidate: piece count | u16 | then that many pieces, in input order |
| piece: begin, end | u16, u16 | the byte range of the composition the piece stands for |
| piece: text | string | what the piece commits |
| piece: reading | string | hiragana for a Japanese piece, otherwise the letters typed |
| piece: typed | u8 | the language the span was typed in (0, 1, 2 as above) |
| piece: origin | u8 | 0 librime, 1 Mozc, 2 English dictionary, 3 pinyin → Japanese lexicon, 4 English → Japanese lexicon, 5 a coordinator rule (the particles は, を, へ) |
| piece: pinned | u8 | 1 when the piece entered Mozc as a fixed value |

A decoder checks everything: the magic, the version, every length against the buffer, that every
string is UTF-8, the languages and origins against their ranges, the cursor and every piece range
against the composition, and that no byte is left over. A buffer that fails any check is dropped
whole.

The unified candidate of the plan maps onto it as follows: text, target language and cost are
candidate fields; the raw input range, the source language, the reading, the dictionary source
and the segmentation are the pieces; fuzzy, cross-language and emoji are flags. Match confidence
is the cost. Emoji are still added by the app (`Candidates.mixed`), after the engine's candidates
at the place the emoji weight gives; no snapshot carries one yet.

## Stale updates

The app keeps the generation and stage of the snapshot on screen. A new snapshot replaces it only
when its generation is higher, or when the generation is the same and its stage is higher
(`Replaces` in `core/mixed/snapshot.h`, `Snapshot.replaces` in the app). Anything else is dropped
unread: a slow answer to an old key never overwrites the answer to a newer one, in every language
alike. A selection names the generation it was made on for the same reason.

## The engines behind the coordinator

The coordinator links no engine and resolves no symbol of another library. It asks five
questions (`core/mixed/engines.h`). Each engine library holds a static table of plain C functions
that answer its questions and returns the table's address through a JNI getter (`Rime.api()`,
`Mozc.api()`, `English.api()`); `Core.open` passes the addresses, with Mozc's and the English
engine's handles, to the coordinator's library, which calls through them
(`core/mixed/device/api_engines.h`). Every table starts with a version, and the coordinator
refuses one it was not built for. The libraries still export nothing but their JNI entry points.
An engine that is not there (a build without Mozc) is a missing table: its questions find
nothing and the other languages keep working.

| Question | Answered by | Table |
|---|---|---|
| which pinyin syllables does the start of these letters spell | librime's spelling table (prism) of the session's schema, mapped when the session opens, so fuzzy rules and corrections apply as in Chinese mode | `core/rime/jni/kotomo_rime_api.h`: `spellings` |
| convert this run of syllables | librime's session with the syllables joined by apostrophes | `kotomo_rime_api.h`: `convert` |
| map this romaji to hiragana | Mozc's romaji table | `core/mozc/kotomo_mozc_api.h`: `hiragana` |
| convert this reading with these spans pinned | Mozc's converter (`MozcEngine::PinnedConvert`) | `kotomo_mozc_api.h`: `convert` |
| is this an English word, and how common | the English dictionary, exact lookup | `core/english/jni/english_api.h`: `lookup` |

No answer learns, and none keeps state between calls except librime's: the coordinator converts
in librime's one session, replacing and clearing its composition, so Mixed mode keeps its
composition in the coordinator and Chinese mode's composition is never open at the same time.
On the host the conversion corpus loads the four libraries into one JVM and crosses the same
boundary (`tests/conversion/run.sh`); the coordinator's own corpus runner links librime and the
English core and reaches Mozc, which builds only inside its Bazel workspace, through a line
service (`core/mozc/kotomo_mozc_service.cc`), which is not part of this contract.

## Versioning

Any change to a field, its encoding, a command, a flag or an origin value raises the contract
version on both sides in one change set, with their tests. A side that reads another version
drops the buffer; nothing is guessed across versions. The engines' function tables carry their
own versions (`KOTOMO_RIME_API_VERSION` and its siblings), raised with any change to a table.
