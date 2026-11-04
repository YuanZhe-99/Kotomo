# PLAN：Kotomo — Android 中英日三语言智能输入法

> 交付对象：负责实施的 Coding Agent。本文是一份实施计划，不代表任何功能已经实现或验证。
>
> 目标：一款本地优先的 Android 输入法。中文拼音、英文和日语罗马字在同一个键盘中输入；混合模式下不必切换语言、不必加分隔，就能连续输入整句，得到正确的中文、英文或日文句子。
>
> 路线：中文复用 librime，日文复用 Mozc，英文复用 AOSP LatinIME 的原生建议引擎，简繁转换复用 OpenCC；自研的是整句混合协调层（候选网格、跨语言片段、整句解码、统一候选）和跨语言词库。AI 只处理用户主动交给它的文本，不进入按键路径。

- 编写日期：2026-11-04。
- 来源：用户的《Android 中英日三语言智能输入法 完整项目开发计划草案》Draft 2.0。本计划保留草案的全部需求，补充核实过的上游事实、架构与引擎设计、阶段与关卡、测试矩阵、风险和停止条件；草案各章在本文中的位置见附录 A。标注"（本计划补充）"的条目不在草案中，由用户确认是否保留。
- 系列参考：MyApps 系列的工作规范（`AGENTS.md`、`doc/` 中英镜像、版本与发布流程）；MyApps-AI 的 Android Gemini Nano 桥（`GenAiChannel.kt`）及其 AI 使用规则；MyApps-DATA 的备份与同步不变量；MyApps-UI 的设置界面呈现方式。Kotomo 是原生 Kotlin/C++ 应用，不依赖这些 Flutter 包，只移植代码或沿用规则（同一作者的 GPL-3.0 代码）。
- 上游不停留在旧版本：librime、Mozc、OpenCC、Android 工具链和数据源跟随最新发行版，以提交号或哈希锁定在 `build-lock.json`（P0 创建）。§3 是 2026-11-04 的阅读结果，P0 在实际构建后逐项复核。
- 核查范围：上游仓库、源码、官方文档与许可证的定向阅读；没有构建或运行任何引擎，也没有在真机上验证任何结论。
- 产品名称为 **Kotomo**；应用 ID 建议为 `com.yuanzhe.kotomo`（与 MyApps 系列的 `com.yuanzhe.*` 一致）。

## 1. 给 Coding Agent 的执行要求

1. 先完成 P0 的技术验证（S1–S8），再以小范围提交推进；每一步都能说明修改位置、行为变化和验证证据。
2. **第一优先级是核心输入系统**：三语言候选生成、连续输入串的语言边界识别、整句混合转换、中文拼音和英文转日语、整句分段与局部修改、候选排序、模糊音和纠错。这些能力优先于 AI、主题市场和其他扩展。
3. 复用成熟引擎，不从零重写拼音切分与造句、罗马字组合、假名汉字转换、英文词典与纠错等基础算法。自研的是独立的协调层，而不是把三个引擎的候选简单拼接（§5）。
4. 协调层和转换语料必须能在桌面主机上构建和运行，不依赖 Android 设备；真机用于延迟、内存、生命周期和 AI。
5. 按键路径（触摸 → 引擎 → 候选绘制）永不等待 I/O、网络、AI、数据库或后台任务持有的锁；首批候选和后续候选分开交付（§5.5）。
6. 用固定的转换语料证明转换质量（§13）；"试了几个词看起来对"不是验收。每个转换错误的修复都在语料中留下一行。
7. 不以"能出候选""界面出现"代替功能完成。延迟、内存、APK 体积、词库部署时间与正确率都要有实测数字和设备信息。
8. 隐私是架构约束，不是发布前的补丁：默认不联网、不上传输入、敏感输入框不学习（§14）。
9. 任何第三方代码、词库数据、Emoji 素材和字体，先完成许可证核查再引入；"关于与致谢"和开源许可证页面从第一个可用版本起就存在（§15）。
10. 普通实现与修复不需要反复确认。确实受设备、依赖、许可证或架构选择阻塞时，记录证据、先完成不受影响的工作，再提出具体问题。

## 2. 目标、范围和交付层级

### 2.1 成功定义

| 交付层级 | 可观察结果 | 不代表什么 |
| --- | --- | --- |
| A：基础输入（G1） | 中文、英文、日文三种模式均可离线日常输入；候选栏、长按符号、简繁切换、基础 Emoji 与主题可用；密码框不学习 | 不代表混合整句可用 |
| B：混合输入（G2） | 混合模式在同一输入串中识别三种语言；§13.2 的用例达到规定名次；拼音和英文转日语、局部修改、模糊音、纠错与学习可用；延迟预算达标 | 不代表任意句子都能一次转换正确 |
| C：完整输入体验（G3） | 日语变形与敬语、候选注音、地区用词、双拼、词库管理与导入、Emoji 联想、剪贴板、九宫格/Flick/单手/分离布局 | 不代表 AI 可用 |
| D：AI 与个性化（G4） | 支持的设备上可用校对、翻译、重写；主题编辑器、Emoji 样式包、备份与可选同步 | 不代表所有设备都有 Gemini Nano |
| E：1.0（G5） | §18 的最终完成清单全部满足，多台真机长时间稳定 | — |

不把 A 标注为 B 已完成。每次报告按层级分别列出 `PASS / FAIL / BLOCKED / NOT_RUN`。

### 2.2 形态与平台

- 一个 APK：输入法服务（`InputMethodService`）、键盘与候选栏、各类面板、设置应用，以及引擎本地库。
- ABI：`arm64-v8a`（真机）和 `x86_64`（模拟器与开发验证）。`armeabi-v7a` 后置，P0 测出体积与构建成本后由用户决定。所有本地库按 16 KB 页对齐构建。
- `minSdk` 26：与 MyApps 系列相同，也是 ML Kit GenAI 的最低要求。`compileSdk` 与 `targetSdk` 跟随最新稳定平台，目前为 API 37；Google Play 自 2026-08-31 起要求至少 API 36（§3.2）。
- 界面语言：English、简体中文、繁體中文、日本語（与 MyApps 系列一致）。
- 键盘用自定义 View 绘制；设置应用用 Jetpack Compose（Material 3），呈现方式参考 MyApps-UI 的设置组件（分组、分段选择、说明文字）。
- 不写死屏幕尺寸：P1 起布局就按窗口尺寸类别与可用宽度计算，并在 P1 交付平板、折叠屏与单手模式的基础适配（§8.10，用户 2026-11-04 要求）；横屏分离与悬浮键盘在 P3。

### 2.3 输入模式（草案 §2）

| 模式 | 功能 |
| --- | --- |
| 中文 | 中文拼音优先，兼容英文 |
| 英文 | 英文输入、预测和纠错 |
| 日文 | 日语罗马字优先，兼容中文拼音、英文转日语 |
| 混合 | 中文拼音、英文、日语罗马字同时识别；是独立的输入方案，不是自动切换语言 |

- 通过语言键切换；长按语言键选择模式；自定义切换顺序；隐藏不需要的模式；按 App 记忆模式；为特定 App 固定模式；切换模式时尽量保留未提交的文字。
- 混合模式的默认排序原则：**原语言优先，日语跨语言候选其次，再按词频、常见程度和上下文动态调整。** 示例（实际顺序由上下文和设置决定）：

| 输入 | 候选示例 |
| --- | --- |
| `diannao` | 电脑、電腦、计算机、コンピューター、💻 |
| `coffee` | coffee、Coffee、コーヒー、☕ |
| `sakura` | 桜、さくら、サクラ、Sakura、🌸 |
| `nihon` | 日本、にほん、ニホン |
| `apple` | apple、Apple、アップル、🍎 |
| `neko` | 猫、ねこ、ネコ、🐱 |

- 智能语言识别：中文拼音优先返回中文，明确的英文单词优先返回英文，明确的日语罗马字优先返回日语；特征不明显时保留多语言候选。最近使用的语言可以影响排序，但不能覆盖原语言优先，也不能强行决定唯一候选（`ai` → 爱、AI、愛、あい）。
- 智能程度三级：保守（原语言严格优先，其他语言靠后）、平衡（原语言优先，结合上下文调整，**默认**）、积极（强化跨语言候选和上下文预测）。中文、英文、日语和 Emoji 的候选权重可分别设置。

### 2.4 明确后置

- 语音输入、手写输入、外接物理键盘、更多语言与输入方案（P5）。
- 英文滑动输入（P5）：目前没有可用的开源手势解码器（§3.1）。
- 在线词库社区与主题市场（P5）；WebDAV 和用户文件夹以外的同步后端。
- 在线 AI：只在 P4 之后、用户显式开启时提供（§11.3）。
- `armeabi-v7a`、iOS 与桌面版本。
- 本地 AI 模型与中文的校对、重写（用户 2026-11-04 决定不做）。

### 2.5 已确定与待用户决定的事项

已由草案确定：Kotlin/C++；键盘自定义 View；设置界面 Compose；本地优先；AI 可选且不进入按键路径；草案 §17 的性能与隐私原则；§21 的致谢要求。

待用户决定（决定之前按"默认"推进，不对外发布）：

| 事项 | 默认 | 说明 |
| --- | --- | --- |
| 许可证 | GPL-3.0 | 与 MyApps 应用一致，可复用 GPL/LGPL 的键盘与词库代码（§15.1）。对外发布前确认 |
| 混合与日文模式下的空格键 | 组合中按空格只插入分隔、不提交，回车或点选提交；中文模式空格选首选 | 草案的整句示例在组合中含空格（`chidai ni kai ni ikou`）；设计稿给出可选方案，交用户选择 |
| 分发渠道 | GitHub Releases（签名 APK） | Google Play 有输入法与数据披露要求；F-Droid 不接受 ML Kit 这类非自由依赖，需要不含 ML Kit 的构建变体 |
| 草案提到的主题截图与 Madamada 交互截图 | 放在本地 `.dev/reference/`，不提交 | 截图尚不在仓库中；第三方产品的界面截图不进入公开仓库 |
| 原创"Apple 风格"Emoji 主题的美术来源 | 暂不制作，先用许可明确的素材 | 需要原创美术或明确授权（§11.5） |
| 自定义 AI 提示词（草案 §13.1） | 不提供 | 只用专用 API 时无法实现；如需提供，要引入 Prompt API |
| Mozc 构建主机 | CI（Linux）产出预构建库，Windows 开发机使用锁定哈希的产物 | Mozc 的 Android 构建只支持 macOS/Linux 主机（§3.1） |

## 3. 已核实事实与待验证假设

### 3.1 引擎与上游

