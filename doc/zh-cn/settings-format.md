# 设置格式

用户在设置应用中修改的设置保存在一个 Preferences DataStore 文件中：应用凭据加密存储区内的
`files/datastore/settings.preferences_pb`。设置应用和键盘运行在同一进程中，共用一个 `SettingsStore`
（`app/src/main/java/com/yuanzhe/kotomo/settings/SettingsStore.kt`）。

## 读取与写入

- `SettingsStore.flow` 是一个 `StateFlow<Settings>`，文件每次变化时发出新值；它从默认值开始，在
  `SettingsStore.get` 之后几毫秒内变为文件中的值。
- `SettingsStore.loaded` 是同一个流，但在文件第一次读完之前为 null；键盘的模糊音部署等待它，
  所以默认值永远不会触发部署。
- `SettingsStore.current` 不做 I/O 即返回最新值，键盘可以在主线程和按键路径上读取。
- `SettingsStore.set`（不等待，按调用顺序排队）和 `SettingsStore.update`（挂起直到写入完成）应用
  `Settings` 的一个 `copy`，只写入值发生变化的键。
- 损坏的文件被替换为空文件，读出的就是默认值。
- 界面语言（设置 → 界面语言，Android 13 及以上）不在这个文件里：它是 Android 的应用语言
  （`LocaleManager`，可选语言列在 `res/xml/locales_config.xml`），由 Android 保存，系统自己的应用
  语言设置也能修改它；Kotomo 的备份不包含它。键盘每次打开时读取它。

## 版本与兼容

| 键 | 类型 | 含义 |
|---|---|---|
| `format_version` | int | 最后写入该文件的格式版本；本版本写入 `1`，从不降低更新版本写入的值 |
| `updated_utc_ms` | long | 最后一次修改的时间，自纪元起的 UTC 毫秒数 |

- 文件或某个键不存在时读出默认值；第一次修改时才创建文件。因此从"没有文件"到版本 1 不需要迁移步骤。
- 本版本不认识的键从不删除。
- 本版本不理解的值（未知的枚举名、超出范围的数字）读作默认值，并保留在文件中，直到用户修改这一项设置；
  因此更新版本写入的值在运行旧版本后仍然保留。
- 枚举值以小写的常量名保存（`balanced`、`left`）；重命名常量即是格式变更。

## 键

混合模式在每次按键时读取它的各个键（混合输入协调层把它们作为选项接收，见 `engine-contract.md`）。日文模式以
同样的方式读取 `japanese.cross.*`：它只向协调层要日文候选，把其中的跨语言候选加到 Mozc 自己的候选里；
`pinyin_to_kanji`、`english_to_kanji` 与 `english_to_katakana` 全部关闭时不询问协调层。`readings` 让候选栏在
跨语言的日文候选旁显示它的读音。尚未读取的有：两种模式的 `pinyin_to_katakana`（没有把拼音对应到片假名词的数据）。

| 键 | 类型 | 含义 |
|---|---|---|
| `format_version` | int | 最后写入该文件的格式版本；本版本写入 `1`，从不降低更新版本写入的值 |
| `updated_utc_ms` | long | 最后一次修改的时间，自纪元起的 UTC 毫秒数 |

- 文件或某个键不存在时读出默认值；第一次修改时才创建文件。因此从"没有文件"到版本 1 不需要迁移步骤。
- 本版本不认识的键从不删除。
- 本版本不理解的值（未知的枚举名、超出范围的数字）读作默认值，并保留在文件中，直到用户修改这一项设置；
  因此更新版本写入的值在运行旧版本后仍然保留。
- 枚举值以小写的常量名保存（`balanced`、`left`）；重命名常量即是格式变更。

## 键

跨语言开关由谁读取见上文"版本与兼容"；下表列出全部的键。

