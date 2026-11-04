# 平台说明

| 平台 | 状态 |
|---|---|
| Android 手机（`arm64-v8a`） | 技术验证 S1 的骨架：带占位候选的键盘，尚无转换引擎；尚未在真机上运行。 |
| Android 模拟器（`x86_64`） | 在主机支持的情况下用于验证布局和插桩测试；从不作为延迟或设备端 AI 的证据。 |
| Android 平板和折叠屏 | 计划在 P1 与单手模式一起交付；布局从不假定手机屏幕尺寸。 |
| 桌面主机（Windows、Linux、macOS） | P0 起用于构建和测试引擎核心；从不作为产品目标平台。 |

## 仓库

在 Windows 11 on ARM64（2026-11-04；JDK 21.0.13）和 GitHub Actions（`ubuntu-latest`）上验证：

| 检查 | 结果 |
|---|---|
| 文档镜像与链接 | `java tool/CheckDocs.java` 通过 |
| 单元测试（S1：布局与命中判定、延迟百分位） | `./gradlew testDebugUnitTest`：11 个测试通过 |
| Lint | `./gradlew check` 通过，没有警告 |
| Release 构建 | `./gradlew assembleRelease`：启用 R8，以 release 密钥签名（APK 签名方案 v2） |

## 技术验证

| 技术验证 | 状态 | 证据 |
|---|---|---|
| S1 IME 骨架 | 真机上 NOT_RUN | 可以构建并通过单元测试。设置应用的诊断页记录按下到按键绘制、按下到候选绘制、冷启动到首帧、Compose Emoji 面板的首帧和内存，等待真机运行。 |
| S2 librime | 主机上 PASS；真机上 NOT_RUN | `libkotomo_rime.so` 可为 arm64-v8a 与 x86_64 构建（去符号后约 2.9 MB，16 KB 对齐，只导出 JNI）；WSL 中的主机测试 14 项全部通过（`nihao` → 你好、`zhongguo` → 中国 均排第一；开启简体时不出现中國；私有方案不学习）。应用的中文模式在真机部署后使用它，部署耗时显示在诊断页上。 |
| S3 Mozc | 主机上 PASS；Android 构建 NOT_RUN | Mozc 3.35.6354 与 Kotomo 的胶水层在 WSL（aarch64）中构建；主机测试 7 项全部通过：`わたしはにほんじん` → 私は日本人，固定池袋 + `にかいにいこう` → 池袋に買いに行こう，拼音不是 Mozc 的读音。Android 库需要在 x86_64 Linux 上运行 `.github/workflows/mozc.yml`，尚未运行。 |
| S4 英文引擎 | 主机上 PASS；真机上 NOT_RUN | `libkotomo_english.so` 可为 arm64-v8a 与 x86_64 构建（去符号后约 360 KB，16 KB 对齐）；2.5 MB 的词典可复现构建；在 WSL（Debian，aarch64）中运行 `core/english/tests/run-host.sh`：13 项全部通过（`helo` → hello、`teh` → the、邻键误触、补全、下一词预测、反例）。主机上每次调用 3.8 ms，不代表真机延迟。 |
| S5 跨语言词库 | 主机上 PASS | `java lexicon/CheckSamples.java`：28 条预期映射全部达到要求的名次（`chidai` 下的池袋、`dongjing` 下的東京、`coffee` 下的コーヒー……），10 条反例 0 条出错（`yunshu` 下没有芸術）。拼音 → 日文覆盖 Mozc 中 98.9% 的全汉字词条。 |
| S6–S8 | NOT_RUN | — |

## 关卡

| 关卡 | 状态 |
|---|---|
| G0 可行性 | NOT_RUN |
| G1 基础输入 | NOT_RUN |
| G2 混合输入 | NOT_RUN |
| G3 完整输入体验 | NOT_RUN |
| G4 AI 与个性化 | NOT_RUN |
| G5 1.0 | NOT_RUN |

## 已知限制

- 英文模式由 S4 引擎提供纠错、补全与下一词预测；输入的原词始终排在第一位，空格提交的就是它。其他模式的候选是占位数据
  （原样、首字母大写和全大写）；还没有中文或日文转换。
- 键盘视图还不支持无障碍功能（触摸浏览）。
- 在 P0 于真机上测量之前，不能对延迟、内存、APK 体积或设备端 AI 作出任何结论。
