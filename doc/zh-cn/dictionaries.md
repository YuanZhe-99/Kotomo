# 词库

用户自己的词语、导入的文件与下载的词库包（PLAN §10.5），以
`app/src/main/java/com/yuanzhe/kotomo/dictionary/` 与词库设置页（`settings/DictionariesPage.kt`）
中的实现为准。引擎侧（librime 的三份运行时词表）在 `core/rime/data/luna_pinyin_simp.custom.yaml`。

## 来源与优先级

| 来源 | 内容 | librime 词表 | 质量 | 开关 |
|---|---|---|---|---|
| 内置 | APK 自带的 luna_pinyin、Mozc 与英文引擎的词典 | 方案自己的 | — | 无：始终开启 |
| 手动添加（`manual`） | 在词库页输入的词语 | `custom_phrase.txt` | 1 | 你的词库 |
| 导入（`import`） | 用户选择的文件中的词语 | `kotomo_low.txt` | −1 | 你的词库 |
| AI 模型生成（`generated`） | 标为 LLM 输出的文件中的词语 | `kotomo_low.txt`，优先级最多 20 | −1 | 你的词库 |
| 下载（`download`） | 从数据源下载、在手机上转换的词库 | `kotomo_packs.txt` | 0 | 下载的词库，以及每个包一个开关 |

不可信的词排在后面：导入或生成的词无论文件怎么写都不会进入 `custom_phrase.txt`，文件自称的来源一律忽略。
每行是 `词<TAB>无空格的拼音<TAB>权重`；没有内容的词表是空文件。只有中文条目进入 librime；日文与英文条目先保存并标为
"尚未使用"，等 Mozc 的用户词典与英文引擎接入后再使用。

## 条目与读音

条目有语言（`zh`、`ja`、`en`）、词、读音、0–100 的权重和来源，并保留带来的其他字段（罗马字、标签、Emoji）。
语言、词与读音都相同的两个条目视为同一个。

- 词去掉首尾空白并做 NFC 规范化；为空、超过 64 个字符、含控制字符、换行或 U+FFFD 时拒绝。
- 中文读音是无调拼音：小写，去掉调号与声调数字，ü 与 `u:` 写作 `v`，空格与隔音符是边界；每一段都必须能切分成
  luna_pinyin 认识的音节（取音节最少的切法：`xian` 是一个，`xi'an` 是两个）。只由汉字组成的词每个字对应一个音节。
  保存时音节之间用空格（`ni hao`）。
- 日文读音只收假名；片假名转为平假名，长音符 ー 保留。
- 英文条目没有读音。

## 导入

| 格式 | 结构 |
|---|---|
| TSV | 每行一个条目；可选的表头给出列名（`language`、`text` 或 `word`、`reading`、`weight`；其他列保留）；没有表头时列依次是语言、词、读音、权重 |
| CSV | RFC 4180（带引号的字段可含逗号、双写的引号与换行），列与可选表头同上 |
| JSON | LLM 提示词（PLAN 附录 B）的字段：一个对象，其 `entries` 中每项含 `language`、`text`（或 `word`）、`reading`、`weight` 及其他成员；`format_version` 大于 1 时拒绝整个文件 |
| Rime `*.dict.yaml` | `...` 之后的行：`词<TAB>带空格的拼音[<TAB>权重]`，中文 |

- 按严格 UTF-8 解码；去掉 BOM；有非法字节或 U+FFFD 时拒绝整个文件。超过 16 MB 的文件不读取。
- 未通过检查的行被拒绝并计数；预览列出前五个及其行号（JSON 条目以其序号代替行号；CSV 记录以起始行为准）。
- 与前面某行重复的行计数并跳过。词库中已有的条目算作冲突：在预览中计数，默认保留已有条目，用户也可以选择用文件中的。
- 权重：缺省为 50；文件中最大的权重超过 100 时，先按对数相对这个最大值缩放，再截到 0–100；LLM 文件封顶 20。
- 预览显示将添加、被拒绝、跳过与冲突的数量；用户确认之前什么都不保存。JSON 文件默认打开"由 AI 模型生成"，其他格式默认关闭；
  没有写明语言的行使用预览中选择的语言。

## 导出

