# 设置格式

用户在设置应用中修改的设置保存在一个 Preferences DataStore 文件中：应用凭据加密存储区内的
`files/datastore/settings.preferences_pb`。设置应用和键盘运行在同一进程中，共用一个 `SettingsStore`
（`app/src/main/java/com/yuanzhe/kotomo/settings/SettingsStore.kt`）。

## 读取与写入

- `SettingsStore.flow` 是一个 `StateFlow<Settings>`，文件每次变化时发出新值；它从默认值开始，在
  `SettingsStore.get` 之后几毫秒内变为文件中的值。
- `SettingsStore.current` 不做 I/O 即返回最新值，键盘可以在主线程和按键路径上读取。
- `SettingsStore.set`（不等待，按调用顺序排队）和 `SettingsStore.update`（挂起直到写入完成）应用
  `Settings` 的一个 `copy`，只写入值发生变化的键。
- 损坏的文件被替换为空文件，读出的就是默认值。

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

键盘目前还不读取 `mixed.level`、各 `mixed.weight_*` 键、`mixed.space`，以及除 `mixed.cross.emoji` 之外的所有
`mixed.cross.*` 与 `japanese.cross.*` 开关：设置应用保存并显示它们，混合协调层完成后才生效。

| 键 | 类型 | 默认值 | 设置 |
|---|---|---|---|
| `default_mode` | string | `mixed` | 键盘启动时的模式：`mixed`、`chinese`、`japanese`、`english`；隐藏的模式改用第一个显示的模式 |
| `modes.order` | string | `mixed,chinese,japanese,english` | 语言选择的顺序，模式名以逗号连接；缺少的模式排到最后，本版本不认识的名称保留在已知名称之后 |
| `modes.hidden` | string | 空 | 语言选择中不提供的模式，以逗号连接；全部隐藏时全部提供 |
| `mixed.level` | string | `balanced` | 混合模式的智能程度：`conservative`、`balanced`、`aggressive` |
| `mixed.weight_chinese`、`mixed.weight_english`、`mixed.weight_japanese`、`mixed.weight_emoji` | int 0–100 | 70、60、60、30 | 混合模式中各语言的候选权重 |
| `mixed.space` | string | `separate` | 混合模式组合中的空格：`separate`（插入分隔）或 `commit_first`（提交首选） |
| `mixed.cross.*`、`japanese.cross.*` | boolean | `true` | 混合模式与日文模式的跨语言转换开关：`pinyin_to_kanji`、`pinyin_to_katakana`、`english_to_kanji`、`english_to_katakana`、`whole_sentence`、`readings`、`emoji` |
| `chinese.script` | string | `simplified` | 中文输出：`simplified`（大陆）、`traditional`（OpenCC 标准）、`taiwan`、`hong_kong`（带地区用词）；快捷面板还会在键盘的记忆（见下文）中按 App 记住选择 |
| `english.suggestions` | boolean | `true` | 候选栏中的英文纠错与预测 |
| `english.auto_capitalize` | boolean | `true` | 句首自动大写 |
| `layout.split` | string | `auto` | 分离键盘：`auto`（按窗口宽度）、`always`、`never` |
| `layout.thumb_zone_dp` | int 200–340 | 268 | 分离键盘每半边的宽度（dp） |
| `layout.tabletop_lower_half` | boolean | `true` | 半折叠时把键盘移到铰链下方 |
| `layout.one_handed` | string | `off` | 2026-11-11 之前的版本保存的单手模式，对所有屏幕生效：`off`、`left`、`right`。现在只在键盘记忆中没有该屏幕与方向的 `one_handed` 条目时读取（见下文），从不写入 |
| `layout.height_percent` | int 80–120 | 100 | 键盘高度，相对设计高度的百分比 |
| `keys.long_press_symbols` | boolean | `true` | 长按按键输入符号 |
| `keys.corner_hints` | boolean | `true` | 在按键角上显示长按符号 |
| `keys.haptics` | boolean | `true` | 按键震动 |
| `keys.sound` | boolean | `false` | 按键时播放系统按键音 |
| `gestures.space_cursor`、`gestures.delete_swipe`、`gestures.double_space_period`、`gestures.space_language`、`gestures.smart_space` | boolean | `true` | 每个手势一个开关：沿空格键滑动移动光标、从删除键向左滑动按词删除、快速按两次空格输入句号、长按空格选择语言、建议的英文单词后输入标点时的智能空格 |
| `privacy.incognito` | boolean | `false` | 无痕模式：不学习、不记录历史与剪贴板 |
| `clipboard.history` | boolean | `true` | 把复制的文字保存在剪贴板历史中（[clipboard.md](clipboard.md)） |
| `clipboard.retention_days` | int 1–90 | 7 | 未固定的剪贴板条目保留的天数（隐私页提供 1、7、30） |
| `clipboard.quick_paste` | boolean | `true` | 在键盘上方提供刚复制的内容和其中的验证码 |
| `theme.choice` | string：`system`、`light`、`dark`、`dynamic` | `system` | 键盘配色；`dynamic` 即 Material You（Android 12 及以上）。仅在主题文件（见下）存在之前读取，之后不再写入 |
| `theme.key_radius_dp` | int，0–24 | `12` | 按键圆角，单位 dp；仅在主题文件存在之前读取 |
| `ai.enabled` | boolean | `false` | 允许 AI 按钮使用写作助手（[ai.md](ai.md)） |

