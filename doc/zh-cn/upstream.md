# 上游契约

Kotomo 依赖上游的哪些行为，以及每一点核实到什么程度。上游跟随各自的最新发行版，所以上游一有变动就重新核实相应条目。
以下各点于 2026-11-04 在上游仓库、源码和文档中阅读所得，没有构建或运行任何东西；P0 逐项复核，并把"已阅读"替换为结果。

## librime

| 要点 | 影响 | 核实 |
|---|---|---|
| BSD-3-Clause；CMake 与 C++17；需要 Boost ≥ 1.74、LevelDB、marisa、OpenCC ≥ 1.0.2 和 yaml-cpp ≥ 0.5（glog 可选） | 作为子模块用 NDK 从源码构建 | 已阅读 |
| 嵌入接口 `rime_api.h`：会话、`process_key`、`set_input`、`get_context`（预编辑、光标、候选页）、`get_commit`、候选选择与迭代、`set_option`、`register_module` | 拼音片段可以经 `set_input` 与 `get_context` 转换；更深的接入走插件模块 | 已阅读 |
| luna_pinyin 的拼写运算已经应用 `pinyin:/spelling_correction` 与 `pinyin:/key_correction`（`hoa` → hao，`tain` → tian）；`translator/enable_correction` 会加载 `.correction.bin`，而 1.17 的词典编译器从不写出它（那段代码在 `#if 0` 中），会话因找不到文件而中止 | 中文拼写纠错来自方案的规则；`enable_correction` 保持关闭 | 主机测试，语料 T01-012、T01-015 |
| 模糊音和双拼都是方案中的 `speller/algebra` 规则 | 修改模糊音开关会改写方案并重新部署，所以部署耗时很重要 | 已阅读 |
| Trime 用 Gradle 的 CMake 集成构建 librime（NDK 30.0.16248370）；fcitx5-android 链接独立 prebuilder 产出的静态库 | 两种构建方式都可用 | 已阅读 |
| 1.17.0 可用 NDK 30 与 CMake 3.22.1，按 Kotomo 自己的目标列表（`core/rime/CMakeLists.txt`）为 Android 构建；它对 Boost 的使用全部只需头文件，Boost 1.92.0 的头文件即可；没有 glog 时使用 `no_logging.h` | `libkotomo_rime.so`，每个 ABI 去符号后约 2.9 MB，16 KB 页面，只导出 JNI；输入内容不可能进入日志 | 已构建（arm64-v8a、x86_64），已在主机上测试 |
| 没有关闭学习的运行时开关；`translator/enable_user_dict: false` 使用户词典不被创建，因此既不读也不写 | 敏感输入框选用方案 `luna_pinyin_simp_private` | 主机测试 T12-S2 |
| 部署器在解析 `__include` 与 `__patch` 之前就从原始方案文件读取 `schema/schema_id`；只靠补丁设置 ID 的方案会被静默跳过 | 派生方案按字面写出 `schema:` 块 | 已在主机上测试 |
| `RimeDeployWorkspace` 同步部署，期间拒绝会话（主机：全新部署 5–15 s，未变化时约 0.2 s；峰值 RSS 294 MB） | librime 把 `<shared>/build` 当作预编译数据；会话可以在空的用户目录上直接打开，不编译任何东西（主机：30 ms） | 应用随附在 Linux 主机上编译好的数据（`scripts/prebuild-rime.sh`、CI），手机上只需解压 | 主机测试 |
| `RimeDeployService` 在独立进程（`:deploy`）中部署到暂存目录，键盘期间继续使用上一份构建的会话，完成后再换入新构建（PLAN §4.2）；会话可以在挪过来的构建上直接打开而无需部署（主机：30 ms），转换结果不变；只有第一次部署完成前，中文模式才直接输入字母，候选栏会给出提示 | 已在主机上测试；真机上 NOT_RUN |
| 会话接口（`set_input` 与候选遍历）在主机上每个前缀约 0.2 ms | 足以按片段调用（PLAN §5.4） | 仅主机 |
| 子模块不遵循仓库的 `.gitattributes`，而 CMake 的 `file(READ)`/`file(WRITE)` 在 Windows 上会转换换行符 | `core/rime/stage-data.cmake` 用 `configure_file(… NEWLINE_STYLE LF)` 写出共享数据，在各主机上逐字节相同 | 已在 Windows 与 Linux 上构建 |

## Mozc

