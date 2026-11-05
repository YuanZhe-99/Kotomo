# 开发

## 环境要求

- JDK 21 或更高版本。文档检查和 Gradle 构建都在其上运行。
- Android SDK，带 `build-lock.json` 中指定的平台和构建工具（目前为平台 37.2 与构建工具 37.0.0），通过被忽略的
  `local.properties` 中的 `sdk.dir` 或 `ANDROID_HOME` 找到。Gradle 本身由 wrapper 提供，wrapper 会校验发行包的哈希。
- 后续 P0 技术验证起：Android NDK，以及 SDK 自带的 CMake 和 Ninja；如果技术验证 S3 保留 Mozc 自身的 Bazel 构建，
  还需要 Bazelisk。每一项由加入它的提交锁定在 `build-lock.json` 中。
- 凡是关于输入延迟、IME 生命周期和设备端 AI 的结论，都需要一台真实的 Android 设备。在主机支持模拟器的情况下，
  模拟器足以验证布局和大部分插桩测试；Google 的 Android Emulator 不能在 Windows on ARM64 主机上运行。

## 命令

```bash
java tool/CheckDocs.java   # doc/en-us and doc/zh-cn mirror each other; local links resolve
./gradlew check            # unit tests and lint
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease  # R8 on; signed when key.properties exists, otherwise left unsigned
java -Xmx4g lexicon/CrossLexicon.java   # S5 lexicon into build/lexicon/; --offline uses .deps/ only
java lexicon/CheckSamples.java          # S5 sample check; non-zero exit on a failed line
java tool/BuildEnglishDict.java         # S4 dictionary; assembleDebug and assembleRelease run it too
java lexicon/EmojiData.java             # emoji asset into build/emoji/emoji.tsv; --offline; the app's emojiData task runs it
sh core/english/tests/run-host.sh       # S4 host test (Linux or WSL: CMake, Ninja, g++, JDK)
scripts/fetch-boost.sh                  # Boost headers for librime; needed before any native build
scripts/prebuild-rime.sh                # compiled Chinese data for the APK (Linux or WSL); CI requires it
scripts/fetch-mozc.sh                   # Mozc libraries and data pinned in build-lock.json (gh); CI requires them
scripts/build-mozc.sh --abi host        # S3 Mozc and glue host build and test (Linux or WSL, Bazelisk)
cmake -S core/rime -B build/host/rime -G Ninja && cmake --build build/host/rime --target kotomo_rime_test && ctest --test-dir build/host/rime -V   # S2 librime host test (Linux or WSL)
./gradlew --no-daemon check             # when Gradle finds no SDK packages: a sandboxed daemon of another agent was reused
```

Gradle 在 Windows 主机上运行（Git Bash 或 PowerShell），不在 WSL 中运行；WSL 只用于在主机上构建 librime 和
Mozc。从 Git Bash 调用 WSL 时加上 `MSYS_NO_PATHCONV=1`，让 `/mnt/c` 下的路径原样传入；多行命令写成脚本。

主机端引擎测试和转换语料运行器随各自的第一份代码加入此列表；`PLAN.md` §17 列出了计划中的命令。

## Release 签名

`assembleRelease` 使用仓库根目录下被忽略的 `key.properties` 所指定的密钥签名。该文件包含四项：

```properties
storeFile=/absolute/path/to/kotomo-release.jks
storePassword=…
keyAlias=kotomo
keyPassword=…
```

`storeFile` 中使用正斜杠。没有这个文件时，release APK 保持未签名（`app-release-unsigned.apk`）；它绝不回退到
debug 密钥，因此安装的 release 版本始终由 release 密钥签名。密钥库本身永远不进入仓库。

## 持续集成

`.github/workflows/build.yml` 在 GitHub 上对每次推送到 `main`、每个拉取请求和每个 `v*` 标签运行：文档检查、
`./gradlew check`，以及 debug 与 release 构建，产物上传为 `kotomo-arm64-v8a` 与 `kotomo-x86_64`。仓库机密 `KEYSTORE_BASE64`、
`STORE_PASSWORD`、`KEY_ALIAS` 和 `KEY_PASSWORD` 会生成临时的密钥库和 `key.properties`；没有它们时（来自分叉的拉取
请求），release APK 保持未签名。`v*` 标签还会把签名的 APK 发布为 GitHub Release。Dependabot 每周提出 Gradle 与
Actions 的更新。

项目已于 2026-11-04 归档，GitHub 仓库连同 CI 产物（包括 `scripts/fetch-mozc.sh` 下载的预编译 Mozc 库）和机密一起删除。
把仓库推送到新的 GitHub 仓库并配置这四项机密后，工作流才会重新运行；在此之前用 `scripts/build-mozc.sh` 在本地构建 Mozc。

## 测试原则

测试保护产品契约和容易出错的逻辑，优先使用表驱动测试。每个修复的缺陷都有回归测试，每个转换错误都在转换语料中增加
一行。每个测试注明它覆盖的测试编号（`T01`…）或关卡。延迟、内存和设备端 AI 的结果只在真机上有效，并注明设备、
Android 版本和构建类型（debug，或启用 R8 的 release）。结果为 `PASS`、`FAIL`、`BLOCKED` 或 `NOT_RUN`。
