# Kotomo 文档

**这些文档是代码的权威描述。** `doc/en-us/` 是源；`doc/zh-cn/` 逐文件镜像它。计划与未完成的工作不写在这里；计划进行期间
只存在于仓库的 `PLAN.md` 中。

Kotomo 是一款面向中文、英文和日文的 Android 输入法。拼音、英文和日语罗马字在同一个键盘中输入；混合模式下，同时包含三种
语言的连续输入串按整句转换。Kotlin 负责输入法服务、键盘和设置应用；经 JNI 调用的 C++ 引擎负责混合输入协调层，其下是
librime（中文）、Mozc（日文）、AOSP LatinIME 的建议引擎（英文）和 OpenCC。

| 文档 | 内容 |
|---|---|
| [decisions.md](decisions.md) | 架构决定记录：决定了什么、为什么，以及什么情况会重新讨论 |
| [upstream.md](upstream.md) | 上游契约与特殊行为：各引擎、Android 输入法平台、ML Kit、数据集 |
| [settings-format.md](settings-format.md) | 设置文件：键、默认值、版本，以及键盘如何读取 |
| [ai.md](ai.md) | 写作助手：用 Gemini Nano 校对与改写、交接流程、遵循的规则 |
| [clipboard.md](clipboard.md) | 剪贴板历史：记录什么、加密文件、验证码、快捷输入条 |
| [dictionaries.md](dictionaries.md) | 词库：来源与优先级、读音、导入与导出、带版本的文件、词库包与在线目录 |
| [third-party.md](third-party.md) | 复用的代码、数据与素材，许可证与义务；设计致谢 |
| [platform-notes.md](platform-notes.md) | 各设备与 Android 版本上已实现与已验证的内容、关卡状态 |
| [development.md](development.md) | 环境、命令、开发流程 |
| [translation-guide.md](translation-guide.md) | 镜像规则与术语表 |
| [version-history.md](version-history.md) | 各版本及其使用的上游版本 |
| [functions/INDEX.md](functions/INDEX.md) | 各模块与各文件的职责和关键声明 |

## 上游版本

Kotomo 跟随每个上游的最新发行版，并以提交号锁定；`build-lock.json` 记录每次构建实际使用的版本。下表为
最近一次检查（2026-11-04）时的最新版本。

| 组件 | 策略 | 最近检查时的最新版本 |
|---|---|---|
| librime | 最新 GitHub 发行版 | 1.17.0 |
| Mozc | 最新标签（Mozc 不发布 GitHub Releases） | 3.35.6354 |
| OpenCC | 最新 GitHub 发行版 | 1.4.2 |
| 英文引擎：HeliBoard 维护的 AOSP LatinIME 原生代码 | HeliBoard 的最新发行版 | HeliBoard 4.2 |
| Android 平台 | 最新稳定 API 作为 `compileSdk` 与 `targetSdk` | API 37（Android 17） |
| Android Gradle Plugin、Gradle、Kotlin、NDK | 最新稳定版 | 9.5.1、9.9.1、2.4.21、r30（30.0.16248370） |
| Compose BOM、Room、DataStore | 最新稳定版 | 2026.09.00、2.8.5、1.3.0 |
| ML Kit GenAI 校对、重写与 Prompt；ML Kit 翻译 | 最新版 | 1.0.0-beta1、1.0.0-beta1、1.0.0-beta5；17.0.3 |
| JMdict（经 jmdict-simplified）、Unihan、CLDR | 最新发布版 | 3.6.2+20261109214828；Unicode 18.0.0；CLDR 49 |
| Noto Emoji、Twemoji | 最新发行版 | v2026-09-24-unicode18_0；17.0.3 |
