# 开发

## 环境要求

- JDK 21 或更高版本。文档检查直接在其上运行，Gradle 构建也将使用它。
- P0 起：带最新平台的 Android SDK、Android NDK，以及 SDK 自带的 CMake 和 Ninja；如果技术验证 S3 保留 Mozc 自身的
  Bazel 构建，还需要 Bazelisk。P0 选定版本并锁定在 `build-lock.json` 中。
- 凡是关于输入延迟、IME 生命周期和设备端 AI 的结论，都需要一台真实的 Android 设备。`x86_64` 模拟器足以验证布局和
  大部分插桩测试。

## 命令

```bash
java tool/CheckDocs.java   # doc/en-us and doc/zh-cn mirror each other; local links resolve
```

Gradle 构建、主机端引擎测试和转换语料运行器随各自的第一份代码加入此列表；`PLAN.md` §17 列出了计划中的命令。

## 测试原则

测试保护产品契约和容易出错的逻辑，优先使用表驱动测试。每个修复的缺陷都有回归测试，每个转换错误都在转换语料中增加
一行。每个测试注明它覆盖的测试编号（`T01`…）或关卡。延迟、内存和设备端 AI 的结果只在真机上有效，并注明设备、
Android 版本和构建类型（debug，或启用 R8 的 release）。结果为 `PASS`、`FAIL`、`BLOCKED` 或 `NOT_RUN`。