| 要点 | 影响 | 核实 |
|---|---|---|
| Google 的代码为 BSD-3-Clause；词典数据带有自己的声明（IPAdic 适用 NAIST 与 ICOT 条款、公有领域的冲绳方言词典、Google 的增补条目） | 声明随数据一起分发 | 已阅读 |
| 只能用 Bazel（Bazelisk）构建；Android 构建（`docs/build_mozc_for_android.md`）只在 macOS 或 Linux 主机上产出 16 KB 对齐的 `libmozc.so` | Mozc 的 Android 库用 `scripts/build-mozc.sh` 在 x86_64 Linux 主机上构建 | 已阅读，已构建 |
| 自带的 JNI 注册在 Google 的类 `com.google.android.apps.inputmethod.libs.mozc.session.MozcJni` 上，只暴露 `evalCommand`（序列化的 `commands::Command`）、`onPostLoad` 和 `getDataVersion`；Android 客户端没有开源 | Kotomo 自写客户端和 C++ 胶水层 | 已阅读 |
| `Segment::SegmentType` 有 `FREE`、`FIXED_BOUNDARY`、`FIXED_VALUE`、`SUBMITTED` 与 `HISTORY`；`ImmutableConverter::Resegment` 保留 `FIXED_VALUE` 片段的值和 lid/rid，只用于转换请求 | 在转换器层的胶水代码中，可以把跨语言片段固定在日文整句转换里；预测候选无法携带固定片段 | 已阅读 |
| 只发布标签（最近检查时为 `3.35.6354`） | 子模块跟随最新标签 | 已阅读 |
| 3.35.6354（`0136c357`）没有 git 子模块；依赖通过 Bazel 模块（abseil-cpp 20260107.1、protobuf 34.1）与 `src/build_tools/update_deps.py`（Android NDK r29）获取 | `git submodule update --init third_party/mozc` 即可，其余由 Bazel 获取 | 已构建 |
| 主机构建可在 aarch64 Linux 上运行（Debian 13、gcc 14.2、经 Bazelisk 1.30.0 的 Bazel 9.0.2）；Bazel 的 `local_jdk` 需要 `JAVA_HOME` | 主机测试在 Windows on ARM64 的 WSL 中运行 | 已构建 |
| rules_android_ndk 获取 NDK 时会运行 NDK 的 clang，而 NDK 只有 x86_64 Linux 与 macOS 的主机工具；只要有 NDK，aarch64 Linux 上连主机构建也会失败 | Android 库在 x86_64 Linux 上构建；2026-11-10 的本地构建（Debian 13）逐字节复现了已删除的 CI 运行的哈希 | 已构建 |
| Bazel 在每个服务进程中只对 NDK 模块扩展（rules_android_ndk）求值一次：同一服务进程里先做主机构建、再获取 NDK 时，Android 构建找不到 C++ 工具链（"No matching toolchains found"） | `scripts/build-mozc.sh` 在第一条 Bazel 命令之前获取 NDK | 已构建 |
| `converter::Converter::ApplyConversion` 是公开接口；手工构造的含 `FIXED_VALUE` 片段的分段会保留固定的值，其余部分由 Mozc 分段并转换 | PLAN §5.4 的固定片段转换在转换器层可行，不需要退路：固定池袋 + `にかいにいこう` → 池袋に買いに行こう | 主机测试 |
| `FIXED_BOUNDARY` 片段经过 `ApplyConversion` 后仍是一段；预测（`StartPrediction`、`PREDICTION`）只有在移动端请求（`mixed_conversion`）下才补全读音，没有 composer 的平假名键还需要 `use_already_typing_corrected_key` | 片段的缩短与延长固定边界；`Predict` 两者都设置（ありがと → ありがとう，主机上 0.4 ms） | 主机测试 |
| `DictionaryInterface::LookupExact` 返回带 lid 与 rid 的词条（池袋/いけぶくろ：1924/1924）；拼音拼写没有词条 | 固定片段取同一词条的 ID；未知词使用普通名词 | 主机测试 |
| `//converter`、`//session:session_handler` 与 `//dictionary:dictionary_interface` 只对 Mozc 自身可见 | Kotomo 的胶水层用 `--nocheck_visibility` 构建，而不是打补丁 | 已构建 |
| 只有提交转换时才学习；`Config.incognito_mode` 与 `Request.is_incognito_mode` 关闭一切可变操作 | 胶水层从不提交，并在敏感输入框中同时设置两者 | 已阅读，主机测试 |
| 助词は的罗马字 `wa` 会转为わ：普通会话中 `watashiwanihonjin` → 私羽日本人 | 语料行 T03-001 需要协调层处理 | 主机测试 |

## OpenCC

