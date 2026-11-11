# Version history

## 0.1.0 (in progress)

- Repository scaffold: working conventions (`AGENTS.md`), the plan (`PLAN.md`, from the user's
  draft 2.0), mirrored documentation and the documentation check (`tool/CheckDocs.java`).
- Upstreams read on 2026-11-04, nothing built yet: librime 1.17.0, Mozc 3.35.6354, OpenCC 1.4.2;
  Android API 37, Android Gradle Plugin 9.5.0, Kotlin 2.4.21, NDK r30.
- Emoji data (P1), checked 2026-11-04: Unicode Emoji 18.0 and CLDR 49.0.0 annotations, the latest
  releases; pinyin and kana readings from the Unihan, CC-CEDICT and Mozc pins above.
- Upstreams checked 2026-11-10 when development resumed on an x86_64 Linux host: Gradle 9.8.2 →
  9.9.1 and Android Gradle Plugin 9.5.0 → 9.5.1; CC-CEDICT moved to the 2026-11-10T08:36:22Z file
  and jmdict-simplified to 3.6.2+20261109214828 (JMdict 2026-11-09). Checked, unchanged: librime
  1.17.0, Mozc 3.35.6354, OpenCC 1.4.2, HeliBoard 4.2, Kotlin 2.4.21, Compose BOM 2026.09.00, the
  AndroidX libraries, ML Kit GenAI 1.0.0-beta1, NDK r30, Unicode 18.0, CLDR 49.0.0, the rime data
  repositories and Bazelisk 1.30.0.
- Dictionary-pack sources pinned 2026-11-11 at their latest releases (`build-lock.json`; nothing
  generated or shipped yet): rime-ice 2026.06.30, rime-frost 1.0.4, fcitx5-pinyin-zhwiki 0.3.1
  (dump 20261018), THUOCL `a30ce79d`, SudachiDict 20261015, mecab-ipadic-neologd seed 20200910,
  mozcdic-ut 2026-11-03, SCOWLv2 rel-2026.02.25.
- Upstreams checked 2026-11-11: CC-CEDICT moved to the 2026-11-11T02:50:39Z file (S5 samples 34/34,
  counter-examples 0/8). Checked, unchanged: librime 1.17.0, Mozc 3.35.6354, OpenCC 1.4.2, HeliBoard
  4.2, Gradle 9.9.1, Android Gradle Plugin 9.5.1, Kotlin 2.4.21, Compose BOM 2026.09.00, the AndroidX
  libraries, ML Kit GenAI 1.0.0-beta1, NDK r30, platform 37.2, build tools 37.0.0, jmdict-simplified
  3.6.2+20261109214828, Unicode 18.0, CLDR 49.0.0, the rime data repositories and Bazelisk 1.30.0.
- Dictionary sources checked again 2026-11-11 for Q6, unchanged: rime-ice 2026.06.30, rime-frost
  1.0.4, fcitx5-pinyin-zhwiki 0.3.1, THUOCL `a30ce79d`, SudachiDict 20261015, mozcdic-ut 2026-11-03,
  SCOWLv2 rel-2026.02.25. From now on the app downloads the offered ones (THUOCL, rime-ice
  `en_dicts.zip` and `8105`, the zhwiki web slang) from their sources at their current version;
  the pins only fix what the host tests convert.