TSV、CSV 与 JSON 包含全部条目以及表头或格式版本；Rime 格式只包含中文条目。来源从不写出，所以导出后再导入的文件与其他文件一样不可信。

## 文件

都在应用的凭据加密存储区中；通过临时文件与原子重命名写入，在 IO 调度器上由同一把锁保护。

| 路径 | 内容 |
|---|---|
| `files/dictionaries/user.json` | 用户的条目 |
| `files/dictionaries/user.prev.json` | 最后一次修改之前的版本；"恢复上一个版本"交换这两个文件 |
| `files/dictionaries/user.kenc`、`user.prev.kenc` | 开启密码保护期间，以上两个文件的加密版本（取代明文文件） |
| `files/dictionaries/state.json` | 开关 |
| `files/dictionaries/packs/<id>/` | 已安装的词库包：`entries.tsv`（规范化的）、`table.txt`（它的 librime 词表行）与 `pack.json` |
| `files/dictionaries/packs/<id>.prev/` | 该词库包的上一个版本，用于回滚 |
| `files/rime/user/custom_phrase.txt`、`kotomo_packs.txt`、`kotomo_low.txt` | librime 的词表，每次修改后重写 |

`user.json`：

| 成员 | 含义 |
|---|---|
| `format_version` | 1；来自更新版本的文件使词库页只读，且永不被覆盖 |
| `updated_utc_ms` | 最后写入时间，UTC 毫秒 |
| `entries` | 对象，含 `language`、`text`、`reading`（英文没有）、`weight`、`source` 及其他字段，均保留 |

其他成员保留。未通过检查的条目（损坏或手工编辑的文件）原样保留并原样写回。

`state.json`：

| 成员 | 含义 |
|---|---|
| `format_version`、`updated_utc_ms` | 同上 |
| `custom_enabled` | 用户自己的词（手动、导入、生成）；默认 `true` |
| `downloads_enabled` | 全部下载的词库包；默认 `true` |
| `packs` | 每个已安装的包：`{"<id>": {"enabled": true}}` |
| `online_accepted` | 用户接受的下载说明的版本；接受之前没有这一项 |
| `protection` | 开启密码保护期间：`kdf`、`iterations`、`salt` 与 `check`（见下） |

## 密码保护

默认关闭（ADR-012）。"用密码保护"以用户设置的密码派生的密钥加密用户的词；Kotomo 不在磁盘上保存密码或密钥，
忘记的密码无法找回（对话框中写明）。

- `user.json` 与 `user.prev.json` 变为 `user.kenc` 与 `user.prev.kenc`，明文文件被删除；之后的每次修改都加密写出。
  关闭密码保护时写回明文文件并删除加密文件，需要先解锁。
- 密钥：PBKDF2（HMAC-SHA256），600,000 轮，随机 16 字节盐，256 位；每个文件用 AES-256-GCM，每次新的 12 字节 IV
  与 128 位标签，文件头作为附加数据。`state.json` 保存 `protection`（`kdf`、`iterations`、`salt`、`check`：一小段
  加密文字，用来区分密码的对错）。
- 键盘进程启动后词库处于锁定状态：解锁之前词库页不显示也不修改任何词。上次解锁时写出的 librime 词表保留，键盘仍能
  打出用户的词；"立即锁定"清空 `custom_phrase.txt` 与 `kotomo_low.txt`，直到下次解锁。librime 需要明文词表，所以
  键盘能使用这些词期间，它们在应用私有存储中是可读的。
- 受保护期间导出的文件以同样方式加密（`kotomo-dictionary.<格式>.kenc`）；导入加密文件时询问它的密码，并按文件头
  中写明的格式读取。

```text
offset  size  content
0       4     magic "KDIC"
4       1     container version (1)
5       4     PBKDF2 rounds (big-endian)
9       1     salt length (16), then the salt
26      1     kind length, then the kind (UTF-8: "user", "check" or an export's format)
…       12    AES-GCM IV
…       n     ciphertext and 128-bit tag; associated data = every byte before the IV
```

容器版本更新的文件从不打开或覆盖：词库页只读。

## 下载的词库

设置 → 词库 → 获取词库 列出下面的来源（`dictionary/Sources.kt`、ADR-011）。Kotomo 不托管任何东西：下载时从作者
发布的地方取来源本身的文件，在手机上后台转换（`DictionaryDownloads`），用的是主机测试运行的同一份代码。转换出的
每一行都经过与导入相同的检查（词、读音、音节数、编码、权重）；未通过的行丢弃并按原因计数。

