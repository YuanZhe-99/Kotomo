# The compiled cross-language lexicon

The cross-language lexicon maps what is typed in one language to Japanese words: pinyin to kanji
words, English words to loanwords and to words that mean the same. `lexicon/CrossLexicon.java`
generates it as two text tables; the app never reads those. A host tool compiles them into one
read-only file, `cross.klx`, which the mixed-input coordinator maps into memory and searches by
key. This is **format version 1** (`kLexiconVersion` in `core/mixed/cross_lexicon.h`).

## Build

```bash
java -Xmx4g lexicon/CrossLexicon.java    # build/lexicon/pinyin-ja.tsv and en-ja.tsv
cmake -S core/mixed -B build/host/mixed -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build/host/mixed --target kotomo_lexicon_compile
build/host/mixed/kotomo_lexicon_compile build/lexicon/pinyin-ja.tsv build/lexicon/en-ja.tsv build/lexicon/cross.klx
```

The compiler refuses a table with a bad line and names it (fewer than eight columns, an empty
key, surface or reading, a string over 255 bytes, a cost outside 0–65535, an unknown class),
then maps its own output back before it reports success. The app build packs
`build/lexicon/cross.klx` uncompressed as the asset `cross.klx`; without the file the app builds
with no cross-language conversion, and `KOTOMO_REQUIRE_LEXICON=1` makes its absence an error.
The file is build output and is never committed; it keeps the licenses of the data it derives
from (`third-party.md`).

## Layout

All integers are little-endian. The sections follow each other without padding.

| Section | Content |
|---|---|
| header, 32 bytes | `KOLX`; u32 version; u32 pinyin key count; u32 English key count; u32 entry count; u32 size of the strings section; u32 length of the longest key; u32 zero |
| pinyin keys | one record per key plus a sentinel: u32 offset of the key in the strings section, u32 index of the key's first entry. A key's entries end where the next record's begin; the sentinel holds the end of the last key's |
| English keys | the same, for the English → Japanese table |
| entries, 12 bytes each | u32 offset of the surface, u32 offset of the reading, u16 cost, u8 syllables, u8 class |
| strings | every distinct string once: one length byte, then that many UTF-8 bytes |

Keys are sorted bytewise within their table, so a lookup is a binary search over the key records;
a key's entries are sorted by cost, then surface. The pinyin table's entries come first, then the
English table's.

| Entry field | Meaning |
|---|---|
| surface | the Japanese word |
| reading | its reading in hiragana |
| cost | Mozc's word cost of the entry; lower is more common |
| syllables | for a pinyin key, how many syllables it has; 0 otherwise |
| class | 0 pinyin → Japanese; 1 English → a Japanese word that means the same; 2 English → its katakana loanword; 3 English → a made-up katakana spelling |

The part-of-speech ids of the text tables are not in the file: when a word enters a conversion as
a pinned span, Mozc's glue looks its ids up by reading and surface.

## Reading

The coordinator maps the file read-only (from the APK in place, at the asset's offset) and
parses nothing at start; a lookup touches only the pages it reads. The file is a boundary: a
reader checks the magic, the version and that the section sizes add up to the file's size when it
opens the file, and checks every offset, range and class when it follows them. A damaged file
finds nothing; it never reads outside the map. A reader refuses any other version.

## Versioning

Any change to the layout or to the meaning of a field raises the version in the compiler and the
reader in one change set; the file is rebuilt with the app, so no migration exists.
