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
| 下载（`download`） | 从在线目录安装的词库包 | `kotomo_packs.txt` | 0 | 下载的词库，以及每个包一个开关 |

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
| `files/dictionaries/state.json` | 开关 |
| `files/dictionaries/packs/<id>/` | 已安装的词库包：`entries.tsv`（规范化的）与 `pack.json`（它在目录中的条目） |
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

## 词库包与在线目录

目录是 JSON，`format_version` 为 1（其他版本一律拒绝），含 `packs`；不认识的成员保留。词库包的每个成员都是必填项；
缺少任何一个或值无效的包被拒绝并计数：

| 成员 | 检查 |
|---|---|
| `id` | 小写字母、数字、`_` 与 `-`，最多 64 个（它是目录名） |
| `titles` | 界面语言（`en`、`zh-CN`、`zh-TW`、`ja`）到标题的对象 |
| `language`、`category`、`license`、`attribution`、`version` | 非空；`language` 为 `zh`、`ja` 或 `en` |
| `size`、`sha256` | 压缩文件的字节数（最多 64 MB）及其小写十六进制 SHA-256 |
| `url` | HTTPS |
| `format` | `tsv.gz` |

词库包是带表头、读音已规范化、权重 0–100 的 gzip 压缩 TSV，由词库包生成器（Q6）写出：

```text
language	text	reading	weight
zh	你好	ni hao	90
```

下载最多读取目录给出的大小并报告进度，大小或 SHA-256 不符即拒绝；之后解压（最多 256 MB），按该包的语言像导入的
TSV 一样读取，未通过检查或语言不同的行丢弃并计数，安装时保留上一个版本。尚不校验签名（PLAN §2.5 决定 9）。

在线词库尚未提供：目录托管在哪里、第一次下载如何告知，等待用户决定（PLAN §2.5 决定 2 与 10）。`Online.CATALOG_URL`
为 null，应用没有自己的联网传输层（`Transport` 是测试实现的接口），词库页说明不会下载任何东西。

## 键盘

键盘与设置在同一进程中共用一个 `DictionaryStore`。每次修改后存储重写三份词表并增加代号；键盘随即重新选择 librime
的方案，从而重新加载词表（主机测试 T17-rime）。首次解锁之前键盘不读取这些文件。敏感输入框的方案读取同样的词表；
不会有任何东西学习进这些词表。

## 测试

`FormatsTest`（读音、编码、各格式、重复、冲突、权重、预览、导出），`DictionaryStoreTest`（带版本的文件、回滚、
更新版本的文件、开关、词表、词库包），`OnlineTest`（目录、用按完整地址应答的假传输层校验下载、
`app/src/test/resources/dictionary/` 中的原创样例包，每种语言十来个词），`DictionaryRimeTest`（把存储写出的词表喂给主机上的
librime，经 `tests/conversion/run.sh` 运行）。

## 已知差距

- 内置词库没有开关；没有它们键盘无法输入。
- `user.json` 没有加密（PLAN §14 要求个人数据加密存储）；librime 读取的词表本来就是明文。
- 日文与英文条目尚未使用。
- 在用户决定托管方式与首次使用的告知之前，没有在线下载。
