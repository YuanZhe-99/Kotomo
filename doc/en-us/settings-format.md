# Settings format

The settings the person changes in the settings app live in one Preferences DataStore file,
`files/datastore/settings.preferences_pb` in the app's credential-protected storage. The settings
app and the keyboard run in the same process and share one `SettingsStore`
(`app/src/main/java/com/yuanzhe/kotomo/settings/SettingsStore.kt`).

## Reading and writing

- `SettingsStore.flow` is a `StateFlow<Settings>` that emits whenever the file changes; it starts
  with the defaults and holds the file's values a few milliseconds after `SettingsStore.get`.
- `SettingsStore.current` returns the latest value without I/O, so the keyboard may read it on the
  main thread and on the key path.
- `SettingsStore.set` (fire and forget, kept in call order) and `SettingsStore.update` (suspends
  until durable) apply a `copy` of `Settings` and write only the keys whose value changed.
- A corrupt file is replaced by an empty one, which reads as the defaults.

## Versioning and compatibility

| Key | Type | Meaning |
|---|---|---|
| `format_version` | int | Format version that last wrote the file; this build writes `1` and never lowers a newer value |
| `updated_utc_ms` | long | Time of the last change, UTC milliseconds since the epoch |

- A missing file, or a missing key, reads as the default; the file is created on the first change.
  Moving from "no file" to version 1 therefore needs no migration step.
- Keys this version does not know are never removed.
- A value this version does not understand (an unknown enum name, a number out of range) reads as
  the default and stays in the file until the person changes that setting, so a newer version's
  value survives a run of an older version.
- Enum values are stored as the lowercase constant name (`balanced`, `left`); renaming a constant
  is a format change.

## Keys

| Key | Type | Default | Setting |
|---|---|---|---|
| `default_mode` | string | `mixed` | Mode the keyboard starts in: `mixed`, `chinese`, `japanese`, `english` |
| `mixed.level` | string | `balanced` | Mixed-mode level: `conservative`, `balanced`, `aggressive` |
| `mixed.weight_chinese`, `mixed.weight_english`, `mixed.weight_japanese`, `mixed.weight_emoji` | int 0–100 | 70, 60, 60, 30 | Mixed-mode candidate weight per language |
| `mixed.space` | string | `separate` | Space while composing in Mixed mode: `separate` (insert a break) or `commit_first` |
| `mixed.cross.*`, `japanese.cross.*` | boolean | `true` | Cross-language switches of Mixed and Japanese mode: `pinyin_to_kanji`, `pinyin_to_katakana`, `english_to_kanji`, `english_to_katakana`, `whole_sentence`, `readings`, `emoji` |
| `chinese.script` | string | `simplified` | Chinese output: `simplified`, `traditional` |
| `english.suggestions` | boolean | `true` | English corrections and predictions in the candidate bar |
| `english.auto_capitalize` | boolean | `true` | Capital letter at the start of a sentence |
| `layout.split` | string | `auto` | Split keyboard: `auto` (by window width), `always`, `never` |
| `layout.thumb_zone_dp` | int 200–340 | 268 | Width of one split half in dp |
| `layout.tabletop_lower_half` | boolean | `true` | Move the keyboard below the hinge when half-folded |
| `layout.one_handed` | string | `off` | One-handed mode: `off`, `left`, `right` |
| `layout.height_percent` | int 80–120 | 100 | Keyboard height in percent of the designed height |
| `keys.long_press_symbols` | boolean | `true` | Long-press a key for its symbol |
| `keys.corner_hints` | boolean | `true` | Show the long-press symbol in the key's corner |
| `keys.haptics` | boolean | `true` | Vibrate on key press |
| `privacy.incognito` | boolean | `false` | Incognito mode: no learning, history or clipboard recording |
| `clipboard.history` | boolean | `true` | Keep copied text in the clipboard history ([clipboard.md](clipboard.md)) |
| `clipboard.retention_days` | int 1–90 | 7 | Days unpinned clipboard items are kept (the Privacy page offers 1, 7 and 30) |
| `clipboard.quick_paste` | boolean | `true` | Offer what was just copied, and codes in it, above the keys |

## Privacy

The file holds only choices made in the settings app: no typed text, candidates or clipboard
content. The manifest disables Android backup (`allowBackup="false"`), so the file stays on the
device.
