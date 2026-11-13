# 第三方代码、数据与素材

Kotomo 以 GPL-3.0 授权。本清单列出它复用或内嵌的每一个上游——代码、词库数据、Emoji 素材和字体——用在哪里，以及由此产生的
义务。`THIRD_PARTY_NOTICES.md` 收录声明本身；设置应用的"关于与致谢"列出同样的组件（代码在开源许可证页面，词库数据及其作者在
数据来源页面），以及下文的设计参考。

## 清单

| 组件 | 仓库与版本 | 用途 | 许可证 | 修改 | 义务 |
|---|---|---|---|---|---|
| MyApps 的工作规范与文档检查 | `YuanZhe-99/Kyokai`（`tools/checkdocs`）、`YuanZhe-99/MyApps-AI`（`tool/check_docs.py`）、MyApps 的 `AGENTS.md` 规则 | `tool/CheckDocs.java` 沿用其镜像与链接规则；`AGENTS.md` 沿用其工作规范 | AGPL-3.0 与 GPL-3.0，与 Kotomo 同一作者 | 为 Kotomo 重新编写 | 除 Kotomo 自身的许可证外无其他义务 |
| Mozc 词典数据（`src/data/dictionary_oss`：`dictionary00–09.txt`、`id.def`、`README.txt`） | `google/mozc` 标签 3.35.6354 | 为跨语言词库提供词条、读音、词性 ID 与代价（S5；构建输入，生成的词库尚未随应用分发）；为 `emoji.tsv` 的日文键提供假名读音（随应用分发）；`id.def` 的词性名称编入 `libkotomo_mozc.so`，用于日文候选的词性与活用（随应用分发，PLAN §10.1、§10.2） | 源自 IPAdic（mecab-ipadic-2.7.0-20070801，NAIST 与 ICOT 条款）、冲绳词典数据与 Google 增补 | 无 | 由其派生的任何词库都随附 `README.txt` 中的声明与免责条款 |
| Unihan | Unicode 18.0.0（`Unihan.zip`） | 为拼音 → 日文映射提供读音（`kMandarin`，以 `kXHC1983`、`kTGHZ2013`、`kHanyuPinyin` 佐证）、`kSimplifiedVariant` 与 `kJapaneseOldVariant`（S5，随 `cross.klx` 分发）；为 `emoji.tsv` 的拼音键提供 `kMandarin` | Unicode License v3 | 无 | 附上 Unicode 版权与许可声明 |
| CC-CEDICT | MDBG 发布于 2026-11-11T02:50:39Z | 为拼音 → 日文映射（S5，随 `cross.klx` 分发）与 `emoji.tsv` 的拼音键（随应用分发）提供词级拼音 | CC BY-SA 4.0（MDBG；基于 Paul Andrew Denisowski 的 CEDICT） | 无 | 署名并链接许可；派生的读音仍为 CC BY-SA 4.0 |
| Unicode Emoji 数据（`emoji-test.txt`） | Unicode Emoji 18.0（`Public/18.0.0/emoji/`，日期 2026-04-30） | Emoji 面板的 Emoji 顺序、分组与肤色变体（`emoji.tsv`，生成后随 APK 分发） | Unicode License v3 | 去掉 Component 组 | 附上 Unicode 版权与许可声明 |
| CLDR 注释与派生注释（cldr-json） | `unicode-org/cldr-json` 49.0.0（`a4b67247`），en、zh、zh-Hant 与 ja 的 `annotations` 与 `annotationsDerived` | Emoji 关键词与名称，即 `emoji.tsv` 的键 | Unicode License v3 | 无；键经过规范化（NFKC、小写） | 附上 Unicode 版权与许可声明 |
| OpenCC 日文新字体表（`JPShinjitaiCharacters.txt`、`JPShinjitaiPhrases.txt`） | `BYVoid/OpenCC` ver.1.4.2 | 为拼音 → 日文映射把新字体映射到旧字体（S5） | Apache-2.0 | 无 | 附上许可与 NOTICE |
| 双拼键位（微软、搜狗、小鹤、自然码） | 与 `rime/rime-double-pinyin`（微软、小鹤、自然码）及 rime-ice 的搜狗方案（`double_pinyin_sogou.schema.yaml`）核对 | `Fuzzy.kt` 为 `chinese.double_pinyin` 生成的拼写规则 | 这些方案是公开的键位；核对所用的方案文件为 LGPL-3.0 与 GPL-3.0 | 不复制任何文件：规则在 Kotomo 生成的 `kotomo_fuzzy.yaml` 中重新写出，沿用这些方案的规则顺序 | 除 Kotomo 的许可证外无 |
| JMdict（英文），经 jmdict-simplified | jmdict-simplified 3.6.2+20261109214828（JMdict 2026-11-09） | 为英文 → 日文映射提供释义（S5，随 `cross.klx` 分发） | 依 EDRDG 许可的 CC BY-SA 4.0 | 无 | 在从菜单进入的独立来源页中注明 EDRDG 为版权所有者，链接许可与 JMdict 项目页，保持数据更新；派生的映射仍为 CC BY-SA 4.0 |
| AOSP LatinIME 原生建议核心，经 HeliBoard | `HeliBorg/HeliBoard` v4.2（`0c60dffb`），`app/src/main/jni/src` | 英文纠错、补全与预测（`libkotomo_english.so`，S4） | Apache-2.0（AOSP 文件）；HeliBoard 整体为 GPL-3.0 | 无；以 Kotomo 自己的 JNI 桥接代替 LatinIME 的 JNI 文件 | 随附 Apache-2.0 许可文本并保留 AOSP 声明 |
| AOSP LatinIME dicttool（仅为构建工具） | AOSP `platform/packages/inputmethods/LatinIME` `127336e9` | 构建时生成英文词典；不随应用分发 | Apache-2.0 | 无 | 对应用无义务 |
| 英文词表：Helium314/aosp-dictionaries 的 `main_en_US__experimental.combined`，源自 Leipzig Corpora Collection | codeberg `Helium314/aosp-dictionaries` `ff0cd52b` | 英文词汇与二元组（`main_en_us.dict`，生成后随 APK 分发） | CC BY 4.0（Leipzig：可下载的语料以 CC BY 授权） | 转换为 LatinIME 二进制格式 202 | 在来源页中为 Leipzig Corpora Collection（莱比锡大学）与 HeliBoard 的 aosp-dictionaries 署名，链接 CC BY 4.0，并注明数据经过转换 |
| librime | `rime/librime` 1.17.0（`33e78140`）；内含 darts-clone 与 utf8-cpp 头文件 | 中文引擎（`libkotomo_rime.so`，S2） | BSD-3-Clause（darts-clone 为 BSD-2-Clause，utf8-cpp 为 BSL-1.0） | 无；构建时不含插件与日志 | 保留版权与许可声明 |
| LevelDB | `google/leveldb` 1.23（`99b3c03b`） | librime 的用户词典 | BSD-3-Clause | 无 | 声明 |
| marisa-trie | `s-yata/marisa-trie` v0.3.1（`3e87d53b`） | librime 与 OpenCC 的字典树 | BSD-2-Clause 或 LGPL-2.1-or-later，按 BSD-2-Clause 使用 | 无 | 声明 |
| yaml-cpp | `jbeder/yaml-cpp` 0.9.0（`56e3bb55`） | librime 的配置 | MIT | 无 | 声明 |
| OpenCC | `BYVoid/OpenCC` ver.1.4.2（`025f371d`）；内含 rapidjson 与 darts-clone | librime 内的繁体 → 简体、台湾与香港转换（带地区用词，Kotomo 的 `t2twp.json` 与 `t2hkp.json`） | Apache-2.0（rapidjson 为 MIT） | 无；构建时整理文本词典并改写 `t2s.json` | 许可与 NOTICE；注明 `t2s.json` 经过转换 |
| Boost（头文件） | boost.org 1.92.0 | librime 使用的头文件 | BSL-1.0 | 无 | 源码分发时附许可文本 |
| rime-luna-pinyin、rime-prelude、rime-essay、rime-stroke | `rime/*`，分别在 `56b934b0`、`082425ea`、`054920de`、`1e8fff9b` | 拼音方案、词典、默认设置与语言模型；编译后的笔画数据随应用分发，供 `u` 开始的笔画查字（PLAN §10.4） | LGPL-3.0 | 无；Kotomo 增加 `default.custom.yaml` 与方案 `luna_pinyin_simp_private` | 随附 LGPL-3.0 与 GPL-3.0 文本，并提供数据来源 |
| Mozc | `google/mozc` 3.35.6354（`0136c357`），子模块 `third_party/mozc` | 日文转换（`libkotomo_mozc.so` 与 `mozc.data`，S3；单独构建的库打包进 APK 时由日文模式使用） | BSD-3-Clause；词典数据适用 NAIST IPAdic 声明与 ICOT 条款，另有公有领域的冲绳词典与 Google 增补 | 无；Kotomo 的胶水层独立存放（`core/mozc`） | 完整随附 Mozc 的 LICENSE，包括 NAIST 声明与 ICOT 的 "NO WARRANTY" 一节 |
| japanese-usage-dictionary、Abseil、Protocol Buffers | 由 Mozc 的 Bazel 构建获取（用法词典 2025-01-25、abseil-cpp 20260107.1、protobuf 34.1） | 编入 `mozc.data` 与 `libkotomo_mozc.so` | BSD-2-Clause；Apache-2.0；BSD-3-Clause | 无 | 声明与许可文本 |
| Google ML Kit：`genai-proofreading`、`genai-rewriting`、`translate` | Google Maven 1.0.0-beta1、1.0.0-beta1、17.0.3（最新版本，2026-11-13） | 写作助手（经 AICore 用 Gemini Nano 校对、改写）与设备端翻译；翻译的语言模型（每种约 30 MB）只在用户点按“下载”时从 Google 下载 | ML Kit 服务条款（闭源；条款未读） | 无 | 在许可证页致谢；在隐私政策中写明 ML Kit 自身收集的数据（`upstream.md`） |
| AndroidX（Compose、Material 3、Activity、Lifecycle、Core、SavedState、DataStore、Autofill）、Kotlin 标准库、kotlinx.coroutines、okio | Google Maven 与 Maven Central，版本见 `gradle/libs.versions.toml`（Compose BOM 2026.09.00、DataStore 1.3.0、Autofill 1.3.0、Kotlin 2.4.21） | 设置应用、键盘的 Compose 面板、设置文件、内联自动填充建议的样式 | Apache-2.0 | 无 | 随附 Apache-2.0 许可文本 |