| 键 | 类型 | 默认值 | 设置 |
|---|---|---|---|
| `default_mode` | string | `mixed` | 键盘启动时的模式：`mixed`、`chinese`、`japanese`、`english`；隐藏的模式改用第一个显示的模式 |
| `modes.url_english` | boolean | `false` | 网址输入框（`TYPE_TEXT_VARIATION_URI`）直接以英文输入；关闭时与其他输入框一样转换，并从该应用上次使用的模式开始，仍保留网址专用的底行，因为浏览器的地址栏同时也是搜索栏。邮箱、密码和代码类输入框不受影响 |
| `modes.order` | string | `mixed,chinese,japanese,english,pc` | 语言选择的顺序，模式名以逗号连接；缺少的模式排到最后，本版本不认识的名称保留在已知名称之后 |
| `modes.hidden` | string | 空 | 语言选择中不提供的模式，以逗号连接；全部隐藏时全部提供 |
| `mixed.level` | string | `balanced` | 混合模式的智能程度：`conservative` 使跨语言词的代价加倍，`aggressive` 使其减半并允许跨语言候选排在首位；`balanced` 保持原语言优先 |
| `mixed.weight_chinese`、`mixed.weight_english`、`mixed.weight_japanese`、`mixed.weight_emoji` | int 0–100 | 70、60、60、30 | 混合模式中各语言的候选权重。某种语言的候选按它的权重偏离默认值的多少上移或下移（取默认值时不移动）；Emoji 权重决定各模式中 Emoji 的位置（PLAN §10.6）：100 时排在最好的两个候选之后，每低 20 点后移一位 |
| `mixed.space` | string | `separate` | 混合模式组合中的空格（ADR-010）：`separate` 在组合内标出一处边界、不提交任何内容，`commit_first` 提交首选候选，`next` 把首选候选显示在输入框里，再按一次换到下一个（此时回车提交当前显示的那个） |
| `mixed.enter` | string | `first` | 混合模式组合中的回车：`first` 提交首选候选，`shown` 提交输入框显示的字母 |
| `japanese.space` | string | `next` | 日文模式组合中的空格：`next` 或 `commit_first`，含义同 `mixed.space`；其他值读作默认值 |
| `japanese.enter` | string | `shown` | 日文模式组合中的回车：`shown` 提交输入的假名（已选定分段后提交转换结果），`first` 提交首选候选 |
| `chinese.space` | string | `commit_first` | 中文模式组合中的空格：`commit_first` 或 `next`；其他值读作默认值 |
| `chinese.enter` | string | `shown` | 中文模式组合中的回车：`shown` 提交输入的字母，`first` 提交首选候选 |
| `mixed.cross.*`、`japanese.cross.*` | boolean | `true` | 混合模式与日文模式的跨语言转换开关：`pinyin_to_kanji`（按中文读音键入的日文汉字词）、`pinyin_to_katakana`、`english_to_kanji`（英文词对应的同义日文词）、`english_to_katakana`（对应的外来语）、`whole_sentence`（关闭时跨语言词只在它就是整个组合时提供）、`readings`、`emoji` |
| `chinese.script` | string | `simplified` | 中文输出：`simplified`（大陆）、`traditional`（OpenCC 标准）、`taiwan`、`hong_kong`（带地区用词）；快捷面板还会在键盘的记忆（见下文）中按 App 记住选择 |
| `chinese.fuzzy.<rule>` | boolean | `false` | 每条模糊音规则一个开关（PLAN §9.6）：`z_zh`、`c_ch`、`s_sh`、`n_l`、`f_h`、`l_r`、`an_ang`、`en_eng`、`in_ing`、`ian_iang`、`uan_uang`；开关改动过一次后才写入该键。键盘把开启的规则写成 librime 的 `kotomo_fuzzy.yaml` 并部署（[upstream.md](upstream.md)） |
| `english.suggestions` | boolean | `true` | 候选栏中的英文纠错与预测 |
| `english.auto_capitalize` | boolean | `true` | 句首自动大写 |
| `layout.split` | string | `auto` | 分离键盘：`auto`（按窗口宽度）、`always`、`never` |
| `layout.thumb_zone_dp` | int 200–340 | 268 | 分离键盘每半边的宽度（dp） |
| `layout.tabletop_lower_half` | boolean | `true` | 半折叠时把键盘移到铰链下方 |
| `layout.one_handed` | string | `off` | 2026-11-11 之前的版本保存的单手模式，对所有屏幕生效：`off`、`left`、`right`。现在只在键盘记忆中没有该屏幕与方向的 `one_handed` 条目时读取（见下文），从不写入 |
| `layout.height_percent` | int 80–120 | 100 | 键盘高度，相对设计高度的百分比 |
| `keys.long_press_symbols` | boolean | `true` | 长按按键输入符号 |
| `keys.shift_separator` | boolean | `true` | 中文或混合模式正在组合拼音时，Shift 键输入音节分隔符 `'`（标为“分词”），组合结束后恢复为 Shift；九宫格保留自己的分词键 |
| `keys.backspace_restores_commit` | boolean | `false` | 混合模式上屏后紧接着按退格，恢复组合以及其中选过的片段（PLAN §5.6）；关闭时和别处一样只删一个字。中文、英文、日文模式始终只删除。与撤销自动替换的退格无关，这个开关不影响它 |
| `keys.corner_hints` | boolean | `true` | 在按键角上显示长按符号 |
| `keys.haptics` | boolean | `true` | 按键震动 |
| `keys.sound` | boolean | `false` | 按键时播放系统按键音 |
| `gestures.space_cursor`、`gestures.delete_swipe`、`gestures.double_space_period`、`gestures.space_language`、`gestures.smart_space` | boolean | `true` | 每个手势一个开关：沿空格键滑动移动光标、从删除键向左滑动按词删除、快速按两次空格输入句号、长按空格选择语言、建议的英文单词后输入标点时的智能空格 |
| `punctuation.smart_quotes`、`punctuation.auto_pair` | boolean | `false` | 智能标点（PLAN §10.8）：`"` 与 `'` 键按光标前的字符输入弯引号与撇号；开括号或开引号同时输入对应的闭合符号，再输入这个闭合符号时跳过它，紧接着退格则删除这对空括号。代码、网址、邮箱与直接输入的输入框中都不生效 |
| `privacy.incognito` | boolean | `false` | 无痕模式：不学习、不记录历史与剪贴板 |
| `privacy.learned_encrypted` | boolean | `false` | 把混合模式学到的读法保存在加密文件里（见下文，ADR-013） |
| `clipboard.history` | boolean | `true` | 把复制的文字保存在剪贴板历史中（[clipboard.md](clipboard.md)） |
| `clipboard.retention_days` | int 1–90 | 7 | 未固定的剪贴板条目保留的天数（隐私页提供 1、7、30） |
| `clipboard.quick_paste` | boolean | `true` | 在键盘上方提供刚复制的内容和其中的验证码 |
| `clipboard.excluded_apps` | string | 空 | 在其输入框中什么都不记录、不提供的应用的包名，以逗号连接（[clipboard.md](clipboard.md)）；读取时丢弃不是包名的项 |
| `theme.choice` | string：`system`、`light`、`dark`、`dynamic` | `system` | 键盘配色；`dynamic` 即 Material You（Android 12 及以上）。仅在主题文件（见下）存在之前读取，之后不再写入 |
| `theme.key_radius_dp` | int，0–24 | `12` | 按键圆角，单位 dp；仅在主题文件存在之前读取 |
| `ai.enabled` | boolean | `false` | 允许 AI 按钮使用写作助手（[ai.md](ai.md)） |
| `pc.enabled` | boolean | `false` | 在语言选择中提供 PC 布局（PLAN §10.11）；它就是输入模式页上 PC 一行的显示/隐藏开关，打开之前一直隐藏。`modes.order` 与 `modes.hidden` 像其他模式一样列出 `pc` |
| `pc.host` | string：`windows`、`macos`、`linux` | `windows` | PC 布局要输入的电脑：修饰键的名称与顺序、Fn 页的快捷键与安全组合键（Ctrl+Alt+Del，macOS 为 ⌥⌘Esc）；发出的键码不变 |
| `pc.wide` | string：`full`、`split` | `full` | 宽屏上的 PC 布局：功能键带加完整的 PC 键盘，或把手机的几页分成两半放在两侧 |
| `toolbar.order` | string | `clipboard,ai,translate,settings,edit,emoji,language,dictionaries,themes,floating,undo,redo,handwriting,voice` | “更多”之后的工具栏工具（PLAN §10.10），工具名以逗号连接；“更多”总在最前面，不列出。缺少的工具排到最后，本版本不认识的名称保留在已知名称之后 |
| `toolbar.inline_autofill` | boolean | `true` | 在空闲的工具栏上显示自动填充服务的内联建议（Android 11 起，PLAN §10.10）；关闭时 Kotomo 不请求 |
| `keys.gap_dp` | int 2–12 | `6` | 按键间距，单位 dp（PLAN §10.9） |
| `keys.text_percent` | int 80–140 | `100` | 按键文字大小，为设计稿的百分比 |
| `keys.long_press_custom` | string | 空 | 用户自定义的长按符号：每行 `字母=符号`（a–z）；不符合的行在读取时丢弃 |
| `layout.number_row` | boolean | `false` | 字母上方加一行数字 |
| `layout.space_percent` | int 60–140 | `100` | 空格键宽度，为设计稿的百分比 |
| `layout.mirrored` | boolean | `false` | 功能键放到另一侧：底行与 ⇧ … ⌫ 一行左右翻转 |
| `layout.floating` | boolean | `false` | 键盘悬浮在 App 之上，可拖动；悬浮时不分离 |
| `chinese.idioms` | boolean | `true` | 中文提交的内容是某个成语的开头时，提供后面的字（PLAN §10.8）；成语来自下载的成语词库（`zh-thuocl-idioms`），没有下载就不提供 |
| `chinese.quick_input` | boolean | `true` | 中文组合以 `v` 开头时进入快捷输入：数字、金额大写、日期、时间、Unicode、算式、自定义替换（PLAN §10.4） |
| `chinese.quick_custom` | string | 空 | 用户的快捷替换，每行 `缩写=文字`（小写字母与数字，至多 16 个）；其他行丢弃 |
| `chinese.stroke_lookup` | boolean | `true` | 中文组合以 `u` 开头时按笔画查字（luna_pinyin 的笔画反查） |
| `chinese.double_pinyin` | string | `none` | 双拼方案：`none`（全拼）、`microsoft`、`sogou`、`xiaohe`、`ziranma`（PLAN §10.4）；不认识的值按全拼处理 |
| `emoji.favorites` | string | 空 | Emoji 面板的收藏，每行一个，按加入顺序（PLAN §10.6）；至多 64 个，超出时先去掉最早的；在 Emoji 的长按卡片中切换 |
| `emoji.combos` | string | 空 | 用户自己的 Emoji 组合，每行一个；面板在“组合”中把它们排在 Kotomo 自带的之后 |
| `emoji.mappings` | string | 空 | 用户为词语指定的 Emoji，每行 `词=Emoji`（词为按键入方式写的小写字母，至多 24 个）；输入正好这些字母时排在 Emoji 候选最前 |
| `japanese.flick` | boolean | `false` | 日文模式使用 12 键滑动布局 |
| `chinese.nine_key` | boolean | `false` | 中文模式使用九宫格：2–9 键代表各自的字母，librime 的九宫格方案把数字读作拼音（PLAN §10.9）；模糊音规则与双拼方案像在字母键盘上一样作用于它（打开双拼时，数字拼的是方案的两个键）；用户词库与下载的词库以字母为编码，九宫格查不到 |
| `toolbar.hidden` | string | `edit,emoji,language,dictionaries,themes,floating,undo,redo,handwriting,voice` | 不放在工具栏上的工具，以逗号连接；它们和工具栏放不下的工具一起在“更多”里。与 `toolbar.order` 一起写入；已存的 `toolbar.order` 中缺少的工具（以后的版本新增的）按默认位置放置 |

