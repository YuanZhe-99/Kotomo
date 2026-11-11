# 翻译指南

## 1. 范围

`doc/en-us/` 是权威来源。`doc/zh-cn/` 逐文件镜像它；任何文档改动都在同一提交中同时更新两者。界面字符串放在
Android 字符串资源中（英文、简体中文、繁体中文和日文），不在这里。`PLAN.md` 用中文撰写，不做镜像。

## 2. 结构一致

两棵目录树包含相同的文件，标题层级、表格行数和代码块一致；代码块逐字节相同。`java tool/CheckDocs.java`
会检查这些规则，并检查本地链接。

## 3. 不翻译的内容

代码、命令、路径、配置键和标识符；阶段、关卡、技术验证和测试编号（`P0`、`G1`、`S3`、`T01`）；状态值（`PASS`、
`FAIL`、`BLOCKED`、`NOT_RUN`）；版本号；API、格式和数据集名称（JNI、NDK、`InputMethodService`、`EditorInfo`、
`InputConnection`、AICore、ML Kit、Gemini Nano、Unicode、CLDR、JMdict、Unihan、WebDAV、JSON）。产品、项目和公司
名称按其所有者的写法保留（Kotomo、librime、Rime、Mozc、OpenCC、Gboard、SwiftKey）；有通行中文名称的产品在中文文本中
使用中文名称（玛嗒玛嗒、微软拼音、搜狗输入法）。

## 4. 风格

使用简明的短句。中文使用全角标点；只在有助于阅读时在中文与拉丁字母之间加空格。输入模式称为中文、英文、日文和混合。

## 5. 术语表

| English | 简体中文 |
|---|---|
| input method (IME) | 输入法（IME） |
| input mode | 输入模式 |
| Mixed mode | 混合模式 |
| raw input (the letters typed) | 输入串 |
| composition (typed, not yet committed) | 组合（未上屏内容） |
| commit | 提交（上屏） |
| candidate | 候选 |
| candidate bar | 候选栏 |
| first candidate | 首选 |
| whole-sentence conversion | 整句转换 |
| segment (of a sentence candidate) | 片段 |
| bunsetsu | 文节 |
| reading | 读音 |
| conversion | 转换 |
| cross-language candidate | 跨语言候选 |
| cross-language lexicon | 跨语言词库 |
| candidate lattice | 候选网格（lattice） |
| decoder | 解码器 |
| language model | 语言模型 |
| fuzzy pinyin / fuzzy match | 模糊音 / 模糊匹配 |
| fuzzy-pinyin rule (one switch) | 模糊音规则 |
| deploy (librime data) | 部署 |
| prebuilt data | 预编译数据 |
| key-proximity correction | 按键纠错 |
| autocorrect | 自动纠错 |
| next-word prediction | 下一词预测 |
| dictionary (lexicon) | 词库 |
| user dictionary | 用户词库 |
| personal learning | 个人学习 |
| incognito mode | 无痕模式 |
| schema (Rime) | 方案（schema） |
| double pinyin | 双拼 |
| romaji | 罗马字 |
| conjugation | 变形 |
| honorific language (keigo) | 敬语 |
| regional vocabulary | 地区用词 |
| emoji style pack | Emoji 样式包 |
| skin tone (emoji) | 肤色 |
| Recents (emoji) | 最近使用 |
| Hepburn romaji | 平文式罗马字 |
| theme | 主题 |
| toolbar | 工具栏 |
| clipboard history | 剪贴板历史 |
| quick phrase | 快捷短语 |
| quick-paste strip | 快捷输入条 |
| verification code | 验证码 |
| pin (a clipboard item) | 固定 |
| text field | 输入框 |
| on-device AI | 设备端 AI |
| proofreading / rewriting | 校对 / 重写 |
| engine | 引擎 |
| coordinator | 协调层 |
| conversion corpus | 转换语料 |
| latency budget | 延迟预算 |
| spike | 技术验证（spike） |
| phase / gate | 阶段 / 关卡 |
| upstream | 上游 |
| submodule | 子模块 |
| patch | 补丁 |
| backup / restore / sync | 备份 / 恢复 / 同步 |
| design reference | 设计参考 |
| acknowledgements | 致谢 |
| architecture decision record (ADR) | 架构决定记录（ADR） |

## 6. 审阅清单

- 两棵目录树的文件、标题层级、表格和代码块一致。
- 术语使用与术语表一致；新术语同时加入两棵目录树中的本术语表。
- 链接在各自目录树中指向相同的文档。
