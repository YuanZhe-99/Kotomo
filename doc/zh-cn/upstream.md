# 上游契约

Kotomo 依赖上游的哪些行为，以及每一点核实到什么程度。上游跟随各自的最新发行版，所以上游一有变动就重新核实相应条目。
以下各点于 2026-11-04 在上游仓库、源码和文档中阅读所得，没有构建或运行任何东西；P0 逐项复核，并把"已阅读"替换为结果。

## librime

| 要点 | 影响 | 核实 |
|---|---|---|
| BSD-3-Clause；CMake 与 C++17；需要 Boost ≥ 1.74、LevelDB、marisa、OpenCC ≥ 1.0.2 和 yaml-cpp ≥ 0.5（glog 可选） | 作为子模块用 NDK 从源码构建 | 已阅读 |
| 嵌入接口 `rime_api.h`：会话、`process_key`、`set_input`、`get_context`（预编辑、光标、候选页）、`get_commit`、候选选择与迭代、`set_option`、`register_module` | 拼音片段可以经 `set_input` 与 `get_context` 转换；更深的接入走插件模块 | 已阅读 |
| luna_pinyin 的拼写运算已经应用 `pinyin:/spelling_correction` 与 `pinyin:/key_correction`（`hoa` → hao，`tain` → tian）；`translator/enable_correction` 在运行时加入 librime 的邻键搜索（把一个键读成 QWERTY 键盘上相邻的键，一个音节最多五个键；编辑距离部分及其 `.correction.bin` 在所固定的提交中处于 `#if 0`，不加载任何文件）。librime 按词频加固定惩罚给纠错读法排序，所以高频词可能排在精确读法之前（`nu` → 你 排在 怒 前），每次翻译最多列出四个纠错读法，并且只在翻译器内部标记它们 | 拼写失误由方案的规则处理。邻近键纠错是开关 `chinese.key_correction`（默认关）：应用的规则文件设置 `enable_correction`，桥接层逐音节对照拼写表重新找出纠错读法，并把它们列在精确读法之后；协调层的转换不包含它们 | 主机测试，语料 T01-012、T01-015、B2-001…N04；`rime_bridge_test` 的 B2 |
| 模糊音和双拼都是方案中的 `speller/algebra` 规则 | 修改模糊音开关会改写方案并重新部署，所以部署耗时很重要 | 已阅读 |
| 以 `?` 结尾的引用是可选的（`__include: kotomo_fuzzy:/patch?`）：文件不存在时该节点不变；配置文件先在用户目录、再在共享目录中查找；`speller/algebra/+` 追加到 luna_pinyin 由 `pinyin.yaml` 拼成的列表之后 | Kotomo 的方案补丁从用户目录引入应用生成的 `kotomo_fuzzy.yaml`；预编译数据（没有该文件）不变；不学习的方案通过同一个补丁得到同样的规则 | 主机测试（`FuzzyRimeTest`）、语料 T08 |
| `fuzz/…/…/` 派生出标为模糊的拼写，权重减半（`log 0.5`）；简拼规则只作用于它运行时已存在的拼写 | 模糊音规则使用 `fuzz`，并在其后再追加一次 luna_pinyin 的两条简拼规则；词频相近时精确拼写优先，但频率高得多的模糊匹配仍可能排在前面（开启 z/zh 时 `zi`：之 在 子 之前） | 语料 T08-020–T08-023 |
| `Projection::Apply` 把每条代数规则作用于前面各条产生的全部拼写，派生出的拼写带着源拼写已经代表的全部音节，所以共享一个字母的两条模糊音规则会连带（同时开启 n/l 与 l/r 时 `ri` 也代表 ni：`rihao` → 你好）。自动补丁插件给每一个被编译的资源打上它的 `.custom` 补丁，被包含的方案也一样，所以包含 `luna_pinyin_simp.schema` 又写明 `luna_pinyin_simp.custom:/patch` 的方案会得到两次补丁；`translator/prism` 相同的两个方案共用一个棱镜文件，由最后部署的那个生成 | 每条模糊音规则把派生的结果写成大写，全部规则之后由一条 `xlit` 改回小写，任何规则都不会作用于另一条的结果（决定 16：l 可代 n 与 r，n 与 r 互不相代）；不学习的方案不再写明这个补丁，它经由包含已经得到。Q6 用大写的尝试之所以失败，只是因为不学习的方案把规则应用了两次、写进共用的棱镜 | 主机探测棱镜；`FuzzyRimeTest`（两个方案）；语料 T08-N21、T08-N22、T08-025、T08-026 |
| Trime 用 Gradle 的 CMake 集成构建 librime（NDK 30.0.16248370）；fcitx5-android 链接独立 prebuilder 产出的静态库 | 两种构建方式都可用 | 已阅读 |
| 1.17.0 可用 NDK 30 与 CMake 3.22.1，按 Kotomo 自己的目标列表（`core/rime/CMakeLists.txt`）为 Android 构建；它对 Boost 的使用全部只需头文件，Boost 1.92.0 的头文件即可；没有 glog 时使用 `no_logging.h` | `libkotomo_rime.so`，每个 ABI 去符号后约 2.9 MB，16 KB 页面，只导出 JNI；输入内容不可能进入日志 | 已构建（arm64-v8a、x86_64），已在主机上测试 |
| 没有关闭学习的运行时开关；`translator/enable_user_dict: false` 使用户词典不被创建，因此既不读也不写 | 敏感输入框选用方案 `luna_pinyin_simp_private` | 主机测试 T12-S2 |
| 部署器在解析 `__include` 与 `__patch` 之前就从原始方案文件读取 `schema/schema_id`；只靠补丁设置 ID 的方案会被静默跳过 | 派生方案按字面写出 `schema:` 块 | 已在主机上测试 |
| `RimeDeployWorkspace` 同步部署，期间拒绝会话（主机：全新部署 5–15 s，未变化时约 0.2 s；峰值 RSS 294 MB） | librime 把 `<shared>/build` 当作预编译数据；会话可以在空的用户目录上直接打开，不编译任何东西（主机：30 ms） | 应用随附在 Linux 主机上编译好的数据（`scripts/prebuild-rime.sh`、CI），手机上只需解压 | 主机测试 |
| `RimeDeployService` 在独立进程（`:deploy`）中部署到暂存目录，键盘期间继续使用上一份构建的会话，完成后再换入新构建（PLAN §4.2）；会话可以在挪过来的构建上直接打开而无需部署（主机：30 ms），转换结果不变；只有第一次部署完成前，中文模式才直接输入字母，候选栏会给出提示 | 已在主机上测试；真机上 NOT_RUN |
| 会话接口（`set_input` 与候选遍历）在主机上每个前缀约 0.2 ms | 足以按片段调用（PLAN §5.4） | 仅主机 |
| 已部署方案的 prism（`build/<schema>.prism.bin`）是方案接受的全部拼写的双数组 trie；`Prism::CommonPrefixSearch` 列出某个串开头的拼写，`QuerySpelling` 给出每个拼写的类型（正常、模糊、简拼、补全）以及它是否由纠错规则派生；它可以只读映射，不需要会话 | 协调层从这张表取拼音音节（`rime_spellings.h`），所以模糊音与纠错规则同中文模式一样生效，Kotomo 自己不带音节表 | 主机测试（S6-spellings） |
| 音节之间用撇号连接（`ni'hao`）时，会话在整段的转换旁边也列出只覆盖前缀的词；类型为 `sentence` 的候选由更短的词拼成，其它类型是词典条目 | 协调层只保留每个音节一个字的候选，并让拼成的整段付出更高的代价，由此区分 `mian'jie` 与 `mi'an'jie` 这样的偶然切分 | 语料经协调层运行（S6） |
| 子模块不遵循仓库的 `.gitattributes`，而 CMake 的 `file(READ)`/`file(WRITE)` 在 Windows 上会转换换行符 | `core/rime/stage-data.cmake` 用 `configure_file(… NEWLINE_STYLE LF)` 写出共享数据，在各主机上逐字节相同 | 已在 Windows 与 Linux 上构建 |

