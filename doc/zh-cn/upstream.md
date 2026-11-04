# 上游契约

Kotomo 依赖上游的哪些行为，以及每一点核实到什么程度。上游跟随各自的最新发行版，所以上游一有变动就重新核实相应条目。
以下各点于 2026-11-04 在上游仓库、源码和文档中阅读所得，没有构建或运行任何东西；P0 逐项复核，并把"已阅读"替换为结果。

## librime

| 要点 | 影响 | 核实 |
|---|---|---|
| BSD-3-Clause；CMake 与 C++17；需要 Boost ≥ 1.74、LevelDB、marisa、OpenCC ≥ 1.0.2 和 yaml-cpp ≥ 0.5（glog 可选） | 作为子模块用 NDK 从源码构建 | 已阅读 |
| 嵌入接口 `rime_api.h`：会话、`process_key`、`set_input`、`get_context`（预编辑、光标、候选页）、`get_commit`、候选选择与迭代、`set_option`、`register_module` | 拼音片段可以经 `set_input` 与 `get_context` 转换；更深的接入走插件模块 | 已阅读 |
| 模糊音和双拼都是方案中的 `speller/algebra` 规则 | 修改模糊音开关会改写方案并重新部署，所以部署耗时很重要 | 已阅读 |
| Trime 用 Gradle 的 CMake 集成构建 librime（NDK 30.0.16248370）；fcitx5-android 链接独立 prebuilder 产出的静态库 | 两种构建方式都可用 | 已阅读 |

## Mozc

| 要点 | 影响 | 核实 |
|---|---|---|
| Google 的代码为 BSD-3-Clause；词典数据带有自己的声明（IPAdic 适用 NAIST 与 ICOT 条款、公有领域的冲绳方言词典、Google 的增补条目） | 声明随数据一起分发 | 已阅读 |
| 只能用 Bazel（Bazelisk）构建；Android 构建（`docs/build_mozc_for_android.md`）只在 macOS 或 Linux 主机上产出 16 KB 对齐的 `libmozc.so` | Mozc 在 Linux、macOS 或 CI 上构建；Windows 机器使用锁定的预构建产物 | 已阅读 |
| 自带的 JNI 注册在 Google 的类 `com.google.android.apps.inputmethod.libs.mozc.session.MozcJni` 上，只暴露 `evalCommand`（序列化的 `commands::Command`）、`onPostLoad` 和 `getDataVersion`；Android 客户端没有开源 | Kotomo 自写客户端和 C++ 胶水层 | 已阅读 |
| `Segment::SegmentType` 有 `FREE`、`FIXED_BOUNDARY`、`FIXED_VALUE`、`SUBMITTED` 与 `HISTORY`；`ImmutableConverter::Resegment` 保留 `FIXED_VALUE` 片段的值和 lid/rid，只用于转换请求 | 在转换器层的胶水代码中，可以把跨语言片段固定在日文整句转换里；预测候选无法携带固定片段 | 已阅读 |
| 只发布标签（最近检查时为 `3.35.6354`） | 子模块跟随最新标签 | 已阅读 |

## OpenCC

| 要点 | 影响 | 核实 |
|---|---|---|
| Apache-2.0；提供简繁、台湾与香港（含词组，`s2twp`、`s2hk` 等）以及日文新字体（`t2jp`、`jp2t`，官方称"有限支持"）的配置 | 地区转换直接使用；其新字体表只是词库流水线的输入之一 | 已阅读 |

## 英文引擎

