# 第三方代码、数据与素材

Kotomo 以 GPL-3.0 授权。本清单列出它复用或内嵌的每一个上游——代码、词库数据、Emoji 素材和字体——用在哪里，以及由此产生的
义务。`THIRD_PARTY_NOTICES.md` 收录声明本身；应用内的许可证页面列出同样的组件。

## 清单

| 组件 | 仓库与版本 | 用途 | 许可证 | 修改 | 义务 |
|---|---|---|---|---|---|
| MyApps 的工作规范与文档检查 | `YuanZhe-99/Kyokai`（`tools/checkdocs`）、`YuanZhe-99/MyApps-AI`（`tool/check_docs.py`）、MyApps 的 `AGENTS.md` 规则 | `tool/CheckDocs.java` 沿用其镜像与链接规则；`AGENTS.md` 沿用其工作规范 | AGPL-3.0 与 GPL-3.0，与 Kotomo 同一作者 | 为 Kotomo 重新编写 | 除 Kotomo 自身的许可证外无其他义务 |
| Mozc 词典数据（`src/data/dictionary_oss`：`dictionary00–09.txt`、`id.def`、`README.txt`） | `google/mozc` 标签 3.35.6354 | 为跨语言词库提供词条、读音、词性 ID 与代价（S5；构建输入，生成的词库尚未随应用分发） | 源自 IPAdic（mecab-ipadic-2.7.0-20070801，NAIST 与 ICOT 条款）、冲绳词典数据与 Google 增补 | 无 | 由其派生的任何词库都随附 `README.txt` 中的声明与免责条款 |
| Unihan | Unicode 18.0.0（`Unihan.zip`） | 为拼音 → 日文映射提供读音（`kMandarin`，以 `kXHC1983`、`kTGHZ2013`、`kHanyuPinyin` 佐证）、`kSimplifiedVariant` 与 `kJapaneseOldVariant`（S5） | Unicode License v3 | 无 | 附上 Unicode 版权与许可声明 |
| CC-CEDICT | MDBG 发布于 2026-11-04T07:19:40Z | 为拼音 → 日文映射提供词级拼音（S5） | CC BY-SA 4.0（MDBG；基于 Paul Andrew Denisowski 的 CEDICT） | 无 | 署名并链接许可；派生的读音仍为 CC BY-SA 4.0 |
| OpenCC 日文新字体表（`JPShinjitaiCharacters.txt`、`JPShinjitaiPhrases.txt`） | `BYVoid/OpenCC` ver.1.4.2 | 为拼音 → 日文映射把新字体映射到旧字体（S5） | Apache-2.0 | 无 | 附上许可与 NOTICE |
| JMdict（英文），经 jmdict-simplified | jmdict-simplified 3.6.2+20261102210542（JMdict 2026-11-02） | 为英文 → 日文映射提供释义（S5） | 依 EDRDG 许可的 CC BY-SA 4.0 | 无 | 在从菜单进入的独立来源页中注明 EDRDG 为版权所有者，链接许可与 JMdict 项目页，保持数据更新；派生的映射仍为 CC BY-SA 4.0 |
| AOSP LatinIME 原生建议核心，经 HeliBoard | `HeliBorg/HeliBoard` v4.2（`0c60dffb`），`app/src/main/jni/src` | 英文纠错、补全与预测（`libkotomo_english.so`，S4） | Apache-2.0（AOSP 文件）；HeliBoard 整体为 GPL-3.0 | 无；以 Kotomo 自己的 JNI 桥接代替 LatinIME 的 JNI 文件 | 随附 Apache-2.0 许可文本并保留 AOSP 声明 |
| AOSP LatinIME dicttool（仅为构建工具） | AOSP `platform/packages/inputmethods/LatinIME` `127336e9` | 构建时生成英文词典；不随应用分发 | Apache-2.0 | 无 | 对应用无义务 |
| 英文词表：Helium314/aosp-dictionaries 的 `main_en_US__experimental.combined`，源自 Leipzig Corpora Collection | codeberg `Helium314/aosp-dictionaries` `ff0cd52b` | 英文词汇与二元组（`main_en_us.dict`，生成后随 APK 分发） | CC BY 4.0（Leipzig：可下载的语料以 CC BY 授权） | 转换为 LatinIME 二进制格式 202 | 在来源页中为 Leipzig Corpora Collection（莱比锡大学）与 HeliBoard 的 aosp-dictionaries 署名，链接 CC BY 4.0，并注明数据经过转换 |

## 计划中的组件

以下组件目前都尚未包含。P0 加入某个组件时，连同其确切版本、文件、修改与义务写入上面的清单；技术验证 S8 完成整体审查。

| 组件 | 许可证 | 计划用途 | 说明 |
|---|---|---|---|
| librime | BSD-3-Clause | 中文引擎 | 连同 Boost、LevelDB、marisa 与 yaml-cpp，构建时记录它们的许可证 |
| Mozc | BSD-3-Clause；词典数据适用 IPAdic（NAIST 与 ICOT）声明，另有公有领域与 Google 增补条目 | 日文引擎与词典 | 词典声明随数据一起分发 |
| OpenCC | Apache-2.0 | 简体、繁体与地区用词转换 | 与 librime 共用 |
| 一套中文方案与词库 | rime-ice 为 GPL-3.0 only；rime_wanxiang 为 CC BY 4.0；rime-luna-pinyin 与 rime-essay 为 LGPL-3.0 | 中文词汇与语言模型 | 在技术验证 S2 中选定；仅限 GPL-3.0 的数据会使整个发行版只能以 GPL-3.0 发布 |
| CLDR 注释 | Unicode License v3 | 四种语言的 Emoji 关键词 | — |
| SudachiDict | Apache-2.0 | 补充日文人名、地名、车站名与外来语 | 可选 |
| Noto Emoji | 图像 Apache-2.0，字体 OFL-1.1，旗帜公有领域 | Emoji 样式包 | — |
| Twemoji（jdecked） | 图形 CC BY 4.0，代码 MIT | Emoji 样式包 | 在"关于"中署名 |
| ML Kit GenAI 与翻译 | Google 的 ML Kit 条款 | 设备端 AI 与翻译 | 其数据处理方式在技术验证 S8 中核实，并写入隐私政策 |

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