### 提供的词库

| 词库包 | 文件 | 校验值 | 词数（主机，锁定的文件） |
|---|---|---|---|
| `zh-web-slang` | fcitx5-pinyin-zhwiki 最新发布的 `web-slang-<日期>.dict.yaml`（9 KB） | GitHub 为该附件公布的 SHA-256 摘要 | 399（丢弃 24 行拼写出的拉丁字母） |
| `zh-thuocl-it`、`-animal`、`-finance`、`-car`、`-idioms`、`-places`、`-food`、`-law`、`-people`、`-medical`、`-poetry` | 取自 `raw.githubusercontent.com` 的 THUOCL `data/THUOCL_<领域>.txt`（每个 0.1–1 MB）与 rime-ice 的 `cn_dicts/8105.dict.yaml`（0.1 MB） | 无：只有 HTTPS | 1,602 至 43,343（IT 11,565） |
| `en-ice-tech` | rime-ice 最新发布的 `en_dicts.zip`（0.2 MB）中的 `en_ext.dict.yaml` | GitHub 的摘要 | 2,328（已保存，尚未使用） |

THUOCL 没有拼音。词的读音由 rime-ice 的单字表拼成：一个字最常见的读音至少是次常见读音的十倍时才采用，因此含有真正
多音字（行 xing / hang）的词作为"有歧义"丢弃，而不是给出错误的读音（IT：1,458 个有歧义，2,976 个含字母或单字表
没有的字）。主机上的数字来自 `KOTOMO_PACKS=1` 下的 `DictionaryPacksTest`，读的是 `build-lock.json` 锁定的文件；
设备下载的是来源当前的版本。

### 评估

PLAN §10.5 批准的每个来源，2026-11-11 核查（计数是 Q6 转换保留的结果）：

| 来源 | 手机需要下载的内容 | 是否公布校验值 | 在手机上 | 是否提供 |
|---|---|---|---|---|
| rime-ice `base`、`ext` | 发布附件 `cn_dicts.zip` 14.7 MB | GitHub 的 SHA-256 摘要 | base：模拟器上 38–44 秒转换出 543,423 个词，未超出 192 MB 的堆（PSS 232 MB）；但 librime 每次选择方案都把下载的词库包当作文本词表载入，安装后 25 秒键盘才重新能打字 | 否：等预编译的词库包词表 |
| rime-ice `en_ext`、`8105` | 发布附件 `en_dicts.zip` 0.2 MB；普通文件 `8105.dict.yaml` 0.1 MB | `en_dicts.zip`：摘要；`8105`：无 | 瞬间完成 | 是（`en-ice-tech`；`8105` 用于 THUOCL 的读音） |
| rime-frost | 44 MB 与 88 MB 的发布整包；普通文件 `base` 与 `ext` 10 MB | 整包：摘要；普通文件：无 | 可行，但与 rime-ice 重叠 | 否 |
| fcitx5-pinyin-zhwiki `zhwiki` | `zhwiki-<日期>.dict.yaml` 57.7 MB | 摘要 | 170 万行：转换与保存都太大 | 否 |
| fcitx5-pinyin-zhwiki `web-slang` | 9 KB | 摘要 | 瞬间完成 | 是 |
| THUOCL | 11 个文本文件，0.1–1 MB | 无 | 每个不到一秒 | 是 |
| SudachiDict | `small_lex.zip` 13.6 MB、`core_lex.zip` 21.7 MB（Amazon S3） | 无 | 77 万与 86 万行，解压后 100 与 166 MB；只有流式处理才可行 | 否：Mozc 用户词典读取之前日文词条不会被使用 |
| mecab-ipadic-neologd | `seed/*.csv.xz` 41 MB | 无 | Android 没有 xz 解码器；322 万行；已停更 | 否 |
| mozcdic-ut | `*.txt.bz2` 0.7–12 MB | 无 | Android 没有 bzip2 解码器；日文尚未使用 | 否 |
| SCOWLv2 | `rel-<日期>.tar.gz` 2.4 MB 的源文件 | 无 | 生成词表需要 Python 与 SQLite（`make`、`scowl word-list`） | 否 |