## Mozc

| 要点 | 影响 | 核实 |
|---|---|---|
| Google 的代码为 BSD-3-Clause；词典数据带有自己的声明（IPAdic 适用 NAIST 与 ICOT 条款、公有领域的冲绳方言词典、Google 的增补条目） | 声明随数据一起分发 | 已阅读 |
| 只能用 Bazel（Bazelisk）构建；Android 构建（`docs/build_mozc_for_android.md`）只在 macOS 或 Linux 主机上产出 16 KB 对齐的 `libmozc.so` | Mozc 的 Android 库用 `scripts/build-mozc.sh` 在 x86_64 Linux 主机上构建 | 已阅读，已构建 |
| 自带的 JNI 注册在 Google 的类 `com.google.android.apps.inputmethod.libs.mozc.session.MozcJni` 上，只暴露 `evalCommand`（序列化的 `commands::Command`）、`onPostLoad` 和 `getDataVersion`；Android 客户端没有开源 | Kotomo 自写客户端和 C++ 胶水层 | 已阅读 |
| `Segment::SegmentType` 有 `FREE`、`FIXED_BOUNDARY`、`FIXED_VALUE`、`SUBMITTED` 与 `HISTORY`；`ImmutableConverter::Resegment` 保留 `FIXED_VALUE` 片段的值和 lid/rid，只用于转换请求 | 在转换器层的胶水代码中，可以把跨语言片段固定在日文整句转换里；预测候选无法携带固定片段 | 已阅读 |
| 只发布标签（最近检查时为 `3.35.6354`） | 子模块跟随最新标签 | 已阅读 |
| 3.35.6354（`0136c357`）没有 git 子模块；依赖通过 Bazel 模块（abseil-cpp 20260107.1、protobuf 34.1）与 `src/build_tools/update_deps.py`（Android NDK r29）获取 | `git submodule update --init third_party/mozc` 即可，其余由 Bazel 获取 | 已构建 |
| 主机构建可在 aarch64 Linux 上运行（Debian 13、gcc 14.2、经 Bazelisk 1.30.0 的 Bazel 9.0.2）；Bazel 的 `local_jdk` 需要 `JAVA_HOME` | 主机测试在 Windows on ARM64 的 WSL 中运行 | 已构建 |
| rules_android_ndk 获取 NDK 时会运行 NDK 的 clang，而 NDK 只有 x86_64 Linux 与 macOS 的主机工具；只要有 NDK，aarch64 Linux 上连主机构建也会失败 | Android 库在 x86_64 Linux 上构建；2026-11-10 的本地构建（Debian 13）逐字节复现了已删除的 CI 运行的哈希 | 已构建 |
| Bazel 在每个服务进程中只对 NDK 模块扩展（rules_android_ndk）求值一次：同一服务进程里先做主机构建、再获取 NDK 时，Android 构建找不到 C++ 工具链（"No matching toolchains found"） | `scripts/build-mozc.sh` 在第一条 Bazel 命令之前获取 NDK | 已构建 |
| `converter::Converter::ApplyConversion` 是公开接口；手工构造的含 `FIXED_VALUE` 片段的分段会保留固定的值，其余部分由 Mozc 分段并转换 | PLAN §5.4 的固定片段转换在转换器层可行，不需要退路：固定池袋 + `にかいにいこう` → 池袋に買いに行こう | 主机测试 |
| `FIXED_BOUNDARY` 片段经过 `ApplyConversion` 后仍是一段；预测（`StartPrediction`、`PREDICTION`）只有在移动端请求（`mixed_conversion`）下才补全读音，没有 composer 的平假名键还需要 `use_already_typing_corrected_key` | 片段的缩短与延长固定边界；`Predict` 两者都设置（ありがと → ありがとう，主机上 0.4 ms） | 主机测试 |
| `DictionaryInterface::LookupExact` 返回带 lid 与 rid 的词条（池袋/いけぶくろ：1924/1924）；拼音拼写没有词条 | 固定片段取同一词条的 ID；未知词使用普通名词 | 主机测试 |
| `//converter`、`//session:session_handler` 与 `//dictionary:dictionary_interface` 只对 Mozc 自身可见 | Kotomo 的胶水层用 `--nocheck_visibility` 构建，而不是打补丁 | 已构建 |
| 只有提交转换时才学习；`Config.incognito_mode` 与 `Request.is_incognito_mode` 关闭一切可变操作 | 胶水层从不提交，并在敏感输入框中同时设置两者 | 已阅读，主机测试 |
| 助词は的罗马字 `wa` 会转为わ：普通会话中 `watashiwanihonjin` → 私羽日本人 | 语料行 T03-001 需要协调层处理 | 主机测试 |
| 组合器的罗马字表能映射的就映射，其余字母保留为 ASCII（`card` → かrd）；`nn` 是 ん，单独的 `n` 只在辅音之前或末尾成为 ん，所以 `konnichiwa` 读作 こんいちわ | 协调层按这张表的最短拼写提出假名边，并让 n + ni 胜过 nn + i | 主机测试、语料经协调层运行（S6） |
| 自由片段的候选带有包含连接代价的代价（あい 5194、日本 4211、に 之后的 買いに行こう 8339）；`FIXED_VALUE` 片段报告的是一个很大的负数 | 协调层对自由片段累加 Mozc 的代价，对固定的词取词库中的词代价加上平均的连接份额 | 语料经协调层运行（S6） |
| Mozc 没有词可用的自由片段会把假名原样返回，代价为 0（平假名）和 1（片假名）：`ざぐら` → ざぐら 0、ザグラ 1；而有词典词的读音代价是几千（`でざぐら` 9208） | 经日语模糊音规则读出的句子里，只要有片段的代价不超过 1，协调层就丢弃这个读法，否则每个不是词的模糊读音都会排到第一；事先用 `DictionaryInterface::LookupExact` 查读音可以知道模糊读音是不是词典里的键（`MozcEngine::WordCost`）。按原样输入的读音仍保留这种回显（后续项：它在混合模式下会排到第一） | 主机服务，2026-11-13 |
| 在两个自由片段之间固定 は（`わたし`、は、`にほんじん`）转换为 私は日本人，总代价低于 わたしわにほんじん（私羽日本人） | 助词规则（wa → は、o → を、e → へ）就是一个固定片段，由 Mozc 的代价在两种读法之间取舍 | 语料行 T03-001 经协调层运行（S6） |