| 上游 | 核实所得（2026-11-04 阅读） | 对 Kotomo 的影响 |
| --- | --- | --- |
| librime | 最新发行版 1.17.0（2026-06-06），BSD-3-Clause；CMake、C++17；依赖 Boost ≥1.74、LevelDB、marisa、OpenCC ≥1.0.2、yaml-cpp ≥0.5（glog 可选）。嵌入接口 `rime_api.h`：`setup`、`initialize`、`deploy`、`create_session`、`process_key`、`set_input`、`get_context`（预编辑、光标、候选页）、`get_commit`、`select_candidate`、候选迭代、`delete_candidate`、`set_option`、`register_module`。模糊音和双拼都是方案中 `speller/algebra` 的规则 | 作为子模块从源码构建，跟随 GitHub 发行版。按片段调用可用 `set_input` + `get_context`；更深的接入走 `register_module` 插件。模糊音开关会改写方案规则并重新部署，部署耗时由 S2 测量 |
| Trime（Rime 的 Android 前端） | GPL-3.0-or-later，约 96% Kotlin；v3.3.13（2026-11-01）；librime 是子模块，由 Gradle 的 CMake 集成从源码构建（NDK 30.0.16248370、CMake 4.1.2），并带 lua、octagram、predict 插件 | S2 首选参考它的构建方式；GPL 代码可在许可证范围内复用 |
| fcitx5-android | LGPL-2.1-or-later；0.1.3（2026-07-26）；插件有 anthy、chewing、hangul、jyutping、rime、sayura、thai、unikey 等，**没有 Mozc**（PR #667 未合并）；librime 等以独立 prebuilder 产出的静态库链接 | 预构建静态库是 S2 的备选方式；没有可直接复用的 Android 版 Mozc 前端 |
| Mozc | Google 的代码为 BSD-3-Clause，LICENSE 另附词典声明；只用 Bazel（Bazelisk，`src/.bazeliskrc` 指定 9.0.2）。官方文档 `docs/build_mozc_for_android.md`：`bazelisk build package --config oss_android --config release_build` 产出 `libmozc.so`（NDK r29，**只支持 macOS/Linux 主机**，含 `arm64-v8a` 与 `x86_64`，16 KB 页对齐）。其 JNI 注册在 Google 的类名 `com.google.android.apps.inputmethod.libs.mozc.session.MozcJni` 上，只暴露 `evalCommand`（序列化的 `commands::Command`）、`onPostLoad`、`getDataVersion`；Android Java 客户端不在开源树中。只发布标签（最新 3.35.6354，2026-10-16），没有 GitHub Releases | Kotomo 自写客户端，并需要自己的 C++ 胶水层（不沿用 Google 包名的 JNI）；跟随最新标签；Mozc 在 Linux/macOS 或 CI 上构建，Windows 开发机使用锁定哈希的预构建产物 |
| Mozc 的片段类型 | `src/converter/segments.h`：`enum SegmentType { FREE, FIXED_BOUNDARY, FIXED_VALUE, SUBMITTED, HISTORY }`。`ImmutableConverter::Resegment` 把 `FIXED_VALUE` 片段以其首个候选的值与 lid/rid 作为最低代价节点插入，**只用于转换请求**；建议和预测请求只接受单个 `FREE` 片段 | §5.4 的"固定片段整句转换"有源码依据，但必须在转换器层调用，并带读音和合法的 lid/rid；预测候选中的跨语言片段需要另行处理 |
| Mozc 词典数据（`dictionary_oss`） | 混合来源：IPAdic（NAIST 声明与 ICOT 条款，需保留声明与免责文本）、冲绳方言词典（公有领域）、Google 增补条目 | 条款宽松；S8 记录义务，并确认与 GPL-3.0 组合分发的方式 |
| OpenCC | Apache-2.0；ver.1.4.2（2026-08-22）；配置 s2t、t2s、s2tw、tw2s、s2twp、tw2sp、s2hk、hk2s、s2hkp、hk2sp、t2tw、tw2t、t2hk、hk2t、t2jp、jp2t；日文新字体表 `JPShinjitaiCharacters.txt`（约 550 行）与 `JPShinjitaiPhrases.txt`，README 称日文支持"有限" | 简繁与地区用词直接使用，跟随 GitHub 发行版；`jp2t` 可用于跨语言词库的字形归一，但必须配合 Unihan 变体字段和人工复核 |
| AOSP LatinIME | Apache-2.0；原生 C++（`native/jni`）含二进制词典、按键邻近信息和建议核心，手势解码为空实现；`dictionaries/` 含 en_US、en_GB 词表；main 分支最近一次提交在 2025-02-26 | 英文引擎首选复用它的原生建议代码与词典格式（S4） |
| HeliBoard | GPL-3.0（含 AOSP 代码的 Apache-2.0 声明）；v4.2（2026-10-12）；原生部分是 AOSP LatinIME 的 C++；词典仓库 `Helium314/aosp-dictionaries` 按文件各有许可证（英文 experimental 词表为 CC BY 4.0）；手势输入只能用 Google 的闭源库，开源替代（NLnet 资助项目）尚未发布 | LatinIME 原生代码的活跃维护版本，S4 的参考；滑动输入后置 |
| FlorisBoard | Apache-2.0；Kotlin + Compose（含 Rust 模块），仍为 beta；词语建议和拼写检查不在当前版本中 | 只作为 Compose 键盘的架构参考 |

