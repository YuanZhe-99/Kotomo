# 函数索引

每个模块和工具一行：它的职责，以及应先阅读的声明。声明在文档注释中带有函数说明层（第一句说明用途；非平凡的声明再写
输入、返回值、副作用和注意事项）。

| 模块 / 文件 | 职责 | 从这里开始 |
|---|---|---|
| `tool/CheckDocs.java` | 文档镜像与链接检查 | `main`、`read` |
| `app/build.gradle.kts` | Android 应用构建：SDK 级别、版本号、R8、从 `key.properties` 读取 release 签名 | `signingConfigs`、`buildTypes` |
| `build-lock.json` | 工具链、上游与数据源的版本及下载哈希 | `toolchain` |
| `.github/workflows/build.yml` | CI：文档检查、Gradle 检查与构建、签名的 release APK；`v*` 标签时创建 GitHub Release | `android`、`release` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/KotomoService.kt` | 输入法服务（S1）：组合、占位候选、敏感输入框中的直接输入、Compose 面板的宿主 | `onKey`、`isDirect`、`refresh` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Layout.kt` | 键盘页面、按键布局与命中判定（纯 Kotlin，有单元测试） | `Layout.letters`、`Layout.place`、`Layout.hit` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/KeyboardView.kt` | Canvas 键盘：绘制、点按、长按、退格连删 | `onTouchEvent`、`onDraw` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/CandidateBar.kt` | 候选栏：绘制与选择 | `show`、`onTouchEvent` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/EmojiPanel.kt` | 带示例 Emoji 的 Compose 面板（S1 对 Compose 的验证） | `EmojiPanel` |
| `app/src/main/java/com/yuanzhe/kotomo/ime/Probe.kt` | S1 的测量（只有耗时和内存）及其保存的摘要 | `Probe`、`LatencyStats` |
| `app/src/main/java/com/yuanzhe/kotomo/settings/MainActivity.kt` | 设置应用：键盘设置引导、试打框、诊断 | `SettingsScreen` |
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
