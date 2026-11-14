# Dictionaries

The person's own words, imported files and downloaded dictionary packs (PLAN §10.5), as built in
`app/src/main/java/com/yuanzhe/kotomo/dictionary/` and the Dictionaries settings page
(`settings/DictionariesPage.kt`). The engine side, librime's three run-time tables, is in
`core/rime/data/luna_pinyin_simp.custom.yaml`.

## Sources and priority

| Source | What it is | librime table | Quality | Switch |
|---|---|---|---|---|
| Built-in | luna_pinyin, Mozc's and the English engine's dictionaries shipped in the APK | the schema's own | — | none: always on |
| Added by hand (`manual`) | words typed in on the Dictionaries page | `custom_phrase.txt` | 1 | Your dictionary |
| Imported (`import`) | words from a file the person picked | `kotomo_low.txt` | −1 | Your dictionary |
| Made with an AI model (`generated`) | words from a file marked as LLM output | `kotomo_low.txt`, priority at most 20 | −1 | Your dictionary |
| Downloaded (`download`) | dictionaries downloaded from their sources and converted on the phone | `kotomo_packs.txt` | 0 | Downloaded dictionaries, and one switch per pack |

Untrusted words rank low: an imported or generated word never reaches `custom_phrase.txt`,
whatever its file says, and a file's own claims about where its words came from are ignored.
Each table line is `word<TAB>pinyin without spaces<TAB>weight`; an empty table is an empty file.
Only Chinese entries reach librime; Japanese and English entries are stored and marked "not used
yet" until Mozc's user dictionary and the English engine read them.

## Entries and readings

An entry has a language (`zh`, `ja`, `en`), a word, a reading, a weight from 0 to 100 and a
source, and keeps any other field it arrived with (romaji, tags, emoji). Two entries are the same
when language, word and reading match.

- The word is trimmed and NFC-normalized; it is refused when empty, longer than 64 characters or
  holding control characters, line breaks or U+FFFD.
- Chinese readings are toneless pinyin: lowercase, tone marks and tone digits removed, ü and `u:`
  written `v`, spaces and apostrophes as boundaries; every part must split into syllables
  luna_pinyin knows (the split with the fewest syllables wins: `xian` is one, `xi'an` two). A word
  written only in Han characters needs one syllable per character. Stored with spaces
  (`ni hao`).
- Japanese readings are kana only; katakana becomes hiragana and the long-vowel mark ー stays.
- English entries have no reading.

## Import

| Format | Layout |
|---|---|
| TSV | one entry per line; an optional header names the columns (`language`, `text` or `word`, `reading`, `weight`; others are kept); without one the columns are language, text, reading, weight |
| CSV | RFC 4180 (quoted fields may hold commas, doubled quotes and line breaks), with the same columns and optional header |
| JSON | the fields of the LLM prompt (PLAN appendix B): an object whose `entries` hold `language`, `text` (or `word`), `reading`, `weight` and any other members; a `format_version` newer than 1 refuses the file |
| Rime `*.dict.yaml` | the lines after `...`: `word<TAB>spaced pinyin[<TAB>weight]`, Chinese |

- The file is decoded as strict UTF-8; a BOM is removed; invalid bytes or U+FFFD refuse the whole
  file. Files over 16 MB are not read.
- Lines that fail a check are refused and counted; the preview lists the first five with their
  line numbers (a JSON entry's position stands in for its line; a CSV record's line is where it
  starts).
- A line repeating an earlier one is counted and skipped. An entry already in the dictionary is a
  conflict: counted in the preview, and the existing entry is kept unless the person picks the
  file's.
- Weights: missing is 50; when the file's largest weight exceeds 100, every weight is first scaled
  by its logarithm against that maximum, then clamped to 0–100; LLM files are capped at 20.
- The preview shows how many words would be added, refused, skipped and in conflict; nothing is
  stored before the person confirms. JSON files start with "Made with an AI model" on, other
  formats with it off; lines that name no language take the language chosen in the preview.

