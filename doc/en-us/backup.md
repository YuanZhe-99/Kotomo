# Backup and sync

Kotomo backs up the person's data to a file they choose and can restore it part by part
(`PLAN.md` §11.6). It follows the behavior contract's rules for versioned data: every file carries a
format version, keeps unknown members, uses UTC times, and a restore never silently replaces newer
data.

## What a backup holds

| Part | Files in the backup | Taken from and put back through |
|---|---|---|
| Settings | `settings/settings.json`: every value of the settings file, with its type | `SettingsStore.exportRaw`, `importRaw` (replaces the file; every known key is range-checked when read) |
| Themes | `themes/current.json`, `themes/library.json`, `themes/images/<name>.png` | the theme file, the theme library and the pictures they use (`settings-format.md`) |
| Your dictionary | `dictionary/user.json`: the list as `user.json` stores it (`dictionaries.md`) | `DictionaryStore.exportUser`, `restoreUser`: the list is replaced and every entry checked as when the file is read; the list before the restore becomes the previous version, so Roll back undoes it. Left out while a password-protected dictionary is locked; while it is unlocked its words go into the backup, encrypted only when the backup has a password |
| Learned readings | `learned/learned.json`, the plain export of Mixed mode's learning | `MixedLearning.export`, `import`: merged, so a reading learned on this device since the backup is kept |
| Keyboard memory | `memory/memory.json`: per-app modes and scripts, emoji history, one-handed widths | `MemoryFormat` |

The clipboard history is never in a backup: it is encrypted with a key that never leaves the
device and is the most sensitive data Kotomo keeps (`PLAN.md` §14). Downloaded dictionaries and
translation models are not in it either; they can be downloaded again.

## The file

A backup (`.kbak`) is a ZIP of `manifest.json` and the parts' files. With a password it is sealed
in the same container as a protected dictionary (AES-256-GCM, PBKDF2-HMAC-SHA256 with 600,000
rounds, ADR-012), its header naming the content `backup`; the password is not kept and cannot be
recovered.

| Manifest member | Type | Meaning |
|---|---|---|
| `format_version` | int | The format; this build writes 1 and refuses a newer one |
| `created_utc_ms` | int | When the backup was made, UTC milliseconds |
| `app_version` | string | The Kotomo version that made it |
| `modules` | object | By part (`settings`, `themes`, `dictionary`, `learned`, `memory`): `updated_utc_ms` (when that data last changed on the device), `files`, `bytes` |

Reading a backup is a trust boundary: at most 64 MB before and after unpacking and 2,000 files,
entry names only of the form `<part>/<name>` or `<part>/<folder>/<name>` without `..`, and each
part checked again by its owner when it is put back.

## Restoring

1. The person picks a file; a sealed one asks for its password.
2. The preview shows when and by which version it was made and, per part, its size. A part whose
   data on this device changed after the backup's is marked "This device has newer data" and starts
   unticked, so nothing newer is replaced unless the person ticks it.
3. Before anything changes, the current data of the parts being restored is saved as
   `files/backup/before-restore.kbak`; "Undo the last restore" puts it back.
4. Restoring turns automatic sync off first, so restored data is neither sent nor replaced before
   the person looks at it.

## Automatic backups

Settings → Backup and sync → Automatic backups writes a backup of every readable part into a folder
the person chose (a document tree they granted), daily or weekly, and keeps the newest
`kotomo-auto-*` files (five by default); the older ones are deleted. Automatic backups are not
encrypted, because no password can be kept for them; the page says so. The config is
`files/backup/config.json`:

| Member | Type | Meaning |
|---|---|---|
| `format_version` | int | The format; this build writes 1 |
| `folder` | string | The granted folder's `content://` address |
| `interval_days` | int: 0, 1, 7 | 0 is off |
| `keep` | int, 1–30 | Automatic backups kept |
| `last_utc_ms` | int | When the last one was made |

A JobScheduler job runs it with the battery not low. The job is not persisted across reboots (that
needs a boot permission Kotomo does not declare): the keyboard and the settings app schedule it
again when they start. The page lists the backups in the folder, newest first, each with Restore.

Not built: incremental backups (each backup is whole; the parts are small JSON files and pictures).

## Sync

Sync (`PLAN.md` §11.7, `backup/Sync.kt`) keeps a copy of chosen parts at one place and brings in
the changes other devices made there. It follows MyApps-DATA's invariants, rewritten in Kotlin.

| Rule | How it is built |
|---|---|
| Off by default | `kind` is `off`; nothing connects until the person chooses a place and taps Sync now or turns on the daily sync |
| Places | A folder the person chose (a document tree another app may sync), or a WebDAV folder over HTTPS that already exists; only GET, PUT and DELETE are used |
| Only a confirmed server | Before the first WebDAV connection a notice names the host; requests go only to that host, and redirects are not followed |
| What is synced | Settings, themes and the dictionary by default; learned readings and the keyboard memory (history data) only when ticked; the clipboard never |
| One file per part | `kotomo-sync-<part>.kbak`, the backup container without a password, holding that part alone |
| Conflicts never settle by themselves | Per part, the state keeps this device's data time and the hash of the copy at the last sync. Only this device changed: send; only the copy changed: receive (through the restore, so Undo on the page takes it back); both changed, or both hold data at the first sync: a conflict, listed with "Keep this device's" and "Use the synced copy" |
| Lock and retry | `kotomo-sync-lock.json` names the device and an expiry five minutes ahead; a device waits 2, 4 and 8 s for another's lock, then reports busy; the lock is deleted when the sync ends. A lock is best effort: neither a folder nor plain WebDAV offers an atomic create |
| Restore pauses sync | A restore turns the daily sync off first |
| UTC and unknown members | Every file stores UTC milliseconds and keeps members a newer version wrote |
| The password | Kept only on the device, AES-GCM with a Keystore key in `files/sync/password.kenc`; never synced or backed up |

`files/sync/config.json`: `format_version` (1), `kind` (`off`, `folder`, `webdav`), `folder`
(`content://` address), `url` (`https://` only), `user`, `confirmed_host`, `modules` (part
names), `auto`, `device` (a random id for the lock). `files/sync/state.json`: `modules` (per part
`local_utc_ms` and `remote_sha256`), `conflicts`, `last_utc_ms`. The daily sync is a JobScheduler
job that needs a network for WebDAV and leaves conflicts for the person.
