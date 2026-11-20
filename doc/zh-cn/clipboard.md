# 剪贴板

Kotomo 自己保存剪贴板历史：Android 13 起系统最迟在最后一次访问后 60 分钟清除系统剪贴板，而且只保留一条
内容。代码位于 `app/src/main/java/com/yuanzhe/kotomo/ime/clipboard/`：`ClipboardHistory.kt`（记录、存储、
分类）、`VerificationCodes.kt`（验证码识别）、`ClipboardPanel.kt`（在键盘位置显示的面板）和
`QuickPaste.kt`（键盘上方的快捷输入条）；设置应用中的历史页是 `settings/ClipboardPage.kt`。

## 记录什么

键盘通过 `ClipboardManager.OnPrimaryClipChangedListener` 监听（Android 10 起只有默认输入法和焦点应用能读取
剪贴板），把每条主剪贴内容交给 `ClipboardHistory.onPrimaryClip`，并传入
`allowed = ClipboardHistory.allowedIn(field, settings)`。

| 情况 | 历史 | 快捷输入条 |
|---|---|---|
| 无痕模式 | 不记录 | 不提供 |
| 密码类输入框（`TYPE_TEXT_VARIATION_PASSWORD`、`…_VISIBLE_PASSWORD`、`…_WEB_PASSWORD`、`TYPE_NUMBER_VARIATION_PASSWORD`）和带 `IME_FLAG_NO_PERSONALIZED_LEARNING` 的输入框 | 不记录 | 不提供 |
| 用户排除的应用（`clipboard.excluded_apps`）中的输入框 | 不记录 | 不提供 |
| 标记为 `ClipDescription.EXTRA_IS_SENSITIVE` 的剪贴（`android.content.extra.IS_SENSITIVE`，Android 13 之前密码管理器也会设置） | 永不记录 | 永不提供 |
| 关闭了历史（`clipboard.history`） | 不记录 | 提供（只在内存中） |
| 关闭了快捷输入（`clipboard.quick_paste`） | 记录 | 不提供 |
| 其他情况（包括网址与电子邮件输入框） | 记录 | 提供 |

- 起决定作用的是键盘看到这条剪贴时所附着的输入框：剪贴板监听器触发时的当前输入框，或键盘下一次显示时所在的输入框
  （这时它会再检查一次剪贴板）。文字是从哪个应用复制的并不起作用。因此，从没有把它标记为敏感的应用里复制的密码，
  若键盘是在普通输入框里看到它的，会像其他文字一样被记录。同样的原因，排除的应用指正在打字的应用，而不是文字的来源：
  剪贴板面板的"此应用不保留"加入当前应用，设置中的历史页列出被排除的应用并可移除。
- 键盘在不允许记录时（敏感输入框、无痕模式）看到的剪贴会一直被拒绝：键盘下一次显示在普通输入框、而这条剪贴仍在
  剪贴板上时，既不记录也不提供。只记住这条剪贴的时间戳（在内存中，并只把这个数字写入 `files/clipboard/refused`），
  从不读取它的文字，所以键盘进程重启后这次拒绝仍然有效。仅仅关闭了历史不算拒绝。
- 关闭历史不会删除任何东西：已保存的内容保留到过期或用户删除为止，开关的说明里写明了这一点。
- 对文本剪贴，只取第一项的纯文本；空白文本与超过 20,000 个字符的文本不记录。
- 对图片剪贴（`image/png`、`image/jpeg`、`image/webp`、`image/gif`），在允许记录时于历史后台线程
  （`Dispatchers.IO`）读取第一项的 `content://` URI：超过 10 MB 的文件忽略，任意一边超过 4,096 像素的
  静态图片按比例缩放到 2,048 像素以内并转为 PNG。Intent 及其他 URI 会被忽略。
- 剪贴的时间取剪贴板自己的时间戳（`ClipDescription.getTimestamp`，UTC 毫秒），因此键盘打开时再次读到同一条
  剪贴不会改变任何东西。
- 复制历史中已有的文本或图片（按精确文本或 SHA-256 摘要）会把它移到最前，并保留其固定状态。
- 来自其他设备的剪贴（`EXTRA_IS_REMOTE_DEVICE`）与本机剪贴同样处理。

## 固定、快捷短语、过期与数量上限