编译后的跨语言词库 `cross.klx`（`lexicon-format.md`）由 `lexicon/CrossLexicon.java` 与
`kotomo_lexicon_compile` 生成（从不提交），自混合模式接入协调层的那次构建起随 APK 分发。它沿用所派生数据的
许可证：取自 JMdict 的映射（EDRDG 许可）与取自 CC-CEDICT 的读音为 CC BY-SA 4.0，Unihan 的读音与异体字为
Unicode License v3，OpenCC 的异体字表为 Apache-2.0，日文词、读音与代价随附 Mozc 词典的声明。关于页的数据来源
逐一列出它们。

`emoji.tsv` 由 `lexicon/EmojiData.java` 生成（从不提交），随 APK 分发。它保留其来源的许可证：Emoji、CLDR 关键词与
Unihan 读音为 Unicode License v3，取自 CC-CEDICT 的拼音为 CC BY-SA 4.0，日文读音随附 Mozc `README.txt` 中的声明；
应用内的许可证页面加入后，在首个版本发布前列出以上全部来源。Emoji 用系统字体绘制：不分发任何 Emoji 图像。

## 按需下载

以下来源不在 APK 中。设置只在用户要求时，从作者发布的地方下载它们并在设备上转换（`dictionaries.md`、ADR-011）。
数据来源页列出每一项及其许可证与作者，每个已安装的词库包在 `pack.json` 中保留其许可证、署名与（THUOCL 的）引用说明。