## Export

TSV, CSV and JSON hold every entry with the header or format version; the Rime format holds the
Chinese entries. The source is never written, so a file exported and imported again is untrusted
like any other.

## Files

All under the app's credential-protected storage; written through a temporary file and an
atomic rename, on the IO dispatcher under one lock.

| Path | Content |
|---|---|
| `files/dictionaries/user.json` | the person's entries |
| `files/dictionaries/user.prev.json` | the version before the last change; "Restore the previous version" swaps the two |
| `files/dictionaries/user.kenc`, `user.prev.kenc` | the same two, sealed, while password protection is on (instead of the plain files) |
| `files/dictionaries/state.json` | the switches |
| `files/dictionaries/packs/<id>/` | an installed pack: `entries.tsv` (normalized), `table.txt` (its librime lines) and `pack.json` |
| `files/dictionaries/packs/<id>.prev/` | the pack's previous version, for rollback |
| `files/rime/user/custom_phrase.txt`, `kotomo_packs.txt`, `kotomo_low.txt` | librime's tables, rewritten after every change |

`user.json`:

| Member | Meaning |
|---|---|
| `format_version` | 1; a file from a newer version makes the page read-only and is never overwritten |
| `updated_utc_ms` | last write, UTC milliseconds |
| `entries` | objects with `language`, `text`, `reading` (not for English), `weight`, `source` and any other fields, kept |

Other members are kept. An entry that fails the checks (a damaged or hand-edited file) is kept
verbatim and written back unchanged.

`state.json`:

| Member | Meaning |
|---|---|
| `format_version`, `updated_utc_ms` | as above |
| `custom_enabled` | the person's own words (hand-added, imported, generated); default `true` |
| `downloads_enabled` | all downloaded packs; default `true` |
| `packs` | `{"<id>": {"enabled": true}}` per installed pack |
| `online_accepted` | the version of the download notice the person accepted; missing until then |
| `protection` | while password protection is on: `kdf`, `iterations`, `salt` and `check` (below) |

## Password protection

Off by default (ADR-012). Protect with a password encrypts the person's words with a key derived
from a password they choose; Kotomo keeps neither the password nor the key on disk, and nothing
recovers a forgotten password (the dialog says so).

- `user.json` and `user.prev.json` become `user.kenc` and `user.prev.kenc`; the plain files are
  deleted. Every later change is written sealed. Turn protection off writes the plain files back
  and deletes the sealed ones; it needs the dictionary unlocked.
- The key: PBKDF2 with HMAC-SHA256, 600,000 rounds, a random 16-byte salt, 256 bits; each file is
  AES-256-GCM with a fresh 12-byte IV and a 128-bit tag, the header as associated data.
  `state.json` keeps `protection` (`kdf`, `iterations`, `salt`, `check`: a small sealed text that
  tells a right password from a wrong one).
- After the keyboard's process starts the dictionary is locked: the page shows nothing of the
  words and changes nothing until Unlock. librime's tables written at the last unlock stay, so
  the keyboard still types the person's words; Lock now empties `custom_phrase.txt` and
  `kotomo_low.txt` until the next unlock. Those tables must be plain text for librime, so while
  the keyboard can use the words they are readable in the app's private storage.
- While protected an export is sealed the same way (`kotomo-dictionary.<format>.kenc`); importing a
  sealed file asks for its password and reads the format named in its header.

```text
offset  size  content
0       4     magic "KDIC"
4       1     container version (1)
5       4     PBKDF2 rounds (big-endian)
9       1     salt length (16), then the salt
26      1     kind length, then the kind (UTF-8: "user", "check" or an export's format)
…       12    AES-GCM IV
…       n     ciphertext and 128-bit tag; associated data = every byte before the IV
```

A file with a newer container version is never opened or overwritten: the page is read-only.

## Downloaded dictionaries