- 固定的条目和快捷短语一直保留，直到用户删除。
- 其他条目在保留期（`clipboard.retention_days`，默认 7 天，1–90）后过期，总计最多保留最新的 200 条，
  其中未固定图片最多保留 30 张。读取文件时、每次修改时、历史关闭期间有内容被复制时，以及调用
  `ClipboardHistory.expire()` 时执行过期；键盘在打开面板时调用它。
  读取时若发现过期条目，会立即把文件重写为不含它们的版本，过期的文字与已被移除的图片文件不会留在磁盘上；
  一条不剩时删除文件及 `images/` 目录。键盘进程
  存活期间，历史还会在最旧的未固定条目到期时安排一次检查，过期的文字和图片无需等键盘被使用就会清除；进程结束后由下一次
  读取完成。
- `ClipboardHistory.clearUnpinned()` 删除所有既未固定也不是短语的条目（隐私页的清除按钮）并清理对应的加密图片文件。

## 历史页

设置 → 隐私 → 查看、搜索与编辑历史，按从新到旧列出条目：

- 搜索把列表缩小到文字中含有查询词的条目（不区分大小写）；分类为全部、已固定、快捷短语、验证码、链接、电话号码、
  地址、图片与文字（`ClipFilter`）。
- 点一个文字条目可以编辑：文字（随修改重新分类；改成另一个条目的文字时两者合并，保留任一方的固定与短语标记）、固定、
  是否为快捷短语；点一个图片条目可以预览大图并切换固定或删除。
- 全选显示的项，或逐个勾选，然后一次删除。
- 添加快捷短语：一直保留到删除，在键盘面板中与固定的条目一起显示。
- 不保留剪贴的应用，每个都可移除。

## 分类

`ClipKind.of` 用本地规则按以下顺序为文本剪贴分类：单独的验证码（4–8 位数字、`123-456`，或 4–8 位大写字母与
数字且至少两位数字）、链接（只有一个网址）、电话号码（7–15 位数字，带 `+`、分隔符，或九位及以上数字）、
最可能的验证码得分不低于 70 的、不超过 160 个字符的消息、地址（日文的都道府县与市区町村、丁目与番地；中文的
省、市、区，或路名加门牌号；英文的门牌号加街道词；不含句末标点），其余为普通文本。图片剪贴在调用
`ClipKind.of` 之前直接归为 `ClipKind.IMAGE`。快捷短语无论类别都显示短语标签。

## 验证码

`VerificationCodes.find` 返回文本中所有可能的验证码，最可能的在前，每个都带分数和前后各约 12 个字符的上下文，
截断处用"…"。多个都可能时全部显示，并把第一个标为更可能（PLAN §10.7）。

- 不含验证码关键词的文本不返回任何结果，除非整段文本就是验证码。关键词包括 code、verification、OTP、
  passcode、PIN、one-time、2FA；验证码、驗證碼、校验码、动态码、动态密码、确认码、安全码、认证码、口令；
  認証コード、確認コード、認証番号、暗証番号、ワンタイム、パスコード。英文关键词只要求前后不是字母，因此
  `您的OTP为…` 也能识别。
- 候选：一组 4–8 位数字；用空格或连字符连接的两组等长的 3 位或 4 位数字（输入时合并）；4–8 位大写字母与
  数字且至少两位数字。全角数字和字母按 ASCII 读取；全角标点保持不变。
- 不是验证码：用 `.`、`,`、`:` 或 `/` 连接的数字组（金额、时间、日期），三组及以上或九位及以上数字（电话号码、
  日期、编号），前面有 `+`、货币符号或货币代码，后面有单位（年、月、日、分、元、円、%、yen、dollars 等）。
- 分数（点）：六位数字 50，四位 40，五、七、八位 35，字母数字混合 30；12 个字符内有关键词加 40，40 个字符内
  加 20；前面是账户尾号（尾号、ending in）减 30，订单词（订单、order、#）减 50，电话词减 40，1900–2099 之间的
  年份减 15。低于 30 点的丢弃；单独的验证码为 100 点。

## 快捷输入条