| 要点 | 影响 | 核实 |
|---|---|---|
| HeliBoard v4.2（`0c60dffb`，2026-10-12）在 `app/src/main/jni/src` 中带有 AOSP LatinIME 的原生建议核心；每个核心文件保留 AOSP 的 Apache-2.0 文件头，仓库整体为 GPL-3.0 | 子模块 `third_party/heliboard`，未修改；`core/english/` 把核心与 Kotomo 自己的 JNI 桥接编译为 `libkotomo_english.so`（16 KB 对齐，只导出 Kotomo 的三个 JNI 符号） | 已构建（NDK 30，arm64-v8a 与 x86_64） |
| 核心的 `ProximityInfo`、`Dictionary` 与 `SuggestionResults` 需要 `JNIEnv` 和 Java 数组；结果按分数从低到高输出，且可能重复列出同一个词 | 桥接层自己构建 QWERTY 邻近网格（32 × 16 格，与 `ProximityInfo.computeNearestNeighbors` 相同），按分数从高到低排序并去重；触摸点使用固定的 1000 × 600 QWERTY 坐标系 | 已在主机上测试 |
| 主词典为二进制格式 202（单个只读文件），按路径、偏移与长度打开；AOSP 的 dicttool（`127336e9`）可直接用 `javac` 编译，`makedict -2` 把 `.combined` 词表转为该格式，结果逐字节可复现 | `tool/BuildEnglishDict.java`；应用直接从 APK 中映射未压缩的资源文件 | 已在主机上测试 |
| 输入时给出纠错与补全（`helo` → hello、`teh` → the）以及二元组预测；乱码会得到多词拆分，LatinIME 的 Java 层原本会过滤它们 | Kotomo 在任何自动替换之前都需要自己的置信度阈值 | 已在主机上测试 |
| 手势解码为空实现；手势输入需要 Google 的闭源库 | 手势输入等待开源解码器 | 已阅读 |

## Android 输入法平台

| 要点 | 影响 | 核实 |
|---|---|---|
| Android 17（API 37）是最新稳定平台；Google Play 自 2026-08-31 起要求以 API 36 为目标；面向 API 35 及以上的应用必须支持 16 KB 页，Play 自 2027-02-01 起拒绝不支持的更新 | `compileSdk` 与 `targetSdk` 取 37；所有本地库 16 KB 对齐 | 已阅读 |
| `IME_FLAG_NO_PERSONALIZED_LEARNING` 是请求而非保证；密码输入框使用 `TYPE_TEXT_VARIATION_*PASSWORD` 各变体与 `TYPE_NUMBER_VARIATION_PASSWORD` | Kotomo 把两者都当作硬规则 | 已阅读 |
| 内联自动填充建议（API 30）、手写笔回调（API 33 起），以及面向 API 37 时用于中日韩转换的 `TextAttribute.Builder.setTextSuggestionSelected()` | 由需要它们的阶段使用 | 已阅读 |
| Android 17 按设备内存限制应用内存，并且在未处理的旋转后不再恢复键盘可见性 | 内存在 Android 17 上测量 | 已阅读 |
| 只有默认输入法和焦点应用能读取剪贴板（Android 10+）；默认输入法读取时不显示提示（Android 12+）；敏感剪贴带 `ClipDescription.EXTRA_IS_SENSITIVE`；系统在最后一次访问后最迟 60 分钟清除剪贴板（Android 13+） | Kotomo 自己保存剪贴板历史，从不记录敏感剪贴 | 已阅读 |
| `ComposeView` 需要视图树中的 `LifecycleOwner` 与 `SavedStateRegistryOwner`；没有面向输入法的官方指南 | 技术验证 S1 决定面板是否使用 Compose | 已阅读 |

## ML Kit

| 要点 | 影响 | 核实 |
|---|---|---|
| GenAI 只在应用位于最前台时推理；前台服务得到 `BACKGROUND_USE_BLOCKED`；每应用配额返回 `BUSY` 与 `PER_APP_BATTERY_USE_QUOTA_EXCEEDED`。开发者报告从 `InputMethodService` 调用同样被拒 | AI 在用户打开的 Kotomo 界面中运行；技术验证 S7 在设备上核实 | 已阅读 |
| 校对与重写（beta）支持英语、日语、法语、德语、意大利语、西班牙语和韩语，不含中文，输入少于 256 token，需要 API 26 及以上，解锁 bootloader 的设备不可用；Prompt API（beta）输入少于 4,000 token | 英文和日文使用这些 API；中文不提供校对与重写，也不使用 Prompt API | 已阅读 |
| 设备端翻译覆盖中文、日文和英文（每种语言约 30 MB，按需下载）；不含英语的语言对经英语中转。它是 ML Kit 的普通 API，不属于 GenAI，文档没有前台限制的说明 | 翻译直接在键盘中运行（技术验证 S7 确认）；中日互译的质量评估之后再提供 | 已阅读 |