## 主题文件

键盘主题存放在单独的 JSON 文件 `files/themes/current.json` 中（PLAN §8.6），每次主题变化都经临时文件
原子替换写入。在它存在之前（来自更早版本的安装），主题从上面的 `theme.*` 键读取；第一次修改会写出该
文件并保持这些键不变，因此旧版本仍能读到最后的值。

| 成员 | 类型 | 含义 |
|---|---|---|
| `format_version` | int | 格式版本；本版本写 2（第 2 版加入了从 `name` 起的成员；第 1 版的文件照常读取），且从不降低更新的值 |
| `updated_utc_ms` | int | 写入时间，UTC 毫秒 |
| `base` | string：`system`、`light`、`dark`、`dynamic` | 主题跟随的配色 |
| `key_radius_dp` | int，0–24 | 按键圆角，单位 dp |
| `name` | string，至多 40 个字符 | 主题的名称（内置主题的名称，或保存时取的名称）；为空时省略 |
| `colors` | object | 用户在基础配色之上设定的颜色（PLAN §11.4），每个为 `#RRGGBB` 或 `#AARRGGBB`：`background`、`key`、`key_function`、`key_pressed`、`text`、`text_secondary`、`accent`、`on_accent`、`candidate`（选中的候选）、`candidate_text`；省略的颜色跟随基础配色 |
| `background_image` | string | 背景图片在 `files/themes/images/` 中的文件名（单纯的 `.png` 文件名，从不是路径） |
| `background_opacity` | int，0–100 | 图片的不透明度，百分比；默认 60 |
| `press_highlight` | boolean | 按下的按键是否变色；默认 `true` |

