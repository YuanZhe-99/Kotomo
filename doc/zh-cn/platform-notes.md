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
| S5 跨语言词库 | 主机上 PASS | `java lexicon/CheckSamples.java`：28 条预期映射全部达到要求的名次（`chidai` 下的池袋、`dongjing` 下的東京、`coffee` 下的コーヒー……），10 条反例 0 条出错（`yunshu` 下没有芸術）。拼音 → 日文覆盖 Mozc 中 98.9% 的全汉字词条。 |
| S2–S4、S6–S8 | NOT_RUN | — |

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

- 键盘把拉丁字母作为组合输入，候选是占位数据（原样、首字母大写和全大写）；还没有中文、日文或英文转换。
- 键盘视图还不支持无障碍功能（触摸浏览）。
- 在 P0 于真机上测量之前，不能对延迟、内存、APK 体积或设备端 AI 作出任何结论。
