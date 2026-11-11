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
| Downloaded (`download`) | packs installed from the online catalog | `kotomo_packs.txt` | 0 | Downloaded dictionaries, and one switch per pack |

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
| `files/dictionaries/state.json` | the switches |
| `files/dictionaries/packs/<id>/` | an installed pack: `entries.tsv` (normalized) and `pack.json` (its catalog entry) |
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

## Packs and the online catalog

The catalog is JSON with `format_version` 1 (any other version refuses it) and `packs`; members it
does not know are kept. Every pack member is required; a pack missing one, or with an invalid
value, is refused and counted:

| Member | Check |
|---|---|
| `id` | lowercase letters, digits, `_` and `-`, at most 64 (it names a directory) |
| `titles` | an object of interface language (`en`, `zh-CN`, `zh-TW`, `ja`) to title |
| `language`, `category`, `license`, `attribution`, `version` | non-empty; `language` is `zh`, `ja` or `en` |
| `size`, `sha256` | the compressed file's bytes (at most 64 MB) and its SHA-256 in lowercase hex |
| `url` | HTTPS |
| `format` | `tsv.gz` |

A pack is the gzip-compressed TSV with the header, normalized readings and weights 0–100, which
the dictionary-pack generator (Q6) writes:

```text
language	text	reading	weight
zh	你好	ni hao	90
```

A download reads at most the catalog's size, reports progress, and is refused when the size or the
SHA-256 differs; the pack is then unpacked (at most 256 MB) and read like an imported TSV in its
language, lines that fail or name another language dropped and counted, and installed with the
previous version kept. Signatures are not checked yet (PLAN §2.5 decision 9).

Online dictionaries are not available: where the catalog is hosted and how the first download is
disclosed wait for the user (PLAN §2.5 decisions 2 and 10). `Online.CATALOG_URL` is null, the app
has no network transport of its own (`Transport` is an interface the tests implement), and the page
says that nothing is downloaded.

## The keyboard

The keyboard and the settings share one `DictionaryStore` per process. After every change the
store rewrites the three tables and raises its generation; the keyboard then re-selects librime's
schema, which reloads the tables (host test T17-rime). Before the first unlock the keyboard reads
none of these files. The sensitive-field schema reads the same tables; nothing is learned into
them.

## Tests

`FormatsTest` (readings, encoding, each format, duplicates, conflicts, weights, preview, export),
`DictionaryStoreTest` (versioned files, rollback, newer files, switches, the tables, packs),
`OnlineTest` (catalog, download checks with a fake transport answering by the full URL, the
original sample packs in `app/src/test/resources/dictionary/`, ten-odd words per language),
`DictionaryRimeTest` (the store's tables fed to the host librime, through `tests/conversion/run.sh`).

## Known gaps

- Built-in dictionaries have no switch; the keyboard cannot type without them.
- `user.json` is not encrypted (PLAN §14 asks for encrypted personal data); librime reads its
  tables as plain text anyway.
- Japanese and English entries are not used yet.
- No online download until the user decides the hosting and the first-use disclosure.