未知成员（更新的格式）在重写文件时保留；未知或超出范围的值按默认值读取，不是有效十六进制颜色的颜色、
不是单纯文件名的图片名会被省略。不是 JSON 对象的文件会被移到 `current.json.unreadable`，并使用默认值。

### 主题库

保存的主题与每种输入模式使用的主题存放在 `files/themes/library.json`（`settings/ThemeLibrary.kt`），
写法相同；图片存放在 `files/themes/images/`，没有主题再使用时删除其文件。

| 成员 | 类型 | 含义 |
|---|---|---|
| `format_version` | int | 格式版本；本版本写 1，且从不降低更新的值 |
| `updated_utc_ms` | int | 写入时间，UTC 毫秒 |
| `themes` | array | 保存的主题（至多 100 个），每个是上述主题对象再加一个 `id`（`saved.<uuid>`）；没有有效 id 的项被省略 |
| `modes` | object | 按模式（`mixed`、`chinese`、`japanese`、`english`）：该模式使用的已保存或内置（`builtin.<名称>`）主题的 id；省略的模式或其主题已不存在时使用当前主题 |

分享用的主题文件（导出）是一个主题对象，图片以 `background_image_png`（base64 编码的 PNG）放在其中，
不带文件名。导入至多读取 12 MB，按上述规则检查每个值，把图片解码后重新编码为 PNG（长边至多 2048 像素），
并在使用前显示预览。

