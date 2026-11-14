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
- Upstreams checked again on 2026-11-11 for the coordinator's device integration (Q7), unchanged:
  librime 1.17.0, Mozc 3.35.6354, OpenCC 1.4.2 and HeliBoard 4.2 by their tags (HeliBoard's only
  newer tag is the pre-release 4.3-beta1); the toolchain and data sources as in the line above.
  Kotomo's Mozc glue gained the coordinator's function table, so both Android libraries were
  rebuilt and their hashes recorded in `build-lock.json`; `mozc.data` is unchanged.
- Source audit (S8) on 2026-11-12, `third-party.md`: latest releases read from the feeds: SudachiDict v20261015, Noto Emoji v2026-09-24-unicode18_0, Twemoji v17.0.3, OpenMoji 17.0.0, jmdict-simplified 3.6.2+20261109214828, cldr-json 49.0.0 (all unchanged or newly recorded); THUOCL unchanged.
- Upstreams checked on 2026-11-13 for translation (Q9): ML Kit `translate` 17.0.3 added at its latest release; ML Kit GenAI proofreading and rewriting 1.0.0-beta1, librime 1.17.0, Mozc 3.35.6354, OpenCC 1.4.2, HeliBoard 4.2 (newer only 4.3-beta1), AGP 9.5.1 and Kotlin 2.4.21 (2.5.0 is a release candidate) unchanged.
- Upstreams checked on 2026-11-14: CC-CEDICT moved to the 2026-11-14T02:25:17Z file, because MDBG replaced the current file and the earlier pin can no longer be downloaded (S5 samples 34/34, counter-examples 0/8; three rows of the pinyin → Japanese table changed, `emoji.tsv` is unchanged; the conversion corpus has no regression). Checked, unchanged: librime 1.17.0, Mozc 3.35.6354, OpenCC 1.4.2, HeliBoard 4.2 (newer only 4.3-beta1), Gradle 9.9.1, Android Gradle Plugin 9.5.1, Kotlin 2.4.21 (2.5.0 is a release candidate), Compose BOM 2026.09.00, jmdict-simplified 3.6.2+20261109214828 and cldr-json 49.0.0.
