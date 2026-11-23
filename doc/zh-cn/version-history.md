# 版本历史

## 0.1.8（2026-11-23）

- 消除输入与剪贴板推荐引起的键盘高度跳动并重构快捷输入条（`QuickPaste`、`QuickPasteStrip`、`KotomoService`、`CandidateBar`、`EmojiPanel`）：
  - 移除打字时自动弹出于候选栏上方的独立 `SegmentStrip`（删除 `SegmentStrip.kt`），将分句调词与变形入口统一整合进候选栏右侧箭头展开的 `SegmentPanel`，确保起打与上屏全程高度恒定；`CandidateBar` 在展开 `CandidatesPanel` 或 `SegmentPanel` 时将右侧箭头由向下（`v`）翻转为向上收起（`^`）。
  - 将原两行约 `110dp` 高的 `QuickPasteStrip` 重构为与候选栏、内联自动填充共占 `barContainer` 的等高单行（`bar_height` = `52dp`）输入条（高度变化为 `0`，显示时遮蔽顶层自动填充浮层）；左侧保留“更多”按钮（打开 `QuickPanel` 并在面板上方露出完整工具栏），中间为跟随主题 `kbColor(...)` 的 `40dp` 圆角胶囊（验证码/文本/图片缩略图），右侧保留关闭按钮。
  - 新增按输入框区分的智能打字静默（`QuickPaste.suppressIn` / `current(nowUtcMs, fieldKey)`）：在该输入框开始按键输入或经 Emoji/手写/语音插入文字后，当前剪贴会在该框静默，避免词间上屏反复弹出，同时非空输入框仍可接收新复制内容的推荐，未静默的其他输入框在 60 秒内仍可收到；联动 `settings.clipboard.quickPaste`，开关关闭时立即隐藏并撤回推荐。
- 上游：与 0.1.7 相同（2026-11-23 核对 librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2，未变；HeliBoard 更新标签仍为测试版预发布）。
- 2026-11-23 标记发布（`versionCode` 9）。在开发主机上验证：`java tool/CheckDocs.java`、`./gradlew check assembleDebug assembleRelease assembleDebugAndroidTest`（`QuickPasteTest` `8/8`，单元测试 `312/320` 通过、8 项主机跳过）、改版本号后的 `./gradlew assembleDebug assembleRelease`，以及模拟器（`Medium_Phone_API_37.0`、Android 17、x86_64、debug）：`MixedTypingTest` `35/35`、`FieldTypingTest` `35/35`、`ClipboardAppTest` `5/5`（含 `quickPasteKeepsConstantHeightMoreButtonAndSuppressesOnTyping`）、`CandidateBarTest` `3/3` 与 `InlineAutofillTest` `4/4`，并核对浅色、深色（`zh-CN`）及 `1.5x` 字体缩放截图（全程 `0px` 跳动）。本次发布周期未在物理真机上运行验证。

## 0.1.7（2026-11-22）

- 全面核查并修复混合、中文、日文、英文与 PC 模式的符号页（`Layout.symbols`、`Layout.symbolMore`）：在混合、中文与日文模式的符号第 1 页补齐 `/`、`*` 与 `_`（此前混合与中文完全缺少 `/` 与 `*`，日文仅藏在 `・` 的长按里）；移除符号第 2 页上与首行重复的 `^`、`=`、`\`，并在第 2 页补上直接可点的 `[`、`]`、`` ` ``、`%` 及日文 `"`、`'`，使所有模式两页合集零重复覆盖全部 32 个可打印 ASCII 符号；确保分离布局下全部成对括号与引号（`（）`、`「」`、`『』`、`【】`、`《》`、`〈〉`、`“”`、`‘’`、`[]`、`{}`、`<>`）不被拆分到左右两半；修复 `Mode.PC` 符号第 2 页首行与 `Mode.ENGLISH` 一致；补齐对称的 `symbolMore` 长按变体（`\` → `/`、`*` → `×`、`|` → `｜`、`:`、`;`、`[`、`]`、`~`、`` ` ``、`°`，并在 `-` 中加入日语长音符号 `ー`）。
- 上游：与 0.1.6 相同（2026-11-22 核对 librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2，未变）。
- 2026-11-22 标记发布（`versionCode` 8）。在开发主机上验证：`java tool/CheckDocs.java`、`./gradlew check assembleDebug assembleRelease assembleDebugAndroidTest`（`LayoutTest` `24/24`、`PcTest` `11/11`，单元测试 `310/318` 通过、8 项主机跳过）、改版本号后的 `./gradlew assembleDebug assembleRelease`，以及模拟器（`Medium_Phone_API_37.0`、Android 17、x86_64、debug）：`FieldTypingTest` `35/35`（含 `symbolPagesTypeSlashAsteriskAndBracketsAcrossModes`），并核对浅色/深色混合模式、`1.5x` 字体缩放混合模式及浅色日文模式两页符号截图。本次发布周期未在物理真机上运行验证。

