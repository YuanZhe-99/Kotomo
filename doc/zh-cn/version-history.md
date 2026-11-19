# 版本历史

## 0.1.1（2026-11-19）

- 混合模式与英文 → 日语匹配优化：带括号限定语的 JMdict 英文释义在剥离括号后若为单词词元则索引进 `cross.klx`（`power` → `パワー`），附属词性义项（`suf`、`pref`、`ctr`、`aux*`、`prt`、`conj`、`int`）排在独立名词与外来语之后（`system` → `システム`），英文常见屈折词尾（`-s`、`-es`、`-ed`、`-ing`）支持词干回退查词，未登录假名回显与残留拉丁字母计入罚分以避免压过外来语，且混合模式下的英文候选保留输入的大写形式（S5 样本 40/40，反例 0/8）。
- 混合模式拼音前缀匹配与分段上屏（引擎契约第 9 版）：多音节拼音输入在整句候选之后提供严格按音节边界对齐的 2–3 音节词组前缀与首音节单字候选（绝不在音节内部截断）；点选前缀候选仅提交该前缀并保留剩余拼音继续组词（在展开面板中点选时保持面板打开并刷新剩余拼音的候选）。
- 展开候选面板长按详情：在 `CandidatesPanel` 与 `SegmentPanel` 中长按候选词可在面板上方的候选栏显示读音、词典来源以及 Mozc 词性、原形与活用详情。
- 混合模式候选语言标识：候选栏、`CandidatesPanel` 与 `SegmentPanel` 中的混合模式候选词旁显示与设置页一致的微型 `中` / `EN` / `日` 标识，由 `mixed.language_tags` 设置项控制（默认开启 `true`，可在“设置 → 混合模式”中开关）。
- 2026-11-19 核对上游，未变：librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2、Gradle 9.9.1、Android Gradle Plugin 9.5.1、Kotlin 2.4.21、Compose BOM 2026.09.00、ML Kit GenAI 1.0.0-beta1、ML Kit translate 17.0.3、NDK r30、平台 37.2、构建工具 37.0.0、CC-CEDICT 2026-11-14T02:25:17Z、jmdict-simplified 3.6.2+20261109214828、Unicode 18.0 与 cldr-json 49.0.0。
- 2026-11-19 发布（`versionCode` 2）。在开发主机上验证：`java tool/CheckDocs.java`、`java lexicon/CheckSamples.java`（40/40）、主机 C++ 混合引擎测试（`11/11`）、整套转换语料（`301/302`，0 回退，Mixed 模式条目经协调层 `133/133` 全部通过）、`./gradlew check assembleDebug assembleRelease`，以及模拟器（Android 17、x86_64、16 KB 页、debug）：`CandidateBarTest` 3/3、`MixedTypingTest` 31/31、`FieldTypingTest` 29/29，并核对浅色/深色主题、四种界面语言与最大字体缩放（2.0）截图。本次发布周期未在物理真机上运行验证。

## 0.1.0（2026-11-14）

- 仓库脚手架：工作规范（`AGENTS.md`）、计划（`PLAN.md`，来自用户的草案 2.0）、镜像文档和文档检查（`tool/CheckDocs.java`）。
- 2026-11-04 阅读的上游版本，尚未构建：librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2；Android API 37、Android Gradle
  Plugin 9.5.0、Kotlin 2.4.21、NDK r30。
- Emoji 数据（P1），2026-11-04 核对：Unicode Emoji 18.0 与 CLDR 49.0.0 注释，均为最新版本；拼音与假名读音沿用上面的
  Unihan、CC-CEDICT 与 Mozc 锁定版本。
- 2026-11-10 在 x86_64 Linux 主机上恢复开发时核对上游：Gradle 9.8.2 → 9.9.1，Android Gradle Plugin 9.5.0 → 9.5.1；
  CC-CEDICT 移到 2026-11-10T08:36:22Z 的文件，jmdict-simplified 移到 3.6.2+20261109214828（JMdict 2026-11-09）。核对后未变：
  librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2、Kotlin 2.4.21、Compose BOM 2026.09.00、各 AndroidX 库、
  ML Kit GenAI 1.0.0-beta1、NDK r30、Unicode 18.0、CLDR 49.0.0、各 rime 数据仓库与 Bazelisk 1.30.0。