Settings → Dictionaries → Get dictionaries lists the sources below (`dictionary/Sources.kt`,
ADR-011). Kotomo hosts nothing: a download fetches the source's own files from where its authors
publish them and converts them on the phone, in the background (`DictionaryDownloads`), with the
same code the host tests run. Every converted line goes through the checks of an import (word,
reading, syllable count, encoding, weight); lines that fail are dropped and counted per reason.

### Offered

| Pack | Files | Checksum | Words (host, pinned files) |
|---|---|---|---|
| `zh-web-slang` | fcitx5-pinyin-zhwiki's latest release, `web-slang-<date>.dict.yaml` (9 KB) | the SHA-256 digest GitHub publishes for the asset | 399 (24 spelled-out Latin letters dropped) |
| `zh-thuocl-it`, `-animal`, `-finance`, `-car`, `-idioms`, `-places`, `-food`, `-law`, `-people`, `-medical`, `-poetry` | THUOCL `data/THUOCL_<domain>.txt` (0.1–1 MB each) and rime-ice's `cn_dicts/8105.dict.yaml` (0.1 MB) from `raw.githubusercontent.com` | none: HTTPS only | 1,602 to 43,343 (IT 11,565) |
| `en-ice-tech` | rime-ice's latest release, `en_dicts.zip` (0.2 MB): `en_ext.dict.yaml` | GitHub's digest | 2,328 (stored, not used yet) |

THUOCL has no pinyin. A word's reading is made from rime-ice's character list: a character's most
frequent reading counts only when it is at least ten times as frequent as its next one, so a word
with a true polyphone (行 xing / hang) is dropped as ambiguous rather than given a wrong reading
(IT: 1,458 ambiguous, 2,976 with letters or characters the list lacks). The host numbers come from
`DictionaryPacksTest` with `KOTOMO_PACKS=1` over the files pinned in `build-lock.json`; a device
downloads the sources' current versions.

### Evaluation

Every source PLAN §10.5 approved, checked on 2026-11-11 (the counts are what the Q6 conversion kept):