## 0.1.6（2026-11-22）

- Gboard 式双击 Shift 大写锁定：在 `KeyboardView.kt` 中抽离 `ShiftState`（`DOUBLE_TAP_MS = 400L`）并接入 `KotomoService.kt`，单按在 `Shift.OFF` ↔ `Shift.ONCE` 间切换，400 ms 内连续按两次 Shift 且中间无其他按键时进入 `Shift.LOCKED`（包括从英文自动大写 `Shift.ONCE` 起手时首击先关闭大写、快速再击进入锁定），在 `Shift.LOCKED` 状态下点击即解锁且不会立刻重新触发锁定；新增大写锁定图标 `ic_key_caps_lock.xml`（带下划线的实心箭头），在 `shift` 变化时刷新 `KeyboardView` 无障碍树以在 `Shift` 与 `Caps lock` 描述间即时切换，并在混合模式大写锁定期间保持大写锁定键可见而不被 `分词`（`separatorOnShift`）替换。
- 上游：与 0.1.5 相同（2026-11-22 核对 librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2，未变）。
- 2026-11-22 标记发布（`versionCode` 7）。在开发主机上验证：`java tool/CheckDocs.java`、`./gradlew check assembleDebug assembleRelease assembleDebugAndroidTest`（`ShiftStateTest` `8/8`，单元测试 `306/314` 通过、8 项主机跳过）、改版本号后的 `./gradlew assembleDebug assembleRelease`，以及模拟器（`Medium_Phone_API_37.0`、Android 17、x86_64、debug）：`FieldTypingTest` `34/34`（含 `shiftDoubleTapEntersCapsLock`、B1 与 A10），并核对浅色与深色模式下的大写锁定截图。本次发布周期未在物理真机上运行验证。

## 0.1.5（2026-11-21）

- 日文与混合模式活用变形、敬语体裁与整句变形（第二阶段）：`core/mozc` 由 `LookupPosIds` 与 `Describe` 共用 `FindStemToken`，使多语素活用谓词（如 `行きます`）在 `PinnedConvert` 时保留动词词性；`Inflection` 支持四档礼貌体裁（普通/礼貌/尊敬/谦让）、通用 `五段動詞` 词尾推行、Mozc 词典 `未然ウ接続` 预变形条目（`行こう`、`飲もう`、`食べよう`、`来よう`、`しよう`）归一化、形容动词与换词敬语、保留前文与助词的整句谓词抽取（`extractTarget`），以及平假名/片假名/半角片假名写法变体。
- 变形面板与三个入口：`FormsPanel` 提供四档体裁分段标签与左右滑动切换、实时整句高亮预览，以及适应字体缩放的三列/两列（`fontScale >= 1.4f`）卡片网格（覆盖英/简/繁/日四语）；`SegmentStrip` 在保持 `scrollX = 0` 的同时于右侧固定“变形”胶囊，`SegmentPanel` 新增“变形”按钮以按片段锁定（`lockText` / `Candidates.pick`）并继续转换其余未锁定片段，`CandidateDetailsBar` 在日文与混合模式下均支持打开“变形”（含混合模式下 `>= 16` 字母输入的二阶段 `mixed.refine()` 收敛）。
- 上游：与 0.1.4 相同（2026-11-21 核对，未变）；因 `LookupPosIds` / `Describe` 词干查词改动重新构建 Android Mozc 库（`arm64-v8a`、`x86_64`）并更新 `build-lock.json`。
- 2026-11-21 标记发布（`versionCode` 6）。在开发主机上验证：`java tool/CheckDocs.java`、`//kotomo:kotomo_mozc_test`（`19/19`）、`ctest build/host/mixed`（`11/11`）、`ctest build/host/rime`（`2/2`）、整套转换语料（`312/313`，0 回退，Mixed 模式条目经协调层 `138/138`）、`./gradlew check assembleDebug assembleRelease assembleDebugAndroidTest`（`InflectionTest` `10/10`，单元测试 `298/306` 通过、8 项主机跳过）、改版本号后的 `./gradlew assembleDebug assembleRelease`，以及模拟器（`Medium_Phone_API_37.0`、Android 17、x86_64、debug）：`FieldTypingTest` `33/33`、`MixedTypingTest` `35/35`，并核对深浅色、四语界面及 `1.0x`/`1.5x` 字体缩放截图。本次发布周期未在物理真机上运行验证。