## 数据集

| 要点 | 影响 | 核实 |
|---|---|---|
| JMdict 与 KANJIDIC2 为 CC BY-SA 4.0（EDRDG）；应用须在从菜单进入的独立页面中署名，并保持数据更新；优先级标记标出常用词与外来语（`gai1`） | 英文 → 日文映射；"关于"下的来源页 | 已阅读 |
| GPLv3 是 CC BY-SA 4.0 的单向兼容许可（Creative Commons，2015） | CC BY-SA 4.0 数据可随 GPL-3.0 应用分发，并保留署名 | 已阅读 |
| Unihan（Unicode License v3）按字提供 `kMandarin`；Unicode 18.0 新增 `kJapaneseOldVariant` 与 `kJapaneseNewVariant`；新字体可能与无关的汉字同形（芸 yún 与 藝 yì） | 拼音 → 日文映射先把新字体映射到旧字体 | 已阅读 |
| CLDR 49 注释（Unicode License v3）提供 en、zh、zh-Hant 与 ja 的 Emoji 关键词和名称 | Emoji 关键词词库 | 已阅读 |
| Noto Emoji：字体 OFL-1.1，图像 Apache-2.0，支持 Unicode 18.0；Twemoji（jdecked）：图形 CC BY 4.0，覆盖 Emoji 17.0；Apple 的 Emoji 不得再分发，也不得包含在第三方键盘中 | Emoji 样式包；绝不使用 Apple 的图像 | 已阅读 |
| 中文方案数据：rime-ice 为 GPL-3.0 only，rime_wanxiang 为 CC BY 4.0，rime-luna-pinyin 与 rime-essay 为 LGPL-3.0。日文：SudachiDict 为 Apache-2.0；alt-cannadic 只允许 GPL-2.0 | 在技术验证 S2 与 S8 中选定；从不使用仅限 GPL-2.0 的数据 | 已阅读 |
| Unihan 18 给部分日本国字标了没有中文词典佐证的 `kMandarin`（込 yū、峠 qiǎ、笹 shì），另一些则有佐证（畑 tián、辻 shí） | 只有 `kXHC1983`、`kTGHZ2013`、`kHanyuPinyin` 或 CC-CEDICT 的单字条目佐证的读音才算数 | 在 S5 中使用 |
| `kJapaneseOldVariant` 的 363 条中有 62 条指向 NFC 会折回原字的 CJK 兼容汉字（神 → U+FA19） | 取读音前先去掉 | 在 S5 中使用 |
| OpenCC 的新字体词组给出历史写法（世論 → 輿論、予定 → 預定），也可能保留新字体（回転 → 廻転）；OpenCC 与 Unihan 对部分字的旧字体不一致（挙、弁） | 先试词组，再在两张表的并集上逐字映射 | 在 S5 中使用 |
| CC-CEDICT 把 ü 写作 `u:`，专有名词首字母大写，并有占位读音（`xx5`）；MDBG 只发布当前文件 | 以文件的日期头与 SHA-256 锁定，有意识地更新 | 在 S5 中使用 |
| jmdict-simplified 把 JMdict 的优先级标记（`news1`、`ichi1`、`spec1/2`、`gai1`）合并为一个 `common` 标志，并提供 `languageSource`；EDRDG 自己的文件每天替换 | 发行版可以按哈希锁定；外来语取自 `languageSource`，而不是 `gai1` | 在 S5 中使用 |
| Mozc `dictionary_oss` 的行格式为读音、lid、rid、代价、表层形式（共 1,289,014 条；普通名词为 1851 号）；平假名表层形式会与其他词的活用形冲突 | 纯假名的映射不与 Mozc 词条关联，使用普通名词 | 在 S5 中使用 |
