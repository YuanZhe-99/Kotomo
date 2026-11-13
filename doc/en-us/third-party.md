# Third-party code, data and assets

Kotomo is licensed under GPL-3.0. This inventory lists every upstream it reuses or embeds — code,
dictionary data, emoji assets and fonts — what it uses and what that obliges.
`THIRD_PARTY_NOTICES.md` holds the notices themselves; the settings app's About and
acknowledgements lists the same components (code on its open-source licenses page, dictionary data
with its authors on its data sources page) and the design references below.

## Inventory

| Component | Repository and version | Used for | License | Modifications | Obligations |
|---|---|---|---|---|---|
| MyApps conventions and documentation checks | `YuanZhe-99/Kyokai` (`tools/checkdocs`), `YuanZhe-99/MyApps-AI` (`tool/check_docs.py`), the MyApps `AGENTS.md` rules | `tool/CheckDocs.java` follows their mirror and link rules; `AGENTS.md` follows their conventions | AGPL-3.0 and GPL-3.0, same author as Kotomo | written anew for Kotomo | none beyond Kotomo's license |
| Mozc dictionary data (`src/data/dictionary_oss`: `dictionary00–09.txt`, `id.def`, `README.txt`) | `google/mozc` tag 3.35.6354 | entries, readings, POS ids and costs for the cross-language lexicon (S5; build input, the generated lexicon is not shipped yet); kana readings of the Japanese keys of `emoji.tsv` (shipped); `id.def`'s POS names, compiled into `libkotomo_mozc.so` for the parts of speech and conjugation of Japanese candidates (shipped, PLAN §10.1, §10.2) | IPAdic-derived (mecab-ipadic-2.7.0-20070801, NAIST and ICOT terms), Okinawa dictionary data, Google additions | none | ship `README.txt`'s notices and disclaimers with any lexicon derived from it |
| Unihan | Unicode 18.0.0 (`Unihan.zip`) | readings (`kMandarin`, attested by `kXHC1983`, `kTGHZ2013`, `kHanyuPinyin`), `kSimplifiedVariant`, `kJapaneseOldVariant` for the pinyin → Japanese mapping (S5, shipped in `cross.klx`); `kMandarin` for the pinyin keys of `emoji.tsv` | Unicode License v3 | none | include the Unicode copyright and license notice |
| CC-CEDICT | MDBG release 2026-11-11T02:50:39Z | word-level pinyin for the pinyin → Japanese mapping (S5, shipped in `cross.klx`) and for the pinyin keys of `emoji.tsv` (shipped) | CC BY-SA 4.0 (MDBG; based on CEDICT by Paul Andrew Denisowski) | none | attribution and a link to the license; derived readings stay CC BY-SA 4.0 |
| Unicode Emoji data (`emoji-test.txt`) | Unicode Emoji 18.0 (`Public/18.0.0/emoji/`, dated 2026-04-30) | emoji order, groups and skin-tone variants of the emoji panel (`emoji.tsv`, generated, shipped in the APK) | Unicode License v3 | the Component group is left out | include the Unicode copyright and license notice |
| CLDR annotations and derived annotations (cldr-json) | `unicode-org/cldr-json` 49.0.0 (`a4b67247`), `annotations` and `annotationsDerived` for en, zh, zh-Hant and ja | emoji keywords and names, the keys of `emoji.tsv` | Unicode License v3 | none; keys are normalized (NFKC, lower case) | include the Unicode copyright and license notice |
| OpenCC Japanese Shinjitai tables (`JPShinjitaiCharacters.txt`, `JPShinjitaiPhrases.txt`) | `BYVoid/OpenCC` ver.1.4.2 | Shinjitai → old forms for the pinyin → Japanese mapping (S5) | Apache-2.0 | none | include the license and NOTICE |
| Double-pinyin key layouts (Microsoft, Sogou, Xiaohe, Ziranma) | checked against `rime/rime-double-pinyin` (Microsoft, Xiaohe, Ziranma) and rime-ice's Sogou schema (`double_pinyin_sogou.schema.yaml`) | the spelling rules `Fuzzy.kt` writes for `chinese.double_pinyin` | the schemes are published key layouts; the schemas checked are LGPL-3.0 and GPL-3.0 | no file is copied: the rules are written anew in Kotomo's generated `kotomo_fuzzy.yaml`, following the schemas' rule order | none beyond Kotomo's license |
| JMdict (English), through jmdict-simplified | jmdict-simplified 3.6.2+20261109214828 (JMdict 2026-11-09) | glosses for the English → Japanese mapping (S5, shipped in `cross.klx`) | CC BY-SA 4.0 under the EDRDG licence | none | credit the EDRDG as copyright holder on a separate sources page reached from a menu, link the licence and the JMdict project page, keep the data updated; the derived mapping stays CC BY-SA 4.0 |
| AOSP LatinIME native suggestion core, through HeliBoard | `HeliBorg/HeliBoard` v4.2 (`0c60dffb`), `app/src/main/jni/src` | English correction, completion and prediction (`libkotomo_english.so`, S4) | Apache-2.0 (AOSP files); HeliBoard as a whole GPL-3.0 | none; Kotomo's own JNI bridge replaces LatinIME's JNI files | ship the Apache-2.0 license text and keep the AOSP notices |
| AOSP LatinIME dicttool (build tool only) | AOSP `platform/packages/inputmethods/LatinIME` `127336e9` | builds the English dictionary at build time; not shipped | Apache-2.0 | none | none for the app |
| English word list: Helium314/aosp-dictionaries `main_en_US__experimental.combined`, derived from the Leipzig Corpora Collection | codeberg `Helium314/aosp-dictionaries` `ff0cd52b` | English vocabulary and bigrams (`main_en_us.dict`, generated, shipped in the APK) | CC BY 4.0 (Leipzig: corpora for download are licensed under CC BY) | converted to LatinIME binary format 202 | attribution to the Leipzig Corpora Collection (Universität Leipzig) and to HeliBoard's aosp-dictionaries on the sources screen, with a link to CC BY 4.0 and a note that the data was converted |
| librime | `rime/librime` 1.17.0 (`33e78140`); bundles darts-clone and utf8-cpp headers | Chinese engine (`libkotomo_rime.so`, S2) | BSD-3-Clause (darts-clone BSD-2-Clause, utf8-cpp BSL-1.0) | none; built without plugins and logging | keep the copyright and license notices |
| LevelDB | `google/leveldb` 1.23 (`99b3c03b`) | librime's user dictionary | BSD-3-Clause | none | notice |
| marisa-trie | `s-yata/marisa-trie` v0.3.1 (`3e87d53b`) | librime and OpenCC tries | BSD-2-Clause or LGPL-2.1-or-later, used under BSD-2-Clause | none | notice |
| yaml-cpp | `jbeder/yaml-cpp` 0.9.0 (`56e3bb55`) | librime configuration | MIT | none | notice |
| OpenCC | `BYVoid/OpenCC` ver.1.4.2 (`025f371d`); bundles rapidjson and darts-clone | Traditional → Simplified, Taiwan and Hong Kong (with regional phrases, Kotomo's `t2twp.json` and `t2hkp.json`) inside librime | Apache-2.0 (rapidjson MIT) | none; text dictionaries and a rewritten `t2s.json` are staged at build time | license and NOTICE; state that `t2s.json` was converted |
| Boost (headers) | boost.org 1.92.0 | headers for librime | BSL-1.0 | none | license text with source distributions |
| rime-luna-pinyin, rime-prelude, rime-essay, rime-stroke | `rime/*` at `56b934b0`, `082425ea`, `054920de`, `1e8fff9b` | pinyin schema, dictionary, defaults and language model; the compiled stroke data ships for the stroke lookup that `u` starts (PLAN §10.4) | LGPL-3.0 | none; Kotomo adds `default.custom.yaml` and the schema `luna_pinyin_simp_private` | ship the LGPL-3.0 and GPL-3.0 texts and provide the data source |
| Mozc | `google/mozc` 3.35.6354 (`0136c357`), submodule `third_party/mozc` | Japanese conversion (`libkotomo_mozc.so` and `mozc.data`, S3; Japanese mode uses them when the separately built libraries are packaged into the APK) | BSD-3-Clause; dictionary data under the NAIST IPAdic notice with the ICOT terms, a public-domain Okinawa dictionary and Google's additions | none; Kotomo's glue is separate (`core/mozc`) | ship Mozc's LICENSE in full, including the NAIST notice and the ICOT "NO WARRANTY" section |
| japanese-usage-dictionary, Abseil, Protocol Buffers | fetched by Mozc's Bazel build (usage dictionary 2025-01-25, abseil-cpp 20260107.1, protobuf 34.1) | compiled into `mozc.data` and `libkotomo_mozc.so` | BSD-2-Clause; Apache-2.0; BSD-3-Clause | none | notices and license texts |
| Google ML Kit: `genai-proofreading`, `genai-rewriting`, `translate` | Google Maven 1.0.0-beta1, 1.0.0-beta1, 17.0.3 (the latest releases, 2026-11-13) | writing help (proofreading, rewriting with Gemini Nano through AICore) and on-device translation; the translation language models (about 30 MB each) are downloaded from Google only when the person taps Download | ML Kit Terms of Service (closed source; terms not read) | none | credit on the licenses page; state ML Kit's own data collection in the privacy policy (`upstream.md`) |
| AndroidX (Compose, Material 3, Activity, Lifecycle, Core, SavedState, DataStore, Autofill), the Kotlin standard library, kotlinx.coroutines, okio | Google Maven and Maven Central at the versions in `gradle/libs.versions.toml` (Compose BOM 2026.09.00, DataStore 1.3.0, Autofill 1.3.0, Kotlin 2.4.21) | the settings app, the keyboard's Compose panels, the settings file, the style of inline autofill suggestions | Apache-2.0 | none | ship the Apache-2.0 license text |

`cross.klx`, the compiled cross-language lexicon (`lexicon-format.md`), is generated by
`lexicon/CrossLexicon.java` and `kotomo_lexicon_compile` (never committed) and ships in the APK
from the build that wires Mixed mode to the coordinator. It keeps the licenses of what it derives
from: CC BY-SA 4.0 for the mappings taken from JMdict (EDRDG licence) and the readings taken from
CC-CEDICT, Unicode License v3 for the Unihan readings and variants, Apache-2.0 for OpenCC's
variant tables, and the notices of Mozc's dictionary for the Japanese words, readings and costs.
The About page's data sources list every one of them.

`emoji.tsv` is generated by `lexicon/EmojiData.java` (never committed) and ships in the APK. It
keeps the licenses of what it derives from: Unicode License v3 for the emoji, the CLDR keys and the
Unihan readings, CC BY-SA 4.0 for the pinyin taken from CC-CEDICT, and the notices of Mozc's
`README.txt` for the Japanese readings; the in-app licenses page, when it is added, lists all of them
before the first release. Emoji are drawn
with the system font: no emoji images ship.

## Downloaded on request

These sources are not in the APK. The settings download them from where their authors publish
them, only when the person asks, and convert them on the device (`dictionaries.md`, ADR-011). The
data sources page lists each one with its license and authors, and every installed pack keeps its
license, attribution and (THUOCL) citation in its `pack.json`.

| Component | Repository and version | Used for | License | Modifications | Obligations |
|---|---|---|---|---|---|
| rime-ice `en_dicts/en_ext.dict.yaml` (release asset `en_dicts.zip`) and `cn_dicts/8105.dict.yaml` (raw file) | `iDvel/rime-ice`, the latest release (`nightly`); host tests pin 2026.06.30 (`6810e891`) | the pack `en-ice-tech`; character readings for the THUOCL packs | GPL-3.0 | converted: readings normalized, weights scaled to 0–100, invalid lines dropped | GPL-3.0, like Kotomo; credit the authors; the converted pack stays GPL-3.0 |
| THUOCL (11 domain lists) | `thunlp/THUOCL` `master` (unchanged since 2018-11-21, `a30ce79d`) | the 11 packs `zh-thuocl-*` | MIT; the README asks for the citation | readings derived from rime-ice's character list (polyphones without a dominant reading dropped), weights scaled | keep the MIT notice; cite "THUOCL: Tsinghua Open Chinese Lexicon" (2016); readings are GPL-3.0 (rime-ice) |
| fcitx5-pinyin-zhwiki `web-slang-<date>.dict.yaml` | `felixonmars/fcitx5-pinyin-zhwiki`, the latest release; host tests pin 0.3.1 (dump 20261018) | the pack `zh-web-slang` | CC BY-SA 4.0 (Wikipedia); converter Unlicense | spelled-out Latin letters dropped | attribution to Wikipedia contributors and a link to the license; the pack stays CC BY-SA 4.0 |

## Source audit (spike S8)

Checked on 2026-11-12 against each source's official page, repository or release feed. "Not read"
marks a point that was not verified in this audit; it is not a conclusion. Verdicts: **usable**,
**conditional** (usable once the stated condition holds) and **not usable**. Nothing here puts a
data file in the repository.

| Source | Latest checked | License as read | Verdict | Obligations and notes |
|---|---|---|---|---|
| JMdict and KANJIDIC2 (EDRDG) | jmdict-simplified 3.6.2+20261109214828 (2026-11-09) | CC BY-SA 4.0 under the EDRDG licence; JMdict covers Japanese and English, other languages are separate copyright; in KANJIDIC2 the pinyin, SKIP and other codes stay with their contributors | JMdict English: usable (shipped). KANJIDIC2: not used | acknowledge the EDRDG on a dedicated screen (About or Sources), ship or link the licence and documentation, never claim copyright, keep a procedure for regular updates (the licence treats stale data as a violation; whether releases alone meet it is an open question for the user) |
| Unicode data: Unihan, `emoji-test.txt`, CLDR (cldr-json) | cldr-json 49.0.0 (2026-10-22), Unicode 18.0 | Unicode License v3 as recorded; text not read | usable (shipped) | the Unicode notice (`THIRD_PARTY_NOTICES.md`) |
| SudachiDict | v20261015 (2026-10-15) | `LEGAL`: Apache-2.0 for the distribution; `small_lex` carries the UniDic BSD-style notice; `core_lex` and `notcore_lex` derive from NEologd, for which `LEGAL` names no separate license and lists acknowledgements (Hatena, Japan Post, a station-name list, a personal-name dataset, web-crawled data) | conditional: `small_lex` usable; `core_lex` and `notcore_lex` usable under the Apache-2.0 notice, their sub-sources not verified one by one | ship `LEGAL` and the UniDic notice with any derived pack; not offered yet |
| THUOCL | `a30ce79d`, unchanged | README: free for research and commercial use; the repository lists MIT | usable (offered) | keep the notice and the citation |
| rime-ice | release feed tag `nightly` (the API dates the release 2024-05-21; the assets are rebuilt, `build-lock.json` pins 2026.06.30) | GPL-3.0 as recorded; text not read | usable (offered) | GPL-3.0, credit the authors |
| Noto Emoji | v2026-09-24-unicode18_0 (2026-09-24), Unicode 18.0 | fonts SIL OFL-1.1; tools and "most" image resources Apache-2.0; flags (`third_party/region-flags`) public domain or exempt; no attribution requirement stated | conditional: usable as a style pack once the exceptions to "most" and the flag files' own terms are read (not read) | ship the OFL and Apache-2.0 texts; credit anyway on the sources page |
| Twemoji (jdecked) | v17.0.3 (2026-06-01), Emoji 17.0 | code MIT, graphics CC BY 4.0; the project accepts a mention in a README, About or Settings screen | usable | credit in About and link the license; emoji new in Unicode 18.0 are missing, the system font fills them |
| OpenMoji | 17.0.0 (2026-04-23) | CC BY-SA 4.0 as recorded in `PLAN.md`; not read | conditional: share-alike reaches adapted images; not a candidate for the first style pack | verify the license before any use |
| Apple emoji; images of unknown origin | — | Apple's license and App Review Guideline 5.2.5 as recorded; not re-read | not usable | never shipped |
| JLPT vocabulary lists (Tanos, Jonathan Waller) | the site no longer resolves; mirrors name Jonathan Waller and "CC BY" without a version; the lists are unofficial (the JLPT publishes none) | version unstated | not usable now | the rule against a CC license of unstated version applies; reopen if the license page or a version turns up |
| Railway station data, MLIT 国土数値情報 N02 | 2024 edition (`N02-24`) | the dataset page labels the 2020 edition on as CC BY 4.0; the site's general terms are the Public Data License 1.0 | conditional: station names usable; the dataset carries no readings | credit the source and the page URL and say it was processed; take readings from Mozc or SudachiDict; the Survey Act approval for products built on the basemaps does not reach names, and Kotomo uses no geometry |
| 駅データ.jp | — | its terms of use were not read | not usable until read | — |
| Wikidata labels | — | structured data (main, Property, Lexeme namespaces) is CC0, labels included | conditional: usable for Japanese ↔ Chinese pairs of proper nouns once a file is fetched and its coverage measured | no attribution required; discussion pages are CC BY-SA 4.0 and are not used |
| Tatoeba sentences | — | CC BY 2.0 FR by default; some sentences and audio carry other conditions (ND, SA, non-commercial audio) | conditional: example or test sentences only, filtered by each sentence's license; no audio | credit the authors |
| Wiktionary, Chinese Open Wordnet | — | not read | not usable until read | — |

Data gaps (`PLAN.md` §3.3):

- Common Chinese glosses of Japanese words: no source with a clear license found. JMdict has no
  Chinese; CC-CEDICT only helps for words written with the same characters, which the pinyin →
  Japanese mapping already uses; Wikidata labels could cover proper nouns. The feature waits.
- JLPT levels: waits (no usable list).
- Station names: MLIT N02 names plus readings from Mozc or SudachiDict; lines outside the dataset
  stay missing.
- Domain dictionaries: Chinese has THUOCL's eleven lists (offered); no Japanese source with a clear
  license was found, so Japanese domains wait.

Decided by the user on 2026-11-13: the conditional items go ahead on their stated conditions, the
Noto Emoji and Wikidata ones included. Also decided on 2026-11-13: JMdict is updated with each
Kotomo release only, which the user accepts as meeting the EDRDG's update rule; the release steps
already bring every data source to its latest version (`AGENTS.md`), so no other process is added.

## Planned components

Nothing below is included or downloaded yet. Each component enters the inventory with its exact
version, files, modifications and obligations when it is added; the audit above (spike S8) covers the sources and assets considered so far. The
Q6 evaluation of the dictionary sources not offered for download is in `dictionaries.md`.

| Component | License | Planned use | Notes |
|---|---|---|---|
| SudachiDict raw lexicon (`small_lex`, `core_lex`) | Apache-2.0; `small_lex` contains part of UniDic (BSD-3-Clause, see `LEGAL`: the UniDic copyright, conditions and disclaimer must ship with it) | Japanese dictionaries, once Mozc's user dictionary reads them; pinned 20261015 in `build-lock.json` | not offered yet (`dictionaries.md`) |
| rime-frost (`base`, `ext`) | GPL-3.0 | an alternative Chinese base dictionary | not offered: its releases ship only 44–88 MB bundles, its raw files publish no checksum, and it overlaps rime-ice |
| rime-ice `base.dict.yaml`, `ext.dict.yaml` | GPL-3.0 | Chinese common and extended words | not offered yet: they convert on a phone, but librime loads a downloaded pack as a text table at every schema selection, and half a million lines kept the keyboard from typing for 25 s on the emulator |
| rime-ice `tencent.dict.yaml`, `41448.dict.yaml` | GPL-3.0 | — | `tencent` is not used: its words come from the Tencent AI Lab Embedding vocabulary, whose license Tencent's own page does not state (one catalog lists CC BY 3.0); `41448` holds single characters without weights |
| fcitx5-pinyin-zhwiki `zhwiki` | Wikipedia titles CC BY-SA 4.0; converter Unlicense | Chinese proper names | not offered: 57 MB and 1.7 million lines are too much to convert and keep on a phone |
| mecab-ipadic-neologd seed (20200910) | Apache-2.0, with the source acknowledgements in `COPYING` | Japanese new words | not offered: xz-compressed (no decoder on Android), 3.2 million lines, unmaintained since 2020 |
| mozcdic-ut place names, personal names, jawiki | public domain (Japan Post ZIP code data); Apache-2.0; CC BY-SA 4.0 | Japanese place names, names, Wikipedia titles | not offered: bzip2-compressed (no decoder on Android); Japanese words are not used yet |
| SCOWLv2 (en-wl/wordlist) | MIT-like (`Copyright`); lists above size 80 add the UKACD terms | English words | not offered: the release ships only sources that need Python and SQLite to produce a list; English words are not used yet |
| Noto Emoji | images Apache-2.0, font OFL-1.1, flags public domain | emoji style pack | — |
| Twemoji (jdecked) | graphics CC BY 4.0, code MIT | emoji style pack | attribution in About |

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