依据：[librime 1.17.0](https://github.com/rime/librime/releases/tag/1.17.0)、[`rime_api.h`](https://github.com/rime/librime/blob/master/src/rime_api.h)、[Trime](https://github.com/osfans/trime)、[fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)、[Mozc Android 构建文档](https://github.com/google/mozc/blob/master/docs/build_mozc_for_android.md)、[`segments.h`](https://github.com/google/mozc/blob/master/src/converter/segments.h)、[`dictionary_oss/README.txt`](https://github.com/google/mozc/blob/master/src/data/dictionary_oss/README.txt)、[OpenCC 1.4.2](https://github.com/BYVoid/OpenCC/releases/tag/ver.1.4.2)、[AOSP LatinIME](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/)、[HeliBoard](https://github.com/HeliBorg/HeliBoard)、[aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries)、[NLnet 手势输入项目](https://nlnet.nl/project/GestureTyping/)、[FlorisBoard](https://github.com/florisboard/florisboard)。

### 3.2 Android 平台与设备端 AI

| 项目 | 核实所得（2026-11-04 阅读） | 对 Kotomo 的影响 |
| --- | --- | --- |
| 平台与工具链 | Android 17（API 37）于 2026-06-16 发布稳定版；Google Play 自 2026-08-31 起要求新应用与更新以 API 36 为目标；面向 API 35 及以上的应用必须支持 16 KB 页大小，2027-02-01 起 Play 拒绝不支持的更新（NDK r28 起默认 16 KB 对齐）。最新稳定版：AGP 9.5.0（需要 Gradle 9.6.0+ 与 JDK 17，支持到 API 37）、Gradle 9.8.2、Kotlin 2.4.21、NDK r30（30.0.16248370，长期支持版）、Compose BOM 2026.09.00、Room 2.8.5（新的 `androidx.room3` 3.0.3 只支持 KSP）、DataStore 1.3.0 | `compileSdk`/`targetSdk` 取 37；P0 锁定这些版本（或届时更新的版本） |
| 输入法接口 | `IME_FLAG_NO_PERSONALIZED_LEARNING`（API 26）只是请求，平台不保证；密码类输入为 `TYPE_TEXT_VARIATION_PASSWORD`、`TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`、`TYPE_TEXT_VARIATION_WEB_PASSWORD`、`TYPE_NUMBER_VARIATION_PASSWORD`；内联自动填充建议（API 30：`supportsInlineSuggestions`、`onCreateInlineSuggestionsRequest`，输入法看不到建议内容）；手写笔回调自 API 33 起，Android 14 起"在任意输入框书写"需要输入法实现这些接口；面向 API 34 及以上的输入法不能覆盖 `onCreateInputMethodSessionInterface` | Kotomo 把无学习标记当作硬约束（§14）；内联建议在 P3，手写笔在 P5 |
| Android 16、17 的变化 | Android 16 新增 `onCustomImeSwitcherButtonRequestedVisible` 与 `onShouldVerifyKeyEvent`。Android 17 对所有应用：未处理的旋转后不再恢复键盘可见性，并按设备内存设定应用的内存上限。面向 API 37：中日韩输入法可以用 `TextAttribute.Builder.setTextSuggestionSelected()` 标记选中的转换候选；禁止修改 `static final` 字段（经 JNI 修改会崩溃）；`System.load()` 加载的本地库必须只读 | 转换片段的标记使用新接口（§8.2）；内存基线必须在 Android 17 设备上测量；JNI 层不碰 `static final` |
| Compose 与输入法 | `ComposeView` 需要经视图树传递的 `LifecycleOwner` 与 `SavedStateRegistryOwner`；没有官方的输入法指南，社区做法是让服务实现这些所有者并设置到输入法窗口的 decor view 上 | S1 验证之后再决定面板是否使用 Compose |
| 剪贴板 | Android 10 起只有默认输入法和焦点应用能读取剪贴板；Android 12 的读取提示不对默认输入法显示；Android 13 起敏感剪贴以 `ClipDescription.EXTRA_IS_SENSITIVE` 标记，键盘应隐藏其预览；Android 13 起系统在最后一次读写后不超过 60 分钟清除剪贴板（CDD §9.8.7，AOSP 默认 1 小时）；Android 14–17 没有剪贴板行为变更 | 剪贴板历史由 Kotomo 自己保存（§10.7），不依赖系统保留；敏感剪贴永不记录 |
| ML Kit GenAI | 提供 Prompt、Proofreading、Rewriting、Summarization、Image Description、Speech Recognition，以及 2026-10-26 新增的 Text-to-Speech、Speech Translation、Agent、Embeddings（alpha，需要 Kotlin 2.3+）。`genai-proofreading` 与 `genai-rewriting` 为 1.0.0-beta1，`genai-prompt` 为 1.0.0-beta5，均为 beta、无 SLA。校对与重写支持英语、日语、法语、德语、意大利语、西班牙语、韩语（**不含中文**），输入少于 256 token，API 26+，解锁 bootloader 的设备不可用；重写风格有 ELABORATE、EMOJIFY、SHORTEN、FRIENDLY、PROFESSIONAL、REPHRASE。Prompt API 输入少于 4,000 token、输出最多 4,096 token。支持的设备包括 Pixel 9/10/11、Galaxy S25/S26/Z Fold7/Flip8/Fold8 和多家厂商的旗舰 | 英文、日文使用专用 API；中文不提供校对与重写（用户决定）；长文本分段处理 |
| 前台限制 | 官方文档："GenAI API inference is permitted only when the app is the top foreground application"；在前台服务中调用同样返回 `BACKGROUND_USE_BLOCKED`（30）。另有每应用配额（`BUSY` 9、`PER_APP_BATTERY_USE_QUOTA_EXCEEDED` 27），数值未公开。没有找到针对输入法的官方豁免。开发者报告（非官方）：在 Galaxy S25 Ultra（Android 16）上从 `InputMethodService` 调用（键盘显示在其他应用之上）返回 `BACKGROUND_USE_BLOCKED`，同一调用在该应用自己的 Activity 中成功；另有报告 `checkStatus()` 显示可用，生成时才被拒绝 | **默认设计：AI 推理在用户主动打开的 Kotomo 前台界面中进行**（§11.1）；S7 在实际设备上复核直接调用与这一设计 |
| ML Kit 翻译与语言识别 | `translate` 17.0.3，API 23+，59 种语言，含中文、日文、英文；每种语言模型约 30 MB，按需下载。它是 ML Kit 的普通语言 API，不属于 GenAI，官方页面没有任何前台或后台限制的说明。定位为简单翻译：不含英语的语言对经英语中转（中日互译即如此），质量随语言对变化。语言识别内置版 17.0.6（约 900 KB），支持 100 多种语言，含罗马化的中文与日文 | 翻译直接在键盘中运行（S7 确认）；结果先预览；中日互译的质量在 P4 评估 |

依据：[Android 17](https://android-developers.googleblog.com/2026/06/Android-17.html)、[Play 目标 API 要求](https://developer.android.com/google/play/requirements/target-sdk)、[16 KB 页大小](https://developer.android.com/guide/practices/page-sizes)、[AGP 版本说明](https://developer.android.com/build/releases/about-agp)、[`EditorInfo`](https://developer.android.com/reference/android/view/inputmethod/EditorInfo)、[输入法与自动填充](https://developer.android.com/guide/topics/text/ime-autofill)、[`InputMethodService`](https://developer.android.com/reference/android/inputmethodservice/InputMethodService)、[Android 17 行为变更](https://developer.android.com/about/versions/17/behavior-changes-17)、[复制与粘贴](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)、[剪贴板安全](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling)、[Android 13 CDD](https://source.android.com/docs/compatibility/13/android-13-cdd)、[ML Kit GenAI](https://developers.google.com/ml-kit/genai)、[校对 API](https://developers.google.com/ml-kit/genai/proofreading/android)、[重写 API](https://developers.google.com/ml-kit/genai/rewriting/android)、[Prompt API](https://developers.google.com/ml-kit/genai/prompt/android/get-started)、[ML Kit 翻译](https://developers.google.com/ml-kit/language/translation/android)；开发者报告 [wmkeyboard #804](https://github.com/wasi-master/wmkeyboard/issues/804)、[googlesamples/mlkit #1013](https://github.com/googlesamples/mlkit/issues/1013)。

### 3.3 数据与素材

| 数据 | 核实所得（2026-11-04 阅读） | 对 Kotomo 的影响 |
| --- | --- | --- |
| 中文方案与词库 | rime-ice：GPL-3.0 only（其来源包括 Unihan、rime-essay-simp、THUOCL 等）；rime-frost：GPL-3.0；rime_wanxiang：CC BY 4.0；rime-luna-pinyin、rime-essay、rime-prelude：LGPL-3.0；双拼方案 rime-double-pinyin：GPL-3.0；libime 数据：LGPL-2.1-or-later；CC-CEDICT：CC BY-SA 4.0（词级拼音，持续更新）；mozillazg/pinyin-data：MIT（源自 Unihan） | S2 在许可允许的候选中按语料表现选择；打包 rime-ice 会使整个发行版实际上只能以 GPL-3.0 发布 |
| 英文词表 | AOSP LatinIME 的 en_US、en_GB 词表（按仓库推断为 Apache-2.0，需要确认）；HeliBoard 词典仓库中的英文 experimental 词表为 CC BY 4.0 | S4、S8 确认许可后选用 |
| 日文词典 | Mozc OSS 词典（§3.1）；SudachiDict：Apache-2.0（含 UniDic 与部分 NEologd），v20261015；UniDic：GPL-2.0、LGPL-2.1、BSD 三重许可；NEologd：Apache-2.0，2020 年后停更；merge-ut-dictionaries 的产物按来源混合，其中 alt-cannadic 只允许 GPL-2.0（与 GPLv3 不兼容）、jawiki 为未注明版本的 CC BY-SA、SKK 的 edict 为 CC BY-SA 3.0 | 默认使用 Mozc OSS 词典；人名、地名、车站名、外来语的扩充优先取 SudachiDict；不采用仅限 GPL-2.0、CC BY-SA 3.0 或版本不明的数据 |
| JMdict 与 KANJIDIC2 | EDRDG 许可：CC BY-SA 4.0（覆盖 JMdict 的日文与英文部分、KANJIDIC2 等；其他语言的释义另有版权）。应用须在从菜单进入的独立页面（关于或来源）中署名，只在启动页提及不够，并须有定期更新数据的流程。优先级标记：news1/2、ichi1/2、spec1/2、gai1/2（常用外来语）、nfxx；`lsource` 记录外来语的来源语言（コーヒー 同时标有英文 coffee 与荷兰文 koffie）。jmdict-simplified 每周一自动发布 JSON（最新 3.6.2+20261102210542） | 英文 → 日文映射的主要来源；署名放在"关于与致谢"的独立来源页；更新流程即 `AGENTS.md` 的上游版本规则 |
| CC BY-SA 4.0 与 GPLv3 | Creative Commons 于 2015-10-08 宣布 GPLv3 为 BY-SA 4.0 的兼容许可（单向）；署名与标注要求依然适用；只针对 4.0 版 | JMdict、CC-CEDICT 的派生数据可以随 GPL-3.0 应用分发，并保留署名 |
| Unihan | Unicode License v3；Unicode 18.0.0 的 UAX #38：`kMandarin` 至多两个值（第一个用于简体，第二个用于繁体）；变体字段仍为临时性质；18.0 新增 `kJapaneseNewVariant`（新字体）与 `kJapaneseOldVariant`（旧字体）。陷阱：新字体可能与无关的汉字同形——「芸」的 `kMandarin` 是 yún（芸香），而它作为新字体对应的旧字体「藝」读 yì；「駅」的旧字体是「驛」 | §5.9 先把新字体映射到旧字体再取读音；多音字优先使用词级读音 |
| CLDR Emoji 注释 | CLDR 49（2026-10-22，含 Unicode 18 新 Emoji 的注释），Unicode License v3；cldr-json 的 `annotations/{en,ja,zh,zh-Hant}` 提供关键词与名称，`annotationsDerived` 覆盖肤色与 ZWJ 序列 | Emoji 关键词词库的来源（§4.1） |
| Emoji 图像 | Noto Emoji：字体为 OFL-1.1，图像为 Apache-2.0，旗帜为公有领域，已支持 Unicode 18.0；Twemoji（jdecked 维护版）：图形为 CC BY 4.0、代码为 MIT，最新 17.0.3 只覆盖 Emoji 17.0；OpenMoji：图形为 CC BY-SA 4.0；Apple：系统软件许可禁止再分发，App Store 审核指南 5.2.5 明确第三方键盘不得包含 Apple Emoji；Telegram 安卓版自带的 Emoji 图像没有授权说明，Telegram-FOSS 因授权不明将其替换 | 默认系统字体；Noto、Twemoji 作为样式包，样式包缺字时回退系统字体；绝不打包 Apple 或来源不明的图像 |
| 最新 Unicode | Unicode 18.0.0 于 2026-09-16 发布（9 个新 Emoji） | Emoji 数据与样式包跟随最新版本 |

依据：[rime-ice](https://github.com/iDvel/rime-ice)、[CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict)、[aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries)、[SudachiDict](https://github.com/WorksApplications/SudachiDict)、[merge-ut-dictionaries](https://github.com/utuhiro78/merge-ut-dictionaries)、[EDRDG 许可](https://www.edrdg.org/edrdg/licence.html)、[JMdict DTD](https://www.edrdg.org/jmdict/jmdict_dtd_h.html)、[jmdict-simplified](https://github.com/scriptin/jmdict-simplified)、[CC 兼容许可](https://creativecommons.org/share-your-work/licensing-considerations/compatible-licenses/)、[UAX #38](https://www.unicode.org/reports/tr38/)、[CLDR 49](https://cldr.unicode.org/downloads/cldr-49)、[Noto Emoji](https://github.com/googlefonts/noto-emoji)、[Twemoji](https://github.com/jdecked/twemoji)、[App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)、[Unicode 18.0](https://blog.unicode.org/2026/09/announcing-unicode-standard-version-180.html)。

数据缺口（S8 继续寻找许可明确的来源，找不到就延后相应功能）：日文词条的常见中文释义（尚未找到许可明确的日中词典数据）、JLPT 词汇分级、日本车站名称全集、专业领域词库。

### 3.4 不应继承为结论的假设

- "librime 和 Mozc 各自能用，拼起来就能混合整句"：两者都是会话式引擎，各自切分输入，不接受外部候选网格；跨语言整句需要协调层（§5）。
- "Mozc 的固定片段能直接用于所有候选"：只有转换请求支持，建议和预测不支持（§3.1）。
- "Gemini Nano 可以直接在输入法里调用"：ML Kit GenAI 只允许位于最前台的应用推理，已有开发者报告从 `InputMethodService` 调用被拒绝（§3.2）；默认改为在 Kotomo 的前台界面中推理，S7 在实际设备上复核。这一限制只针对 GenAI：设备端翻译属于 ML Kit 的普通 API，不受它约束（§3.2）。
- "ML Kit 的校对与重写支持中文"：不支持（§3.2）；Kotomo 因此不为中文提供校对与重写（§11.1）。
- "英文转日文就是音译"：以词典映射为主，音译只作最后的候选（草案 §5.3）。
- "简繁转换只需逐字转换"：需要词组级转换和地区用词，并让地区词库参与候选生成（草案 §4.3）。
- "日文汉字词可以直接得到汉语拼音"：需要字形归一（新字体 → 旧字体 → 简体）和多音字处理；新字体可能与无关的汉字同形，日本国字没有汉语读音（§3.3、§5.9）。
- "键盘里显示的 Emoji 样式就是对方 App 显示的样式"：不是；提交的是 Unicode，显示由目标 App 决定（草案 §8.3）。
- "Telegram 使用 Apple 风格 Emoji，所以素材可以用"：不能据此推定任何授权（草案 §8.4）。
- "LLM 生成的词库可以直接用"：不可以，必须校验且默认低优先级（草案 §7.4）。
- "首批候选 50 ms 在所有设备上都能达到"：需要真机基线，先测量再承诺（草案 §17.1）。
- 草案 §3.4 与 §19.1 的 `chida nikainiikou` 是 `chidai nikainiikou` 的笔误（用户 2026-11-04 确认）：与 §3.1 一样，`chidai` 是「池袋」的汉语拼音，这些用例验证的都是"拼音 → 日文"加罗马字的整句转换（§13.2）。
- 草案用例 `chocolateokainiikou` 中的 `o` 对应助词「を」（标准罗马字写作 `wo`）：需要一条带代价、只在名词之后生效的"助词 o → を"容错规则，不能把它当成普通的「お」。

## 4. 架构决策

### 4.1 默认技术选择

| 项目 | 默认方案 | 采用条件/限制 |
| --- | --- | --- |
| 应用语言 | Kotlin（最新稳定版），协程与 `Flow` | 工具可用 Java 单文件程序（`java tool/…`） |
| 构建 | Gradle Kotlin DSL + 版本目录；本地代码用 CMake（`externalNativeBuild`） | Mozc 用其自身的 Bazel 构建，由脚本产出预构建库供 Gradle 打包（S3 决定细节） |
| 键盘与候选栏 | 自定义 View + Canvas | 面板（Emoji、剪贴板）可以用 Compose，前提是 S1 证明它在 IME 窗口中的首帧和内存可以接受 |
| 设置应用 | Jetpack Compose，Material 3 | 参考 MyApps-UI 的设置呈现；品牌色与图标由设计稿给出 |
| 中文引擎 | librime | 方案与词库数据按许可证与语料表现选择（§3.3、S2） |
| 日文引擎 | Mozc | 使用其组合器、转换器、词典与预测；客户端与 C++ 胶水层自写 |
| 英文引擎 | AOSP LatinIME 的原生建议代码与二进制词典 | 词表来源按许可证选择（§3.3、S4）；做不到时以 ADR 改为自研的词表 + 二元组 + 按键纠错 |
| 混合协调层 | 自研 C++（`core/`） | 候选网格、解码、跨语言词库运行时、统一候选与 JNI |
| 简繁与地区用词 | OpenCC | librime 本身也依赖 OpenCC；同一份库与数据，不重复打包 |
| 跨语言词库 | 自建流水线（`lexicon/`），从 JMdict、Unihan、OpenCC 与 Mozc 词典生成 | 生成结果连同来源与哈希可复现（§5.9） |
| Emoji 关键词 | Unicode CLDR 注释（英文、简体中文、繁体中文、日文） | 中文关键词另生成拼音键，日文关键词另生成罗马字键 |
| Emoji 图像 | 默认用系统 Emoji 字体；Noto Emoji、Twemoji 作为可选样式包 | 样式只影响键盘内显示，提交的始终是 Unicode；样式包缺字时回退系统字体 |
| 存储 | Room（SQLite）存用户词库、学习记录、剪贴板与短语；DataStore 存设置；主题为 JSON 文件 | 格式带版本，保留未知字段，使用 UTC 时间；数据迁移与配置升级可以回滚 |
| 后台任务 | WorkManager | 词库更新、自动备份 |
| 设备端 AI | ML Kit GenAI（经 AICore 调用 Gemini Nano） | 从 MyApps-AI 的 `GenAiChannel.kt` 移植；推理在用户打开的 Kotomo 前台界面中进行（§3.2、S7） |
| 翻译 | ML Kit 设备端翻译 | 模型由用户点击下载 |
| 测试 | GoogleTest（主机端 C++）、JUnit（Kotlin 逻辑）、AndroidX Test（真机与模拟器）、Macrobenchmark（延迟） | GoogleTest 与 librime、Mozc 的测试框架一致 |

### 4.2 运行结构

```text
触摸 ─→ KeyboardView（主线程）─→ 按键事件 ─→ 引擎线程（唯一）─→ core（C++）
                                              │  组合更新 → 网格增量更新 → 快速解码
                                              ├→ 快照 v1（首批候选）─→ 主线程 ─→ 候选栏绘制
                                              └→ 后续阶段（可被新按键取消）：
                                                 k-best 整句、Mozc/librime 收尾、Emoji、联想
                                                 ─→ 快照 v2 …（代号递增，旧快照丢弃）
提交 ─→ InputConnection.commitText（主线程）─→ 学习更新（引擎线程，延后执行；敏感输入框不执行）
```

- 输入法与设置应用在同一进程；设置的修改经 DataStore 通知引擎线程重新加载。
- librime 部署（编译方案）与词库加载在后台线程进行，完成前键盘继续使用上一份已部署的数据。
- AI、翻译、备份、同步与下载都不在引擎线程上运行。
- 用户数据（用户词库、学习记录、剪贴板、设置）与系统词库（随应用或下载得到的只读数据）分开存储；替换或回滚系统词库不触及用户数据。
- Mozc 胶水层（含 protobuf、abseil）与 librime（含 Boost）分属不同的共享库，隐藏内部符号，避免同名符号冲突；由 S3 确认。

### 4.3 模块与目录

目录随第一份真实内容出现，不预建空目录。

```text
app/                Android 应用：输入法服务、键盘与布局、候选栏、面板、设置、存储、剪贴板、备份与同步、AI
core/               C++ 协调层：组合、候选网格、解码、跨语言词库运行时、引擎适配与 JNI
core/tests/         主机端单元测试
lexicon/            词库构建流水线：源数据 → 运行时词库，含来源与校验
tests/conversion/   转换语料（主机与真机共用）
third_party/        librime、Mozc、OpenCC、LatinIME 原生代码等子模块（固定提交）
patches/            对上游的补丁（编号、注明原因）
scripts/            构建上游、生成词库、运行语料的脚本
design/             设计 Agent 的独立界面稿（HTML）与设计令牌
build-lock.json     工具链、上游提交、数据源版本与哈希
```

草案 §16.2 的模块（Input Coordinator、Language Engines、Dictionary Manager 等）是职责划分：它们落在上表的目录中，不是逐个建类的清单。

### 4.4 引擎 ↔ 应用契约

- 应用侧一个 Kotlin 外观类，对应 `core/jni` 的少量入口，只在引擎线程上调用。
- 输入：按键事件（字符与按下坐标，用于按键纠错）、命令（选择候选、选择片段、锁定片段、部分提交、撤销转换、恢复原始输入、清空、切换模式与选项）、上下文（光标前的文本；敏感输入框不传）。
- 输出：快照，包括组合文本及其片段划分与光标、候选页及每个候选的元数据、快照代号。每次按键只跨一次 JNI；快照用紧凑的二进制格式或 protobuf-lite 序列化，不用逐键 JSON（P0 选定）。
- 统一候选字段（草案 §6.1）：候选文字、原始输入范围、来源语言、目标语言、读音、词频、匹配置信度、分段信息、词库来源、是否模糊匹配、是否跨语言转换、是否 Emoji。
- 契约带版本号，P0 结束前写入 `doc/en-us/`；此后一侧改动契约，两侧及其测试在同一变更中更新。

## 5. 整句混合输入引擎（核心设计）

本节是草案 §3 的实现方案，属于默认设计：S2–S6 的结果可以改变实现手段，但不能降低 §13.2 的验收要求。

### 5.1 输入模型

- 输入串是用户按下的拉丁字母和少量分隔符（`'` 与组合中的空格）；每个字符保留按下坐标，供按键纠错使用。
- 分隔符只是边界提示：`chidai ni kai ni ikou` 与 `chidainikainiikou` 都要能转换；分隔符处的切分代价更低，但不是强制边界。
- 组合内可以移动光标、在中间插入或删除；未受影响的前缀结果复用。

### 5.2 候选网格

网格的节点是输入串中的位置，边是"输入串的一段 → 一种解释"。每类解释由一个提议者产生：

| 提议者 | 输入 → 解释 | 数据 |
| --- | --- | --- |
| 中文拼音 | 拼音音节（全拼、简拼、模糊音）→ 中文词 | librime 的拼音切分与词典 |
| 日语罗马字 | 罗马字 → 假名（含 `si`/`shi` 等写法变体）→ 日文词 | Mozc 的组合表与词典 |
| 拼音 → 日文 | 拼音 → 日文汉字词（附日文读音） | 跨语言词库 |
| 英文 | 英文词（含拼写与按键纠错） | 英文词典 |
| 英文 → 日文 | 英文词 → 片假名外来语或语义对应的汉字词 | 跨语言词库 |
| Emoji | 三种语言的关键词 → Emoji | CLDR 派生词库 |
| 符号与数字 | 数字、标点、日期、金额等转换 | 内置规则 |

每条边记录输入范围、解释文字、读音、来源语言、目标语言、各项代价、词库来源、是否模糊、是否跨语言。同一段输入可以同时有多种解释（`sakura` → さくら/桜、Sakura、其他词库的匹配）。

除了引擎自带的词典，所有词库（内置、专业、在线、自定义、学习、跨语言、Emoji）都编译成同一种只读的运行时格式，以同样的方式查询并进入网格（草案 §16.3 的"词库具有统一接口"）；词库来源与优先级是边上的属性，不是不同的代码路径。

### 5.3 解码与评分

- 在网格上做 k-best 束搜索（Viterbi 的 k-best 扩展），状态为（位置、当前目标语言、语言模型状态）；不做从左到右的贪心匹配。
- 路径代价等于各边代价加转移代价，综合草案 §3.3 的各项：原语言匹配、词汇频率、输入路径准确度（拼写、模糊和纠错惩罚）、句子上下文、语言切换代价、语法合理程度、用户历史、自定义词库优先级、模糊音惩罚。
- 各词库的频率先校准到可比较的尺度（每种语言用对数概率并分段线性校准），否则词条多的语言会系统性占优。校准参数由转换语料拟合，写入词库元数据。
- 语言切换有代价：允许跨语言（`zailiucard` → 在留カード），但无意义的频繁切换不能获得更高分数。
- 整句候选有一个目标语言（中文、日文或英文）。跨语言边（拼音 → 日文、英文 → 日文）只出现在目标为日文的路径中；英文词可以原样出现在中文或日文句子中（今天的meeting）。Emoji 不进入整句，只作为独立候选。
- 智能程度三级与各语言权重（§2.3）改变的是先验代价，不改变解码算法。
- 用户历史用带衰减和最少证据阈值的平滑计数，避免少量偶然输入改变长期习惯（草案 §6.4）。

### 5.4 按目标语言收尾

- **日文**：解码结果是"读音串 + 已固定的片段"，例如 [池袋/いけぶくろ（固定）] + [に] + [かいにいこう]。交给 Mozc 转换时，固定片段作为 `FIXED_VALUE` 片段传入（带读音和合法的 lid/rid，取自 Mozc 词典中的同一词条），其余部分由 Mozc 的词典、连接代价和活用规则转换，得到「池袋|に|買いに|行こう」及每个文节的候选。这需要在 Mozc 转换器层调用的 C++ 胶水层（S3）。做不到时依次退回：(1) 把固定片段作为本次会话的临时用户词条，再整句转换；(2) 用 Mozc 的词典与连接代价在协调层内自建日文网格（成本最高，需要 ADR）。
- **中文**：拼音片段交给 librime，得到整句与词候选；英文片段原样保留。S2 决定调用深度：按片段使用会话接口（`set_input` + `get_context`），或以 `register_module` 插件直接使用其词典与造句。
- **英文**：英文词序列，带大小写、拼写纠错与短语补全。
- 收尾结果保留片段结构，供 §5.6 的局部修改使用。

### 5.5 增量解析与两阶段候选

- 每次追加按键只新增以新位置结尾的边（词典按前缀索引查找），前向分数只重算受影响的后缀；删除时截断；在组合内移动光标时只重建受影响的区间。
- 第一阶段（目标：真机 p95 ≤ 50 ms）：最佳路径加各语言的首个词候选，立即显示。
- 第二阶段（异步，可被下一次按键取消）：k-best 整句、Mozc/librime 收尾、Emoji、联想，按代号递增更新候选栏；过期结果丢弃。

### 5.6 整句候选与局部修改（草案 §3.4）

- 候选栏显示完整的句子候选，可展开整句预览，可查看分段候选。
- 点击某个片段，列出覆盖这段输入的所有解释（三种语言与引擎候选）；选中后该片段被锁定，其余部分在此约束下重新解码。已确认的片段不被重写：修改「池袋」时，「に買いに行こう」不变。
- 支持撤销上一次转换、恢复原始输入、保留英文原文、部分提交后继续输入后续内容。
- 片段边界可以调整（文节伸缩），调整后同样只重算受影响的部分。

### 5.7 语法要求（草案 §3.5）

- 日文：助词搭配、动词与形容词活用、常用表达、词语连接、敬语与普通形、片假名外来语、专有名词与地名、用户的文体偏好。主要由 Mozc 的转换器承担；协调层负责跨语言片段与 Mozc 的衔接。
- 中文：拼音切分歧义、同音词、常用搭配、上下文语义、简繁地区用词。主要由 librime 及其语言模型承担。
- 英文：单词边界、拼写纠错、自动大小写、下一词预测、常见短语补全。

### 5.8 规则引擎与 AI 的分工（草案 §3.6）

正常打字只用规则、词典、语言模型和候选搜索。Gemini Nano 只用于用户主动触发的复杂文本处理。可选的本地轻量重排序模型只有在满足延迟预算时才启用，并始终保留传统算法路径；未经 ADR 不引入。

### 5.9 跨语言词库

- **拼音 → 日文汉字词**（草案 §5.2）：取 Mozc 词典中全由汉字构成的词条（普通名词、专有名词、地名、车站名、人名、专业术语等），逐字求汉语读音：先把新字体映射到旧字体（Unihan 18.0 的 `kJapaneseOldVariant`，辅以 OpenCC 的日文新字体表），再转为简体，然后取拼音——优先用中文词典（如 CC-CEDICT）中同形词的词级读音，否则用 Unihan 的 `kMandarin`；多音字展开并加代价。必须先映射旧字体：「芸」直接取读音是 yún，而作为「藝」的新字体应读 yì（§3.3）。例：池袋 → chí dài → `chidai`；東京 → 东京 → `dongjing`；面接 → `mianjie`；在留 → `zailiu`。没有汉语读音的日本国字（如込、畑、峠）跳过，或由人工映射补充。候选同时显示日文读音（池袋 / いけぶくろ）。用户可以自定义映射。
- **英文 → 日文**（草案 §5.3）：从 JMdict 的英文释义反查，分三类：(1) 常见外来语（片假名词，参考优先级标记 `gai1` 等）；(2) 语义对应的汉字词（`school` → 学校）；(3) 没有现成词条时的音译候选——排在最后、可单独关闭，不当作常用词。不以逐字母替换作为英文转日文。
- 日文词条以 Mozc 词典的条目为准（读音、表层形式、词性 ID），使固定片段能带合法的 lid/rid 进入转换（§5.4）；不在 Mozc 词典中的词（用户自定义）使用通用名词的词性。
- 校验：每类映射都有人工复核的样本清单和不应出现的反例；覆盖率与错误率写入词库元数据。
- 许可：JMdict 派生数据遵循 CC BY-SA 4.0，按 EDRDG 的要求署名（§15.1）。

## 6. 阶段依赖和验收关卡

```text
P0 技术验证与工具链
 ├─ S1 IME 骨架与延迟基线 ───────────────────────────┐
 ├─ S2 librime ──────┐                              │
 ├─ S3 Mozc ─────────┤                              │
 ├─ S4 英文引擎 ──────┼─→ S6 混合整句原型（主机）──────┤
 ├─ S5 跨语言词库 ────┘                              │
 ├─ S7 输入法中的 Gemini Nano（独立）                 │
 └─ S8 许可证与数据源审查                             │
                                                    ↓
 P1 基础可用版本（G1）→ P2 核心混合输入（G2）→ P3 高级输入体验（G3）→ P5 扩展与优化（G5 = 1.0）
              └──────→ P4 AI 与个性化（G4；AI 部分只依赖 P1 与 S7，可与 P2、P3 并行）
```

依赖图不要求多个 Agent；单个 Agent 也按这个顺序推进。S7 失败只影响 P4 的 AI 部分。P1 可以在 S1–S4 与 S8 通过后开始，不必等 S6。

| 关卡 | 必须证明 | 未通过的处理 |
| --- | --- | --- |
| G0：可行性 | librime、Mozc 与英文引擎在 `arm64-v8a` 真机上经 JNI 运行；S6 原型在主机上对 §13.2 的用例给出结果与差距分析；延迟、内存与体积基线已测；许可证清单完成 | 不开始 P2 的整句实现；改选引擎或调用深度，并写 ADR |
| G1：基础输入 | 三种单语模式在真机上可日常使用；敏感输入框不学习；致谢、许可证与隐私页面就位 | 修复后再发布第一个版本 |
| G2：混合输入 | §13.2 的用例达到规定名次；单语语料没有回退；延迟预算达标 | 继续 P2，不用扩展功能掩盖 |
| G3：完整输入体验 | §10 各项在真机上通过；词库导入的校验与回滚可用 | 未完成项明确列出 |
| G4：AI 与个性化 | 支持的设备上 AI 可用且符合 §14；不支持的设备体验无损；备份恢复往返正确 | AI 不可用时如实标注，不用在线 AI 代替 |
| G5：1.0 | §18 的完成清单全部满足 | 只完成一部分时按层级交付 |

## 7. P0 — 技术验证与工具链

### 输入

- 本仓库、本计划与 §3 的上游事实。
- 至少一台 `arm64-v8a` 真机（S7 需要支持 Gemini Nano 的设备）和一个 `x86_64` 模拟器。
- Android SDK/NDK、CMake；构建 Mozc 用的 Linux 或 macOS 主机（或 CI）与 Bazelisk。

### 工作项

- [ ] 工具链：选定并锁定 AGP、Kotlin、Gradle、NDK、CMake、`compileSdk`/`targetSdk`；创建 Gradle 工程（`app/`）、`build-lock.json`、CI（主机测试与 Android 构建）和 Dependabot；`.gitmodules` 只写上游的公开地址。
- [ ] **S1 IME 骨架**：`InputMethodService` + 自定义 View 的 QWERTY 键盘 + 候选栏（假数据）；测量按下到绘制的延迟、输入法冷启动到首帧的时间、内存；验证 Compose 能否用于面板（ViewTree 所有者、首帧、内存）。
- [ ] **S2 librime**：参考 Trime，以子模块从源码构建 librime 及其依赖，打通 JNI，在真机上部署一个拼音方案；记录部署时间、内存、体积、每键延迟；比较候选方案与词库（§3.3）在中文语料上的表现；判断 §5.4 的调用深度；确认在敏感输入框中关闭其用户词典写入的方式。
- [ ] **S3 Mozc**：在 Linux/macOS 主机或 CI 上用 Bazel 构建 Mozc，写 Kotomo 自己的 C++ 胶水层（会话命令 + 转换器层的固定片段转换），产出各 ABI 的共享库并锁定哈希；真机上转换 `わたしはにほんじん` → 私は日本人；验证固定片段转换 [池袋（固定）] + `にかいにいこう` → 池袋に買いに行こう；记录体积、内存、延迟；确认其历史学习在敏感输入框中关闭的方式。
- [ ] **S4 英文引擎**：构建 AOSP LatinIME 的原生建议代码（参考 HeliBoard 的维护版本），用许可明确的词表生成二进制词典；验证按键纠错、拼写纠错与下一词预测；记录体积与延迟。
- [ ] **S5 跨语言词库原型**：按 §5.9 从 JMdict、Unihan、OpenCC 与 Mozc 词典生成拼音 → 日文与英文 → 日文映射；统计样本清单的覆盖率与错误，至少覆盖池袋、東京、面接、在留、日本、桜、猫、カード、コーヒー、コンピューター、チョコレート、スケジュール、学校。
- [ ] **S6 混合整句原型（主机）**：用 S2–S5 的组件实现 §5.2–§5.4 的最小协调层，在桌面上运行 §13.2 的用例与单语基线，输出前 k 名与差距分析。
- [ ] **S7 输入法中的 Gemini Nano**：在支持的真机上，(1) 从 `InputMethodService` 直接调用 ML Kit GenAI 的校对 API，在目标 App 位于前台时记录结果（§3.2 的报告预期为 `BACKGROUND_USE_BLOCKED`）；(2) 验证默认设计：用户在键盘上点 AI 后打开 Kotomo 的前台界面完成推理，返回后把结果作为替换建议交给输入法，并测量原输入框的文本与选区能否可靠恢复（§11.1）；(3) 从输入法服务直接调用 ML Kit 设备端翻译，确认它不受前台限制。
- [ ] **S8 许可证与数据源审查**：为每个候选上游与数据源记录版本、来源、哈希、许可证、义务和是否采用（中文方案与词库、英文词表、日文词典、JMdict、Unihan、CLDR、Emoji 素材、字体、ML Kit 的数据处理条款）。
- [ ] 把引擎 ↔ 应用契约的初稿写入 `doc/en-us/`；第一个 ADR 记录引擎组合与调用深度、Mozc 的构建方式和快照序列化格式。

### 产物与验收

- `build-lock.json`、可构建的 `app/` 与 `core/`，CI 通过。
- S1–S8 各自的结果（`PASS / FAIL / BLOCKED / NOT_RUN`）与证据写入 `doc/en-us/platform-notes.md`，上游结论写入 `upstream.md`，许可证写入 `third-party.md`。
- G0 的状态。

## 8. P1 — 基础可用版本（草案 Phase 1）

### 8.1 输入法框架与键盘

- 输入法服务；设置应用首页引导启用和切换到 Kotomo。
- Gboard 风格的布局：中文拼音 26 键、英文 QWERTY、日语罗马字布局、数字键盘、符号键盘。
- 长按按键选择符号、按键弹出预览、按键震动与音效、空格键左右滑动移动光标、长按空格切换语言、删除键滑动批量删除、长按删除连续删除、双击空格输入句号、快速插入 Emoji、自动大小写、智能空格；每个手势单独开关。
- 横屏与竖屏都可用（横屏分离布局在 P3）。
- （本计划补充）Direct Boot：设备首次解锁前，键盘只使用设备保护存储中的只读内置数据，不读写任何个人数据（锁屏密码输入）。
- （本计划补充）无障碍：自定义 View 键盘从一开始就提供无障碍节点，TalkBack 可以逐键朗读，避免后期返工。

### 8.2 三种单语模式

- 中文：librime 的全拼、整句输入、长句预测、自动造词、词频学习、拼写纠错；中英混输的基本形态（英文原样作为候选）。
- 英文：词典预测、按键与拼写纠错、自动大小写、下一词预测基础版。
- 日文：Mozc 的罗马字 → 平假名、片假名、汉字，整句转换、文节调整、预测。
- （本计划补充）面向 API 37 时，用 `TextAttribute.Builder.setTextSuggestionSelected()` 标记组合中当前选中的转换片段（§3.2）。
- 模式切换：语言键、长按选择、顺序与隐藏、按 App 记忆与固定、保留未提交文字（§2.3）。

### 8.3 基础混合模式

- 三个引擎并行产出词级候选，按 §2.3 的原则合并排序（不含跨语言整句）。`diannao`、`coffee`、`sakura`、`nihon`、`apple`、`neko`、`ai` 的候选包含 §2.3 示例所列的语言。

### 8.4 简繁转换

- 大陆简体、台湾繁体、香港繁体；手动切换、候选栏快速切换、按 App 记忆；OpenCC 的词组级转换与地区用词（例：网络/網路、软件/軟體、鼠标/滑鼠、视频/影片、信息/資訊）。地区词库参与候选生成在 P3。

### 8.5 候选栏

- 横向滑动、展开全部候选、长按候选查看读音与来源、删除学习记录（降权与屏蔽在 P2）。

### 8.6 Emoji 与主题（基础）

- Emoji 面板：分类浏览、常用与最近使用、搜索（CLDR 关键词）、肤色选择；提交 Unicode。
- 主题：浅色、深色、Material You 动态配色，以及高度、圆角等少量参数；主题以 JSON 存储。

### 8.7 输入框适配（草案 §10）

利用 `InputMethodService`、`EditorInfo` 等接口识别输入框类型，自动调整布局、模式和候选策略：

| 输入框 | 行为 |
| --- | --- |
| 邮箱 | 优先提供 @、. |
| URL | 优先提供 /、.、.com |
| 搜索 | 显示搜索按钮 |
| 聊天 | 显示发送或换行按钮 |
| 电话 | 电话数字布局 |
| 数字 | 数字键盘 |
| 密码 | 关闭学习、AI 和历史记录 |
| 代码 | 英文优先、符号优化（完整代码模式在 P3） |

按输入类型调整标点、空格、大小写、回车键、候选生成、自动纠错与布局。无法提供标准编辑能力的输入框使用兼容模式，保证基本字符输入正常。

### 8.8 设置应用、关于与隐私

- 设置：模式、键盘、候选、隐私（清除学习数据、无痕模式）、关于。
- "关于与致谢"页面、开源许可证页面、第三方组件与数据来源（§15）。
- `PRIVACY_POLICY.md` 与应用内的隐私说明。

### 8.9 发布管线

- 版本号、签名（Kotomo 新建自己的签名密钥，只存在于 CI secrets 与维护者的离线副本中）、release 构建（R8）、GitHub 仓库建立后的 Actions 发布产物、`version-history.md`。

### 8.10 平板、折叠屏与单手模式（用户 2026-11-04 要求）

- 布局按窗口尺寸类别（紧凑、中等、展开）与实际可用宽度计算：键宽、键高、间距与候选栏密度都由令牌和可用尺寸推导，不写死手机尺寸；分屏、多窗口与自由窗口下按实际窗口布局。
- 平板：大屏上限制键盘的最大宽度（居中或靠边），横屏时键高随屏幕高度缩放；候选栏一次显示更多候选，整句候选可以展开得更宽。
- 折叠屏：折叠与展开、外屏与内屏切换、半折叠（桌面姿态，用 Jetpack WindowManager 的折叠特征识别铰链位置）时立即重新布局，组合内容与候选不丢失；按键不落在铰链区域。
- 单手模式：左手、右手切换，宽度可调；空出的一侧提供切换方向与恢复全宽的按钮；按设备与方向分别记住设置。
- 设计稿（`design/`）给出紧凑、中等、展开三种宽度与单手模式下的键盘和候选栏。

### G1 验收

- 三种单语模式在真机上达到单语语料（T01–T03）规定的名次。
- 密码类输入、`IME_FLAG_NO_PERSONALIZED_LEARNING` 与无痕模式下不学习、不记录（T12）。
- 首批候选延迟、输入法冷启动和内存达到 §13.4 的目标，或记录差距与原因。
- §8.10 在一台平板、一台折叠屏（含折叠与展开切换、半折叠）和手机的单手模式下通过（T14）。
- release 构建在真机上可用；致谢、许可证与隐私页面完整。

## 9. P2 — 核心混合输入（草案 Phase 2，最主要的开发阶段）

### 9.1 三语言同时识别

- 按 §5.2–§5.3 实现完整的候选网格与解码；智能语言识别体现为评分（拼音音节合法性、罗马字合法性、词典命中、频率、用户历史），不另设独立的分类器。
- 语料中加入大量歧义输入：英文与拼音同形（`change`、`hang`、`long`、`men`、`fan`）、罗马字与拼音同形（`kai`、`ni`、`ai`）。

### 9.2 整句连续混合输入（草案 §3.1–§3.3）

- 无空格、无语言标记的连续输入：`chidai ni kai ni ikou` → 池袋に買いに行こう，`zailiucard` → 在留カード，`chocolateokainiikou` → チョコレートを買いに行こう（预期分段 chocolate | o | kai | ni | ikou）。
- 多路径解析：Trie/FST 词典索引、输入规则转导、多路径分词、候选网格、束搜索、N-gram 语言模型、用户词频与上下文重排序。
- 增量解析与两阶段候选（§5.5）。

### 9.3 中文拼音 → 日文（草案 §5.2）

- 常见日语汉字词、汉字姓名、日本地名、车站名称、专业术语、用户自定义映射；候选显示日文读音；简体、繁体、新字体的字形归一（§5.9）。

### 9.4 英文 → 日文（草案 §5.3）

- 区分常见外来语、语义对应的汉字词与音译候选；优先采用经过校验的映射（§5.9）。

### 9.5 局部修改与撤销（草案 §3.4）

- 按 §5.6 实现：片段选择、锁定、重算、撤销、恢复原始输入、保留英文原文、部分提交。

### 9.6 模糊音（草案 §4.2、§5.5）

- 中文声母 z/zh、c/ch、s/sh、n/l、f/h、l/r；韵母 an/ang、en/eng、in/ing、ian/iang、uan/uang；每条规则独立开关。拼音缺字、多字纠错，邻近按键误触纠错，音节边界纠错，用户自定义模糊音。精确匹配优先于模糊匹配。
- 日语罗马字的等价写法（不加惩罚）：

| 输入 | 结果 |
| --- | --- |
| si / shi | し |
| ti / chi | ち |
| tu / tsu | つ |
| hu / fu | ふ |
| zi / ji | じ |
| sya / sha | しゃ |
| tya / cha | ちゃ |

- 另外支持 n/nn 等鼻音规则、长音输入变体、促音（重复辅音）和小假名。容错（加惩罚）：片假名长音容错、常见拼写错误、邻近按键误触。等价写法与真正的错误容忍分开处理，防止过度模糊匹配。

### 9.7 四层纠错（草案 §6.3）

1. 按键纠错：基于键盘几何与按下坐标识别相邻键误触。
2. 拼写纠错：常见英文、中文拼音和日语罗马字错误。
3. 模糊音纠错：使用用户启用的中日模糊音规则。
4. 上下文纠错：根据句子内容调整候选。

纠错以提供候选为主；自动替换可以关闭，替换后立即按退格即可撤销。

### 9.8 排序与用户控制（草案 §6.2）

- 排序综合：精确匹配、源语言识别、常用词频、整句上下文、语法合理程度、用户个人词频、当前 App 的使用习惯、自定义词库权重、模糊音惩罚、跨语言转换优先级。
- 用户可以：固定首选候选、提高或降低词语优先级、删除错误的学习记录、屏蔽不需要的候选、查看候选的读音与来源。

### 9.9 用户学习（草案 §6.4）

- 个人词频、短语学习、自动造词、下一词预测、按 App 分语言学习、候选降权、自定义词优先、清除学习记录、无痕模式。平滑与衰减见 §5.3。

### 9.10 跨语言转换开关（草案 §5.4）

以下默认值适用于日文模式；混合模式单独配置：

| 功能 | 默认 |
| --- | --- |
| 罗马字 → 平假名 | 开启 |
| 罗马字 → 汉字 | 开启 |
| 罗马字 → 片假名 | 开启 |
| 中文拼音 → 日语汉字 | 开启 |
| 中文拼音 → 日语片假名 | 开启 |
| 英文 → 日语汉字 | 开启 |
| 英文 → 日语片假名 | 开启 |
| 跨语言整句混合识别 | 开启 |
| 候选词注音 | 开启 |
| 智能整句翻译 | 关闭 |

### G2 验收

- §13.2 的用例全部达到规定名次；T05–T10 通过；单语语料（T01–T03）没有回退。
- 首批候选在参考设备上满足 §13.4；长句（30 个字母以上）的第二阶段不阻塞输入。
- 局部修改不改写已确认的片段；所有跨语言开关生效。

## 10. P3 — 高级输入体验（草案 Phase 3）

### 10.1 日语变形与敬语（草案 §5.6）

- 长按日文候选显示变形面板：原形、ます形、て形、た形、ない形、可能形、被动形、使役形、意向形、命令形、尊敬语、谦让语、礼貌程度调整（例：行く → 行きます、行って、行った、行かない、行ける、参ります）。
- 交互参考 Madamada 的上下、左右滑动，按 Android 的触控习惯重新设计（设计稿）。
- 变形由 Mozc 词典的词性信息加活用规则表生成；规则表对每个活用型都有测试（T16）。

### 10.2 候选注音与日语辅助学习（草案 §5.7）

- 汉字假名注音、候选读音预览、动词词典形、词性、常见中文释义、自动生词本、词汇收藏、JLPT 词汇分类。
- 可选功能，不影响普通输入速度。中文释义与 JLPT 分类的数据来源见 §3.3；没有许可明确的数据时延后。

### 10.3 地区用词（草案 §4.3）

- 台湾、香港的地区词库参与候选生成，而不只是在输出后替换。

### 10.4 拼音扩展与特殊输入（草案 §4.1、§4.4）

- 简拼、超简拼；双拼：微软、搜狗、小鹤、自然码与自定义方案；生僻字输入。
- 参考微软拼音的 U/V 模式，为 Android 设计快捷输入：拆字与笔画、数字转中文、金额大写、日期与时间格式、Unicode 字符、数学表达式、常用特殊字符、自定义文本替换规则。

### 10.5 词库系统（草案 §7）

- 类型：

| 类型 | 内容 |
| --- | --- |
| 内置词库 | 中英日基础词汇 |
| 专业词库 | 科学、工程、医学、计算机等 |
| 在线词库 | 可下载的分类词库 |
| 自定义词库 | 用户手动创建和导入 |
| 学习词库 | 根据输入习惯自动生成 |
| 跨语言词库 | 中文、英文到日语的映射 |
| Emoji 词库 | 词汇与 Emoji 的对应关系 |

- 管理：启用与禁用、下载、安装与卸载、版本管理、自动与手动更新、词频调整、词条编辑、导入导出、去重与冲突检测、按语言、地区和领域分类、版本回滚。
- 推荐分类：中文（常用词、网络用语、科研与工程、计算机、数学物理、专有名词）；日语（常用日语、汉字读音、人名、日本车站、地名、外来语、JLPT、留学生活、动词及形容词变形、敬语）；英语（常用词、学术英语、技术术语、常用缩写、编程词汇）。
- 编辑器与导入：JSON、CSV、TSV、Rime 兼容词表、自定义词库包。导入时执行数据结构校验、重复词条检测、读音检测、编码检查、权重归一化、冲突处理、预览与确认（T17）。LLM 生成的词库默认低优先级；应用内提供附录 B 的提示词。
- 在线词库：下载前说明来源；签名与哈希校验；可回滚。

### 10.6 Emoji（草案 §8.1、§8.2）

- 语义联想：中文、英文、日语以及拼音、罗马字都能触发 Emoji 候选（apple/pingguo → 🍎，neko/mao/cat → 🐱，sakura/yinghua → 🌸，birthday/shengri → 🎂，coffee/kafei → ☕，computer/diannao → 💻）；与文字候选共享候选系统，使用独立权重，默认排在文字候选之后。
- 面板：收藏、Emoji 组合、颜文字、自定义快捷输入、词语与 Emoji 的自定义映射。

### 10.7 持久化剪贴板（草案 §12）

- 文本历史、搜索、固定、收藏、分类、编辑、批量删除、自动过期、最大记录数、本地加密、备份与恢复、快捷短语；按 App 控制是否记录；密码与敏感内容默认不保存。
- 不依赖系统剪贴板永久保存历史；处理应用未运行、剪贴板被系统清除和切换输入法等情况。平台事实（§3.2）：默认输入法可以读取剪贴板，且不会触发读取提示；Android 13 起系统最迟 60 分钟后清除剪贴板，历史必须由 Kotomo 自己保存；标记为敏感（`EXTRA_IS_SENSITIVE`）的内容不记录、不预览。

### 10.8 智能标点、联想与输入辅助（草案 §14.1–§14.3）

- 标点：中文、英文、日语标点，全角与半角，成对括号，智能引号，常用数学符号，科研特殊符号，日期与时间快捷输入。
- 联想：下一词预测、常用短语补全、中文成语、英文固定搭配、日语助词与常用表达、专业术语、个性化预测。
- 辅助：常用短语快捷输入、文本替换、自定义快捷码、拼写建议、光标控制、文本选择辅助、撤销和重做。

### 10.9 布局（草案 §9.1、§9.2）

- 中文九宫格、日语 12 键 Flick 输入、横屏分离式键盘、悬浮键盘；在 §8.10 的基础上，为平板与折叠屏展开状态提供分离布局等大屏优化。
- 自定义：键盘高度、按键宽度与间距、圆角、字体大小、数字行、工具栏、空格键大小、功能键位置、长按符号映射。

### 10.10 工具栏、代码模式与按 App 设置（草案 §9.4、§10）

- 工具栏可放：语言切换、剪贴板、Emoji、翻译、AI、光标移动、文本编辑、设置、词库、主题；可调整顺序、可隐藏。
- 代码输入模式：不自动替换引号、不强制添加空格、默认保留英文原文、优先显示编程符号、快捷括号与运算符。
- 按 App 的个性化设置。
- （本计划补充）Android 11 起的内联自动填充建议（密码管理器等）显示在候选栏中。

### G3 验收

- §10 各项在真机上通过（T11、T14、T16、T17）；词库导入能校验、能回滚；剪贴板遵守 §14。

## 11. P4 — AI 与个性化（草案 Phase 4）

### 11.1 Gemini Nano（草案 §13.1、§13.3）

- 通过官方支持的 ML Kit GenAI（经 AICore）：校对、拼写修正、语法修正、翻译、文本重写、润色、扩写、缩写、日语敬语转换、语气调整、自定义提示词。AI 功能独立于核心输入引擎。
- 语言与接口（用户 2026-11-04 决定）：英文和日文直接使用校对与重写的专用 API（输入须少于 256 token，超出时分段处理）；**中文不提供校对与重写**，选中中文文本时不显示这些按钮；不使用 Prompt API，也不使用本地模型。翻译只用 ML Kit 设备端翻译（中日互译经英语中转，质量在 P4 评估）。草案功能与重写风格的对应：润色 → REPHRASE 或 PROFESSIONAL，扩写 → ELABORATE，缩写 → SHORTEN，语气调整 → FRIENDLY 或 PROFESSIONAL，日语敬语转换 → PROFESSIONAL。草案的"自定义提示词"需要 Prompt API，因此暂不提供（§2.5）。
- 运行位置：前台限制只针对 GenAI（Gemini Nano），ML Kit 设备端翻译直接在键盘中运行。GenAI 只允许位于最前台的应用推理，已有报告显示从输入法服务调用会被拒绝（§3.2），所以校对、重写等功能的默认设计是：用户在键盘上点 AI 后，打开 Kotomo 的前台界面（底部弹出或半透明的 Activity）完成推理；回到原输入框后，只有原文与选区都没有变化时才替换，否则把结果复制到剪贴板并说明原因。S7 的结果可以改变这一设计。
- 移植 MyApps-AI 的 `GenAiChannel.kt`：模型变体探测、错误映射（`BACKGROUND_USE_BLOCKED` → 后台、配额、过长等）、R8 保留规则；沿用 MyApps-AI 的规则：默认关闭；开关就是闸门（关闭时不调用任何 AI 接口，连状态也不查）；每次请求前重新检查状态；不代替用户下载模型；结果标注生成位置。
- 兼容：设备支持、Android 版本、AICore 状态、模型下载状态、语言限制、输入长度限制、模型版本差异。不支持的设备上隐藏 AI 入口或说明原因，所有输入功能照常可用。

### 11.2 AI 操作界面（草案 §13.2）

- 选中文本后可以点击：校对 / 翻译 / 重写 / 润色。
- 结果预览、原文对比、一键替换、复制、撤销、多候选选择、自定义风格；不经确认绝不替换文本。

### 11.3 在线 AI（可选）

- 默认关闭；只有用户主动开启、并接受写明服务提供方的说明后才使用；从不作为设备端 AI 的后备。密钥只存于本机（Android Keystore 加密），不进入备份与同步。

### 11.4 主题编辑器（草案 §11）

- 内置主题、自定义主题、在线主题、导入导出；浅色与深色、Material You 动态配色、背景图片与透明度、按键颜色、功能键颜色、字体颜色、候选栏颜色、按键按下动画；内置主题可以按城市、地区等命名分类；不同语言可以使用不同主题。
- 可视化编辑与实时预览：普通键盘、中文候选、日语候选、英文键盘、Emoji 面板、横屏布局。主题以 JSON 存储，便于导入和分享。

### 11.5 Emoji 样式包（草案 §8.3、§8.4）

- 系统 Emoji、Noto Emoji、Twemoji、用户自定义素材包（导入时提示用户须遵守素材授权）；原创"Apple 风格"主题视美术来源而定（§2.5），绝不打包未经授权的 Apple Emoji 图像。
- 界面明确区分"键盘中的显示样式"与"目标 App 的最终显示"；Emoji 素材包与键盘配色主题相互独立。

### 11.6 备份（草案 §15.2）

- 手动备份、定时自动备份、自定义备份文件夹、压缩备份包、增量备份、加密备份、备份版本管理、恢复预览、分模块恢复。

### 11.7 同步（草案 §15.3）

- WebDAV、用户指定的文件夹；默认关闭；可以只同步词库、主题或设置，剪贴板和历史数据默认不同步。
- 沿用 MyApps-DATA 的不变量，用 Kotlin 重写：UTC 时间戳、保留未知字段、冲突绝不静默自动解决、恢复前先关闭自动同步、锁与重试的语义、只连接用户确认过的服务器。

### G4 验收

- 支持的设备上 AI 功能可用，满足 §14 与 T18；不支持的设备上体验无损。
- 主题编辑与 Emoji 样式包可用；备份、恢复与同步的往返正确（T19）。

## 12. P5 — 扩展与优化（草案 Phase 5）→ 1.0

- 中英日语音输入、中文与日语手写（含 Android 14 起由输入法实现的手写笔"在任意输入框书写"，§3.2）、外接物理键盘、更多输入方案与语言、英文滑动输入（开源手势解码器可用之后）。语音输入若使用 ML Kit 的设备端语音识别，很可能同样受前台限制（推断，§3.2），需先验证。
- 性能优化、主流厂商系统上的兼容性测试、平板与折叠屏的进一步优化、在线词库社区与主题扩展。

### G5 验收

- §18 的最终完成清单全部满足。

## 13. 测试资产与结果矩阵（草案 §19）

### 13.1 测试编号

| ID | 测试 | 验证重点 | 阶段 |
| --- | --- | --- | --- |
| T01 | 中文单语语料 | 全拼、整句、同音词、简拼 | P1 |
| T02 | 英文单语语料 | 预测、纠错、大小写 | P1 |
| T03 | 日文单语语料 | 罗马字、整句、文节 | P1 |
| T04 | 混合词级语料 | §2.3 的示例、原语言优先、`ai` | P1 |
| T05 | 拼音 → 日文 | §5.9 的样本与反例 | P2 |
| T06 | 英文 → 日文 | 外来语、语义词、音译的排序 | P2 |
| T07 | 整句混合 | §13.2 | P2 |
| T08 | 模糊音与纠错 | 声母、韵母、罗马字变体、长音与促音、误触、连续输入中的拼写错误 | P2 |
| T09 | 局部修改 | 锁定、重算、撤销、恢复原始输入 | P2 |
| T10 | 排序与学习 | 原语言优先、词频、词库优先级、Emoji、歧义、语言偏好、衰减 | P2 |
| T11 | 简繁与地区用词 | 网络/網路、软件/軟體 等 | P1、P3 |
| T12 | 隐私 | 敏感输入框、无学习标记、无痕模式、日志不含输入内容、敏感剪贴 | P1 |
| T13 | 输入框适配 | §8.7 的各类输入框 | P1 |
| T14 | 交互与适配 | 竖屏、横屏、分离式、单手、平板、折叠与展开切换、半折叠、分屏、输入框切换、长按、剪贴板、主题、Emoji 包、备份与恢复 | P1–P4 |
| T15 | 性能 | 快速连续打字、长句、多词库、低端设备内存、冷启动、候选刷新、频繁切换 App、后台恢复、长时间稳定 | P0–P5 |
| T16 | 日语变形与敬语 | 每个活用型 | P3 |
| T17 | 词库导入 | 校验、冲突、回滚、LLM 词库低优先级 | P3 |
| T18 | AI | 前台限制、不支持的设备、预览与确认、敏感输入框禁用 | P4 |
| T19 | 备份与同步 | 往返、冲突、恢复前关闭自动同步 | P4 |

### 13.2 整句与跨语言验收用例（草案 §19.1）

转换允许合法的备选候选，不要求每个场景只有唯一输出；名次要求如下：

| 用例 | 模式 | 输入 | 期望 | 名次 |
| --- | --- | --- | --- | --- |
| T01-001 | 中文 | `nihao` | 你好 | 首选 |
| T01-002 | 中文 | `jin tian tian qi hen hao` | 今天天气很好 | 首选 |
| T03-001 | 日文 | `watashiwanihonjin` | 私は日本人 | 首选 |
| T05-001 | 混合 | `mianjie` | 面接 | 前 3 |
| T05-002 | 混合 | `chidai` | 池袋（读音いけぶくろ） | 前 3 |
| T05-003 | 混合 | `dongjing` | 東京（在中文"东京"之后） | 前 3 |
| T06-001 | 混合 | `computer` | コンピューター | 前 3 |
| T06-002 | 混合 | `coffee` | コーヒー | 前 3 |
| T07-001 | 混合 | `chidai ni kai ni ikou` | 池袋に買いに行こう | 首个整句候选 |
| T07-002 | 混合 | `chidai nikainiikou` | 池袋に買いに行こう | 前 3（草案 §3.4、§19.1 写作 `chida`，为笔误） |
| T07-003 | 混合 | `chidainikainiikou` | 池袋に買いに行こう | 前 3 |
| T07-004 | 混合 | `zailiucard` | 在留カード | 前 3 |
| T07-005 | 混合 | `chocolateokainiikou` | チョコレートを買いに行こう | 前 3 |

每个用例都配有反例，例如 `nihao` 在混合模式中不以日文结果为首选，`coffee` 不以拼音拆分为首选。

### 13.3 语料格式

- 一个用例一行（TSV）：`id`、`mode`、`context`（光标前文本，可为空）、`input`、`expected`（多个可接受结果用 `|` 分隔）、`rank`（`1`、`3`、`5`）、`notes`；以 `!` 开头的期望表示"不得出现在前 N 名"。
- 主机端与真机端使用同一份语料；运行器输出每一行的名次，并与上一次运行比较，按行报告回退。

### 13.4 性能目标与测量（草案 §17.1）

| 指标 | 目标 | 测量 |
| --- | --- | --- |
| 按键响应 | 没有可感知的延迟：按键预览在下一帧绘制 | 真机帧时间与 trace |
| 首批候选 | 中端参考设备上 p95 ≤ 50 ms | 自定义 trace 区段（按键 → 候选绘制） |
| 混合候选 | 异步并行生成，不阻塞首批候选 | 同上 |
| 整句转换 | 增量解析，避免重复计算 | 长句用例的第二阶段耗时 |
| Emoji 联想 | 本地索引与缓存 | 首批候选耗时中的占比 |
| 词库更新 | 后台执行 | 更新期间的按键延迟 |
| AI 推理 | 不阻塞键盘 | 推理期间的按键延迟 |
| 离线输入 | 完整可用 | 飞行模式下运行全部单语与混合语料 |

此外在 P0 建立基线、在后续阶段持续记录：输入法冷启动到首帧、空闲与打字时的内存（PSS，至少在一台 Android 17 设备上测量，因为它按设备内存设定应用的内存上限）、每个 ABI 的 APK 体积、librime 部署时间、Mozc 数据加载时间。除首批候选外的数值目标在基线之后以 ADR 设定，不预先编造。

参考设备：一台中端设备（P0 选定并记录型号）、一台低端设备（≤ 4 GB 内存）、一台支持 Gemini Nano 的设备、一台折叠屏、一台平板（模拟器可以补充尺寸覆盖，但折叠切换以真机为准）；模拟器只用于功能验证。

### 13.5 验证方法

- 主机端：`core/` 单元测试与全部转换语料，在每次改动 `core/`、`lexicon/`、`third_party/` 或 `patches/` 时于 CI 运行。
- 真机端：插桩测试向测试 App 输入；Macrobenchmark 测延迟；30 分钟连续输入脚本、100 次键盘显示与隐藏、频繁切换 App 检查稳定性与内存增长。
- 截图用于界面验证，不能代替行为断言；模拟器结果不作为延迟、内存与 AI 的证据。

### 13.6 每次验收报告模板

```text
Build / commit:
Upstreams (librime / Mozc / OpenCC / LatinIME commits), lexicon versions:
Device / Android version / RAM / build type:
Test ID / corpus revision:
Command:
Expected result:
Actual result:
Status: PASS | FAIL | BLOCKED | NOT_RUN
Evidence:
Known limitations:
Next blocking issue:
```

## 14. 隐私与安全（草案 §17.2）

- 默认不上传输入内容，不上传用户词库；没有分析与广告 SDK；崩溃信息中不含输入内容。
- 敏感输入：密码类输入（`TYPE_TEXT_VARIATION_PASSWORD`、`TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`、`TYPE_TEXT_VARIATION_WEB_PASSWORD`、`TYPE_NUMBER_VARIATION_PASSWORD`）、带 `IME_FLAG_NO_PERSONALIZED_LEARNING` 的输入框（平台只把它当作请求，Kotomo 当作硬约束）、标记为敏感的剪贴内容与无痕模式下，不学习、不记录历史、不记录剪贴板、不调用 AI、不显示个性化预测；librime 与 Mozc 自身的学习同样关闭（S2、S3 确认方式）。
- 本地数据（用户词库、学习记录、剪贴板）加密存储，密钥在 Android Keystore 中；用户可以清除全部学习数据。
- 日志不记录输入内容、候选、剪贴板与提示词，只记录长度与耗时；release 构建去掉调试日志。
- 网络：P1–P2 不声明 `INTERNET` 权限（本计划补充）；第一个需要网络的功能（P3 在线词库或 P4 模型下载）加入时由用户确认，并写入隐私政策。在线 AI 与同步必须显式开启；在线词库验证完整性与来源。
- 除 ML Kit 外不引入会联网或收集数据的 SDK；ML Kit 的数据处理条款在 S8 核实并写入隐私政策。
- 备份可加密；同步默认不包含剪贴板与历史数据。

## 15. 许可证、致谢与第三方清单（草案 §21、§8.4）

### 15.1 许可证策略

- Kotomo 默认 GPL-3.0（§2.5）。与之兼容、可以组合的上游许可证包括：BSD-3-Clause（librime、Mozc）、Apache-2.0（OpenCC、AOSP LatinIME）、LGPL-2.1-or-later（fcitx5-android）、GPL-3.0（Trime、HeliBoard、rime-ice）、Unicode License v3（Unihan、CLDR）、CC BY 4.0，以及单向兼容 GPLv3 的 CC BY-SA 4.0（JMdict 等，依据 FSF 的许可证列表）。打包 GPL-3.0-only 的数据（如 rime-ice）会使整个发行版实际上只能以 GPL-3.0 发布。
- 不采用：许可证不明或不允许再分发的代码与数据（包括无许可声明的抓取词库）、未经授权的 Apple Emoji 图像、商业输入法的词库与素材。用户可以导入自己提供的词库文件，但应用不分发来源不明的词库。
- 每个随应用分发的组件都在 `doc/en-us/third-party.md`（版本、来源、许可证、修改、义务）、`THIRD_PARTY_NOTICES.md` 和应用内的许可证页面中各有一条；生成的词库保留其来源数据的许可证与署名。
- JMdict 等 EDRDG 数据：在"关于与致谢"中、从菜单进入的独立来源页上署名，并链接其许可与文档（只在启动页提及不够）；按上游版本规则定期更新数据（§3.3）。

### 15.2 设计参考与致谢（草案 §21.1–§21.3）

- **特别致谢：玛嗒玛嗒输入法（Madamada）及其开发者。** 它为中文用户学习和输入日语所做的设计，是 Kotomo 日语输入部分的重要参考，特别包括：中文拼音直接输入日语汉字、英文直接输入日语片假名、中文拼音、英文与日语罗马字混合连打、整句连续输入与转换、候选词读音标注、日语动词变形、敬语切换、分类日语词库、自动生词本、城市主题与个性化键盘。Kotomo 明确说明这些功能设计受 Madamada 启发；借鉴交互理念不代表可以使用其代码、词库、图像和其他受保护的资源。官方名称：玛嗒玛嗒（繁体 瑪嗒瑪嗒，日文 マダマダ，英文 Madamada），官网 <https://madamada.catchingnow.com/>；目前是 iOS/iPadOS 应用（也兼容 Mac 与 visionOS），没有 Android 版本，也没有开源（2026-11-04 核实）。
- 其他参考输入法：[Gboard](https://play.google.com/store/apps/details?id=com.google.android.inputmethod.latin)（键盘布局、触控操作、候选栏、多语言、Emoji、滑动输入、输入框适配）；[微软拼音](https://support.microsoft.com/windows/microsoft-simplified-chinese-ime-9b962a3b-2fa4-4f37-811c-b1886320dd72)（模糊音、长句转换、词频学习、双拼、U/V 模式、拼音纠错）；[搜狗输入法](https://shurufa.sogou.com/)（智能联想、长句输入、按键纠错、词库管理、用户造词、候选操作、键盘皮肤）；[Microsoft SwiftKey](https://www.microsoft.com/swiftkey)（个性化预测、英文纠错、多语言预测、智能空格、剪贴板、输入手势）；[Google 日本語入力](https://www.google.co.jp/ime/) / [Mozc](https://github.com/google/mozc)（罗马字转换、假名汉字转换、日语长句输入、文节转换、日语词典与预测）。
- 开源项目：实际使用时按许可证致谢并保留版权声明，重点为 Rime/librime、Mozc、Fcitx5 for Android、OpenCC、Noto Emoji、Twemoji，以及实际使用的其他词库与工具。Telegram 只作为 Emoji 独立渲染架构的参考，不据此推定 Apple Emoji 素材可以使用。
- 措辞：不得暗示参考项目或其开发者对 Kotomo 进行了官方认可、合作或授权。只参考交互设计的项目使用设计致谢；实际使用的代码与素材另行提供许可证与版权声明。

### 15.3 致谢的落地（草案 §21.4）

1. README 中的 Acknowledgements 章节（已建立）。
2. 应用设置中的"关于与致谢"页面。
3. 开源许可证页面。
4. 第三方依赖及其版权信息。
5. 第三方词库的来源说明。
6. Emoji 素材的来源与授权说明。
7. 参考输入法的官方网站或项目链接。

## 16. 风险、决策与停止条件

| 风险 | 优先级 | 缩小风险的行动 | 触发后的处理 |
| --- | --- | --- | --- |
| 跨语言整句与 Mozc、librime 的衔接做不到（固定片段、调用深度） | 最高 | S3、S6 提前验证；§5.4 的退路 | 按退路实现并写 ADR；不降低 §13.2 的要求，只调整手段 |
| 混合模式的语言歧义（`change`、`hang`、`kai` 等） | 最高 | 语料中大量歧义用例；频率校准；切换代价 | 收紧"积极"档；默认档更保守 |
| 中低端设备上首批候选达不到 50 ms | 高 | S1–S4 基线；两阶段候选；增量解析 | 缩小第一阶段的内容，绝不阻塞主线程 |
| APK 体积与内存（librime + Mozc + LatinIME + 词库；Android 17 按设备内存限制应用内存） | 高 | P0 测量；词库可按需下载 | 拆分可下载的数据包；ADR |
| Mozc 的 Bazel 构建难以融入 Gradle 与 CI，且不支持 Windows 主机 | 高 | S3 先做独立脚本与缓存 | 预构建产物加锁定哈希 |
| ML Kit GenAI 在输入法中被前台限制拒绝（已有开发者报告） | 高 | 默认在 Kotomo 的前台界面中推理；S7 复核 | 前台界面也不可行时，AI 只在设置应用中提供；不以在线 AI 代替 |
| 词库与数据的许可证不清 | 高 | S8 | 不采用；寻找替代或自建 |
| 日中释义、JLPT 分类、车站名等数据缺口 | 中 | S8 列出缺口 | 功能延后或由用户导入 |
| 跨语言词库的错误映射（同形异义、日本国字） | 中 | 样本复核与反例 | 降权或移除；用户可以屏蔽 |
| 厂商系统上的输入法兼容问题 | 中 | P5 兼容性测试 | 兼容模式 |
| Android 剪贴板的限制与自动清除 | 中 | §3.2 | 使用自有历史，并在界面中说明 |
| 没有成熟的开源滑动输入解码器 | 低 | 关注 §3.1 的开源项目 | 后置到 P5 |

### 必须停止该依赖链的情形

- 为让语料通过而对单条用例硬编码，或以其他用例的回退为代价。
- 首批候选需要在主线程上做阻塞工作才能达标。
- 某功能需要在敏感输入框中学习、记录或调用 AI 才能实现。
- 需要引入许可证不明的代码或数据才能继续。
- 缺少真机证据却准备宣布关卡通过。

停止的是受影响的依赖链，不是整个项目。继续可以独立完成的工作，并写出最小的阻塞报告。

## 17. 构建与命令接口契约

下面是**待实现的目标接口**，P0 起逐步实现。名称可以按仓库风格调整，但文档、脚本与 CI 必须一致。

```bash
# 文档镜像与链接检查（已存在）
java tool/CheckDocs.java

# Android 构建与检查
./gradlew assembleDebug
./gradlew check              # 单元测试与 lint
./gradlew connectedCheck     # 真机或模拟器上的插桩测试

# 上游与词库
scripts/build-mozc.sh --abi arm64-v8a   # 在 Linux/macOS 主机或 CI 上运行
scripts/build-lexicon.sh                # 源数据 → 运行时词库，记录来源与哈希

# 主机端引擎测试与转换语料
cmake -S core -B build/host && cmake --build build/host
ctest --test-dir build/host
build/host/kotomo-corpus tests/conversion/   # 输出每行的名次，与上一次运行比较
```

脚本要求：

- 显式检查每个外部程序的返回码，失败时以非零状态退出；路径以参数传递，不拼接不受控的 shell 字符串。
- 清理只针对已确认的构建目录，绝不用宽泛的通配符递归删除仓库或用户数据。
- 下载固定版本并校验哈希；依赖已经就绪时支持离线构建；不在系统范围安装任何东西。

## 18. 提交粒度与最终交付清单

### 建议提交顺序（P0）

1. `build: Gradle project, toolchain lock and CI`
2. `ime: input method service skeleton and latency probe (S1)`
3. `rime: build librime for Android and bridge it (S2)`
4. `mozc: build Mozc and the converter glue (S3)`
5. `english: LatinIME native suggestions and dictionary (S4)`
6. `lexicon: cross-language lexicon prototype (S5)`
7. `core: mixed lattice prototype on the host (S6)`
8. `ai: Gemini Nano from the input method (S7)`
9. `docs: P0 results, license audit and the engine contract (S8)`

每个提交附上对应的测试结果；允许为修复和实验拆成更小的提交。不在同一个提交中混入上游移动、整体重构与新行为。

### 最终完成清单（草案 §22）

- [ ] 中文、英文、日语均可正常离线输入。
- [ ] 提供中文、英文、日文、混合四种输入模式。
- [ ] 混合模式可自动识别三语言输入。
- [ ] 能够识别连续输入串中的不同语言片段。
- [ ] 能够进行完整的整句混合转换。
- [ ] 支持中文拼音直接输入日语汉字。
- [ ] 支持英文直接输入日语片假名及常见汉字词。
- [ ] 支持跨语言转换独立开关。
- [ ] 支持中日模糊音。
- [ ] 支持智能纠错和候选词频学习。
- [ ] 支持局部修改和重新转换。
- [ ] 支持日语动词变形和敬语转换。
- [ ] 支持简繁及地区词汇转换。
- [ ] 支持多种词库来源及自定义词库。
- [ ] 支持 Emoji 语义联想。
- [ ] 支持 Emoji 显示主题。
- [ ] 支持 Gboard 风格键盘交互。
- [ ] 支持竖屏、横屏及分离式布局。
- [ ] 支持平板、折叠屏与单手模式（用户 2026-11-04 加入）。
- [ ] 支持高度自定义的键盘主题。
- [ ] 支持持久化剪贴板。
- [ ] 支持智能输入框适配。
- [ ] 支持 Gemini Nano AI 增强。
- [ ] 支持本地备份、恢复及可选同步。
- [ ] 不支持 AI 的设备仍可完整使用核心输入功能。
- [ ] 所有实际使用的第三方代码和素材均完成许可证检查。
- [ ] 项目 README 和 App 中包含完整的参考项目致谢。

只完成部分关卡时，按层级交付可用成果和阻塞报告，不勾选整项。

## 19. 首次工作会话应立即完成的事情

1. 阅读 `AGENTS.md` 与本计划，记录工作树状态与已配置的远程仓库。
2. 选定并锁定工具链，创建 Gradle 工程与 CI。
3. S1：输入法骨架与延迟基线（真机）。
4. S2、S3、S4：librime、Mozc 与英文引擎的最小闭环（先在主机上打通，再上真机）。
5. 建立转换语料文件与运行器，先放入 §13.2 的用例。
6. 汇报实际改动、执行的命令、各项结果与下一项具体阻塞；随后按依赖图继续。

**第一轮优先交付两个独立的证据：librime 与 Mozc 确实能在 Android 真机上经 JNI 工作；混合整句原型确实能在主机上对 §13.2 的用例给出可检验的结果。** 其余功能都建立在这两项证据之上。

## 附录 A：草案章节对应

| 草案 | 本计划 |
| --- | --- |
| 一 项目概述 | 文首、§1、§2.1 |
| 二 输入模式 | §2.3、§8.2–§8.3 |
| 三 整句级智能混合输入 | §5、§9.1–§9.5、§13.2 |
| 四 中文输入系统 | §8.2、§8.4、§9.6、§10.3–§10.4 |
| 五 日语输入系统 | §8.2、§9.3–§9.4、§9.6、§9.10、§10.1–§10.2 |
| 六 统一候选与智能纠错 | §4.4、§9.7–§9.9 |
| 七 词库系统 | §10.5 |
| 八 Emoji 系统 | §8.6、§10.6、§11.5、§15 |
| 九 键盘布局与交互 | §8.1、§8.10、§10.9–§10.10 |
| 十 智能输入框适配 | §8.7、§10.10 |
| 十一 键盘主题系统 | §8.6、§11.4 |
| 十二 持久化剪贴板 | §10.7 |
| 十三 Gemini Nano AI 功能 | §11.1–§11.3 |
| 十四 其他输入效率优化 | §10.8、§12 |
| 十五 数据管理与同步 | §11.6–§11.7、§14 |
| 十六 技术架构 | §4 |
| 十七 性能与隐私 | §13.4、§14 |
| 十八 开发阶段 | §6–§12 |
| 十九 测试与验收 | §13 |
| 二十 LLM 词库生成提示词 | §10.5、附录 B |
| 二十一 参考输入法与致谢 | §15 |
| 二十二 项目最终验收标准 | §18 |
| 二十三 开发 Agent 执行要求 | §1、`AGENTS.md` |

## 附录 B：LLM 词库生成提示词（草案 §20）

应用内提供下面的可复制提示词，帮助用户用 LLM 制作词库。P3 定义导入格式（JSON Schema）后，字段名以 Schema 为准，并同步更新这段提示词。导入时仍必须校验，不直接信任模型生成的内容。

```text
你是一名专业的中英日三语言输入法词库维护专家。

请为 Android 中英日智能输入法生成高质量词库。

【参数】
领域：{领域名称}
语言：{中文 / 英文 / 日文 / 混合}
地区：{zh-CN / zh-TW / zh-HK / ja-JP / en-US}
词条数量：{数量}
词库类型：{普通 / 专业 / 跨语言 / Emoji / 混合}

【要求】

1. 使用真实、常见、有实际输入价值的词汇。
2. 中文提供正确的完整汉语拼音。
3. 日语提供平假名读音及罗马字。
4. 英语保留正确拼写和大小写。
5. 日语汉字词尽可能增加中文拼音输入映射。
6. 日语外来语尽可能增加英文输入映射。
7. 支持整句输入所需的短语和固定搭配。
8. 中文区分简体、台湾繁体和香港繁体地区用词。
9. 支持常见词汇对应 Emoji。
10. 为词条设置 1-100 的初始推荐权重。
11. 不得编造专业术语或读音。
12. 不得重复生成相同词条。
13. 对不能确认的词条直接跳过。
14. 仅输出合法 JSON，不添加解释。
15. 读音、语言代码和目标词必须一致。

【JSON 示例】

{
  "name": "Japanese Mixed Dictionary",
  "version": "1.0.0",
  "entries": [
    {
      "language": "ja",
      "text": "池袋",
      "reading": "いけぶくろ",
      "romaji": "ikebukuro",
      "pinyin": "chidai",
      "weight": 90,
      "tags": ["地名", "东京"]
    },
    {
      "language": "ja",
      "text": "コンピューター",
      "reading": "コンピューター",
      "romaji": "konpyuutaa",
      "english": "computer",
      "weight": 90,
      "tags": ["计算机", "外来语"]
    },
    {
      "language": "zh",
      "text": "苹果",
      "reading": "pingguo",
      "weight": 95,
      "emoji": ["🍎"],
      "tags": ["水果"]
    }
  ]
}

【额外要求】

- 日语词条的中文拼音对应其日语汉字写法或可信的中文词汇映射。
- 罗马字应符合一致的转写规则。
- 英文映射应基于实际词义。
- 同形异义词要保留不同语言信息。
- 不要把随机音译结果当作常用日语词汇。
- 固定短语应保留完整表达。
- 所有输出必须符合指定 JSON Schema。
```
