# 函数索引

每个模块和工具一行：它的职责，以及应先阅读的声明。声明在文档注释中带有函数说明层（第一句说明用途；非平凡的声明再写
输入、返回值、副作用和注意事项）。

| 模块 / 文件 | 职责 | 从这里开始 |
|---|---|---|
| `tool/CheckDocs.java` | 文档镜像与链接检查 | `main`、`read` |
| `app/build.gradle.kts` | Android 应用构建：SDK 级别、版本号、R8、从 `key.properties` 读取 release 签名 | `signingConfigs`、`buildTypes` |
| `build-lock.json` | 工具链、上游与数据源的版本及下载哈希 | `toolchain` |
| `.github/workflows/build.yml` | CI：文档检查、Gradle 检查与构建、签名的 release APK；`v*` 标签时创建 GitHub Release | `android`、`release` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/KotomoService.kt` | 输入法服务：各模式与引擎线程上的引擎、按 `Fields` 的判定直接输入与不学习、生效中的设置（SettingsStore）、Compose 面板的宿主 | `onKey`、`refresh`、`applyEngineSettings` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Layout.kt` | 键盘页面（底行随输入框调整的字母页、符号页、电话与数字键盘、在铰链两侧分开的底行）、按键布局与命中判定（纯 Kotlin，有单元测试） | `Layout.letters`、`Layout.pad`、`Layout.place`、`Layout.hit` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Fields.kt` | 输入框适配与空格规则（PLAN §8.7、§8.1）：由输入类型与提示判定输入框种类、敏感输入框与直接输入的唯一定义（PLAN §14）、回车键样式、双击空格句号、智能空格（纯 Kotlin，有单元测试） | `Fields.of`、`Fields.sensitive`、`Fields.direct`、`Fields.enter` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Posture.kt` | 布局所用的窗口尺寸类别与折叠姿态（PLAN §8.10）：何时分离、桌面姿态与矮窗口下的高度上限（有单元测试） | `Posture.split`、`Posture.heightLimit` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/KeyboardView.kt` | 按设计稿绘制的 Canvas 键盘（手机，以及宽屏上固定宽度的分离两半）、点按、Gboard 式长按弹出（滑动选择、松开输入）、按键预览、在空格上拖动移动光标与从退格向左拖动删除词、退格连删、每个按键的 TalkBack 节点；`PopupLayer` 绘制弹出框 | `onTouchEvent`、`onDraw`、`PopupLayer` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/CandidateBar.kt` | 按键上方的一行：可滑动的候选胶囊、展开箭头、空闲时的工具栏、状态提示、长按后显示的候选详情（读音、来源，学到的词可忘记） | `show`、`hint`、`details`、`onTouchEvent` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/EmojiPanel.kt` | 在键盘位置显示的 Compose 面板：Emoji 面板（按分组列出系统字体能显示的全部 Emoji；仅在输入框允许学习时把最近使用的记入应用私有偏好；长按选择肤色）、全部候选，以及日文片段修改（整句与各片段的按钮、所选片段的候选、缩短、延长） | `EmojiPanel`、`Recents`、`TonePicker`、`CandidatesPanel`、`SegmentPanel` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/EmojiIndex.kt` | 内存中的 Emoji 数据，在主线程之外加载一次：分组、肤色变体，以及按英文、中文、拼音、日文、假名与罗马字查找（纯 Kotlin 解析，有单元测试） | `parse`、`lookup`、`load` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/clipboard/ClipboardHistory.kt` | 剪贴板历史（[clipboard.md](../clipboard.md)）：只在允许时记录（敏感输入框、敏感剪贴与无痕模式不记录）、去重、固定与快捷短语、保留期与数量上限、以 Keystore 密钥加密的 AES-GCM 文件、内存快照；剪贴分类 | `onPrimaryClip`、`allowedIn`、`items`、`ClipFile`、`ClipKind.of` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/clipboard/VerificationCodes.kt` | 从复制的文本中识别验证码：带上下文的排序结果，排除日期、价格、电话与订单号（纯 Kotlin，有单元测试） | `find`、`bare` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/clipboard/ClipboardPanel.kt`、`QuickPaste.kt` | 在键盘位置显示的剪贴板面板（分类标签、两列卡片、页脚、长按操作）与键盘上方的快捷输入条及其提供规则（60 秒、每条剪贴一次） | `ClipboardPanel`、`QuickPaste`、`QuickPasteStrip` |
| `lexicon/EmojiData.java` | Emoji 数据管线：下载并校验 Unicode Emoji 与 CLDR 数据，生成 `emoji.tsv`（分组、肤色、键；拼音取自 CC-CEDICT 与 Unihan，假名取自 Mozc，罗马字为平文式）；由应用的 `emojiData` 任务调用 | `main`、`SOURCES`、`index` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Probe.kt` | S1 的测量（只有耗时和内存）及其保存的摘要 | `Probe`、`LatencyStats` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/MainActivity.kt` | 设置应用的外壳：带设置引导卡片和试打框的首页、页面导航（单栏加返回栈；840 dp 起导航列表与页面并排）、由 Kotomo 令牌生成的 Material 3 主题 | `SettingsApp`、`Page`、`SetupCard` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/Pages.kt` | 各设置页（输入模式、混合模式、中文、日文、英文、布局与尺寸、按键与手势、隐私（含剪贴板设置）、之后提供的页面）与诊断页；共用的行与控件 | `MixedPage`、`LayoutPage`、`DiagnosticsPage`、`Section` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/About.kt` | 关于、开源许可证、数据来源与设计致谢；其中的致谢列表与 [third-party.md](../third-party.md) 一致 | `CODE`、`DATA`、`AboutPage` |
| `design/` | 设计 Agent 的设计稿：每个界面一个 HTML 画板（手机、折叠屏、平板、设置）和共用的键盘按键区组件，从设计画布固化而来；总览页与 Kotomo 自己的画板模板渲染器 | `index.html`、`support.js`、`Keyboard.dc.html`、`canvas.json` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Candidates.kt` | 由各引擎的结果组装候选栏的列表（中文在两个最佳词之后放原样的字母；日文的整句、补全、片段与假名，各带自己的读音；英文原词在先；Emoji 的位置），以及选择、缩短、延长日文片段时的分段；键盘与转换语料共用 | `chinese`、`japanese`、`pick`、`resize`、`extend` |
| `tests/conversion/` | 转换语料（`corpus.tsv`）、已接受的结果（`baseline.tsv`）与运行器（`run.sh`）；`app/src/test/…/ConversionCorpusTest.kt` 给每一行排名并报告回退 | `ConversionCorpusTest` |
| `app/src/androidTest/`、`app/src/debug/…/fieldtest/` | 在模拟器或真机上经 Kotomo 的键输入的插桩测试，以及它们输入的、只在 debug 构建中存在的输入框 | `FieldTypingTest`、`FieldTestActivity` |
| `tests/device/` | 里程碑时交给用户的真机检查清单（逐行 PASS / FAIL） | `p1-checklist.md` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/Settings.kt`、`SettingsStore.kt` | 设置模型、带版本的 Preferences DataStore 文件（[settings-format.md](../settings-format.md)），以及进程内共用、无需 I/O 即可读取快照的存储 | `Settings`、`SettingsFormat`、`SettingsStore.current` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/Themes.kt`、`app/src/main/java/com/yuanzhe/kotomo/dictionary/Json.kt` | 以带版本的 JSON 文件保存的键盘主题（`files/themes/current.json`，[settings-format.md](../settings-format.md)）及其所用的纯 Kotlin JSON 读写（有单元测试） | `ThemeFormat`、`ThemeFile`、`Json.parse` |
| `lexicon/CrossLexicon.java` | 跨语言词库流水线（S5）：获取并校验数据源，在 `build/lexicon/` 中生成拼音 → 日文与英文 → 日文表 | `main`、`SOURCES` |
| `lexicon/CheckSamples.java` | 用 `tests/lexicon/samples.tsv`（正例与反例）检查生成的表 | `main` |
| `lexicon/llm-dictionary-prompt.txt` | 词库设置页将提供的提示词（中文，来自用户的草案），用于借助 LLM 制作词库；目前还没有读取方 | — |
| `core/CMakeLists.txt` | 应用的顶层原生构建；每个引擎库一个子目录 | — |
| `core/english/` | 英文引擎（S4）：LatinIME 核心与 Kotomo 的 JNI 桥接的 CMake 构建；主机测试 | `jni/english_jni.cpp`、`tests/run-host.sh` |
| `app/src/main/java/com/yuanzhe/kotomo/engine/English.kt` | `libkotomo_english.so` 的 Kotlin 外观（只在引擎线程上调用） | `English.open`、`suggestions` |
| `tool/BuildEnglishDict.java` | 用 AOSP 的 dicttool 从锁定的词表构建英文词典 | `main` |
| `core/rime/` | 中文引擎（S2）：把 librime 及其依赖构建为 `libkotomo_rime.so` 的 CMake 构建、桥接层、数据整理与主机测试 | `jni/rime_bridge.h`、`stage-data.cmake`、`tests/rime_bridge_test.cc` |
| `app/src/main/java/com/yuanzhe/kotomo/engine/Rime.kt` | `libkotomo_rime.so` 的 Kotlin 外观（只在引擎线程上调用） | `initialize`、`deploy`、`processKey`、`setLearning` |
| `scripts/fetch-boost.sh` | 下载并校验 librime 需要的 Boost 头文件 | — |
| `core/mozc/` | 日文引擎胶水层（S3）：基于 Mozc 的会话转换、固定片段与固定边界转换、预测，JNI，主机测试；在 Mozc 的 Bazel 工作区内构建 | `kotomo_mozc.h`、`tests/kotomo_mozc_test.cc` |
| `app/src/main/java/com/yuanzhe/kotomo/engine/Mozc.kt` | `libkotomo_mozc.so` 的 Kotlin 外观：日文模式用 `sessionConvert` 转换、`predict` 预测、`pinnedConvert`（固定片段与固定边界）修改片段 | `sessionConvert`、`pinnedConvert`、`predict` |
| `scripts/build-mozc.sh` | 为主机或 Android ABI 构建 Mozc 与胶水层（Android 需要 x86_64 Linux 或 macOS）；把 Android 产物放进 `build/mozc-prebuilt`，并与 `build-lock.json` 比较 | `--abi` |
| `.github/workflows/mozc.yml` | CI（新的 GitHub 仓库建立之前不运行）：主机测试，以及带哈希清单的 Android Mozc 库产物 | `mozc` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/RimeDeploy.kt` | 在独立进程中把 librime 部署到暂存目录、`filesDir/rime` 下的文件布局、构建的换入与部署报告 | `RimeDeployService`、`RimeFiles.promote` |
| `core/rime/tools/prebuild.cc`、`scripts/prebuild-rime.sh` | 在 Linux 主机上把 librime 的数据编译进随 APK 分发的共享数据 | `main` |
| `scripts/fetch-mozc.sh` | 获取 mozc.yml 构建的 Mozc 库与数据，按 build-lock.json 中的运行编号与 SHA-256 锁定；该运行已于 2026-11-04 随 GitHub 仓库删除，CI 重新存在之前它下载不到任何东西；`build-mozc.sh` 在本地构建同样的文件 | — |
| `app/src/main/java/com/yuanzhe/kotomo/ime/QuickPanel.kt` | 在键盘位置显示的工具栏快捷设置：单手方向、分离布局、简繁、无痕、设置应用 | `QuickPanel` |
| `app/src/main/java/com/yuanzhe/kotomo/ai/GenAi.kt` | 通过 ML Kit GenAI 用 Gemini Nano 校对与改写（[ai.md](../ai.md)）：状态、由用户发起的下载、长文字拆分、错误映射 | `GenAi`、`AiAction`、`AiFailure` |
| `app/src/main/java/com/yuanzhe/kotomo/ai/AiActivity.kt`、`AiHandoff.kt` | 写作助手页面（语言、操作、建议、替换、复制）及其与键盘之间的内存交接 | `AiActivity`、`AiHandoff`、`AiRequest` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/KeyboardColors.kt` | 按所选主题（跟随系统、浅色、深色、Material You）为视图与 Compose 面板提供键盘调色板 | `apply`、`color`、`kbColor` |
