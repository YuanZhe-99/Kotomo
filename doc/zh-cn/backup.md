# 备份与同步

Kotomo 把用户的数据备份到用户选择的文件中，并可以按部分恢复（`PLAN.md` §11.6）。它遵循行为约定中关于版本化数据的规则：每个文件带格式版本、保留未知成员、使用 UTC 时间，恢复从不静默替换更新的数据。

## 备份包含什么

| 部分 | 备份中的文件 | 从哪里取出、经什么放回 |
|---|---|---|
| 设置 | `settings/settings.json`：设置文件的每个值及其类型 | `SettingsStore.exportRaw`、`importRaw`（替换整个文件；读取时检查每个已知键的范围） |
| 主题 | `themes/current.json`、`themes/library.json`、`themes/images/<名称>.png` | 主题文件、主题库以及它们使用的图片（`settings-format.md`） |
| 你的词库 | `dictionary/user.json`：按 `user.json` 的格式保存的列表（`dictionaries.md`） | `DictionaryStore.exportUser`、`restoreUser`：替换整个列表，每个词条按读取该文件时的规则检查；恢复前的列表成为上一个版本，因此“回滚”可以撤销。受密码保护的词库锁定时不包含；解锁时其词条进入备份，只有备份设了密码才加密 |
| 学到的读法 | `learned/learned.json`，混合模式学习数据的明文导出 | `MixedLearning.export`、`import`：合并，所以备份之后在本机学到的读法会保留 |
| 键盘记忆 | `memory/memory.json`：按 App 的模式与字形、Emoji 历史、单手宽度 | `MemoryFormat` |

剪贴板历史从不进入备份：它用从不离开设备的密钥加密，也是 Kotomo 保存的最敏感的数据（`PLAN.md` §14）。下载的词库与翻译模型也不在其中，它们可以重新下载。

## 文件

备份（`.kbak`）是一个 ZIP，包含 `manifest.json` 与各部分的文件。设了密码时，它用与受保护词库相同的容器封装（AES-256-GCM，PBKDF2-HMAC-SHA256 600,000 轮，ADR-012），头部把内容标为 `backup`；密码不保存，也无法找回。

| 清单成员 | 类型 | 含义 |
|---|---|---|
| `format_version` | int | 格式版本；本版本写 1，拒绝更新的版本 |
| `created_utc_ms` | int | 备份的时间，UTC 毫秒 |
| `app_version` | string | 制作它的 Kotomo 版本 |
| `modules` | object | 按部分（`settings`、`themes`、`dictionary`、`learned`、`memory`）：`updated_utc_ms`（该数据在设备上最后改变的时间）、`files`、`bytes` |

读取备份是信任边界：解包前后至多 64 MB、至多 2,000 个文件，条目名只能是 `<部分>/<名称>` 或 `<部分>/<文件夹>/<名称>` 且不含 `..`，每个部分放回时再由其所有者检查一次。

## 恢复

1. 用户选择文件；加密的文件会要求输入密码。
2. 预览显示它的制作时间与版本，以及每个部分的大小。在本机上比备份更晚改变的部分标为"本机数据更新"，默认不勾选，所以除非用户勾选，较新的数据不会被替换。
3. 在任何东西改变之前，要恢复的各部分的当前数据保存为 `files/backup/before-restore.kbak`；"撤销上次恢复"会把它放回。
4. 恢复先关闭自动同步，这样在用户查看之前，恢复的数据不会被发送或替换。

## 自动备份

设置 → 备份与同步 → 自动备份 每天或每周把所有可读部分的备份写入用户选择的文件夹（用户授权的文档树），并保留最新的 `kotomo-auto-*` 文件（默认五份），更早的被删除。自动备份不加密，因为无法为它保存密码；页面上写明了这一点。配置是 `files/backup/config.json`：

| 成员 | 类型 | 含义 |
|---|---|---|
| `format_version` | int | 格式版本；本版本写 1 |
| `folder` | string | 授权文件夹的 `content://` 地址 |
| `interval_days` | int：0、1、7 | 0 为关闭 |
| `keep` | int，1–30 | 保留的自动备份数 |
| `last_utc_ms` | int | 上一次的时间 |

JobScheduler 任务在电量不低时运行它。任务不跨重启保留（那需要 Kotomo 没有声明的开机权限）：键盘与设置应用启动时重新安排。页面列出文件夹中的备份，最新的在前，每个都可以恢复。

未做：增量备份（每次备份都是完整的；各部分都是小的 JSON 文件与图片）。
