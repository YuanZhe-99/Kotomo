# 函数索引

每个模块和工具一行：它的职责，以及应先阅读的声明。声明在文档注释中带有函数说明层（第一句说明用途；非平凡的声明再写
输入、返回值、副作用和注意事项）。

| 模块 / 文件 | 职责 | 从这里开始 |
|---|---|---|
| `tool/CheckDocs.java` | 文档镜像与链接检查 | `main`、`read` |
| `app/build.gradle.kts` | Android 应用构建：SDK 级别、版本号、R8、从 `key.properties` 读取 release 签名 | `signingConfigs`、`buildTypes` |
| `build-lock.json` | 工具链、上游与数据源的版本及下载哈希 | `toolchain` |
| `.github/workflows/build.yml` | CI：文档检查、Gradle 检查与构建、签名的 release APK；`v*` 标签时创建 GitHub Release | `android`、`release` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/KotomoService.kt` | 输入法服务：各模式与引擎线程上的引擎、敏感输入框中的直接输入、生效中的设置（SettingsStore）、Compose 面板的宿主 | `onKey`、`isDirect`、`refresh` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Layout.kt` | 键盘页面、按键布局与命中判定（纯 Kotlin，有单元测试） | `Layout.letters`、`Layout.place`、`Layout.hit` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/KeyboardView.kt` | 按设计稿绘制的 Canvas 键盘（手机，以及宽屏上固定宽度的分离两半）、点按、Gboard 式长按弹出（滑动选择、松开输入）、按键预览、在空格上拖动移动光标与从退格向左拖动删除词、退格连删、每个按键的 TalkBack 节点；`PopupLayer` 绘制弹出框 | `onTouchEvent`、`onDraw`、`PopupLayer` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/CandidateBar.kt` | 按键上方的一行：可滑动的候选胶囊、展开箭头、空闲时的工具栏、状态提示 | `show`、`hint`、`onTouchEvent` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/EmojiPanel.kt` | 在键盘位置显示的 Compose 面板：Emoji 面板（按分组列出系统字体能显示的全部 Emoji；仅在输入框允许学习时把最近使用记入应用私有的偏好设置；长按选择肤色）与全部候选 | `EmojiPanel`、`Recents`、`TonePicker`、`CandidatesPanel` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/EmojiIndex.kt` | 内存中的 Emoji 数据，在主线程之外加载一次：分组、肤色变体，以及按英文、中文、拼音、日文、假名与罗马字查找（纯 Kotlin 解析，有单元测试） | `parse`、`lookup`、`load` |
| `lexicon/EmojiData.java` | Emoji 数据管线：下载并校验 Unicode Emoji 与 CLDR 数据，生成 `emoji.tsv`（分组、肤色、键；拼音取自 CC-CEDICT 与 Unihan，假名取自 Mozc，罗马字为平文式）；由应用的 `emojiData` 任务调用 | `main`、`SOURCES`、`index` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Probe.kt` | S1 的测量（只有耗时和内存）及其保存的摘要 | `Probe`、`LatencyStats` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/MainActivity.kt` | 设置应用的外壳：带设置引导卡片和试打框的首页、页面导航（单栏加返回栈；840 dp 起导航列表与页面并排）、由 Kotomo 令牌生成的 Material 3 主题 | `SettingsApp`、`Page`、`SetupCard` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/Pages.kt` | 各设置页（输入模式、混合模式、中文、日文、英文、布局与尺寸、按键与手势、隐私、之后提供的页面）与诊断页；共用的行与控件 | `MixedPage`、`LayoutPage`、`DiagnosticsPage`、`Section` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/About.kt` | 关于、开源许可证、数据来源与设计致谢；其中的致谢列表与 [third-party.md](../third-party.md) 一致 | `CODE`、`DATA`、`AboutPage` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/Settings.kt`、`SettingsStore.kt` | 设置模型、带版本的 Preferences DataStore 文件（[settings-format.md](../settings-format.md)），以及进程内共用、无需 I/O 即可读取快照的存储 | `Settings`、`SettingsFormat`、`SettingsStore.current` |
| `lexicon/CrossLexicon.java` | 跨语言词库流水线（S5）：获取并校验数据源，在 `build/lexicon/` 中生成拼音 → 日文与英文 → 日文表 | `main`、`SOURCES` |
| `lexicon/CheckSamples.java` | 用 `tests/lexicon/samples.tsv`（正例与反例）检查生成的表 | `main` |
| `core/CMakeLists.txt` | 应用的顶层原生构建；每个引擎库一个子目录 | — |
| `core/english/` | 英文引擎（S4）：LatinIME 核心与 Kotomo 的 JNI 桥接的 CMake 构建；主机测试 | `jni/english_jni.cpp`、`tests/run-host.sh` |
| `app/src/main/java/com/yuanzhe/kotomo/engine/English.kt` | `libkotomo_english.so` 的 Kotlin 外观（只在引擎线程上调用） | `English.open`、`suggestions` |
| `tool/BuildEnglishDict.java` | 用 AOSP 的 dicttool 从锁定的词表构建英文词典 | `main` |
| `core/rime/` | 中文引擎（S2）：把 librime 及其依赖构建为 `libkotomo_rime.so` 的 CMake 构建、桥接层、数据整理与主机测试 | `jni/rime_bridge.h`、`stage-data.cmake`、`tests/rime_bridge_test.cc` |
| `app/src/main/java/com/yuanzhe/kotomo/engine/Rime.kt` | `libkotomo_rime.so` 的 Kotlin 外观（只在引擎线程上调用） | `initialize`、`deploy`、`processKey`、`setLearning` |
| `scripts/fetch-boost.sh` | 下载并校验 librime 需要的 Boost 头文件 | — |
| `core/mozc/` | 日文引擎胶水层（S3）：基于 Mozc 的会话转换与固定片段转换、JNI、主机测试；在 Mozc 的 Bazel 工作区内构建 | `kotomo_mozc.h`、`tests/kotomo_mozc_test.cc` |
| `app/src/main/java/com/yuanzhe/kotomo/engine/Mozc.kt` | `libkotomo_mozc.so` 的 Kotlin 外观（Android 库生成前应用不使用它） | `sessionConvert`、`pinnedConvert` |
| `scripts/build-mozc.sh` | 为主机或 Android ABI 构建 Mozc 与胶水层（Android 需要 x86_64 Linux 或 macOS） | `--abi` |
| `.github/workflows/mozc.yml` | CI：主机测试，以及带哈希清单的 Android Mozc 库产物 | `mozc` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/RimeDeploy.kt` | 在独立进程中把 librime 部署到暂存目录、`filesDir/rime` 下的文件布局、构建的换入与部署报告 | `RimeDeployService`、`RimeFiles.promote` |
| `core/rime/tools/prebuild.cc`、`scripts/prebuild-rime.sh` | 在 Linux 主机上把 librime 的数据编译进随 APK 分发的共享数据 | `main` |
| `scripts/fetch-mozc.sh` | 获取 mozc.yml 构建的 Mozc 库与数据，按 build-lock.json 中的运行编号与 SHA-256 锁定 | — |
