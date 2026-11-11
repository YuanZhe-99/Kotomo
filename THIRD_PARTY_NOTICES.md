# Third-party notices

Kotomo is licensed under GPL-3.0 (`LICENSE`). This file holds the notices of the third-party code,
data and assets it includes or downloads. The inventory with versions, modifications and
obligations is [doc/en-us/third-party.md](doc/en-us/third-party.md); the settings app lists the
same components on its open-source licenses and data sources pages. The full license texts are in
each upstream's repository (the submodules under `third_party/` and the pinned sources in
`build-lock.json`); release builds ship them with the complete corresponding source.

## Code in the APK

| Component | Notice | License |
|---|---|---|
| librime | Copyright (c) 2014, RIME Developers | BSD-3-Clause |
| darts-clone (in librime and OpenCC) | Copyright (c) 2008-2014, Susumu Yata | BSD-2-Clause |
| utf8-cpp (in librime) | Copyright 2006 Nemanja Trifunovic | BSL-1.0 |
| LevelDB | Copyright (c) 2011 The LevelDB Authors | BSD-3-Clause |
| marisa-trie | Copyright (c) 2010-2025, Susumu Yata | BSD-2-Clause |
| yaml-cpp | Copyright (c) 2008-2015 Jesse Beder | MIT |
| OpenCC (with rapidjson, MIT) | Copyright (c) BYVoid and the OpenCC contributors | Apache-2.0 |
| Boost (headers) | Boost Software License 1.0 | BSL-1.0 |
| Mozc, with japanese-usage-dictionary, Abseil and Protocol Buffers | Copyright 2010-2018, Google Inc.; its dictionary data carries the NAIST IPAdic notice with the ICOT "NO WARRANTY" terms, which ship in full with Mozc's LICENSE | BSD-3-Clause; BSD-2-Clause; Apache-2.0; BSD-3-Clause |
| AOSP LatinIME suggestion core (through HeliBoard) | Copyright (C) The Android Open Source Project | Apache-2.0 |
| AndroidX, Jetpack Compose, the Kotlin standard library, kotlinx.coroutines, okio | Copyright The Android Open Source Project, JetBrains s.r.o. and contributors, Square, Inc. | Apache-2.0 |

## Data in the APK

| Data | Notice | License |
|---|---|---|
| rime-luna-pinyin, rime-prelude, rime-essay | the Rime developers | LGPL-3.0 |
| Mozc dictionary data | the notices and disclaimers of Mozc's `src/data/dictionary_oss/README.txt` | IPAdic (NAIST, ICOT terms), Okinawa dictionary, Google |
| Unihan, Unicode Emoji data, CLDR annotations | Copyright © 1991-2026 Unicode, Inc. | Unicode License v3 |
| CC-CEDICT readings (in `emoji.tsv`) | MDBG, based on CEDICT by Paul Andrew Denisowski | CC BY-SA 4.0 |
| English word list | Leipzig Corpora Collection, Universität Leipzig, through HeliBoard's aosp-dictionaries; converted | CC BY 4.0 |

## Dictionaries downloaded on request

Not in the APK: the settings download them from their sources when the person asks
(`doc/en-us/dictionaries.md`).

| Data | Notice | License |
|---|---|---|
| rime-ice | iDvel and the rime-ice contributors | GPL-3.0 |
| THUOCL | Copyright (c) 2018 THUNLP. Cite: Han Shiyi, Zhang Yuhui, Ma Yunshan, Tu Cunchao, Guo Zhipeng, Liu Zhiyuan, Sun Maosong. THUOCL: Tsinghua Open Chinese Lexicon. Tsinghua University, 2016. | MIT |
| fcitx5-pinyin-zhwiki web slang | Chinese Wikipedia contributors; converted by Felix Yan | CC BY-SA 4.0 (converter Unlicense) |