## 主题文件

键盘主题存放在单独的 JSON 文件 `files/themes/current.json` 中（PLAN §8.6），每次主题变化都经临时文件
原子替换写入。在它存在之前（来自更早版本的安装），主题从上面的 `theme.*` 键读取；第一次修改会写出该
文件并保持这些键不变，因此旧版本仍能读到最后的值。

| 成员 | 类型 | 含义 |
|---|---|---|
| `format_version` | int | 格式版本；本版本写 1，且从不降低更新的值 |
| `updated_utc_ms` | int | 写入时间，UTC 毫秒 |
| `base` | string：`system`、`light`、`dark`、`dynamic` | 主题跟随的配色 |
| `key_radius_dp` | int，0–24 | 按键圆角，单位 dp |

未知成员（更新的格式、P4 主题编辑器的颜色）在重写文件时保留；未知或超出范围的值按默认值读取。不是
JSON 对象的文件会被移到 `current.json.unreadable`，并使用默认值。

## 键盘记忆文件

键盘自己记住的东西（而不是用户设置的东西）存放在 `files/keyboard/memory.json`（`ime/KeyboardMemory.kt`）：
上次使用的模式；按 App 记住的上次使用的模式、为它固定的模式（快捷面板的"在此固定这个模式"，PLAN §2.3）
和在其中选择的中文字形；Emoji 历史（最近使用与每个的发送次数），以及按屏幕与方向记住的单手模式。每次变化后在后台线程经临时文件原子替换整份写入；键盘在启动时
读取一次并保存在内存中。

| 成员 | 类型 | 含义 |
|---|---|---|
| `format_version` | int | 格式版本；本版本写 1，且从不降低更新的值 |
| `updated_utc_ms` | int | 写入时间，UTC 毫秒 |
| `last_mode` | string | 在任何地方最后选择的模式（`mixed`、`chinese`、`japanese`、`english`） |
| `apps` | object | 按包名：`mode`（上次在其中使用的）、`pinned_mode`（为它固定的）、`script`（`chinese.script` 的值）；每项都可以缺省 |
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

## Direct Boot

首次解锁之前，键盘无法读取这些文件（凭据保护存储），也不读写键盘记忆。此时使用默认值，保存在设备保护存储中的一次性
文件里，每次都会删除；设备解锁后切换到用户的设置（[platform-notes.md](platform-notes.md)）。

## 隐私

该文件只保存在设置应用中做出的选择：不含输入的文字、候选或剪贴板内容。清单关闭了 Android 备份
（`allowBackup="false"`），因此该文件只留在本机。
