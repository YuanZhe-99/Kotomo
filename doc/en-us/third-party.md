# Third-party code, data and assets

Kotomo is licensed under GPL-3.0. This inventory lists every upstream it reuses or embeds — code,
dictionary data, emoji assets and fonts — what it uses and what that obliges.
`THIRD_PARTY_NOTICES.md` holds the notices themselves; the in-app licenses page lists the same
components.

## Inventory

| Component | Repository and version | Used for | License | Modifications | Obligations |
|---|---|---|---|---|---|
| MyApps conventions and documentation checks | `YuanZhe-99/Kyokai` (`tools/checkdocs`), `YuanZhe-99/MyApps-AI` (`tool/check_docs.py`), the MyApps `AGENTS.md` rules | `tool/CheckDocs.java` follows their mirror and link rules; `AGENTS.md` follows their conventions | AGPL-3.0 and GPL-3.0, same author as Kotomo | written anew for Kotomo | none beyond Kotomo's license |
| Mozc dictionary data (`src/data/dictionary_oss`: `dictionary00–09.txt`, `id.def`, `README.txt`) | `google/mozc` tag 3.35.6354 | entries, readings, POS ids and costs for the cross-language lexicon (S5; build input, the generated lexicon is not shipped yet) | IPAdic-derived (mecab-ipadic-2.7.0-20070801, NAIST and ICOT terms), Okinawa dictionary data, Google additions | none | ship `README.txt`'s notices and disclaimers with any lexicon derived from it |
| Unihan | Unicode 18.0.0 (`Unihan.zip`) | readings (`kMandarin`, attested by `kXHC1983`, `kTGHZ2013`, `kHanyuPinyin`), `kSimplifiedVariant`, `kJapaneseOldVariant` for the pinyin → Japanese mapping (S5) | Unicode License v3 | none | include the Unicode copyright and license notice |
| CC-CEDICT | MDBG release 2026-11-04T07:19:40Z | word-level pinyin for the pinyin → Japanese mapping (S5) | CC BY-SA 4.0 (MDBG; based on CEDICT by Paul Andrew Denisowski) | none | attribution and a link to the license; derived readings stay CC BY-SA 4.0 |
| OpenCC Japanese Shinjitai tables (`JPShinjitaiCharacters.txt`, `JPShinjitaiPhrases.txt`) | `BYVoid/OpenCC` ver.1.4.2 | Shinjitai → old forms for the pinyin → Japanese mapping (S5) | Apache-2.0 | none | include the license and NOTICE |
| JMdict (English), through jmdict-simplified | jmdict-simplified 3.6.2+20261102210542 (JMdict 2026-11-02) | glosses for the English → Japanese mapping (S5) | CC BY-SA 4.0 under the EDRDG licence | none | credit the EDRDG as copyright holder on a separate sources page reached from a menu, link the licence and the JMdict project page, keep the data updated; the derived mapping stays CC BY-SA 4.0 |
| AOSP LatinIME native suggestion core, through HeliBoard | `HeliBorg/HeliBoard` v4.2 (`0c60dffb`), `app/src/main/jni/src` | English correction, completion and prediction (`libkotomo_english.so`, S4) | Apache-2.0 (AOSP files); HeliBoard as a whole GPL-3.0 | none; Kotomo's own JNI bridge replaces LatinIME's JNI files | ship the Apache-2.0 license text and keep the AOSP notices |
| AOSP LatinIME dicttool (build tool only) | AOSP `platform/packages/inputmethods/LatinIME` `127336e9` | builds the English dictionary at build time; not shipped | Apache-2.0 | none | none for the app |
| English word list: Helium314/aosp-dictionaries `main_en_US__experimental.combined`, derived from the Leipzig Corpora Collection | codeberg `Helium314/aosp-dictionaries` `ff0cd52b` | English vocabulary and bigrams (`main_en_us.dict`, generated, shipped in the APK) | CC BY 4.0 (Leipzig: corpora for download are licensed under CC BY) | converted to LatinIME binary format 202 | attribution to the Leipzig Corpora Collection (Universität Leipzig) and to HeliBoard's aosp-dictionaries on the sources screen, with a link to CC BY 4.0 and a note that the data was converted |
| librime | `rime/librime` 1.17.0 (`33e78140`); bundles darts-clone and utf8-cpp headers | Chinese engine (`libkotomo_rime.so`, S2) | BSD-3-Clause (darts-clone BSD-2-Clause, utf8-cpp BSL-1.0) | none; built without plugins and logging | keep the copyright and license notices |
| LevelDB | `google/leveldb` 1.23 (`99b3c03b`) | librime's user dictionary | BSD-3-Clause | none | notice |
| marisa-trie | `s-yata/marisa-trie` v0.3.1 (`3e87d53b`) | librime and OpenCC tries | BSD-2-Clause or LGPL-2.1-or-later, used under BSD-2-Clause | none | notice |
| yaml-cpp | `jbeder/yaml-cpp` 0.9.0 (`56e3bb55`) | librime configuration | MIT | none | notice |
| OpenCC | `BYVoid/OpenCC` ver.1.4.2 (`025f371d`); bundles rapidjson and darts-clone | Traditional → Simplified inside librime | Apache-2.0 (rapidjson MIT) | none; text dictionaries and a rewritten `t2s.json` are staged at build time | license and NOTICE; state that `t2s.json` was converted |
| Boost (headers) | boost.org 1.92.0 | headers for librime | BSL-1.0 | none | license text with source distributions |
| rime-luna-pinyin, rime-prelude, rime-essay, rime-stroke | `rime/*` at `56b934b0`, `082425ea`, `054920de`, `1e8fff9b` | pinyin schema, dictionary, defaults, language model and stroke lookup | LGPL-3.0 | none; Kotomo adds `default.custom.yaml` and the schema `luna_pinyin_simp_private` | ship the LGPL-3.0 and GPL-3.0 texts and provide the data source |
| Mozc | `google/mozc` 3.35.6354 (`0136c357`), submodule `third_party/mozc` | Japanese conversion (`libkotomo_mozc.so` and `mozc.data`, S3; not in the app yet) | BSD-3-Clause; dictionary data under the NAIST IPAdic notice with the ICOT terms, a public-domain Okinawa dictionary and Google's additions | none; Kotomo's glue is separate (`core/mozc`) | ship Mozc's LICENSE in full, including the NAIST notice and the ICOT "NO WARRANTY" section |
| japanese-usage-dictionary, Abseil, Protocol Buffers | fetched by Mozc's Bazel build (usage dictionary 2025-01-25, abseil-cpp 20260107.1, protobuf 34.1) | compiled into `mozc.data` and `libkotomo_mozc.so` | BSD-2-Clause; Apache-2.0; BSD-3-Clause | none | notices and license texts |

