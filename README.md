# Kotomo

**Chinese, English and Japanese on one Android keyboard — even in one sentence**

Kotomo is a local-first input method for Android. Chinese pinyin, English and Japanese romaji are
typed on the same keyboard without switching languages. In Mixed mode one continuous string can
hold all three and converts as a whole sentence: `chidai ni kai ni ikou` becomes 池袋に買いに行こう
(池袋 typed by its Chinese reading), and `zailiucard` becomes 在留カード. Chinese pinyin and English
words can also produce Japanese kanji and katakana directly.

> **Status:** planning and scaffold. Nothing here can be installed or typed with yet. See
> [PLAN.md](PLAN.md) (in Chinese) and the [platform notes](doc/en-us/platform-notes.md).

## Features

| Feature | Status |
|---|---|
| Chinese (pinyin, whole sentences, fuzzy and double pinyin), English and Japanese (romaji, kana–kanji conversion) modes, all offline | planned |
| Mixed mode: three languages recognized in one continuous string, converted as a whole sentence, with per-segment correction | planned |
| Chinese pinyin → Japanese kanji words; English → katakana and kanji words | planned |
| Simplified and Traditional Chinese with regional vocabulary | planned |
| Ranking, correction and personal learning that can be reviewed and cleared | planned |
| Japanese conjugation and keigo, readings on candidates | planned |
| Built-in, downloadable and custom dictionaries; emoji suggestions in three languages | planned |
| Gboard-style layouts and gestures, themes, emoji style packs, clipboard history | planned |
| Optional on-device AI (Gemini Nano): proofreading, translation, rewriting | planned |
| Local backup and optional WebDAV sync | planned |

## Architecture

- **The app** — Kotlin: the input method service, a custom-drawn keyboard and candidate bar, and a
  Jetpack Compose settings app.
- **The engine** — C++ through JNI: Kotomo's own mixed-input coordinator (candidate lattice,
  whole-sentence decoder, cross-language lexicon) over [librime](https://github.com/rime/librime)
  for Chinese, [Mozc](https://github.com/google/mozc) for Japanese, AOSP LatinIME's suggestion
  engine for English and [OpenCC](https://github.com/BYVoid/OpenCC) for Simplified and Traditional
  Chinese.

Typing never needs the network, and nothing typed leaves the device unless the person turns on a
feature that says it will.

## Building from Source

Builds are not available yet. Requirements and commands:
[doc/en-us/development.md](doc/en-us/development.md).

## Documentation

- [English documentation](doc/en-us/README.md) · [中文文档](doc/zh-cn/README.md)

## Privacy

Typing works offline and stays on the device; Kotomo has no internet permission. See
[PRIVACY_POLICY.md](PRIVACY_POLICY.md).

## Acknowledgements

Kotomo's design draws on these input methods. It is an independent project, not affiliated with,
endorsed or authorized by any of them, and it uses none of their code, dictionaries or images.

- **[玛嗒玛嗒 (Madamada)](https://madamada.catchingnow.com/)** and its developer — special thanks.
  Its design for Chinese speakers typing Japanese shaped Kotomo's Japanese input: Japanese kanji
  from Chinese pinyin and katakana from English, mixed continuous typing of pinyin, English and
  romaji, whole-sentence conversion, readings on candidates, verb conjugation and keigo switching,
  categorized Japanese dictionaries, the automatic vocabulary book, and city themes.
- **[Gboard](https://play.google.com/store/apps/details?id=com.google.android.inputmethod.latin)** —
  the modern Android keyboard: layouts, touch interaction, the candidate bar, multilingual input,
  emoji, gesture typing and input field adaptation.
- **[Microsoft Pinyin](https://support.microsoft.com/windows/microsoft-simplified-chinese-ime-9b962a3b-2fa4-4f37-811c-b1886320dd72)** —
  fuzzy pinyin, long-sentence conversion, frequency learning, double pinyin, the U/V modes and
  pinyin correction.
- **[Sogou Pinyin (搜狗输入法)](https://shurufa.sogou.com/)** — smart prediction, long-sentence
  input, key correction, dictionary management, user-made words, candidate actions and skins.
- **[Microsoft SwiftKey](https://www.microsoft.com/swiftkey)** — personalized and multilingual
  prediction, English correction, smart spacing, the clipboard and gestures.
- **[Google Japanese Input](https://www.google.co.jp/ime/) / [Mozc](https://github.com/google/mozc)** —
  romaji, kana–kanji and long-sentence conversion, bunsetsu conversion, Japanese dictionaries and
  prediction.

The open source projects Kotomo builds on — Rime/librime, Mozc, Fcitx5 for Android, OpenCC, Noto
Emoji, Twemoji and the dictionaries it uses — are credited with their licenses as they are added:
[doc/en-us/third-party.md](doc/en-us/third-party.md).

## Open Source Licenses

Kotomo is licensed under [GPL-3.0](LICENSE). Third-party code, dictionary data and emoji assets with
their licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and
[doc/en-us/third-party.md](doc/en-us/third-party.md). Product names are trademarks of their owners;
Android is a trademark of Google LLC.
