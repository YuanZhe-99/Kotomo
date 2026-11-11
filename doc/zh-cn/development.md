# 开发

## 环境要求

- 一台 x86_64 Linux 主机（维护者的是 Debian 13）：Mozc 的 Android 构建需要 x86_64 Linux 或 macOS，因为 NDK
  没有 aarch64 Linux 的主机工具。其余所有构建也都在这台主机上进行。
- JDK 21 或更高版本。文档检查和 Gradle 构建都在其上运行；Mozc 的 Bazel 构建需要 `JAVA_HOME`（未设置时从 `PATH`
  上的 `java` 推导）。
- Android SDK，带 `build-lock.json` 中指定的平台和构建工具（目前为平台 37.2 与构建工具 37.0.0）以及其中指定的
  NDK 与 CMake，通过 `ANDROID_HOME`（或被忽略的 `local.properties` 中的 `sdk.dir`）找到。Gradle 本身由 wrapper
  提供，wrapper 会校验发行包的哈希。
- 主机构建需要 CMake、Ninja、C++ 编译器、Python 3、`rsync` 和 `curl`。Mozc 的构建会自行下载锁定的 Bazelisk 与
  NDK r29，不在系统范围安装任何东西。
- 带 API 37 x86_64、16 KB 页镜像的 Android 模拟器，用于布局、逻辑和插桩测试。凡是关于输入延迟、内存、IME 生命周期
  和设备端 AI 的结论，都需要一台真实的设备。

## 命令

```bash
java tool/CheckDocs.java   # doc/en-us and doc/zh-cn mirror each other; local links resolve
./gradlew check            # unit tests and lint
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-<abi>-debug.apk
./gradlew assembleRelease  # R8 on; signed when key.properties exists, otherwise left unsigned
java -Xmx4g lexicon/CrossLexicon.java   # S5 lexicon into build/lexicon/; --offline uses .deps/ only
java lexicon/CheckSamples.java          # S5 sample check; non-zero exit on a failed line
java tool/BuildEnglishDict.java         # S4 dictionary; assembleDebug and assembleRelease run it too
java lexicon/EmojiData.java             # emoji asset into build/emoji/emoji.tsv; --offline; the app's emojiData task runs it
sh core/english/tests/run-host.sh       # S4 host test; needs the dictionary from BuildEnglishDict
scripts/fetch-boost.sh                  # Boost headers for librime; needed before any native build
scripts/prebuild-rime.sh                # compiled Chinese data for the APK
scripts/build-mozc.sh --abi host        # S3 Mozc and glue host build and test
scripts/build-mozc.sh --abi arm64-v8a --abi x86_64   # Mozc for the APK into build/mozc-prebuilt, compared with build-lock.json
cmake -S core/rime -B build/host/rime -G Ninja && cmake --build build/host/rime && ctest --test-dir build/host/rime -V   # S2 librime host test
tests/conversion/run.sh                 # conversion corpus on the host, compared with tests/conversion/baseline.tsv; --update accepts the run
./gradlew --no-daemon check             # when Gradle finds no SDK packages: a sandboxed daemon of another agent was reused
```

从全新克隆构建时依次运行：`git submodule update --init --recursive`、`scripts/fetch-boost.sh`、
`scripts/prebuild-rime.sh`、上面构建 Android 版 Mozc 的那一行，再在设置 `KOTOMO_REQUIRE_MOZC=1` 与
`KOTOMO_REQUIRE_RIME_PREBUILT=1` 的情况下运行 Gradle，使 APK 不会在缺少日文或缺少预编译中文数据时产出。
`scripts/fetch-mozc.sh` 改为从 GitHub Actions 的运行下载同样的库；它锁定的运行已随仓库删除，只有 CI 重新存在后才能再用。

转换语料（`tests/conversion/corpus.tsv`）在主机的 JVM 中经应用自己的引擎外观与候选组装运行：`run.sh` 先构建主机版
librime 与英文库，再在设置 `KOTOMO_CORPUS=1` 的情况下运行 `ConversionCorpusTest`。它需要预编译的中文数据、主机版
Mozc、英文词典与 Emoji 数据。基线中通过、这次失败的行是回退，会让运行失败；在基线中也失败的行是已知差距。每次改动引擎、
词库或排序前后都运行它，读过报告之后才用 `--update` 接受新的基线。`PLAN.md` §17 列出了计划要求、但尚不存在的接口。

## 模拟器

```bash
emulator -avd <avd> -no-snapshot &                       # an API 37 x86_64 16 KB-page image
adb install -r app/build/outputs/apk/release/app-x86_64-release.apk
adb shell ime enable com.yuanzhe.kotomo/.ime.KotomoService
adb shell ime set com.yuanzhe.kotomo/.ime.KotomoService
adb shell settings put secure show_ime_with_hard_keyboard 1
```

模拟器报告自己带有硬件键盘，因此在设置 `show_ime_with_hard_keyboard` 之前，Android 会隐藏所有屏幕键盘。模拟器的
运行只覆盖布局和逻辑，从不作为延迟、内存或设备端 AI 的证据。

## Release 签名

`assembleRelease` 使用仓库根目录下被忽略的 `key.properties` 所指定的密钥签名。该文件包含四项：

```properties
storeFile=/absolute/path/to/kotomo-release.p12
storePassword=…
keyAlias=…
keyPassword=…
```

`storeFile` 中使用正斜杠。没有这个文件时，release APK 保持未签名（`app-release-unsigned.apk`）；它绝不回退到
debug 密钥，因此安装的 release 版本始终由 release 密钥签名。密钥库本身永远不进入仓库。

## 持续集成

`.github/workflows/build.yml` 与 `.github/workflows/mozc.yml` 在 GitHub 上运行，直到项目于 2026-11-04 归档、GitHub
仓库连同 CI 产物和机密一起删除。它们留作重启之用；把仓库推送到新的 GitHub 仓库并配置机密 `KEYSTORE_BASE64`、
`STORE_PASSWORD`、`KEY_ALIAS` 和 `KEY_PASSWORD` 之前，它们不做任何事。在此之前，所有构建与测试都在开发主机上运行。

## 测试原则

测试保护产品契约和容易出错的逻辑，优先使用表驱动测试。每个修复的缺陷都有回归测试，每个转换错误都在转换语料中增加
一行。每个测试注明它覆盖的测试编号（`T01`…）或关卡。延迟、内存和设备端 AI 的结果只在真机上有效，并注明设备、
Android 版本和构建类型（debug，或启用 R8 的 release）。结果为 `PASS`、`FAIL`、`BLOCKED` 或 `NOT_RUN`。