`QuickPaste` 在复制后 60 秒内提供刚复制的内容，每条剪贴只提供一次：输入它、关闭输入条或复制了更新的内容都会
撤下，同一条剪贴（相同文本或图片摘要与时间）不会再次提供。对文本，输入条为每个验证码显示一个卡片，最可能的高亮，然后是复制的
文本；在数字输入框中，或整条剪贴就是验证码时，只显示验证码。对图片，仅当当前焦点输入框声明支持兼容的图片 MIME 类型
（`EditorInfoCompat.getContentMimeTypes`）且非数字输入框时显示图片缩略图卡片；点按时通过临时 `FileProvider` URI
（`${applicationId}.clipboard.fileprovider`）经由 `InputConnectionCompat.commitContent` 插入。输入条的内容从不写入磁盘。验证码只来自复制的内容：键盘从不读取通知（用户 2026-11-13 决定不做，因为 Android 15 起系统对不受信任的通知监听服务隐去一次性验证码，见 `upstream.md`）。`ClipboardAppTest.aCopiedCodeIsOfferedAndTyped`（CLIP-27）在模拟器上核对文字验证码：复制的短信文字里的验证码会被提供并可输入，标记为敏感的同一段文字什么都不提供；`ClipboardAppTest.aCopiedImageIsSavedAndCommitted`（CLIP-33）核对加密图片记录与 `commitContent` 发送。

## 存储格式

历史保存在应用凭据加密存储区内的 `files/clipboard/history.kcb`。Android 备份与设备迁移都排除它
（`data_extraction_rules.xml`，`allowBackup="false"`）。

```
offset  size  content
0       4     magic "KCLP"
4       1     container version (1)
5       12    AES-GCM IV, fresh for every write
17      n     AES-256-GCM ciphertext of the record list, 128-bit tag; AAD = bytes 0..4

record list (big-endian, java.io.DataOutputStream):
int     record count (header first, then one record per item)
per record:
  int   field count
  per field: writeUTF(key), int length, UTF-8 bytes of the value
```

- 密钥是 Android Keystore 中的 `kotomo_clipboard`（AES-256、GCM、无填充），从不离开 Keystore。
- 头部字段：`format_version`（本版本写入 `2`，从不降低更新的值）和 `updated_utc_ms`。
- 条目字段：`id`、`text`、`time_utc_ms`（UTC 毫秒）、`pinned` 与 `phrase`（`1` 或 `0`），对图片条目另有：
  `image`（`<64 位小写 hex sha256>.kci`）、`mime`（`image/png`、`image/jpeg`、`image/webp` 或 `image/gif`）、
  `width` 与 `height`（`1..4096`）。类别和验证码在读取时推导，从不存储。
- 加密图片载荷存储在 `files/clipboard/images/<sha256>.kci`，以相同方式加帧，魔数为 `"KCLI"`、容器版本字节为 `1`：
  - `0..3`：魔数 `"KCLI"`
  - `4`：容器版本（`1`）
  - `5..16`：每次写入全新生成的 12 字节 AES-GCM IV
  - `17..n`：原始图片编码字节的 AES-256-GCM 密文（128 位认证标签，AAD = 字节 `0..4`）
- 通过 `FileProvider` 粘贴图片时在 `cacheDir/clipboard_share/` 下写入的临时共享副本有数量上限（最多保留最新的 3 份），并于 5 分钟后过期清理。
- 历史加载、保存、过期或转移不可读索引时，`files/clipboard/images/` 中未被当前列表引用的 `.kci` 或 `.tmp` 文件会被删除。
- 本版本不认识的字段（无论在头部还是条目中）在重写文件时保留。更新的格式版本只增加字段；不兼容的变化提高
  容器版本。
- 容器版本更新的文件保持原样：本次运行的历史从空开始，且从不写入，因此不会覆盖更新的数据。无法解密或解析的
  文件（密钥丢失、损坏）被移到 `history.kcb.unreadable`，历史从空开始。这样的文件至多保留一个（新的会替换旧的），自它最后一次写入起超过
  保留期后删除，每次读取与每次过期时检查。Keystore 无法提供密钥时，历史只在内存中
  工作。
- 崩溃打断写入时留下的 `history.kcb.tmp` 在下一次读取历史时删除。
- 每次修改都在同一个后台线程上按提交顺序，经 `history.kcb.tmp` 和原子替换重写文件。键盘只读取内存中的快照
  （`ClipboardHistory.items`）。

## 隐私

- 复制的内容不写入日志、不发送、不用于学习；代码中没有带剪贴文本的日志调用。
- 敏感输入框、敏感剪贴与无痕模式什么都不记录（见上表）。
- 历史以 Keystore 密钥加密存储，并排除在 Android 备份之外。