| 要点 | 影响 | 核实 |
|---|---|---|
| Apache-2.0；提供简繁、台湾与香港（含词组，`s2twp`、`s2hk` 等）以及日文新字体（`t2jp`、`jp2t`，官方称"有限支持"）的配置 | 地区转换直接使用；其新字体表只是词库流水线的输入之一 | 已阅读 |
| 1.4.2 的数据构建需要 Python 和它自己的 `opencc_dict`；库本身也能读 `text` 词典；`Config::NewFromFile` 也会加载标记为 `may_output_tofu` 的词典（`TSCharactersExt`），缺少它会使整个配置失败 | Kotomo 分发文本词典，改写 `t2s.json`，并按 `extract_tofu_risk.py` 的方式生成 `TSCharactersExt.txt`（`core/rime/stage-data.cmake`） | 已在主机上测试 |

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
| CLDR 49 注释（Unicode License v3）提供 en、zh、zh-Hant 与 ja 的 Emoji 关键词和名称 | Emoji 关键词词库 | 在 P1 中使用 |
| cldr-json 文件不带版本字段，键省略 U+FE0F（`☺` 而非 `☺️`）；`annotations` 覆盖单个 Emoji，`annotationsDerived` 覆盖肤色与 ZWJ 序列（家庭、职业）；日文关键词不带读音 | 锁定到 49.0.0 标签的提交并校验 SHA-256；查找时去掉 U+FE0F 再试；两个文件都读取；假名读音取自 Mozc 词典 | 在 P1 中使用 |
| `emoji-test.txt` 中每个基础 Emoji 后紧跟它的全部肤色形式；混合肤色的双人组合没有自己的无肤色形式（👩🏻‍🤝‍👨🏼 属于 👫，🫱🏻‍🫲🏼 属于 🤝），Component 组只含单独的肤色色块与发型部件 | 带肤色的序列作为其前一个基础 Emoji 的变体；去掉 Component 组 | 在 P1 中使用 |
| Noto Emoji：字体 OFL-1.1，图像 Apache-2.0，支持 Unicode 18.0；Twemoji（jdecked）：图形 CC BY 4.0，覆盖 Emoji 17.0；Apple 的 Emoji 不得再分发，也不得包含在第三方键盘中 | Emoji 样式包；绝不使用 Apple 的图像 | 已阅读 |
| 中文方案数据：rime-ice 为 GPL-3.0 only，rime_wanxiang 为 CC BY 4.0，rime-luna-pinyin 与 rime-essay 为 LGPL-3.0。日文：SudachiDict 为 Apache-2.0；alt-cannadic 只允许 GPL-2.0 | 在技术验证 S2 与 S8 中选定；从不使用仅限 GPL-2.0 的数据 | 已阅读 |
| Unihan 18 给部分日本国字标了没有中文词典佐证的 `kMandarin`（込 yū、峠 qiǎ、笹 shì），另一些则有佐证（畑 tián、辻 shí） | `kMandarin` 的读音一律采用，因为中文词典对国字本来就没有读音（用户决定，2026-11-04）；只有完全没有读音的字（﨑、𩸽）会使其所在的词被跳过 | 在 S5 中使用 |
| `kJapaneseOldVariant` 的 363 条中有 62 条指向 NFC 会折回原字的 CJK 兼容汉字（神 → U+FA19） | 取读音前先去掉 | 在 S5 中使用 |
| OpenCC 的新字体词组给出历史写法（世論 → 輿論、予定 → 預定），也可能保留新字体（回転 → 廻転）；OpenCC 与 Unihan 对部分字的旧字体不一致（挙、弁） | 先试词组，再在两张表的并集上逐字映射；词组换成了另一个字时，该词同时保留历史读音（yulun）与按字面的读音（shilun），这是用户的决定（2026-11-04） | 在 S5 中使用 |
| CC-CEDICT 把 ü 写作 `u:`，专有名词首字母大写，并有占位读音（`xx5`）；MDBG 只发布当前文件 | 以文件的日期头与 SHA-256 锁定，有意识地更新 | 在 S5 中使用 |
| jmdict-simplified 把 JMdict 的优先级标记（`news1`、`ichi1`、`spec1/2`、`gai1`）合并为一个 `common` 标志，并提供 `languageSource`；EDRDG 自己的文件每天替换 | 发行版可以按哈希锁定；外来语取自 `languageSource`，而不是 `gai1` | 在 S5 中使用 |
| 词库包的数据源（2026-11-11 阅读）：rime-ice 与 rime-frost 的 `*.dict.yaml` 在 `...` 之后为 `词<TAB>空格分隔的无调拼音<TAB>权重`，ü 写作 `v`；rime-ice 的 `8105`、`41448` 是单字，`41448` 没有权重；zhwiki 的行没有权重，拉丁字母写成大写音节（`B zhan`）；THUOCL 为 `词<TAB>文档频率`，没有拼音并带填充空格；SudachiDict 原始 CSV 的第 0–5 列为见出し、lid、rid、代价、表层形式、词性，第 11 列为片假名读音，id 为 `-1` 的条目不参与分析；neologd 种子是 MeCab CSV（表层形式、lid、rid、代价、词性……、片假名读音），含 3,224,584 个专有名词与颜文字；mozcdic-ut 的文件是 Mozc 行（读音、lid、rid、代价、表层形式）；SCOWLv2 只发布源文件，`make`（Python 3、SQLite）构建 `scowl.db`，再用 `scowl word-list <size> A,B,Z,C 1` 列出词 | Q6 的生成器读取这些格式；没有拼音的中文词表（THUOCL）要从其他来源推出读音 | 已阅读 |
| Mozc `dictionary_oss` 的行格式为读音、lid、rid、代价、表层形式（共 1,289,014 条；普通名词为 1851 号）；平假名表层形式会与其他词的活用形冲突 | 纯假名的映射不与 Mozc 词条关联，使用普通名词 | 在 S5 中使用 |