| 组件 | 仓库与版本 | 用途 | 许可证 | 修改 | 义务 |
|---|---|---|---|---|---|
| rime-ice 的 `en_dicts/en_ext.dict.yaml`（发布附件 `en_dicts.zip`）与 `cn_dicts/8105.dict.yaml`（普通文件） | `iDvel/rime-ice` 最新发布（`nightly`）；主机测试锁定 2026.06.30（`6810e891`） | 词库包 `en-ice-tech`；THUOCL 词库包的单字读音 | GPL-3.0 | 转换：读音规范化、权重缩放到 0–100、丢弃无效行 | 与 Kotomo 同为 GPL-3.0；署名作者；转换后的词库包仍为 GPL-3.0 |
| THUOCL（11 个领域词表） | `thunlp/THUOCL` `master`（2018-11-21 后未变，`a30ce79d`） | 11 个词库包 `zh-thuocl-*` | MIT；README 要求引用 | 读音由 rime-ice 的单字表推出（没有主导读音的多音字丢弃），权重缩放 | 保留 MIT 声明；引用"THUOCL：清华大学开放中文词库"（2016）；读音为 GPL-3.0（rime-ice） |
| fcitx5-pinyin-zhwiki 的 `web-slang-<日期>.dict.yaml` | `felixonmars/fcitx5-pinyin-zhwiki` 最新发布；主机测试锁定 0.3.1（转储 20261018） | 词库包 `zh-web-slang` | CC BY-SA 4.0（维基百科）；转换器 Unlicense | 丢弃拼写出的拉丁字母 | 署名维基百科贡献者并链接许可证；词库包保持 CC BY-SA 4.0 |