## OpenCC

| 要点 | 影响 | 核实 |
|---|---|---|
| Apache-2.0；提供简繁、台湾与香港（含词组，`s2twp`、`s2hk` 等）以及日文新字体（`t2jp`、`jp2t`，官方称"有限支持"）的配置 | 地区转换直接使用；其新字体表只是词库流水线的输入之一 | 已阅读 |
| 1.4.2 的数据构建需要 Python 和它自己的 `opencc_dict`；库本身也能读 `text` 词典；`Config::NewFromFile` 也会加载标记为 `may_output_tofu` 的词典（`TSCharactersExt`），缺少它会使整个配置失败 | Kotomo 分发文本词典，改写 `t2s.json`，并按 `extract_tofu_risk.py` 的方式生成 `TSCharactersExt.txt`（`core/rime/stage-data.cmake`） | 已在主机上测试 |

## 英文引擎

| 要点 | 影响 | 核实 |
|---|---|---|
| HeliBoard v4.2（`0c60dffb`，2026-10-12）在 `app/src/main/jni/src` 中带有 AOSP LatinIME 的原生建议核心；每个核心文件保留 AOSP 的 Apache-2.0 文件头，仓库整体为 GPL-3.0 | 子模块 `third_party/heliboard`，未修改；`core/english/` 把核心与 Kotomo 自己的 JNI 桥接编译为 `libkotomo_english.so`（16 KB 对齐，只导出 Kotomo 的三个 JNI 符号） | 已构建（NDK 30，arm64-v8a 与 x86_64） |
| 核心的 `ProximityInfo`、`Dictionary` 与 `SuggestionResults` 需要 `JNIEnv` 和 Java 数组；结果按分数从低到高输出，且可能重复列出同一个词 | 桥接层自己构建 QWERTY 邻近网格（32 × 16 格，与 `ProximityInfo.computeNearestNeighbors` 相同），按分数从高到低排序并去重；触摸点使用固定的 1000 × 600 QWERTY 坐标系 | 已在主机上测试 |
| 主词典为二进制格式 202（单个只读文件），按路径、偏移与长度打开；AOSP 的 dicttool（`127336e9`）可直接用 `javac` 编译，`makedict -2` 把 `.combined` 词表转为该格式，结果逐字节可复现 | `tool/BuildEnglishDict.java`；应用直接从 APK 中映射未压缩的资源文件 | 已在主机上测试 |
| 输入时给出纠错与补全（`helo` → hello、`teh` → the）以及二元组预测；乱码会得到多词拆分，LatinIME 的 Java 层原本会过滤它们 | Kotomo 在任何自动替换之前都需要自己的置信度阈值 | 已在主机上测试 |
| 精确查找不需要 `JNIEnv`：结构策略的 `getWordId` 与 `getWordAttributesInContext` 给出一个词的概率（0–255）与标志；词表中有些词只有首字母大写或全大写的写法，`AI` 被标为可能冒犯（f=113）而 `ai` 只有 f=41，另有许多很短的条目（`Le`、`Rd`、`Chi`） | 协调层按三种大小写查找一段字母，很短的词或可能冒犯的词只有作为整个输入时才提出；`core/mixed/propose_english.cc` 中有一张很小的放行表，每一项都写明原因，让被误标的词可以出现在句子中间（目前只有 `AI`），词表本身从不改动 | 经协调层的主机运行（S6） |
| 手势解码为空实现；手势输入需要 Google 的闭源库 | 手势输入等待开源解码器 | 已阅读 |