### 下载与校验

- 只用 HTTPS，重定向也一样；明文 `http://` 地址或重定向到这样的地址一律拒绝。GitHub 的发布 API（`api.github.com`）
  给出最新发布的附件、其大小与 SHA-256 摘要；下载必须两者都相符，没有摘要的附件被拒绝。普通文件没有公布的校验值：
  词库页如实写明，只受 HTTPS 保护。
- 每个文件最多下载 64 MB，从压缩包解出最多 256 MB；大小在读取时计数，不信任声明。
- 第一次下载之前的说明写明要连接的主机（`api.github.com`、`github.com`、`release-assets.githubusercontent.com`、
  `raw.githubusercontent.com`），说明只下载词库文件、不发送任何输入内容；不接受就不联网。接受的版本记在
  `state.json`（`online_accepted`）；说明的内容有变化时换一个版本，重新询问。
- 下载显示进度，可以取消。工作文件在应用的缓存中；取消或失败（网络、大小、校验值、文件无法读取、没有可用的词、
  存储）时已安装的版本不变；结束后删除下载的文件。
- 转换出的词一次写出（从不整体保存为条目对象）：词库包的 `entries.tsv`（规范化的 TSV，表头
  `language text reading weight`）、它的 librime 词表 `table.txt` 与 `pack.json`；上一个版本移到 `packs/<id>.prev/`。
  权重按导入的规则处理（最大频率超过 100 时按对数缩放；没有频率为 50）。

```text
language	text	reading	weight
zh	你好	ni hao	90
```

`pack.json` 保存 `id`、`titles`（`en`、`zh-CN`、`zh-TW`、`ja`）、`language`、`category`、`license`、
`attribution`、`version`（发布附件的日期，或下载当天）、`checked`（是否核对了公布的校验值）、`count`、
`license_url`、`dropped`（按原因计的丢弃行数）、`notice`（THUOCL 的引用说明）以及本版本不认识的成员。缺少必填
成员的 `pack.json` 使该包不显示。

## 键盘

键盘与设置在同一进程中共用一个 `DictionaryStore`。每次修改后存储重写三份词表（词库包那一份由已启用的包的
`table.txt` 拼接，修改时不再重新解析词库包）并增加代号；键盘随即重新选择 librime
的方案，从而重新加载词表（主机测试 T17-rime）。首次解锁之前键盘不读取这些文件。敏感输入框的方案读取同样的词表；
不会有任何东西学习进这些词表。

## 测试

`FormatsTest`（读音、编码、各格式、重复、冲突、权重、预览、导出），`DictionaryStoreTest`（带版本的文件、回滚、
更新版本的文件、开关、词表、词库包及其 `pack.json`、下载说明），`ProtectionTest`（加密格式、错误的密码、被改动的
字节、更新的版本；磁盘上没有明文的词、重启后锁定、锁定、解锁、关闭保护、加密的导出），`SourcesTest`（各来源格式的极小原创样例：保留的词、
读音、权重、按原因丢弃的行；提供的来源），`DownloadsTest`（带自有证书的本地 HTTPS 服务器，经真实的传输层：发布
摘要与大小、只走 HTTPS 的重定向、缺失的文件、损坏的压缩包、取消、失败时保留已安装的版本），`DictionaryRimeTest`
与 `DictionaryPacksTest`（存储的词表与转换出的词库包在主机 librime 中按可信程度排序；`KOTOMO_PACKS=1` 时从锁定的
文件转换每个提供的来源），经 `tests/conversion/run.sh` 运行；模拟器上 `DictionaryKeyboardTest` 与
`DictionaryDownloadTest`（真实下载后在键盘上打出）。

## 已知差距

- 内置词库没有开关；没有它们键盘无法输入。
- 只有用户开启密码保护时 `user.json` 才加密（用户 2026-11-11 决定）；键盘能使用这些词时，librime 的词表总是明文。
- 日文与英文条目尚未使用。
- 只有 GitHub 的发布附件带有公布的校验值；THUOCL 与 rime-ice 的单字表只受 HTTPS 保护。
- Mozc 用户词典读取日文词条之前，不提供日文来源。
- 设备从不按固定的哈希校验词库包：来源会更新，`build-lock.json` 只锁定主机测试读取的文件。
