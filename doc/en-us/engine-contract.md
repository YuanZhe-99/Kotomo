# Engine ↔ app contract

How the app and the native engine talk: what goes in, what comes out, and how the mixed-input
coordinator (`core/mixed/`) reaches the three engines. This is **contract version 1**
(`kContractVersion` in `core/mixed/snapshot.h`). The output side and the coordinator run on the
development host (spike S6, `platform-notes.md`); the app does not use the contract yet: its three
facades (`engine/Rime.kt`, `Mozc.kt`, `English.kt`) still call the three libraries directly, and
the device side is wired with the coordinator's integration. The decisions behind it are ADR-008
and ADR-009 (`decisions.md`).

## Shape

- One Kotlin facade over one native library, called only on the engine thread. The coordinator is
  that library; it reaches librime, Mozc and the English core through plain C functions those
  libraries export, so one key is one JNI crossing whatever the number of engines it asks.
- In: a key event or a command with the generation the app counts. Out: one snapshot, encoded as
  bytes. No JSON and no per-candidate JNI calls.
- Nothing in the contract learns. Learning happens on commit, in a later call, and never for a
  sensitive field.

## Input

| Call | Carries | Version 1 |
|---|---|---|
| convert | the whole composition (letters, with spaces and apostrophes as boundary hints) and the generation | implemented on the host: `Coordinator::Convert` |
| key | one character with its touch coordinates, for key correction | not yet: version 1 hands over the whole composition |
| select candidate, select piece, lock piece, partial commit | the candidate or piece index within the snapshot of a generation | not yet; the snapshot already carries the pieces these commands address |
| undo conversion, restore raw input, clear | — | not yet |
| set mode and options | the mode, the level, one switch per cross-language conversion | host: `Options` at construction |
| context | the text before the cursor; never sent for a sensitive field | not yet |

A call that the engine cannot serve (a composition with characters outside letters and
delimiters, or longer than 256 bytes) returns a snapshot with the composition and no candidates.

## Output: the snapshot

One snapshot describes what the app shows after one update: the composition, the cursor and the
candidates, best first. Every candidate covers the whole composition and keeps its pieces: the
span of the composition each piece stands for, its text, its reading, the language the span was
typed in and where the text came from. The pieces are what a later edit locks or changes, so the
other pieces are never rewritten.

All integers are little-endian. A string is a `u16` byte length followed by UTF-8 bytes.

| Field | Type | Meaning |
|---|---|---|
| magic | 2 bytes | `KS` |
| version | u16 | the contract version; a decoder refuses any other |
| generation | u64 | the app's count of keys and commands this snapshot answers |
| stage | u8 | 0 for the first candidates of a generation, 1 and up for later stages |
| input | string | the composition as typed |
| cursor | u16 | byte offset of the cursor in the composition |
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

A decoder checks everything: the magic, the version, every length against the buffer, the
languages and origins against their ranges, every piece range against the composition, and that
no byte is left over. A buffer that fails any check is dropped whole.

The unified candidate of the plan maps onto it as follows: text, target language and cost are
candidate fields; the raw input range, the source language, the reading, the dictionary source
and the segmentation are the pieces; fuzzy, cross-language and emoji are flags. Match confidence
is the cost.

## Stale updates

The app keeps the generation and stage of the snapshot on screen. A new snapshot replaces it only
when its generation is higher, or when the generation is the same and its stage is higher
(`Replaces` in `core/mixed/snapshot.h`). Anything else is dropped unread: a slow answer to an old
key never overwrites the answer to a newer one, in every language alike.

## The engines behind the coordinator

The coordinator links no engine. It asks five questions (`core/mixed/engines.h`); on the device
each is one C function an engine library exports next to its JNI entry points, and on the host
`core/mixed/host/` answers them.

| Question | Answered by | On the host |
|---|---|---|
| which pinyin syllables does the start of these letters spell | librime's spelling table (prism) of the deployed schema, so fuzzy rules and corrections apply as in Chinese mode | `core/rime/jni/rime_spellings.h`, linked in |
| convert this run of syllables | a librime session with the syllables joined by apostrophes | `core/rime/jni/rime_bridge.h`, linked in |
| map this romaji to hiragana | Mozc's romaji table | `kotomo_mozc_service`, request `kana` |
| convert this reading with these spans pinned | Mozc's converter (`MozcEngine::PinnedConvert`) | `kotomo_mozc_service`, request `convert` |
| is this an English word, and how common | the English dictionary, exact lookup | the LatinIME core, linked in |

No answer keeps state between calls and none learns. On the host Mozc runs in its own process
behind a line protocol, because Mozc builds only inside its own Bazel workspace; the protocol is
described at the top of `core/mozc/kotomo_mozc_service.cc` and is not part of this contract.

## Versioning

Any change to a field, its encoding, a flag or an origin value raises the contract version on
both sides in one change set, with their tests. A side that reads another version drops the
snapshot; nothing is guessed across versions.