| Source | What a phone would download | Checksum published | On a phone | Offered |
|---|---|---|---|---|
| rime-ice `base`, `ext` | release asset `cn_dicts.zip` 14.7 MB | GitHub's SHA-256 digest | base: 543,423 words converted on the emulator in 38–44 s within the 192 MB heap (PSS 232 MB); but librime loads a downloaded pack as a text table at every schema selection, and the keyboard typed again only 25 s after the install | no: waits for compiled pack tables |
| rime-ice `en_ext`, `8105` | release asset `en_dicts.zip` 0.2 MB; raw `8105.dict.yaml` 0.1 MB | `en_dicts.zip`: digest; `8105`: none | instant | yes (`en-ice-tech`; `8105` for THUOCL's readings) |
| rime-frost | release bundles of 44 MB and 88 MB; raw `base` and `ext` 10 MB | bundles: digest; raw: none | possible, but it overlaps rime-ice | no |
| fcitx5-pinyin-zhwiki `zhwiki` | `zhwiki-<date>.dict.yaml` 57.7 MB | digest | 1.7 million lines: too large to convert and keep | no |
| fcitx5-pinyin-zhwiki `web-slang` | 9 KB | digest | instant | yes |
| THUOCL | 11 text files, 0.1–1 MB | none | under a second each | yes |
| SudachiDict | `small_lex.zip` 13.6 MB, `core_lex.zip` 21.7 MB (Amazon S3) | none | 0.77 and 0.86 million rows, 100 and 166 MB unpacked; feasible only with streaming | no: Japanese words are not used until Mozc's user dictionary reads them |
| mecab-ipadic-neologd | `seed/*.csv.xz` 41 MB | none | xz has no decoder on Android; 3.2 million rows; unmaintained | no |
| mozcdic-ut | `*.txt.bz2` 0.7–12 MB | none | bzip2 has no decoder on Android; Japanese not used yet | no |
| SCOWLv2 | `rel-<date>.tar.gz` 2.4 MB of sources | none | a word list needs Python and SQLite (`make`, `scowl word-list`) | no |

### Download and checks

- Only HTTPS, redirects included; a plain `http://` address or a redirect to one is refused.
  GitHub's release API (`api.github.com`) names the latest release's asset, its size and its
  SHA-256 digest; the download must match both, and an asset without a digest is refused. A plain
  file has no published checksum: the page says so, and only HTTPS protects it.
- At most 64 MB is downloaded per file and 256 MB unpacked from an archive; the sizes are counted
  while reading, not trusted. A file that ends before the length its server announced is a network
  failure, however the connection closed; one that matches that length but not the published size
  is a size failure.
- Before the first download the notice names the hosts (`api.github.com`, `github.com`,
  `release-assets.githubusercontent.com`, `raw.githubusercontent.com`), says that only dictionary
  files are downloaded and nothing typed is sent; without accepting it nothing connects. Its
  version is kept in `state.json` (`online_accepted`); a notice that says something new gets a new
  version and is asked again.
- A download shows its progress and can be cancelled. It works in the app's cache and leaves the
  installed version untouched when it is cancelled or fails (network, size, checksum, unreadable
  file, no usable words, storage); the files are deleted afterwards.
- The converted words are written in one pass (never held as entry objects) as the pack's
  `entries.tsv` (the normalized TSV, header `language text reading weight`), its librime table
  `table.txt` and `pack.json`; the previous version moves to `packs/<id>.prev/`. Weights follow the
  import rule (logarithm against the largest frequency above 100; 50 without one).

```text
language	text	reading	weight
zh	你好	ni hao	90
```

`pack.json` holds `id`, `titles` (`en`, `zh-CN`, `zh-TW`, `ja`), `language`, `category`,
`license`, `attribution`, `version` (the release asset's date, or the day of the download),
`checked` (whether a published checksum was compared), `count`, `license_url`, `dropped` (lines
dropped by reason), `notice` (THUOCL's citation) and any member this version does not know. A
`pack.json` missing a required member hides the pack.

## The keyboard

The keyboard and the settings share one `DictionaryStore` per process. After every change the
store rewrites the three tables (the packs' one by joining the enabled packs' `table.txt`, so a
change never parses a pack again) and raises its generation; the keyboard then re-selects librime's
schema, which reloads the tables (host test T17-rime). Before the first unlock the keyboard reads
none of these files. The sensitive-field schema reads the same tables; nothing is learned into
them.

## Tests

`FormatsTest` (readings, encoding, each format, duplicates, conflicts, weights, preview, export),
`DictionaryStoreTest` (versioned files, rollback, newer files, switches, the tables, packs and
their `pack.json`, the notice), `ProtectionTest` (the sealed format, wrong passwords, changed bytes,
newer versions; no plain words on disk, locked after a restart, Lock, unlock, turning it off,
sealed exports), `SourcesTest` (each source format on tiny original samples:
words kept, readings, weights, lines dropped by reason; the offered sources), `DownloadsTest`
(a local HTTPS server with its own certificate through the real transport: release digests and
sizes, HTTPS-only redirects, missing files, damaged archives, cancellation, the installed version
kept on failure), `DictionaryRimeTest` and `DictionaryPacksTest` (the store's tables and a
converted pack in the host librime, ranked by trust; with `KOTOMO_PACKS=1` every offered source
converted from the pinned files), through `tests/conversion/run.sh`; on the emulator
`DictionaryKeyboardTest` and `DictionaryDownloadTest` (a real download typed on the keyboard).

## Known gaps

- Built-in dictionaries have no switch; the keyboard cannot type without them.
- `user.json` is encrypted only when the person turns password protection on (user decision
  2026-11-11); librime's tables are plain text whenever the keyboard can use the words.
- Japanese and English entries are not used yet.
- Only GitHub release assets come with a published checksum; THUOCL and rime-ice's character
  list are protected by HTTPS alone.
- Japanese sources are not offered until Mozc's user dictionary reads Japanese entries.
- A device never verifies a pack against a pinned hash: sources update, and `build-lock.json`
  pins only what the host tests read.