## 0.1.4（2026-11-20）

- 修复输入时键盘上下跳动：`updateSegmentStrip` 在等待引擎线程处理新按键期间保留上一快照的片段条显示（与候选栏一致），不再在按下按键时将 `SegmentStrip` 清空隐藏为 `GONE`、待快照返回又恢复为 `VISIBLE`，同时 `SegmentStrip.show` 在片段项未变时跳过冗余布局刷新。
- 上游：与 0.1.3 相同（2026-11-20 核对，未变）。
- 2026-11-20 标记发布（`versionCode` 5）。在开发主机上验证：`java tool/CheckDocs.java` 与 `./gradlew assembleDebug assembleRelease`。

## 0.1.3（2026-11-20）

- 实时片段条与分组片段面板：混合与日文输入时在候选栏上方显示实时片段条（`SegmentStrip`）；点击片段可打开带来源标识切片的片段面板、两列语言/读音分组候选卡片与三列“假名与原文”行（片段条与面板按钮在 1.5 倍字体缩放下完整容纳）。
- 中日共用汉字简繁/日文新字体切换（混合引擎契约第 10 版，命令 `14` 锁定文本、候选标志位 `5` `sharedZhJa`）：中日共用汉字显示 `中日` 标识；两行 `CandidateDetailsBar` 借助内置 OpenCC 转换表在中文与日文汉字形式间切换，并与“忘记”“变形”并排显示而不遮挡。
- 符号页标点与内联自动填充浮窗修复：混合、中文与日文母页保持全角逗号与句号，两页符号页底行直接输入半角 `,` 与 `.`（长按默认选中全角在前）；顶行长按弹窗与按键预览不再被系统置顶内联自动填充条遮挡，`KeyboardView` 触控追踪改为按 `pointerId` 跟踪。
- 上游：Android Gradle Plugin 从 9.5.1 升至 9.5.2（PR #1）；其余上游与 0.1.2 基线（2026-11-19 对 0.1.1 的核对）相同。
- 2026-11-20 标记发布（`versionCode` 4）。在 `1a22596`（合并 PR #1 之前）于开发主机验证：`ctest core/mixed`（11/11）、整套转换语料（`312/313`，0 回退，Mixed 模式条目经协调层 `138/138` 全部通过）、`./gradlew check assembleDebug assembleRelease assembleDebugAndroidTest`，以及模拟器（Android 17、API 37、debug）：`InlineAutofillTest`、`FieldTypingTest`、`MixedTypingTest` 与 `CandidateBarTest`（73/73）。合并 PR #1（AGP 9.5.2）后按用户指示未重跑测试；本次发布周期未在物理真机上运行验证。

## 0.1.2（2026-11-19）

- 普通输入栏不再切到代码模式：多行、无建议与带代码提示的输入栏保持当前输入模式与全角符号；只有在为该应用打开代码模式时才使用英文与半角符号，`modes.url_english` 关闭时网址输入栏跟随当前模式。
- 长按：弹窗锚定在所按的键上方，即使是边缘键也无需滑动即选中角标（`o` → `9`、`i` → `8`）；字母页底行和两页符号页上长按全角符号默认选中对应的半角符号。
- 现代中文词汇：构建时把 rime-ice 2026.06.30（`8105`、`base`、`ext`）并入内置拼音词库；用户词典与词库包在混合模式下同样生效；可下载的 `zh-ice-base` 与 `zh-ice-ext` 词库包在后台编译。
- 剪贴板图片：复制的 PNG、JPEG、WebP 与 GIF 图片按与文字相同的规则记录（敏感输入栏、无痕模式与排除的应用中从不记录），加密保存在设备上，在剪贴板面板、剪贴板设置页与快捷粘贴条中显示缩略图，并可粘贴到接受图片的输入栏。
- 混合模式整句改写不再从 librime 读取前缀候选，过期的改写会被取消（主机上 `zhongguo` 2,475 ms → 65 ms）；快捷粘贴条收起时键盘保持贴底。
- 上游：与 0.1.1 在 2026-11-19 的核对相同，未变；新内置 rime-ice 2026.06.30。
- 2026-11-19 发布（`versionCode` 3）。在开发主机上验证：`java tool/CheckDocs.java`、`java lexicon/CheckSamples.java`（40/40）、主机引擎测试（英文、rime `2/2`、混合 `11/11`）、整套转换语料（`312/313`，0 回退，Mixed 模式条目经协调层 `138/138` 全部通过）、`./gradlew check assembleDebug assembleRelease assembleDebugAndroidTest`，以及模拟器（Android 17、x86_64、debug）：`FieldTypingTest` 31/31、`MixedTypingTest` 31/31、`ClipboardAppTest` 4/4、`DictionaryKeyboardTest` 3/3、`FuzzySettingsTest` 4/4，并核对浅色/深色主题、四种界面语言与字体缩放 1.5 截图。本次发布周期未在物理真机上运行验证。