## Android 输入法平台

| 要点 | 影响 | 核实 |
|---|---|---|
| Android 17（API 37）是最新稳定平台；Google Play 自 2026-08-31 起要求以 API 36 为目标；面向 API 35 及以上的应用必须支持 16 KB 页，Play 自 2027-02-01 起拒绝不支持的更新 | `compileSdk` 与 `targetSdk` 取 37；所有本地库 16 KB 对齐 | 已阅读 |
| `IME_FLAG_NO_PERSONALIZED_LEARNING` 是请求而非保证；密码输入框使用 `TYPE_TEXT_VARIATION_*PASSWORD` 各变体与 `TYPE_NUMBER_VARIATION_PASSWORD` | Kotomo 把两者都当作硬规则 | 已阅读 |
| 内联自动填充建议（API 30）、手写笔回调（API 33 起），以及面向 API 37 时用于中日韩转换的 `TextAttribute.Builder.setTextSuggestionSelected()` | 由需要它们的阶段使用 | 已阅读 |
| `SpeechRecognizer.createOnDeviceSpeechRecognizer`（API 31）创建在设备上运行的识别器，`isOnDeviceRecognitionAvailable` 为假时抛出异常；`checkRecognitionSupport` 与 `triggerModelDownload`（API 33）报告并获取某种语言的模型，可能需要用户操作；默认的 `createSpeechRecognizer` 很可能把音频流式发送到远程服务器；所有调用都在主线程，需要 `RECORD_AUDIO`，用完必须 `destroy()` | 语音输入只使用设备端识别器（ADR-015） | 已读 |
| Android 17 按设备内存限制应用内存，并且在未处理的旋转后不再恢复键盘可见性 | 内存在 Android 17 上测量 | 已阅读 |
| 只有默认输入法和焦点应用能读取剪贴板（Android 10+）；默认输入法读取时不显示提示（Android 12+）；敏感剪贴带 `ClipDescription.EXTRA_IS_SENSITIVE`；系统在最后一次访问后最迟 60 分钟清除剪贴板（Android 13+） | Kotomo 自己保存剪贴板历史，从不记录敏感剪贴 | 已阅读 |
| `ComposeView` 需要视图树中的 `LifecycleOwner` 与 `SavedStateRegistryOwner`；没有面向输入法的官方指南 | 技术验证 S1 决定面板是否使用 Compose | 已阅读 |
| 输入法只能经 `InputConnection` 作用于输入框：`sendKeyEvent` 把一个 `KeyEvent`（键码、meta 状态、`FLAG_SOFT_KEYBOARD`）像来自键盘一样交给获得焦点的视图；无法发送扫描码或 X11 keysym，无法指定远端协议，也无法向别的应用注入指针事件。到达不处理它的 Activity 的 `KEYCODE_ESCAPE` 等同返回 | PC 布局（PLAN §10.11）把每个键（字母也一样）作为带 meta 状态的按键事件发出（应用读取它们时 Ctrl+C 成立）；远程桌面或终端应用是否转发由该应用决定；不做触摸板。它的插桩测试不按 Esc | 模拟器测试 `PcKeysTest`（T20） |
| Android 15 及以上会对不受信任的 `NotificationListenerService` 读取的通知隐去一次性验证码；只有受信任的应用（如伴侣设备关联）可免除 | 从通知读取验证码（`PLAN.md` §10.7）在 Android 15 及以上看不到未隐去的验证码，文档也没有给输入法提供免除的途径；该功能可能只在 Android 14 及更早版本上可用 | 已读（Android 15 行为变更，2026-11-12）；Android 16、17 的变更未读 |
| Google Play 的用户数据政策要求在访问敏感数据前给出显著的应用内说明并取得明确同意；用户数据与无障碍 API 两页没有专门写 `NotificationListenerService` 或验证码；搜索摘要称 Play Protect 的指引把未经明确同意读取通知内容列为禁止 | 该功能需要计划中已有的说明与同意界面；Play 审核是否接受，靠公开页面无法判定 | 部分已读（Play Protect 页面与 Play Console 声明表单未读） |

