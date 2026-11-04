# Third-party code, data and assets

Kotomo is licensed under GPL-3.0. This inventory lists every upstream it reuses or embeds — code,
dictionary data, emoji assets and fonts — what it uses and what that obliges.
`THIRD_PARTY_NOTICES.md` holds the notices themselves; the in-app licenses page lists the same
components.

## Inventory

| Component | Repository and version | Used for | License | Modifications | Obligations |
|---|---|---|---|---|---|
| MyApps conventions and documentation checks | `YuanZhe-99/Kyokai` (`tools/checkdocs`), `YuanZhe-99/MyApps-AI` (`tool/check_docs.py`), the MyApps `AGENTS.md` rules | `tool/CheckDocs.java` follows their mirror and link rules; `AGENTS.md` follows their conventions | AGPL-3.0 and GPL-3.0, same author as Kotomo | written anew for Kotomo | none beyond Kotomo's license |

## Planned components

Nothing below is included yet. Each component enters the inventory with its exact version, files,
modifications and obligations when P0 adds it; spike S8 completes the audit.

| Component | License | Planned use | Notes |
|---|---|---|---|
| librime | BSD-3-Clause | Chinese engine | with Boost, LevelDB, marisa and yaml-cpp, whose licenses are recorded when they are built |
| Mozc | BSD-3-Clause; dictionary data under the IPAdic (NAIST and ICOT) notice, plus public-domain and Google entries | Japanese engine and dictionary | the dictionary notices ship with the data |
| OpenCC | Apache-2.0 | Simplified, Traditional and regional conversion | shared with librime |
| AOSP LatinIME native code, or HeliBoard's maintained copy | Apache-2.0; HeliBoard GPL-3.0 | English engine | chosen in spike S4 |
| A Chinese schema and dictionary set | rime-ice GPL-3.0 only; rime_wanxiang CC BY 4.0; rime-luna-pinyin and rime-essay LGPL-3.0 | Chinese vocabulary and language model | chosen in spike S2; GPL-3.0-only data makes the whole distribution GPL-3.0-only |
| An English word list | AOSP lists (Apache-2.0, to be confirmed); HeliBoard's English experimental lists CC BY 4.0 | English vocabulary | chosen in spike S4 |
| JMdict (EDRDG), through jmdict-simplified | CC BY-SA 4.0 | English → Japanese mapping | credited on a separate sources screen reached from a menu, with links to the license and documentation; kept up to date |
| CC-CEDICT | CC BY-SA 4.0 | word-level pinyin for the pinyin → Japanese mapping | attribution |
| Unihan | Unicode License v3 | readings and variants for the pinyin → Japanese mapping | — |
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