## 来源审查（技术验证 S8）

2026-11-12 对照各来源的官方页面、仓库或发布源核实。"未读"表示本次审查没有核实该点，不是结论。结论分三种：**可用**、**有条件可用**（满足所写条件后可用）、**不可用**。本节不会把任何数据文件放进仓库。

| 来源 | 核实到的最新版本 | 读到的许可证 | 结论 | 义务与说明 |
|---|---|---|---|---|
| JMdict 与 KANJIDIC2（EDRDG） | jmdict-simplified 3.6.2+20261109214828（2026-11-09） | EDRDG 许可下的 CC BY-SA 4.0；JMdict 只覆盖日文与英文，其他语言的释义另有版权；KANJIDIC2 中的拼音、SKIP 等编码归各贡献者 | JMdict 英文：可用（已分发）。KANJIDIC2：不使用 | 在专门的页面（关于或数据来源）致谢 EDRDG，随附或链接许可与文档，不得声称版权，保持定期更新的办法（许可把数据过期视为违规；只靠发布版本是否满足，留给用户决定） |
| Unicode 数据：Unihan、`emoji-test.txt`、CLDR（cldr-json） | cldr-json 49.0.0（2026-10-22），Unicode 18.0 | 按记录为 Unicode License v3；未读许可证文本 | 可用（已分发） | Unicode 声明（`THIRD_PARTY_NOTICES.md`） |
| SudachiDict | v20261015（2026-10-15） | `LEGAL`：整个发行版为 Apache-2.0；`small_lex` 带 UniDic 的 BSD 式声明；`core_lex` 与 `notcore_lex` 来自 NEologd，`LEGAL` 没有为它单独写许可证，只列出致谢（Hatena、日本邮政、一份车站名表、一份人名数据、网络抓取数据） | 有条件可用：`small_lex` 可用；`core_lex` 与 `notcore_lex` 可按 Apache-2.0 声明使用，其子来源没有逐一核实 | 任何衍生词库包都附带 `LEGAL` 与 UniDic 声明；暂不提供 |
| THUOCL | `a30ce79d`，未变 | README：研究与商用均免费；仓库标为 MIT | 可用（已提供下载） | 保留声明与引用 |
| rime-ice | 发布源标签 `nightly`（API 标的发布日期为 2024-05-21，资产每日重建，`build-lock.json` 固定在 2026.06.30） | 按记录为 GPL-3.0；未读许可证文本 | 可用（已提供下载） | GPL-3.0，署名作者 |
| Noto Emoji | v2026-09-24-unicode18_0（2026-09-24），Unicode 18.0 | 字体 SIL OFL-1.1；工具与"大部分"图像资源 Apache-2.0；旗帜（`third_party/region-flags`）为公有领域或不受版权保护；没有写明署名要求 | 有条件可用：读完"大部分"之外的例外与旗帜文件自己的条款（未读）后，可作样式包 | 附 OFL 与 Apache-2.0 文本；仍在数据来源页署名 |
| Twemoji（jdecked） | v17.0.3（2026-06-01），Emoji 17.0 | 代码 MIT，图形 CC BY 4.0；项目接受在 README、关于或设置页中提及 | 可用 | 在"关于"中署名并链接许可证；Unicode 18.0 新增的 Emoji 缺失，由系统字体补上 |
| OpenMoji | 17.0.0（2026-04-23） | 按 `PLAN.md` 的记录为 CC BY-SA 4.0；未读 | 有条件可用：相同方式共享会波及改编后的图像；不作为第一个样式包的候选 | 使用前先核实许可证 |
| Apple 的 Emoji；来源不明的图像 | — | 按记录为 Apple 的许可与 App Review 指南 5.2.5；本次未重读 | 不可用 | 永不分发 |
| JLPT 词汇表（Tanos，Jonathan Waller） | 该网站已无法解析；镜像写明作者 Jonathan Waller 与"CC BY"，没有版本；词表非官方（JLPT 不公布词表） | 版本未注明 | 暂不可用 | 适用"不分发未注明版本的 CC 许可"的规则；若找到许可页面或版本再重新评估 |
| 铁道数据，MLIT 国土数值情报 N02 | 2024 年版（`N02-24`） | 数据集页面标明 2020 年版起为 CC BY 4.0；站点的总条款为公共数据许可 1.0 | 有条件可用：站名可用；数据集不含读音 | 注明来源与页面网址，并说明做过加工；读音取自 Mozc 或 SudachiDict；基于基础地图的产品所需的测量法批准不涉及站名，Kotomo 不使用几何数据 |
| 駅データ.jp | — | 其使用条款未读 | 读过之前不可用 | — |
| Wikidata 标签 | — | 结构化数据（主、Property、Lexeme 命名空间）为 CC0，标签包括在内 | 有条件可用：取得文件并量过覆盖率后，可用于日文 ↔ 中文的专有名词对 | 不要求署名；讨论页为 CC BY-SA 4.0，不使用 |
| Tatoeba 例句 | — | 默认 CC BY 2.0 FR；部分例句与音频带有其他条件（ND、SA、非商用音频） | 有条件可用：只作例句或测试句，按每句的许可证过滤；不用音频 | 署名作者 |
| Wiktionary、Chinese Open Wordnet | — | 未读 | 读过之前不可用 | — |