## ML Kit

| 要点 | 影响 | 核实 |
|---|---|---|
| GenAI 只在应用位于最前台时推理；前台服务得到 `BACKGROUND_USE_BLOCKED`；每应用配额返回 `BUSY` 与 `PER_APP_BATTERY_USE_QUOTA_EXCEEDED`。开发者报告从 `InputMethodService` 调用同样被拒 | AI 在用户打开的 Kotomo 界面中运行；技术验证 S7 在设备上核实 | 已阅读 |
| 校对与重写（beta）支持英语、日语、法语、德语、意大利语、西班牙语和韩语，不含中文，输入少于 256 token，需要 API 26 及以上，解锁 bootloader 的设备不可用；Prompt API（beta）输入少于 4,000 token | 英文和日文使用这些 API；中文不提供校对与重写，也不使用 Prompt API | 已阅读 |
| 设备端翻译覆盖中文、日文和英文（每种语言约 30 MB，按需下载）；不含英语的语言对经英语中转。它是 ML Kit 的普通 API，不属于 GenAI，文档没有前台限制的说明 | 翻译直接在键盘中运行（`translate` 17.0.3；在模拟器 Android 17 上，键盘下载了模型并完成翻译，`TranslateTest`；没有真机运行）；中日互译于 2026-11-13 用十个日常句子（每个方向五句）评估：七句可用，三句因英语中转而出错（地名被译成英文短语、十分"十分钟"被理解为"足够"、"推迟到下午三点"被理解为"推迟到三点为止"）。带着这一限制提供，`ai.md` 写明 | 已阅读；已在模拟器评估 |
| 数字墨水识别（`digital-ink-recognition` 19.0.0，minSdk 23）在设备上识别笔画（带时间的点）；每个语言标签一个模型，每个约 20 MB，经 `RemoteModelManager` 下载，可以删除；所用标签为 `zh-Hani-CN`、`zh-Hani-TW`、`zh-Hani-HK` 与 `ja`；`RecognitionContext` 接受光标前的文字（前文）与书写区域的尺寸。文档没有前台限制的说明；数据披露页在所有 ML Kit 功能都收集的内容之外只列出所配置的语言 | 手写面板直接在键盘中识别，并传入光标前最后 20 个字符；在模拟器（Android 17）上，键盘下载了中国大陆的中文模型，把一笔识别为「一」（`HandwritingTest`）；没有真机运行 | 2026-11-13 已阅读；已在模拟器运行 |
| ML Kit 的数据披露页列出其 SDK 收集的内容：GenAI 为所配置的语言、设备与应用信息（包名、版本）、用于诊断与分析的标识符、每次安装的标识符、性能指标、输入输出的大小、事件类型与错误码；翻译另有源语言与目标语言，并使用 Firebase Remote Config 与 Installations；语言识别另有识别出的语言。页面称数据在传输中加密、不转交第三方，并由开发者自己填写 Play 的数据安全表单；它没有说明输入文字本身是否离开设备 | Kotomo 自己不发送文字（`ai.md`），但隐私政策写明 ML Kit 可能发送这些诊断数据，数据安全表单也要列出；ML Kit 不发送输入文字这一点没有核实 | 2026-11-12 已读（ML Kit 服务条款未读） |