## 0.1.1（2026-11-19）

- 混合模式与英文 → 日语匹配优化：带括号限定语的 JMdict 英文释义在剥离括号后若为单词词元则索引进 `cross.klx`（`power` → `パワー`），附属词性义项（`suf`、`pref`、`ctr`、`aux*`、`prt`、`conj`、`int`）排在独立名词与外来语之后（`system` → `システム`），英文常见屈折词尾（`-s`、`-es`、`-ed`、`-ing`）支持词干回退查词，未登录假名回显与残留拉丁字母计入罚分以避免压过外来语，且混合模式下的英文候选保留输入的大写形式（S5 样本 40/40，反例 0/8）。
- 混合模式拼音前缀匹配与分段上屏（引擎契约第 9 版）：多音节拼音输入在整句候选之后提供严格按音节边界对齐的 2–3 音节词组前缀与首音节单字候选（绝不在音节内部截断）；点选前缀候选仅提交该前缀并保留剩余拼音继续组词（在展开面板中点选时保持面板打开并刷新剩余拼音的候选）。
- 展开候选面板长按详情：在 `CandidatesPanel` 与 `SegmentPanel` 中长按候选词可在面板上方的候选栏显示读音、词典来源以及 Mozc 词性、原形与活用详情。
- 混合模式候选语言标识：候选栏、`CandidatesPanel` 与 `SegmentPanel` 中的混合模式候选词旁显示与设置页一致的微型 `中` / `EN` / `日` 标识，由 `mixed.language_tags` 设置项控制（默认开启 `true`，可在“设置 → 混合模式”中开关）。
- 2026-11-19 核对上游，未变：librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2、Gradle 9.9.1、Android Gradle Plugin 9.5.1、Kotlin 2.4.21、Compose BOM 2026.09.00、ML Kit GenAI 1.0.0-beta1、ML Kit translate 17.0.3、NDK r30、平台 37.2、构建工具 37.0.0、CC-CEDICT 2026-11-14T02:25:17Z、jmdict-simplified 3.6.2+20261109214828、Unicode 18.0 与 cldr-json 49.0.0。
- 2026-11-19 发布（`versionCode` 2）。在开发主机上验证：`java tool/CheckDocs.java`、`java lexicon/CheckSamples.java`（40/40）、主机 C++ 混合引擎测试（`11/11`）、整套转换语料（`301/302`，0 回退，Mixed 模式条目经协调层 `133/133` 全部通过）、`./gradlew check assembleDebug assembleRelease`，以及模拟器（Android 17、x86_64、16 KB 页、debug）：`CandidateBarTest` 3/3、`MixedTypingTest` 31/31、`FieldTypingTest` 29/29，并核对浅色/深色主题、四种界面语言与最大字体缩放（2.0）截图。本次发布周期未在物理真机上运行验证。

## 0.1.0（2026-11-14）

- 仓库脚手架：工作规范（`AGENTS.md`）、计划（`PLAN.md`，来自用户的草案 2.0）、镜像文档和文档检查（`tool/CheckDocs.java`）。
- 2026-11-04 阅读的上游版本，尚未构建：librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2；Android API 37、Android Gradle
  Plugin 9.5.0、Kotlin 2.4.21、NDK r30。
- Emoji 数据（P1），2026-11-04 核对：Unicode Emoji 18.0 与 CLDR 49.0.0 注释，均为最新版本；拼音与假名读音沿用上面的
  Unihan、CC-CEDICT 与 Mozc 锁定版本。
- 2026-11-10 在 x86_64 Linux 主机上恢复开发时核对上游：Gradle 9.8.2 → 9.9.1，Android Gradle Plugin 9.5.0 → 9.5.1；
  CC-CEDICT 移到 2026-11-10T08:36:22Z 的文件，jmdict-simplified 移到 3.6.2+20261109214828（JMdict 2026-11-09）。核对后未变：
  librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2、Kotlin 2.4.21、Compose BOM 2026.09.00、各 AndroidX 库、
  ML Kit GenAI 1.0.0-beta1、NDK r30、Unicode 18.0、CLDR 49.0.0、各 rime 数据仓库与 Bazelisk 1.30.0。