## Planned components

Nothing below is included yet. Each component enters the inventory with its exact version, files,
modifications and obligations when P0 adds it; spike S8 completes the audit.

| Component | License | Planned use | Notes |
|---|---|---|---|
| CLDR annotations | Unicode License v3 | emoji keywords in four languages | — |
| SudachiDict | Apache-2.0 | extra Japanese names, places, stations and loanwords | optional |
| Noto Emoji | images Apache-2.0, font OFL-1.1, flags public domain | emoji style pack | — |
| Twemoji (jdecked) | graphics CC BY 4.0, code MIT | emoji style pack | attribution in About |
| ML Kit GenAI and translation | Google's ML Kit terms | on-device AI and translation | its data handling is checked in spike S8 and stated in the privacy policy |

## Never shipped

- Apple's emoji images: Apple's software license forbids redistributing them, and Apple's App Review
  Guidelines (5.2.5) bar them from third-party keyboards.
- Emoji images of unknown origin, including those bundled with Telegram for Android.
- Data licensed GPL-2.0 only (such as alt-cannadic), CC BY-SA 3.0, or a CC license of unstated
  version.
- Dictionaries, images, sounds or themes taken from other input methods.

## Design references

Kotomo's interaction design draws on these products. Kotomo is independent: it is not affiliated
with, endorsed or authorized by any of them, and it uses none of their code, dictionaries, images or
other protected material.

| Product | What Kotomo learned from it |
|---|---|
| [玛嗒玛嗒 (Madamada)](https://madamada.catchingnow.com/) and its developer — special thanks | typing Japanese kanji from Chinese pinyin and katakana from English; mixed continuous typing of pinyin, English and romaji; whole-sentence conversion; readings on candidates; verb conjugation and keigo switching; categorized Japanese dictionaries; the automatic vocabulary book; city themes and personal keyboards |
| [Gboard](https://play.google.com/store/apps/details?id=com.google.android.inputmethod.latin) | layouts, touch interaction, the candidate bar, multilingual input, emoji, gesture typing, input field adaptation |
| [Microsoft Pinyin](https://support.microsoft.com/windows/microsoft-simplified-chinese-ime-9b962a3b-2fa4-4f37-811c-b1886320dd72) | fuzzy pinyin, long-sentence conversion, frequency learning, double pinyin, the U/V modes, pinyin correction |
| [Sogou Pinyin (搜狗输入法)](https://shurufa.sogou.com/) | smart prediction, long-sentence input, key correction, dictionary management, user-made words, candidate actions, keyboard skins |
| [Microsoft SwiftKey](https://www.microsoft.com/swiftkey) | personalized and multilingual prediction, English correction, smart spacing, the clipboard, gestures |
| [Google Japanese Input (Google 日本語入力)](https://www.google.co.jp/ime/) and [Mozc](https://github.com/google/mozc) | romaji conversion, kana–kanji and long-sentence conversion, bunsetsu conversion, Japanese dictionaries and prediction |

Telegram is a reference only for drawing emoji inside the keyboard; that never implies its emoji
assets may be used.

## Trademarks

Product names in this file are trademarks of their owners. Android is a trademark of Google LLC.