数据缺口（`PLAN.md` §3.3）：

- 日文词条的常见中文释义：没有找到许可明确的来源。JMdict 没有中文；CC-CEDICT 只对用相同汉字书写的词有用，拼音 → 日文的映射已经在用；Wikidata 标签可能覆盖专有名词。该功能等待。
- JLPT 分级：等待（没有可用的词表）。
- 车站名：MLIT N02 的站名，加上取自 Mozc 或 SudachiDict 的读音；数据集之外的线路仍然缺失。
- 专业领域词库：中文有 THUOCL 的 11 个词表（已提供下载）；日文没有找到许可明确的来源，所以日文领域词库等待。

用户 2026-11-13 的决定：有条件可用的各项按所写条件推进，包括 Noto Emoji 与 Wikidata。同日另决定：JMdict 只随 Kotomo 发版更新，用户接受这满足 EDRDG 的更新规则；发版步骤本来就把所有数据源更新到最新版本（`AGENTS.md`），不另加流程。

## 计划中的组件

以下组件目前既未包含、也未提供下载。加入某个组件时，连同其确切版本、文件、修改与义务写入上面的清单；技术验证 S8
完成整体审查。Q6 对未提供下载的词库来源的评估见 `dictionaries.md`。

| 组件 | 许可证 | 计划用途 | 说明 |
|---|---|---|---|
| SudachiDict 原始词表（`small_lex`、`core_lex`） | Apache-2.0；`small_lex` 含 UniDic 的一部分（BSD-3-Clause，见 `LEGAL`：须随附 UniDic 的版权声明、条件与免责声明） | Mozc 用户词典能读取后的日文词库；`build-lock.json` 锁定 20261015 | 暂不提供（`dictionaries.md`） |
| rime-frost（`base`、`ext`） | GPL-3.0 | 另一套中文基础词库 | 不提供：它的发布只有 44–88 MB 的整包，普通文件没有校验值，且与 rime-ice 重叠 |
| rime-ice 的 `base.dict.yaml`、`ext.dict.yaml` | GPL-3.0 | 中文常用词与扩展词 | 暂不提供：能在手机上转换，但 librime 每次选择方案都把下载的词库包当作文本词表载入，模拟器上 50 万行使键盘 25 秒无法打字 |
| rime-ice 的 `tencent.dict.yaml`、`41448.dict.yaml` | GPL-3.0 | — | 不用 `tencent`：其词条来自腾讯 AI Lab 词向量的词表，腾讯自己的页面没有写明许可证（有一个目录标为 CC BY 3.0）；`41448` 是没有权重的单字 |
| fcitx5-pinyin-zhwiki 的 `zhwiki` | 维基百科标题 CC BY-SA 4.0；转换器 Unlicense | 中文专有名词 | 不提供：57 MB、170 万行，在手机上转换与保存都太大 |
| mecab-ipadic-neologd 种子（20200910） | Apache-2.0，附 `COPYING` 中的来源致谢 | 日文新词 | 不提供：xz 压缩（Android 没有解码器）、322 万行、2020 年起停更 |
| mozcdic-ut 地名、人名、jawiki | 公有领域（日本邮政邮编数据）；Apache-2.0；CC BY-SA 4.0 | 日文地名、人名、维基百科标题 | 不提供：bzip2 压缩（Android 没有解码器）；日文词条尚未使用 |
| SCOWLv2（en-wl/wordlist） | 类 MIT（`Copyright`）；大于 80 号的词表另附 UKACD 条款 | 英文词 | 不提供：发布只有源文件，要用 Python 与 SQLite 才能生成词表；英文词条尚未使用 |
| Noto Emoji | 图像 Apache-2.0，字体 OFL-1.1，旗帜公有领域 | Emoji 样式包 | — |
| Twemoji（jdecked） | 图形 CC BY 4.0，代码 MIT | Emoji 样式包 | 在"关于"中署名 |