- 词库包的数据源于 2026-11-11 锁定在最新版本（`build-lock.json`；尚未生成、尚未分发）：rime-ice 2026.06.30、rime-frost 1.0.4、
  fcitx5-pinyin-zhwiki 0.3.1（20261018 的转储）、THUOCL `a30ce79d`、SudachiDict 20261015、mecab-ipadic-neologd 种子 20200910、
  mozcdic-ut 2026-11-03、SCOWLv2 rel-2026.02.25。
- 2026-11-11 核对上游：CC-CEDICT 移到 2026-11-11T02:50:39Z 的文件（S5 样本 34/34，反例 0/8）。核对后未变：librime 1.17.0、
  Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2、Gradle 9.9.1、Android Gradle Plugin 9.5.1、Kotlin 2.4.21、Compose BOM
  2026.09.00、各 AndroidX 库、ML Kit GenAI 1.0.0-beta1、NDK r30、平台 37.2、构建工具 37.0.0、jmdict-simplified
  3.6.2+20261109214828、Unicode 18.0、CLDR 49.0.0、各 rime 数据仓库与 Bazelisk 1.30.0。
- 2026-11-11 为 Q6 再次核对词库数据源，未变：rime-ice 2026.06.30、rime-frost 1.0.4、fcitx5-pinyin-zhwiki 0.3.1、
  THUOCL `a30ce79d`、SudachiDict 20261015、mozcdic-ut 2026-11-03、SCOWLv2 rel-2026.02.25。此后应用直接从来源下载提供的
  词库（THUOCL、rime-ice 的 `en_dicts.zip` 与 `8105`、zhwiki 的网络用语），取来源当前的版本；锁定只固定主机测试转换的文件。
- 2026-11-11 为协调层的设备接入（Q7）再次核对上游，未变：librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2 与 HeliBoard 4.2
  （按各自的标签；HeliBoard 唯一更新的标签是预发布的 4.3-beta1）；工具链与数据源同上一条。Kotomo 的 Mozc 胶水层
  加入了给协调层的函数表，因此重新构建了两个 Android 库，并把它们的哈希记入 `build-lock.json`；`mozc.data` 未变。
- 2026-11-12 的来源审查（S8，`third-party.md`）：从发布源读到的最新版本：SudachiDict v20261015、Noto Emoji v2026-09-24-unicode18_0、Twemoji v17.0.3、OpenMoji 17.0.0、jmdict-simplified 3.6.2+20261109214828、cldr-json 49.0.0（均未变或为新记录）；THUOCL 未变。
- 2026-11-13 为翻译（Q9）核对上游：新加入 ML Kit `translate` 17.0.3（最新版本）；ML Kit GenAI 校对与改写 1.0.0-beta1、librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2（更新的只有 4.3-beta1）、AGP 9.5.1 与 Kotlin 2.4.21（2.5.0 是候选版本）未变。
- 2026-11-14 核对上游：CC-CEDICT 移到 2026-11-14T02:25:17Z 的文件，因为 MDBG 替换了当前文件，之前锁定的文件已无法下载（S5 样本 34/34，反例 0/8；拼音 → 日文表有三行变化，`emoji.tsv` 未变；转换语料无回退）。核对后未变：librime 1.17.0、Mozc 3.35.6354、OpenCC 1.4.2、HeliBoard 4.2（更新的只有 4.3-beta1）、Gradle 9.9.1、Android Gradle Plugin 9.5.1、Kotlin 2.4.21（2.5.0 是候选版本）、Compose BOM 2026.09.00、jmdict-simplified 3.6.2+20261109214828 与 cldr-json 49.0.0。
- 2026-11-14 发布（`versionCode` 1），第一个版本。上游即上面 2026-11-14 那次检查的版本。在开发主机上验证过：文档检查、Gradle
  检查、主机引擎测试、整套转换语料无回退（Mixed 模式的条目经协调层 115 条全部通过），以及模拟器上的插桩测试（Android 17、
  x86_64、16 KB 页；`e3871fe` 上 107 项：105 项通过、1 项跳过，1 项失败过一次、单独运行三次都通过，原因没有确认）；已签名的 `arm64-v8a` release 构建（开启
  R8）在用户的手机上按 `tests/device/checklist.md` 安装使用了四轮，最后一轮是 `4e60561` 的构建；发布的版本与该构建相比只改了文档和
  CI 工作流。没有验证的：清单中有 115 行没有记录，其中包括最后一轮的待复测行；关卡 G0–G5 没有判定；从未在真机上测量延迟和内存；
  设备端 AI（Gemini Nano）从未运行；CI 模拟器那次运行中 `MixedTypingTest` 的五项失败（`platform-notes.md`）没有得到解释。
