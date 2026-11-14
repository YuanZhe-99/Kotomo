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
scripts/build-mozc.sh --abi host        # S3 Mozc and glue host build and test; also the line service the S6 host prototype uses
scripts/build-mozc.sh --abi arm64-v8a --abi x86_64   # Mozc for the APK into build/mozc-prebuilt, compared with build-lock.json
cmake -S core/rime -B build/host/rime -G Ninja && cmake --build build/host/rime && ctest --test-dir build/host/rime -V   # S2 librime host test
./gradlew connectedDebugAndroidTest    # instrumentation tests on a running emulator or device (debug build)
tests/conversion/run.sh                 # conversion corpus and the host fuzzy-pinyin test on the host, compared with tests/conversion/baseline.tsv; --update accepts the run
KOTOMO_PACKS=1 tests/conversion/run.sh  # also converts every downloadable dictionary from the pinned files in .deps/ (DictionaryPacksTest)
cmake -S core/mixed -B build/host/mixed -G Ninja && cmake --build build/host/mixed && ctest --test-dir build/host/mixed   # the coordinator: unit tests on fake engines, libkotomo_core.so for the host JVM, the lexicon compiler, the corpus runner
build/host/mixed/kotomo_lexicon_compile build/lexicon/pinyin-ja.tsv build/lexicon/en-ja.tsv build/lexicon/cross.klx   # the compiled cross-language lexicon the APK packs (lexicon-format.md)
build/host/mixed/kotomo_mixed_corpus . --convert "chidai ni kai ni ikou"   # one composition through the coordinator and the three host engines, with costs and pieces
./gradlew --no-daemon check             # when Gradle finds no SDK packages: a sandboxed daemon of another agent was reused
```

从全新克隆构建时依次运行：`git submodule update --init --recursive`、`scripts/fetch-boost.sh`、
`scripts/prebuild-rime.sh`、上面构建 Android 版 Mozc 的那一行、`java -Xmx4g lexicon/CrossLexicon.java`
以及构建并运行 `kotomo_lexicon_compile` 的那两行 `core/mixed` 命令，再在设置 `KOTOMO_REQUIRE_MOZC=1`、
`KOTOMO_REQUIRE_RIME_PREBUILT=1` 与 `KOTOMO_REQUIRE_LEXICON=1` 的情况下运行 Gradle，使 APK 不会在缺少日文、
缺少预编译中文数据或缺少跨语言词库时产出。
`scripts/fetch-mozc.sh` 改为从 GitHub Actions 的运行下载同样的库；它锁定的运行已随仓库删除，只有 `build-lock.json` 记下新 CI 的一次运行、且其产物尚未过期时才能再用。

转换语料（`tests/conversion/corpus.tsv`）在主机的 JVM 中经应用自己的引擎外观与候选组装运行：`run.sh` 先构建主机版
librime、英文库与协调层的库（`libkotomo_core.so`），编译跨语言词库，再在设置 `KOTOMO_CORPUS=1` 的情况下运行
`ConversionCorpusTest`。混合模式的行经 `Core` 逐键输入，所以跨过的是与手机上相同的 JNI 边界与函数表。它需要预编译的
中文数据、主机版 Mozc、英文词典、Emoji 数据与词库的两张表（`CrossLexicon.java`）。基线中通过、这次失败的行是回退，会让运行失败；在基线中也失败的行是已知差距。每次改动引擎、
词库或排序前后都运行它，读过报告之后才用 `--update` 接受新的基线。中文或混合模式的行可以在模式后写出模糊音规则
（`chinese+fuzzy:z_zh,n_l`、`chinese+fuzzy:all`）：运行器先在预编译数据上部署应用生成的规则文件，再在这份构建上转换。中文语料行还可以指定双拼方案（`chinese+double:xiaohe`）或输出字形（`chinese+script:taiwan`、`hong_kong`、`traditional`），按键盘的方式设置，包括地区用词的排序（T11），或在九宫格上输入（`chinese+nine`，输入为键盘数字）。
`run.sh` 随后让同一份语料单独经过协调层（`core/mixed/host/`）：`kotomo_mixed_corpus` 转换混合行，并把单语行作为
基线、在各自语言的候选中判定，写出 `build/conversion/mixed-report.txt`（每个未通过的行带有差距分类），并把混合行与
`tests/conversion/baseline-mixed.tsv` 比较。它另外需要主机版的 Mozc 行服务。两次运行并列报告：应用路线给出键盘会
显示的内容（含 Emoji 与原样的字母），协调层路线给出代价与片段。Mozc 的 Bazel 构建需要带 `include/` 目录的 JDK：Android Studio 自带的
JetBrains 运行时没有，运行 `build-mozc.sh` 时不要设置 `JAVA_HOME`，或让它指向完整的 JDK。
`run.sh` 还运行 `FuzzyRimeTest`（T08：不学习的方案、暂存部署的换入、回到预编译数据）以及词库测试 `DictionaryRimeTest` 与 `DictionaryPacksTest`；`KOTOMO_PACKS=1` 时后者用应用自己的转换器把 `build-lock.json` 锁定的文件（须在 `.deps/` 中）转换为每个可下载的词库，并打印计数、耗时与内存。`DictionaryDownloadTest`（插桩测试）从真实的来源下载，来源无法连接时记为跳过而不是通过。`PLAN.md` §17 列出了计划要求、但尚不存在的接口。

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

`.github/workflows/build.yml` 在每次推送到 `main`、每个拉取请求、每个 `v*` 标签以及手动触发（`workflow_dispatch`）时，
运行上面"从全新克隆构建"的全部步骤。它的作业：

| 作业 | 运行什么 |
| --- | --- |
| `docs` | `java tool/CheckDocs.java` |
| `mozc` | `.github/workflows/mozc.yml`：`scripts/build-mozc.sh --abi host --abi arm64-v8a --abi x86_64`，含胶水层测试；产物与 `build-lock.json` 比较，并作为本次运行的 `kotomo-mozc` 产物保存 |
| `data` | `scripts/fetch-boost.sh`、`scripts/prebuild-rime.sh`、`BuildEnglishDict.java`、`EmojiData.java`、`CrossLexicon.java`、`CheckSamples.java` 与 `kotomo_lexicon_compile`，作为 `kotomo-data` 产物保存 |
| `host` | librime 主机测试（`ctest`）、英文主机测试（`run-host.sh`），然后是含协调层单元测试的 `tests/conversion/run.sh`；任一行相对任一基线回退，作业即失败 |
| `android` | 带 `KOTOMO_REQUIRE_MOZC=1`、`KOTOMO_REQUIRE_RIME_PREBUILT=1` 与 `KOTOMO_REQUIRE_LEXICON=1` 的 `./gradlew check assembleDebug assembleRelease`；每个 ABI 一个 APK 产物 |
| `release` | `v*` 标签时：把签名的 release APK 发布到 GitHub Release |

Mozc 只来自同一次运行：Bazel 要一个小时以上，所以 `mozc.yml` 以 Mozc 子模块的提交和 `core/mozc/`、`patches/mozc/`、
`scripts/build-mozc.sh` 与工作流本身的哈希为键缓存产物，键相同的运行什么都不构建。新仓库的第一次运行完整构建，
不需要事先存在任何产物。与 `build-lock.json` 记录的哈希（开发主机构建的哈希）不同时只给出警告、不算失败，因为换一个发行版不一定能逐字节构建出相同的结果。
机密 `KEYSTORE_BASE64`、`STORE_PASSWORD`、`KEY_ALIAS` 与 `KEY_PASSWORD` 存在时 release APK 签名，否则不签名，工作流照样通过；
只有 `release` 作业要求它们。
`.deps/` 中锁定的下载以锁定值本身为键缓存，`data` 作业失败时也会保存，因为 CC-CEDICT 只发布当前文件：MDBG 一换文件，
新下载的文件就对不上锁定，作业以"Pinned data source changed"失败，需要有意移动锁定（并重跑 `CheckSamples` 与整套语料）。

工作流在公开的 GitHub 仓库（远端 `github`）上运行。原仓库在项目于 2026-11-04 归档时连同运行记录、产物和机密一起删除，
2026-11-14 重建。`main` 同时推到两个远端。没有命中 Mozc 缓存的运行约 45 分钟（Mozc 约 22 分钟）；命中缓存时 15 到 22 分钟
（`android` 13 到 19 分钟，`host` 7 到 8 分钟，`data` 2 到 4 分钟）。各次运行验证了什么记在 `platform-notes.md`。
只用开发主机仍然可以完整构建。

## 测试原则

测试保护产品契约和容易出错的逻辑，优先使用表驱动测试。每个修复的缺陷都有回归测试，每个转换错误都在转换语料中增加
一行。每个测试注明它覆盖的测试编号（`T01`…）或关卡。延迟、内存和设备端 AI 的结果只在真机上有效，并注明设备、
Android 版本和构建类型（debug，或启用 R8 的 release）。结果为 `PASS`、`FAIL`、`BLOCKED` 或 `NOT_RUN`。
