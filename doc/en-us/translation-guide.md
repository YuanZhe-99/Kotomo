# Translation guide

## 1. Scope

`doc/en-us/` is authoritative. `doc/zh-cn/` mirrors it file for file; every documentation change
updates both in the same commit. Interface strings live in the Android string resources (English,
Simplified Chinese, Traditional Chinese and Japanese), not here. `PLAN.md` is written in Chinese
and is not mirrored.

## 2. Structural parity

Both trees contain the same files with the same heading levels, table rows and code blocks; code
blocks are byte-identical. `java tool/CheckDocs.java` enforces this and checks local links.

## 3. Never translated

Code, commands, paths, configuration keys and identifiers; phase, gate, spike and test IDs (`P0`,
`G1`, `S3`, `T01`); status values (`PASS`, `FAIL`, `BLOCKED`, `NOT_RUN`); version numbers; API,
format and data-set names (JNI, NDK, `InputMethodService`, `EditorInfo`, `InputConnection`,
AICore, ML Kit, Gemini Nano, Unicode, CLDR, JMdict, Unihan, WebDAV, JSON). Product, project and
company names stay as their owners write them (Kotomo, librime, Rime, Mozc, OpenCC, Gboard,
SwiftKey); a product with an established Chinese name uses it in Chinese text (玛嗒玛嗒, 微软拼音,
搜狗输入法).

## 4. Style

Plain, short sentences. Chinese text uses full-width punctuation, with a space between Chinese and
Latin words only where it helps readability. The input modes are 中文, 英文, 日文 and 混合.

## 5. Glossary

| English | 简体中文 |
|---|---|
| input method (IME) | 输入法（IME） |
| input mode | 输入模式 |
| Mixed mode | 混合模式 |
| PC layout / PC keys | PC 布局 / PC 按键 |
| modifier key (latched, locked, held) | 修饰键（锁住下一键、锁定、按住） |
| host system (the computer typed to) | 主机系统（要输入的电脑） |
| smart quotes | 智能引号 |
| auto-pairing (brackets) | 成对补全（括号） |
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
| dictionary pack | 词库包 |
| catalog (online dictionaries) | 目录 |
| import preview | 导入预览 |
| rollback | 回滚 |
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
| lattice | 候选网格 |
| edge (of the lattice) | 边 |
| proposer | 提议者 |
| decoder | 解码器 |
| finishing (per target language) | 收尾 |
| target language | 目标语言 |
| cross-language | 跨语言 |
| snapshot | 快照 |
| generation (of a snapshot) | 代号 |
| stage (of a snapshot) | 阶段 |
| piece (of a candidate) | 片段 |
| pinned span | 固定片段 |
| boundary hint | 边界提示 |
| particle | 助词 |
| engine ↔ app contract | 引擎 ↔ 应用契约 |
| facade | 外观类 |
| line service | 行服务 |
| function table (of an engine library) | 函数表 |
| request (of the contract) | 请求 |
| session (of the coordinator) | 会话 |
| compiled lexicon | 编译后的词库 |
| learned reading | 已学习的读法 |
| rank lower (a learned reading) | 降低排序 |
| key correction | 按键纠错 |
| refine (request of the contract) | 细化 |
| later stage (of a snapshot) | 后一阶段 |
| physical (hardware) keyboard | 外接物理键盘 |
| handwriting; writing area | 手写；书写区域 |
| stylus handwriting (write in any field) | 手写笔书写（在任意输入框书写） |
| voice typing | 语音输入 |
| on-device speech recognition | 设备端语音识别 |

## 6. Review checklist

- Same files, heading levels, tables and code blocks in both trees.
- Glossary terms used consistently; new terms added to this glossary in both trees.
- Links point to the same documents in each tree.