## Emoji 样式文件

Emoji 样式（PLAN §11.5）存放在 `files/emoji/style.json`（`ime/EmojiStyles.kt`），字体放在它旁边：
`noto-color-emoji.ttf`（下载，按锁定的大小与 SHA-256 核对）与 `custom.ttf`（导入：至多 40 MB、
Android 能加载的 TrueType 或 OpenType 文件）。

| 成员 | 类型 | 含义 |
|---|---|---|
| `format_version` | int | 格式版本；本版本写 1，且从不降低更新的值 |
| `updated_utc_ms` | int | 写入时间，UTC 毫秒 |
| `style` | string：`system`、`noto`、`custom` | 键盘的 Emoji 面板如何绘制 Emoji；字体缺失的样式按系统字体绘制 |
| `custom_name` | string，至多 60 个字符 | 导入字体显示的名称 |

## 键盘记忆文件

键盘自己记住的东西（而不是用户设置的东西）存放在 `files/keyboard/memory.json`（`ime/KeyboardMemory.kt`）：
上次使用的模式；按 App 记住的上次使用的模式、为它固定的模式（快捷面板的"在此固定这个模式"，PLAN §2.3）
和在其中选择的中文字形、用户为它做的设置（不学习、按代码输入；"更多"面板，PLAN §10.10）；Emoji 历史（最近使用与每个的发送次数），以及按屏幕与方向记住的单手模式。每次变化后在后台线程经临时文件原子替换整份写入；键盘在启动时
读取一次并保存在内存中。

| 成员 | 类型 | 含义 |
|---|---|---|
| `format_version` | int | 格式版本；本版本写 1，且从不降低更新的值 |
| `updated_utc_ms` | int | 写入时间，UTC 毫秒 |
| `last_mode` | string | 在任何地方最后选择的模式（`mixed`、`chinese`、`japanese`、`english`） |
| `apps` | object | 按包名：`mode`（上次在其中使用的）、`pinned_mode`（为它固定的）、`script`（`chinese.script` 的值）、`incognito`（`true`：在其中什么都不学习、不记录，即"在此 App 中不学习"）与 `code`（`true`：它的普通文本框按代码输入框处理，即"在此 App 中按代码输入"；PLAN §10.10）；每项都可以缺省，后两项只在为 `true` 时写入 |
| `emoji.recent` | 字符串数组 | 从面板或其搜索发送的 Emoji，最新的在前，最多 32 个 |
| `one_handed` | object | 按屏幕与方向（`sw<最小宽度 dp>-portrait` 或 `-landscape`，PLAN §8.10）：`side`（`off`、`left`、`right`）与 `width_percent`（66–92，默认 82） |
| `emoji.uses` | object | 每个 Emoji 发送的次数（Emoji → 次数 ≥ 1），最多 200 个，先去掉用得最少的；发送两次及以上的列为"常用" |

- 只保存包名，从不保存输入的内容。在敏感输入框、直接输入的输入框和无痕模式下，不按 App 记录任何东西，
  也不记录 Emoji 历史（PLAN §14）；那里只会写入全局的 `last_mode`。
- 未知成员和本版本无法读取的值（更新版本的模式）在重写文件时保留，按缺省读取。不是 JSON 对象的文件会被
  移到 `memory.json.unreadable`，键盘从什么都不记得开始。
