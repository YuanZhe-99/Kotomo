# 决定

架构决定记录（ADR）：每条写明决定了什么、为什么，以及什么情况会重新讨论。决定只由用户更改；新的记录取代旧的记录时
写明这一点，旧记录保留并更改状态。日期是决定生效的日期。

| ID | 决定 | 状态 |
|---|---|---|
| ADR-001 | 引擎：librime、Mozc 与 AOSP LatinIME 的建议核心 | 2026-11-04 采纳 |
| ADR-002 | 经 librime 的会话接口调用它，不写插件 | 2026-11-04 采纳 |
| ADR-003 | Mozc 用自身的 Bazel 构建，产出单独的库 | 2026-11-04 采纳，2026-11-10 修订 |
| ADR-004 | 每个引擎一个共享库，只导出 JNI 入口 | 2026-11-04 采纳 |
| ADR-005 | 键盘是自定义 View；面板与设置应用用 Compose | 2026-11-04 采纳 |
| ADR-006 | 中文数据在构建时编译；设备上的部署在独立进程中进行 | 2026-11-04 采纳 |
| ADR-007 | 接受 ML Kit 带入的联网权限 | 2026-11-10 采纳 |

## ADR-001 引擎

**决定。** 中文用 librime 1.17.0 与 `luna_pinyin` 方案（rime-luna-pinyin，LGPL-3.0）；日文用 Mozc 3.35.6354，客户端
与 C++ 胶水层由 Kotomo 自写；英文用 HeliBoard 4.2 维护的 AOSP LatinIME 原生建议核心，词表为 aosp-dictionaries 的
en_US。简繁转换用 OpenCC，与 librime 共用。

**原因。** 它们分别是各语言成熟的开源引擎，并能为 Android 构建（`upstream.md`）。Kotomo 自写的是混合输入协调层，
而不是切分、假名汉字转换或拼写纠错（`PLAN.md` §1）。

**没有比较。** 计划要求技术验证 S2 在中文语料上比较方案与词库；当时没有语料，直接选了 `luna_pinyin`。有了转换语料后
补做这项比较。

**重新讨论的条件。** 语料表明另一种方案或引擎明显更好，或某个上游停止维护。

## ADR-002 经会话接口调用 librime

**决定。** 桥接层驱动 librime 的会话：按键用 `process_key`，片段用 `set_input`，再迭代候选、`select_candidate` 与
`get_commit`。不写 librime 插件（`register_module`）。

**原因。** 技术验证 S2 在主机上测得 `set_input` 加候选迭代每个前缀约 0.2 ms，足够协调层按片段使用（`PLAN.md` §5.4）。

**代价。** 现在中文每键跨 5 次 JNI（`Rime.kt`）；引擎 ↔ 应用契约（`PLAN.md` §4.4）把它改为每键一次。

**重新讨论的条件。** 协调层需要 librime 的网格而不只是候选，或真机上每个片段的延迟过高。

## ADR-003 Mozc 的构建

**决定。** Mozc 与 Kotomo 的胶水层（`core/mozc/`）经 `scripts/build-mozc.sh` 用 Mozc 自身的 Bazel 构建，产出
`libkotomo_mozc.so` 与 `mozc.data`。胶水层复制进 Mozc 的工作副本，子模块保持不变；可见性用 `--nocheck_visibility`
放宽，而不打补丁。应用构建从 `build/mozc-prebuilt/` 打包产物。

**原因。** Mozc 只能用 Bazel 构建，而它的转换器层（固定片段转换，协调层的 ADR 随后写）只能在它的工作区内部调用。

**2026-11-10 修订。** Android 库起初由 GitHub Actions 构建并按哈希锁定，因为当时的开发主机是 Windows on ARM64，而
NDK 没有 aarch64 Linux 的主机工具。那个仓库已删除；开发主机现在是 x86_64 Linux，库在这台主机上构建。第一次本地构建
与锁定的哈希逐字节一致。

**重新讨论的条件。** Mozc 提供了嵌入转换器的正式途径，或构建成本妨碍发布。

## ADR-004 每个引擎一个库

**决定。** `libkotomo_rime.so`、`libkotomo_mozc.so` 与 `libkotomo_english.so` 是三个独立的共享库。每个只导出 JNI
入口（链接器版本脚本；Mozc 的构建在导出多于 JNI 时由 `build-mozc.sh` 判为失败），并按 16 KB 对齐；2026-11-10 的
release 构建中六个库都用 `llvm-readelf` 核对过。

**原因。** librime 带入 Boost，Mozc 带入 protobuf 与 abseil；只露出 JNI 能避免它们的符号冲突，而且 Mozc 的 Bazel
构建无法链接进 Gradle 的 CMake 构建。

**后果。** 照现在的库，C++ 协调层不能在进程内直接调用各引擎；它在设备上怎样调用尚待决定（`PLAN.md` §2.5 决定 4）。

## ADR-005 View 与 Compose

**决定。** 键盘与候选栏是在 Canvas 上绘制的自定义 View。键盘里的面板（Emoji、剪贴板、快捷设置）与设置应用用 Jetpack
Compose 与 Material 3。

**原因。** 技术验证 S1 测得自定义 View 的按键绘制 p95 为 19–21 ms，Compose Emoji 面板首帧 73 ms（Galaxy Z Fold，
Android 17，release）：面板可以接受，按键路径不行。输入法中的 Compose 需要视图树里有 `LifecycleOwner` 与
`SavedStateRegistryOwner`，由 `KotomoService` 提供。

**重新讨论的条件。** 测量表明某个面板或键盘达不到帧预算。

## ADR-006 预编译的中文数据与独立的部署进程

**决定。** APK 带着在构建主机上编译好的 librime 数据（`scripts/prebuild-rime.sh`），手机首次使用时不编译。设备上需要
改变数据时，librime 在独立进程（`:deploy`）中部署到暂存目录，键盘在此期间继续使用上一份构建的会话，完成后再换入新的
构建。

**原因。** 主机上一次全新部署需要 5–15 s，峰值约 294 MB，期间拒绝会话（`upstream.md`）；键盘必须能继续输入。

## ADR-007 ML Kit 带入的联网权限

**决定。** 应用自己的清单不声明任何权限，合并后的清单保留 ML Kit（写作助手）带入的 `INTERNET`、`ACCESS_NETWORK_STATE`
与绑定 AICore 的权限。Kotomo 自己的代码不建立任何连接；第一个由它自己联网的功能（词库下载）加入前先与用户确认，并
写入隐私政策。

**原因。** 这是 `PLAN.md` §2.5 决定 10 的默认做法，在恢复开发时记录下来。`PRIVACY_POLICY.md` 与 `ai.md` 已经说明了
这些权限。

**重新讨论的条件。** 用户要求一个不带 ML Kit 的构建（例如 F-Droid 不接受 ML Kit）：届时把写作助手放进单独的构建变体。