## 数据集

| 要点 | 影响 | 核实 |
|---|---|---|
| JMdict 与 KANJIDIC2 为 CC BY-SA 4.0（EDRDG）；应用须在从菜单进入的独立页面中署名，并保持数据更新；优先级标记标出常用词与外来语（`gai1`） | 英文 → 日文映射；"关于"下的来源页 | 已阅读 |
| GPLv3 是 CC BY-SA 4.0 的单向兼容许可（Creative Commons，2015） | CC BY-SA 4.0 数据可随 GPL-3.0 应用分发，并保留署名 | 已阅读 |
| Unihan（Unicode License v3）按字提供 `kMandarin`；Unicode 18.0 新增 `kJapaneseOldVariant` 与 `kJapaneseNewVariant`；新字体可能与无关的汉字同形（芸 yún 与 藝 yì） | 拼音 → 日文映射先把新字体映射到旧字体 | 已阅读 |
| CLDR 49 注释（Unicode License v3）提供 en、zh、zh-Hant 与 ja 的 Emoji 关键词和名称 | Emoji 关键词词库 | 在 P1 中使用 |
| cldr-json 文件不带版本字段，键省略 U+FE0F（`☺` 而非 `☺️`）；`annotations` 覆盖单个 Emoji，`annotationsDerived` 覆盖肤色与 ZWJ 序列（家庭、职业）；日文关键词不带读音 | 锁定到 49.0.0 标签的提交并校验 SHA-256；查找时去掉 U+FE0F 再试；两个文件都读取；假名读音取自 Mozc 词典 | 在 P1 中使用 |
| `emoji-test.txt` 中每个基础 Emoji 后紧跟它的全部肤色形式；混合肤色的双人组合没有自己的无肤色形式（👩🏻‍🤝‍👨🏼 属于 👫，🫱🏻‍🫲🏼 属于 🤝），Component 组只含单独的肤色色块与发型部件 | 带肤色的序列作为其前一个基础 Emoji 的变体；去掉 Component 组 | 在 P1 中使用 |
| Noto Emoji：字体 OFL-1.1，图像 Apache-2.0，支持 Unicode 18.0；Twemoji（jdecked）：图形 CC BY 4.0，覆盖 Emoji 17.0；Apple 的 Emoji 不得再分发，也不得包含在第三方键盘中 | Emoji 样式包；绝不使用 Apple 的图像 | 已阅读 |
| 中文方案数据：rime-ice 为 GPL-3.0 only，rime_wanxiang 为 CC BY 4.0，rime-luna-pinyin 与 rime-essay 为 LGPL-3.0。日文：SudachiDict 为 Apache-2.0；alt-cannadic 只允许 GPL-2.0 | 在技术验证 S2 与 S8 中选定；从不使用仅限 GPL-2.0 的数据 | 已阅读 |
| Unihan 18 给部分日本国字标了没有中文词典佐证的 `kMandarin`（込 yū、峠 qiǎ、笹 shì），另一些则有佐证（畑 tián、辻 shí） | `kMandarin` 的读音一律采用，因为中文词典对国字本来就没有读音（用户决定，2026-11-04）；只有完全没有读音的字（﨑、𩸽）会使其所在的词被跳过 | 在 S5 中使用 |
| `kJapaneseOldVariant` 的 363 条中有 62 条指向 NFC 会折回原字的 CJK 兼容汉字（神 → U+FA19） | 取读音前先去掉 | 在 S5 中使用 |
| OpenCC 的新字体词组给出历史写法（世論 → 輿論、予定 → 預定），也可能保留新字体（回転 → 廻転）；OpenCC 与 Unihan 对部分字的旧字体不一致（挙、弁） | 先试词组，再在两张表的并集上逐字映射；词组换成了另一个字时，该词同时保留历史读音（yulun）与按字面的读音（shilun），这是用户的决定（2026-11-04） | 在 S5 中使用 |
| CC-CEDICT 把 ü 写作 `u:`，专有名词首字母大写，并有占位读音（`xx5`）；MDBG 只发布当前文件 | 以文件的日期头与 SHA-256 锁定，有意识地更新 | 在 S5 中使用 |
| jmdict-simplified 把 JMdict 的优先级标记（`news1`、`ichi1`、`spec1/2`、`gai1`）合并为一个 `common` 标志，并提供 `languageSource`；EDRDG 自己的文件每天替换 | 发行版可以按哈希锁定；外来语取自 `languageSource`，而不是 `gai1` | 在 S5 中使用 |
| 词库包的数据源（2026-11-11 阅读）：rime-ice 与 rime-frost 的 `*.dict.yaml` 在 `...` 之后为 `词<TAB>空格分隔的无调拼音<TAB>权重`，ü 写作 `v`；rime-ice 的 `8105`、`41448` 是单字，`41448` 没有权重；zhwiki 的行没有权重，拉丁字母写成大写音节（`B zhan`）；THUOCL 为 `词<TAB>文档频率`，没有拼音并带填充空格；SudachiDict 原始 CSV 的第 0–5 列为见出し、lid、rid、代价、表层形式、词性，第 11 列为片假名读音，id 为 `-1` 的条目不参与分析；neologd 种子是 MeCab CSV（表层形式、lid、rid、代价、词性……、片假名读音），含 3,224,584 个专有名词与颜文字；mozcdic-ut 的文件是 Mozc 行（读音、lid、rid、代价、表层形式）；SCOWLv2 只发布源文件，`make`（Python 3、SQLite）构建 `scowl.db`，再用 `scowl word-list <size> A,B,Z,C 1` 列出词 | Q6 的生成器读取这些格式；没有拼音的中文词表（THUOCL）要从其他来源推出读音 | 已阅读 |
| 词库来源在哪里发布（2026-11-11 阅读）：GitHub 的发布 API 给出每个附件的大小与 `sha256:` 摘要（rime-ice 的 `nightly`、fcitx5-pinyin-zhwiki 0.3.1）；`raw.githubusercontent.com` 的文件没有校验值（其 ETag 不是文件的 SHA-256），SudachiDict 的 S3 文件也没有；发布附件的下载从 `github.com` 重定向到 `release-assets.githubusercontent.com`；rime-ice 的最新发布是每天重建的 `nightly` | Kotomo 核对发布附件的 GitHub 摘要，普通文件只信任到 HTTPS 为止（ADR-011） | Q6 中使用 |
| librime 每次选择方案都把 `user_dict` 文本词表（`db_class: stabledb`）载入内存；`kotomo_packs.txt` 有 543,423 行时，模拟器上的键盘在安装后 25 秒才重新能打字 | 下载的词库包保持较小（提供的最大一个 43,343 词）；大的需要预编译的词表 | Q6 中实测 |
| Mozc `dictionary_oss` 的行格式为读音、lid、rid、代价、表层形式（共 1,289,014 条；普通名词为 1851 号）；平假名表层形式会与其他词的活用形冲突 | 纯假名的映射不与 Mozc 词条关联，使用普通名词 | 在 S5 中使用 |