## 永不分发

- Apple 的 Emoji 图像：Apple 的软件许可禁止再分发，App Store 审核指南（5.2.5）也禁止第三方键盘包含它们。
- 来源不明的 Emoji 图像，包括 Telegram 安卓版自带的图像。
- 只允许 GPL-2.0（如 alt-cannadic）、CC BY-SA 3.0 或未注明版本的 CC 许可的数据。
- 取自其他输入法的词库、图像、声音或主题。

## 设计参考

Kotomo 的交互设计参考了下列产品。Kotomo 是独立项目：与它们没有隶属关系，也没有得到它们的认可或授权，不使用它们的任何代码、
词库、图像或其他受保护的素材。

| 产品 | Kotomo 从中借鉴的内容 |
|---|---|
| [玛嗒玛嗒（Madamada）](https://madamada.catchingnow.com/)及其开发者——特别致谢 | 用中文拼音输入日语汉字、用英文输入片假名；拼音、英文与罗马字混合连打；整句转换；候选词读音标注；动词变形与敬语切换；分类日语词库；自动生词本；城市主题与个性化键盘 |
| [Gboard](https://play.google.com/store/apps/details?id=com.google.android.inputmethod.latin) | 键盘布局、触控操作、候选栏、多语言输入、Emoji、滑动输入、输入框适配 |
| [微软拼音](https://support.microsoft.com/windows/microsoft-simplified-chinese-ime-9b962a3b-2fa4-4f37-811c-b1886320dd72) | 模糊音、长句转换、词频学习、双拼、U/V 模式、拼音纠错 |
| [搜狗输入法](https://shurufa.sogou.com/) | 智能联想、长句输入、按键纠错、词库管理、用户造词、候选操作、键盘皮肤 |
| [Microsoft SwiftKey](https://www.microsoft.com/swiftkey) | 个性化与多语言预测、英文纠错、智能空格、剪贴板、输入手势 |
| [Google 日本語入力](https://www.google.co.jp/ime/)与 [Mozc](https://github.com/google/mozc) | 罗马字转换、假名汉字转换与长句转换、文节转换、日语词典与预测 |

Telegram 只作为在键盘内绘制 Emoji 的参考；这从不意味着可以使用它的 Emoji 素材。

## 商标

本文件中的产品名称是其各自所有者的商标。Android 是 Google LLC 的商标。
