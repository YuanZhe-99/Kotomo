# 平台说明

| 平台 | 状态 |
|---|---|
| Android 手机（`arm64-v8a`） | 在 Galaxy Z Fold（Android 17，release 构建）上运行：S1 与 S2 在该机通过。P1 的设备检查清单尚未逐条记录；只能观察屏幕上显示的内容。 |
| Android 模拟器（`x86_64`） | 在主机支持的情况下用于验证布局和插桩测试；从不作为延迟或设备端 AI 的证据。 |
| Android 平板和折叠屏 | P1 代码已就位（尚未在设备上运行）：窗口尺寸类别、居中的最大宽度、半折叠时按键位于铰链下方、竖向铰链两侧的分离两半与两个空格键（有单元测试）。 |
| 桌面主机（Windows、Linux、macOS） | P0 起用于构建和测试引擎核心；从不作为产品目标平台。 |

## 仓库

在 Windows 11 on ARM64（2026-11-04；JDK 21.0.13）和 GitHub Actions（`ubuntu-latest`）上验证：

| 检查 | 结果 |
|---|---|
| 文档镜像与链接 | `java tool/CheckDocs.java` 通过 |
| 单元测试（S1：布局与命中判定、延迟百分位；设置格式：默认值、往返、未知键、无文件；剪贴板：记录规则、加密文件、验证码、快捷输入） | `./gradlew testDebugUnitTest`：62 个测试，61 个通过、1 个跳过（设置格式与剪贴板的测试目前只在 Windows 主机上运行过） |
| Lint | `./gradlew check` 通过，没有警告 |
| Release 构建 | `./gradlew assembleRelease`：启用 R8，以 release 密钥签名（APK 签名方案 v2） |

2026-11-04 恢复后没有再运行这些检查：开发机上没有安装 Android SDK（`local.properties` 指向的目录只剩 `.sdk` 标记文件），`./gradlew --no-daemon` 在配置阶段就因 NDK 许可错误中止，没有任何任务执行。文档检查仍可运行；上表的 Gradle 结果仍是此前那次运行的结果。

## 技术验证

| 技术验证 | 状态 | 证据 |
|---|---|---|
| S1 IME 骨架 | 真机上 PASS（Galaxy Z Fold，SM-F971U1，Android 17，release 构建，2026-11-04） | 按下到按键绘制 p50 9–10 ms、p95 19–21 ms；按下到候选绘制 p50 15–48 ms、p95 120–130 ms（英文与中文引擎，不计首次调用）；键盘视图到首帧 11–14 ms；Compose Emoji 面板首帧 73 ms；进程内存 61–80 MB。展开屏上为分离布局，外屏为手机布局，按键位于系统栏之上。 |
| S2 librime | 真机（同一部手机）与主机上 PASS | 使用构建时编译的数据可以输入中文（首次启动解压约 120 ms）。发现并修复了两个只在真机上出现的问题：JNI 字符串转换丢掉了数据路径的最后一个字符；真机上部署时无法创建 build/。诊断页的自检会显示其状态。主机：24 项检查，包括预编译数据与按键路径。 |
| S3 Mozc | 主机上 PASS；Android 构建 NOT_RUN | Mozc 3.35.6354 与 Kotomo 的胶水层在 WSL（aarch64）中构建；主机测试 7 项全部通过：`わたしはにほんじん` → 私は日本人，固定池袋 + `にかいにいこう` → 池袋に買いに行こう，拼音不是 Mozc 的读音。Android 库需要在 x86_64 Linux 上运行 `.github/workflows/mozc.yml`，尚未运行。 |
| S4 英文引擎 | 主机上 PASS；真机上 NOT_RUN | `libkotomo_english.so` 可为 arm64-v8a 与 x86_64 构建（去符号后约 360 KB，16 KB 对齐）；2.5 MB 的词典可复现构建；在 WSL（Debian，aarch64）中运行 `core/english/tests/run-host.sh`：13 项全部通过（`helo` → hello、`teh` → the、邻键误触、补全、下一词预测、反例）。主机上每次调用 3.8 ms，不代表真机延迟。 |
| S5 跨语言词库 | 主机上 PASS | `java lexicon/CheckSamples.java`：34 条预期映射全部达到要求的名次（`chidai` 下的池袋、`dongjing` 下的東京、`coffee` 下的コーヒー……），8 条反例 0 条出错（`yunshu` 下没有芸術）。拼音 → 日文覆盖 Mozc 中 99.97% 的全汉字词条。 |
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

- P1 的手势、输入框适配、台湾与香港输出、候选详情、Direct Boot 和 API 37 组合属性只经过单元测试或主机测试。P1 检查清单（`tests/device/p1-checklist.md`）按屏幕所见判定，目前尚未逐条记录，因此 G1 仍为 NOT_RUN。首次解锁之前，键盘只用默认设置和内置数据输入：中文不学习、英文可用、没有日文（Mozc 的数据在凭据保护存储中）、没有剪贴板、AI 和最近使用的 Emoji。
- 英文模式由 S4 引擎提供纠错、补全与下一词预测；输入的原词始终排在第一位，空格提交的就是它。中文模式用构建时编译好的
  数据经 librime（S2）转换拼音；在混合协调层（S6）完成之前，混合模式也这样做。日文模式经 Mozc（S3，库由 Linux CI
  构建）转换罗马字：组合显示假名，第一个候选是整句，回车保留假名；逐段修改在 P2 实现。
- 在 P0 于真机上测量之前，不能对延迟、内存、APK 体积或设备端 AI 作出任何结论。