- 词库包的数据源于 2026-11-11 锁定在最新版本（`build-lock.json`；尚未生成、尚未分发）：rime-ice 2026.06.30、rime-frost 1.0.4、
  fcitx5-pinyin-zhwiki 0.3.1（20261018 的转储）、THUOCL `a30ce79d`、SudachiDict 20261015、mecab-ipadic-neologd 种子 20200910、
  mozcdic-ut 2026-11-03、SCOWLv2 rel-2026.02.25。
- 2026-11-11 核对上游：CC-CEDICT 移到 2026-11-11T02:50:39Z 的文件（S5 样本 34/34，反例 0/8）。核对后未变：librime 1.17.0、
  Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2、Gradle 9.9.1、Android Gradle Plugin 9.5.1、Kotlin 2.4.21、Compose BOM
  2026.09.00、各 AndroidX 库、ML Kit GenAI 1.0.0-beta1、NDK r30、平台 37.2、构建工具 37.0.0、jmdict-simplified
  3.6.2+20261109214828、Unicode 18.0、CLDR 49.0.0、各 rime 数据仓库与 Bazelisk 1.30.0。
- 2026-11-11 为 Q6 再次核对词库数据源，未变：rime-ice 2026.06.30、rime-frost 1.0.4、fcitx5-pinyin-zhwiki 0.3.1、
  THUOCL `a30ce79d`、SudachiDict 20261015、mozcdic-ut 2026-11-03、SCOWLv2 rel-2026.02.25。此后应用直接从来源下载提供的
  词库（THUOCL、rime-ice 的 `en_dicts.zip` 与 `8105`、zhwiki 的网络用语），取来源当前的版本；锁定只固定主机测试转换的文件。
- 2026-11-11 为协调层的设备接入（Q7）再次核对上游，未变：librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2 与 HeliBoard 4.2
  （按各自的标签；HeliBoard 唯一更新的标签是预发布的 4.3-beta1）；工具链与数据源同上一条。Kotomo 的 Mozc 胶水层
  加入了给协调层的函数表，因此重新构建了两个 Android 库，并把它们的哈希记入 `build-lock.json`；`mozc.data` 未变。
- 2026-11-12 的来源审查（S8，`third-party.md`）：从发布源读到的最新版本：SudachiDict v20261015、Noto Emoji v2026-09-24-unicode18_0、Twemoji v17.0.3、OpenMoji 17.0.0、jmdict-simplified 3.6.2+20261109214828、cldr-json 49.0.0（均未变或为新记录）；THUOCL 未变。
- 2026-11-13 为翻译（Q9）核对上游：新加入 ML Kit `translate` 17.0.3（最新版本）；ML Kit GenAI 校对与改写 1.0.0-beta1、librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2（更新的只有 4.3-beta1）、AGP 9.5.1 与 Kotlin 2.4.21（2.5.0 是候选版本）未变。
- 2026-11-14 核对上游：CC-CEDICT 移到 2026-11-14T02:25:17Z 的文件，因为 MDBG 替换了当前文件，之前锁定的文件已无法下载（S5 样本 34/34，反例 0/8；拼音 → 日文表有三行变化，`emoji.tsv` 未变；转换语料无回退）。核对后未变：librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2（更新的只有 4.3-beta1）、Gradle 9.9.1、Android Gradle Plugin 9.5.1、Kotlin 2.4.21（2.5.0 是候选版本）、Compose BOM 2026.09.00、jmdict-simplified 3.6.2+20261109214828 与 cldr-json 49.0.0。
- 2026-11-14 发布（`versionCode` 1），第一个版本。上游即上面 2026-11-14 那次检查的版本。在开发主机上验证过：文档检查、Gradle
  检查、主机引擎测试、整套转换语料无回退（Mixed 模式的条目经协调层 115 条全部通过），以及模拟器上的插桩测试（Android 17、
  x86_64、16 KB 页；`e3871fe` 上 107 项：105 项通过、1 项跳过，1 项失败过一次、单独运行三次都通过，原因没有确认）；已签名的 `arm64-v8a` release 构建（开启
  R8）在用户的手机上按 `tests/device/checklist.md` 安装使用了四轮，最后一轮是 `4e60561` 的构建；发布的版本与该构建相比只改了文档和
  CI 工作流。没有验证的：清单中有 115 行没有记录，其中包括最后一轮的待复测行；关卡 G0–G5 没有判定；从未在真机上测量延迟和内存；
  设备端 AI（Gemini Nano）从未运行；CI 模拟器那次运行中 `MixedTypingTest` 的五项失败（`platform-notes.md`）没有得到解释。