- 格式 1 之前的版本把同样的值存放在 SharedPreferences 中（`shared_prefs/ime.xml`：`mode`、`mode.<app>`、
  `script.<app>`；`shared_prefs/emoji_recents.xml`：`items`）。`memory.json` 不存在时从那里读取；第一次
  变化写出该文件，旧文件保持原样，因此覆盖安装的旧版本仍能读到它们。
- 清单把所有文件排除在 Android 备份与设备迁移之外（`data_extraction_rules.xml`）。

词库有自己的带版本文件（`files/dictionaries/user.json`、`state.json`），见 [dictionaries.md](dictionaries.md)。

## 已学习读法文件

混合模式从用户选过的候选中学到的内容保存在 `files/keyboard/learned.json`（`ime/MixedLearning.kt`），与设置
文件一样带版本：保留不认识的成员，不降低更新的格式版本，损坏的文件移到 `learned.json.unreadable`。

| 键 | 类型 | 含义 |
|---|---|---|
| `format_version` | int | 本版本写入 `1` |
| `updated_utc_ms` | long | 最后一次修改的时间，UTC 毫秒 |
| `readings` | list | 每条学到的读法一个对象 |
| 读法：`input` | string | 输入的字母，小写，不含空格 |
| 读法：`text` | string | 它们提交成的文字 |
| 读法：`count` | int | 被选中的次数 |
| 读法：`used_utc_ms` | long | 最后一次选中的时间，UTC 毫秒 |
| 读法：`rank` | string | 缺省，或用户的决定：`demoted`、`blocked`、`pinned` |

混合模式的一次提交记录整个输入的读法；候选有多个片段时，还记录每个片段的读法。每用一次，该读法在引擎中的
代价降低 3000 个单位，最多计三次；效果自最后一次使用起每 30 天减半，低于五分之一次时归零。降低排序的读法
代价增加 3000，不再提供的读法永不出现，固定首选的读法始终排在最前。最多保留 2000 条，最弱且未被用户排序的
先被去掉。敏感输入框、要求不做个性化学习的输入框、无痕模式与首次解锁前不记录，此时引擎也不使用已学到的
内容。提交后紧接着的退格连同它的学习一起撤回。隐私 → 已学习的读法列出它们，可降低排序、不再提供、固定首选、
恢复默认与删除；隐私 → 清除学习数据清空该文件。文件是应用私有存储中的普通 JSON，不离开设备。

开启 `privacy.learned_encrypted`（默认关闭，ADR-013）后，同样的 JSON 文本改为加密保存在
`files/keyboard/learned.kenc`，`learned.json` 被删除：

```text
offset  size  content
0       4     magic "KLRN"
4       1     container version (1)
5       12    AES-GCM IV, fresh for every write
17      n     AES-256-GCM ciphertext of the JSON text, 128-bit tag; AAD = bytes 0..4
```

- 密钥是 Android Keystore 中的 `kotomo_learned`（AES-256、GCM、无填充）；它从不离开 Keystore，所以键盘读取
  该文件时不需要用户做任何事。
- 磁盘上的形式以进程第一次读取读法时找到的文件为准；开关（以及键盘每次读到的已保存设置）会改变它。无论哪个
  方向，都先经临时文件与原子替换写出新文件，写入成功后才删除另一个；两个文件都在时读取较新的那个。关闭开关
  会写回 `learned.json` 并删除 `learned.kenc`。
- 容器版本更新的加密文件保持原样：不读取其中内容，本次运行也不写入任何内容。无法解密或解析的加密文件（密钥
  丢失、损坏）移到 `learned.kenc.unreadable`，读法从空开始。需要写入时 Keystore 给不出密钥，则跳过这次写入，
  磁盘上的文件保持不变。

隐私 → 已学习的读法还提供导出与导入。导出的是上面的 JSON 文本，从不加密（密钥不能离开设备），写到用户选择的
文件里。导入读取这样的文件（最大 4 MB）并合并：本机没有的读法被加入，双方都有的保留较晚使用的那份，最多保留
2000 条；不是这种格式的文件不改变任何内容。

## Direct Boot

首次解锁之前，键盘无法读取这些文件（凭据保护存储），也不读写键盘记忆。此时使用默认值，保存在设备保护存储中的一次性
文件里，每次都会删除；设备解锁后切换到用户的设置（[platform-notes.md](platform-notes.md)）。

## 隐私

该文件只保存在设置应用中做出的选择：不含输入的文字、候选或剪贴板内容。清单关闭了 Android 备份
（`allowBackup="false"`），因此该文件只留在本机。
