# Settings format

The settings the person changes in the settings app live in one Preferences DataStore file,
`files/datastore/settings.preferences_pb` in the app's credential-protected storage. The settings
app and the keyboard run in the same process and share one `SettingsStore`
(`app/src/main/java/com/yuanzhe/kotomo/settings/SettingsStore.kt`).

## Reading and writing

- `SettingsStore.flow` is a `StateFlow<Settings>` that emits whenever the file changes; it starts
  with the defaults and holds the file's values a few milliseconds after `SettingsStore.get`.
- `SettingsStore.loaded` is the same flow but null until the file's first read; the keyboard's
  fuzzy-pinyin deploy waits for it, so the defaults never trigger a deploy.
- `SettingsStore.current` returns the latest value without I/O, so the keyboard may read it on the
  main thread and on the key path.
- `SettingsStore.set` (fire and forget, kept in call order) and `SettingsStore.update` (suspends
  until durable) apply a `copy` of `Settings` and write only the keys whose value changed.
- A corrupt file is replaced by an empty one, which reads as the defaults.
- The interface language (Settings → Interface language, Android 13 and later) is not in this
  file: it is Android's per-app language (`LocaleManager`, the languages listed in
  `res/xml/locales_config.xml`), which Android keeps and which the system's own app-language
  setting changes too; Kotomo's backups do not carry it. The keyboard reads it each time it opens.

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

Mixed mode reads its keys on every key typed (the mixed-input coordinator gets them as options,
`engine-contract.md`). Japanese mode reads `japanese.cross.*` the same way: it asks the
coordinator for Japanese candidates only and adds the cross-language ones to Mozc's own, and with
`pinyin_to_kanji`, `english_to_kanji` and `english_to_katakana` all off it does not ask at all.
`readings` shows a cross-language Japanese candidate's reading next to it in the candidate bar.
Not read yet: `pinyin_to_katakana` in either mode (no data maps pinyin to katakana words).

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

What reads the cross-language switches is described under "Versioning and compatibility" above;
the table lists every key.

| Key | Type | Default | Setting |
|---|---|---|---|
| `default_mode` | string | `mixed` | Mode the keyboard starts in: `mixed`, `chinese`, `japanese`, `english`; a hidden mode falls back to the first one shown |
| `modes.url_english` | boolean | `false` | Web-address fields (`TYPE_TEXT_VARIATION_URI`) type directly in English; off, they convert like other fields and start in the mode last used in the app, keeping the web-address bottom row, since a browser's address bar also searches. E-mail, password and code fields are not affected |
| `modes.order` | string | `mixed,chinese,japanese,english,pc` | The language choice's order, mode names joined by commas; a missing mode joins at the end, names this version does not know are kept after the known ones |
| `modes.hidden` | string | empty | Modes the language choice does not offer, joined by commas; with all of them hidden, all are offered |
| `mixed.level` | string | `balanced` | Mixed-mode level: `conservative` doubles the cost of a cross-language word, `aggressive` halves it and lets a cross-language candidate come first; `balanced` keeps the original language first |
| `mixed.weight_chinese`, `mixed.weight_english`, `mixed.weight_japanese`, `mixed.weight_emoji` | int 0–100 | 70, 60, 60, 30 | Mixed-mode candidate weight per language. A language's candidates move up or down by how far its weight is from its default (nothing moves at the defaults); the emoji weight places the emoji, in every mode (PLAN §10.6): after the two best candidates at 100, one place later for every 20 points below |
| `mixed.space` | string | `separate` | Space while composing in Mixed mode (ADR-010): `separate` marks a boundary inside the composition and commits nothing, `commit_first` commits the first candidate, `next` shows the first candidate in the field and, pressed again, the next one (Enter then commits the one shown) |
| `mixed.enter` | string | `first` | Enter while composing in Mixed mode: `first` commits the first candidate, `shown` the letters as the field shows them |
| `japanese.space` | string | `next` | Space while composing in Japanese mode: `next` or `commit_first`, as for `mixed.space`; any other value reads as the default |
| `japanese.enter` | string | `shown` | Enter while composing in Japanese mode: `shown` commits the kana as typed (the conversion once a segment was chosen), `first` the first candidate |
| `chinese.space` | string | `commit_first` | Space while composing in Chinese mode: `commit_first` or `next`; any other value reads as the default |
| `chinese.enter` | string | `shown` | Enter while composing in Chinese mode: `shown` commits the letters as typed, `first` the first candidate |
| `mixed.cross.*`, `japanese.cross.*` | boolean | `true` | Cross-language switches of Mixed and Japanese mode: `pinyin_to_kanji` (a Japanese kanji word typed by its Chinese reading), `pinyin_to_katakana`, `english_to_kanji` (an English word as the Japanese word that means it), `english_to_katakana` (as its loanword), `whole_sentence` (off: a cross-language word is offered only when it is the whole composition), `readings`, `emoji` |
| `chinese.script` | string | `simplified` | Chinese output: `simplified` (Mainland), `traditional` (OpenCC standard), `taiwan`, `hong_kong` (with regional phrases); the quick panel also remembers a choice per app in the keyboard's memory (below) |
| `chinese.fuzzy.<rule>` | boolean | `false` | One switch per fuzzy-pinyin rule (PLAN §9.6): `z_zh`, `c_ch`, `s_sh`, `n_l`, `f_h`, `l_r`, `an_ang`, `en_eng`, `in_ing`, `ian_iang`, `uan_uang`; a key is written once its switch has been changed. The keyboard turns the rules that are on into librime's `kotomo_fuzzy.yaml` and deploys it ([upstream.md](upstream.md)) |
| `english.suggestions` | boolean | `true` | English corrections and predictions in the candidate bar |
| `english.auto_capitalize` | boolean | `true` | Capital letter at the start of a sentence |
| `layout.split` | string | `auto` | Split keyboard: `auto` (by window width), `always`, `never` |
| `layout.thumb_zone_dp` | int 200–340 | 268 | Width of one split half in dp |
| `layout.tabletop_lower_half` | boolean | `true` | Move the keyboard below the hinge when half-folded |
| `layout.one_handed` | string | `off` | One-handed mode as builds before 2026-11-11 kept it, for every screen: `off`, `left`, `right`. Now read only for a screen and orientation that has no `one_handed` entry in the keyboard memory (below), never written |
| `layout.height_percent` | int 80–120 | 100 | Keyboard height in percent of the designed height |
| `keys.long_press_symbols` | boolean | `true` | Long-press a key for its symbol |
| `keys.shift_separator` | boolean | `true` | While Chinese or Mixed mode composes pinyin, the Shift key types the syllable separator `'` (labeled 分词) and is Shift again once the composition ends; the 9-key page keeps its own separator key |
| `keys.backspace_restores_commit` | boolean | `false` | Backspace right after a Mixed-mode commit brings the composition back, with the pieces chosen in it (PLAN §5.6); off, it deletes one character as anywhere else. Chinese, English and Japanese mode always delete. Not the undo of an automatic replacement, which this switch does not touch |
| `keys.corner_hints` | boolean | `true` | Show the long-press symbol in the key's corner |
| `keys.haptics` | boolean | `true` | Vibrate on key press |
| `keys.sound` | boolean | `false` | The system's key click on key press |
| `gestures.space_cursor`, `gestures.delete_swipe`, `gestures.double_space_period`, `gestures.space_language`, `gestures.smart_space` | boolean | `true` | One switch per gesture: slide on the space bar to move the cursor, slide left from delete to delete words, two quick spaces type a full stop, long-press space for the language choice, smart space around punctuation after a suggested English word |
| `punctuation.smart_quotes`, `punctuation.auto_pair` | boolean | `false` | Smart punctuation (PLAN §10.8): the `"` and `'` keys type curly quotes and apostrophes from the character before the cursor; an opening bracket or quote types its closing one too, typing that closing mark steps over it and backspace right away deletes the empty pair. Neither applies in code, URL, e-mail or direct-input fields |
| `privacy.incognito` | boolean | `false` | Incognito mode: no learning, history or clipboard recording |
| `privacy.learned_encrypted` | boolean | `false` | Keeps Mixed mode's learned readings in an encrypted file (below, ADR-013) |
| `clipboard.history` | boolean | `true` | Keep copied text in the clipboard history ([clipboard.md](clipboard.md)) |
| `clipboard.retention_days` | int 1–90 | 7 | Days unpinned clipboard items are kept (the Privacy page offers 1, 7 and 30) |
| `clipboard.quick_paste` | boolean | `true` | Offer what was just copied, and codes in it, above the keys |
| `clipboard.excluded_apps` | string | empty | Package names, joined by commas, of the apps in whose fields nothing is recorded or offered ([clipboard.md](clipboard.md)); entries that are not package names are dropped when read |
| `theme.choice` | string: `system`, `light`, `dark`, `dynamic` | `system` | The keyboard's colors; `dynamic` is Material You (Android 12 and later). Read only until the theme file exists (below), never written after that |
| `theme.key_radius_dp` | int, 0–24 | `12` | Key corner radius in dp; read only until the theme file exists |
| `ai.enabled` | boolean | `false` | Let the AI button use writing help ([ai.md](ai.md)) |
| `pc.enabled` | boolean | `false` | Offer the PC layout (PLAN §10.11) in the language choice; it is the PC row's show/hide switch on the Input modes page, so the layout stays hidden until turned on. `modes.order` and `modes.hidden` list `pc` like the other modes |
| `pc.host` | string: `windows`, `macos`, `linux` | `windows` | The computer the PC layout types to: the modifiers' names and order, the Fn page's shortcuts and the security combination (Ctrl+Alt+Del, ⌥⌘Esc on macOS); the key codes sent are the same |
| `pc.wide` | string: `full`, `split` | `full` | The PC layout on a wide screen: the function strip and the whole PC keyboard, or the phone pages as two halves at the sides |
| `toolbar.order` | string | `clipboard,ai,translate,settings,edit,emoji,language,dictionaries,themes,floating,undo,redo,handwriting,voice` | The toolbar's tools after More (PLAN §10.10), tool names joined by commas; More is always first and never listed. A missing tool joins at the end, names this version does not know are kept after the known ones |
| `toolbar.inline_autofill` | boolean | `true` | Show an autofill service's inline suggestions over the idle toolbar (Android 11 and later, PLAN §10.10); off: Kotomo asks for none |
| `keys.gap_dp` | int 2–12 | `6` | The gap between keys, in dp (PLAN §10.9) |
| `keys.text_percent` | int 80–140 | `100` | Key label size, in percent of the design's |
| `keys.long_press_custom` | string | empty | The person's long-press symbols: lines `letter=symbols` (a–z); lines that are not one are dropped when read |
| `layout.number_row` | boolean | `false` | A row of digits above the letters |
| `layout.space_percent` | int 60–140 | `100` | The space bar's width, in percent of the design's |
| `layout.mirrored` | boolean | `false` | The function keys on the other side: the bottom row and the ⇧ … ⌫ row mirrored |
| `layout.floating` | boolean | `false` | The keyboard floats over the app and can be dragged; never split |
| `chinese.idioms` | boolean | `true` | After a Chinese commit that starts an idiom, offer the rest (PLAN §10.8); the idioms come from the downloaded Idioms dictionary (`zh-thuocl-idioms`), so nothing is offered without it |
| `chinese.quick_input` | boolean | `true` | `v` at the start of a Chinese composition opens the quick input: numbers, capital money, dates, times, Unicode, sums, the person's replacements (PLAN §10.4) |
| `chinese.quick_custom` | string | empty | The person's quick-input replacements, `abbreviation=text` per line (lowercase letters and digits, at most 16); other lines are dropped |
| `chinese.stroke_lookup` | boolean | `true` | `u` at the start of a Chinese composition looks characters up by strokes (luna_pinyin's stroke reverse lookup) |
| `chinese.double_pinyin` | string | `none` | The double-pinyin scheme: `none` (full pinyin), `microsoft`, `sogou`, `xiaohe`, `ziranma` (PLAN §10.4); an unknown value reads as full pinyin |
| `emoji.favorites` | string | empty | The emoji panel's favorites, one per line in the order added (PLAN §10.6); at most 64, the oldest dropped first; switched from an emoji's long-press card |
| `emoji.combos` | string | empty | The person's own emoji combinations, one per line; the panel shows them after Kotomo's under Combinations |
| `emoji.mappings` | string | empty | The person's emoji for words, `word=emoji` per line (the word in lowercase letters as typed, at most 24); they come first among the emoji suggestions for exactly those letters |
| `japanese.flick` | boolean | `false` | Japanese mode uses the 12-key flick layout |
| `chinese.nine_key` | boolean | `false` | Chinese mode uses the 9-key layout: keys 2–9 stand for their letters and librime's 9-key schema reads the digits as pinyin (PLAN §10.9); the fuzzy-pinyin rules and a double-pinyin scheme apply to it as to the letter layout (with double pinyin on, the digits spell the scheme's two keys); the person's and the downloaded dictionaries, whose codes are letters, are not reached |
| `toolbar.hidden` | string | `edit,emoji,language,dictionaries,themes,floating,undo,redo,handwriting,voice` | Tools kept off the bar, joined by commas; they wait under More with the tools the bar has no room for. Written together with `toolbar.order`; a tool missing from a stored `toolbar.order` (one a later version adds) takes its default place |

## Theme file

The keyboard theme lives in its own JSON file, `files/themes/current.json` (PLAN §8.6), written
through a temporary file and an atomic replace on every theme change. Until it exists (an install
from before it), the theme is read from the `theme.*` keys above; the first change writes the file
and leaves those keys untouched, so an older build still reads its last value.

| Member | Type | Meaning |
|---|---|---|
| `format_version` | int | The format; this build writes 2 (version 2 added the members from `name` on; version 1 files read unchanged) and never lowers a newer value |
| `updated_utc_ms` | int | When it was written, UTC milliseconds |
| `base` | string: `system`, `light`, `dark`, `dynamic` | The colors the theme follows |
| `key_radius_dp` | int, 0–24 | Key corner radius in dp |
| `name` | string, at most 40 characters | The theme's name (a built-in theme's, or the one it was saved under); left out when empty |
| `colors` | object | The person's own colors over the base (PLAN §11.4), each `#RRGGBB` or `#AARRGGBB`: `background`, `key`, `key_function`, `key_pressed`, `text`, `text_secondary`, `accent`, `on_accent`, `candidate` (the highlighted candidate), `candidate_text`; a color left out follows the base |
| `background_image` | string | The background picture's file name in `files/themes/images/` (a plain `.png` name, never a path) |
| `background_opacity` | int, 0–100 | The picture's opacity in percent; default 60 |
| `press_highlight` | boolean | Whether pressed keys change color; default `true` |

Unknown members (a newer format) are kept when the file is rewritten; unknown or out-of-range
values read as the defaults, and a color that is not a valid hex color or a picture name that is not
a plain file name is left out. A file that is not a JSON object is moved aside to
`current.json.unreadable` and the defaults apply.

### Theme library

The saved themes and the theme each input mode uses live in `files/themes/library.json`
(`settings/ThemeLibrary.kt`), written the same way; the pictures in `files/themes/images/`, which
lose their file once no theme uses them.

| Member | Type | Meaning |
|---|---|---|
| `format_version` | int | The format; this build writes 1 and never lowers a newer value |
| `updated_utc_ms` | int | When it was written, UTC milliseconds |
| `themes` | array | The saved themes (at most 100), each a theme object as above with an `id` (`saved.<uuid>`); an entry without a valid id is left out |
| `modes` | object | By mode (`mixed`, `chinese`, `japanese`, `english`): the id of the saved or built-in (`builtin.<name>`) theme that mode uses; a mode left out, or whose theme is gone, uses the current theme |

A shared theme file (Export) is one theme object with the picture inside as `background_image_png`
(base64 PNG) and no file name. Import reads at most 12 MB, checks every value as above, decodes and
re-encodes the picture as PNG (at most 2048 pixels on its long side), and shows a preview before the
theme is used.

## Emoji style file

The emoji style (PLAN §11.5) lives in `files/emoji/style.json` (`ime/EmojiStyles.kt`), with the
fonts beside it: `noto-color-emoji.ttf` (downloaded, checked against the pinned size and SHA-256)
and `custom.ttf` (imported: at most 40 MB, a TrueType or OpenType file that Android loads).

| Member | Type | Meaning |
|---|---|---|
| `format_version` | int | The format; this build writes 1 and never lowers a newer value |
| `updated_utc_ms` | int | When it was written, UTC milliseconds |
| `style` | string: `system`, `noto`, `custom` | How the keyboard's emoji panel draws emoji; a style whose font is missing draws the system's |
| `custom_name` | string, at most 60 characters | The imported font's name, as shown |

## Keyboard memory file

What the keyboard remembers by itself, rather than what the person sets, lives in
`files/keyboard/memory.json` (`ime/KeyboardMemory.kt`): the mode last used, per app the mode last
used there, the mode fixed for it ("Fix this mode here" in the quick panel, PLAN §2.3) and the
Chinese script chosen there, the person's own settings for it (no learning, code; the More panel,
PLAN §10.10), the emoji history (recent and how often each was sent), and
one-handed mode per screen and orientation. It is written whole through a temporary file
and an atomic replace on a background thread after each change; the keyboard reads it once when
it starts and keeps it in memory.

| Member | Type | Meaning |
|---|---|---|
| `format_version` | int | The format; this build writes 1 and never lowers a newer value |
| `updated_utc_ms` | int | When it was written, UTC milliseconds |
| `last_mode` | string | The mode last chosen anywhere (`mixed`, `chinese`, `japanese`, `english`) |
| `apps` | object | Per package name: `mode` (last used there), `pinned_mode` (fixed for it), `script` (a `chinese.script` value), `incognito` (`true`: nothing is learned or recorded there, "Don't learn in this app") and `code` (`true`: its ordinary text fields behave as code fields, "Code in this app"; PLAN §10.10); each may be missing, the last two are written only when `true` |
| `emoji.recent` | array of strings | Emoji sent from the panel or its search, newest first, at most 32 |
| `one_handed` | object | Per screen and orientation (`sw<smallest width in dp>-portrait` or `-landscape`, PLAN §8.10): `side` (`off`, `left`, `right`) and `width_percent` (66–92, default 82) |
| `emoji.uses` | object | How often each emoji was sent (emoji → count ≥ 1), at most 200, the least used dropped first; those sent twice or more are "Frequently used" |

- Only package names are stored, never anything typed. Nothing is recorded per app, and no emoji
  history, in sensitive and direct fields or in incognito mode (PLAN §14); the global `last_mode`
  is the only value written there.
- Unknown members, and values this version cannot read (a newer mode), are kept when the file is
  rewritten; they read as absent. A file that is not a JSON object is moved aside to
  `memory.json.unreadable` and the keyboard starts with nothing remembered.
- Builds before format 1 kept the same values in SharedPreferences (`shared_prefs/ime.xml`:
  `mode`, `mode.<app>`, `script.<app>`; `shared_prefs/emoji_recents.xml`: `items`). While
  `memory.json` does not exist they are read from there; the first change writes the file, and the
  old files are left as they are, so an older build installed over this one still reads them.
- The manifest excludes every file from Android backup and device transfer (`data_extraction_rules.xml`).

The dictionaries keep their own versioned files (`files/dictionaries/user.json`, `state.json`), described in [dictionaries.md](dictionaries.md).

## Learned readings file

What Mixed mode learned from the candidates the person chose lives in
`files/keyboard/learned.json` (`ime/MixedLearning.kt`), versioned like the settings file: unknown
members are kept, a newer format version is never lowered, a damaged file is moved aside to
`learned.json.unreadable`.

| Key | Type | Meaning |
|---|---|---|
| `format_version` | int | this build writes `1` |
| `updated_utc_ms` | long | time of the last change, UTC milliseconds |
| `readings` | list | one object per learned reading |
| reading: `input` | string | the letters typed, lower case, without spaces |
| reading: `text` | string | what they were committed as |
| reading: `count` | int | how often it was chosen |
| reading: `used_utc_ms` | long | when it was last chosen, UTC milliseconds |
| reading: `rank` | string | absent, or what the person decided: `demoted`, `blocked`, `pinned` |

A commit in Mixed mode records the whole input's reading and, when the candidate has several
pieces, each piece's. Each use lowers the reading's cost in the engine by 3000 units, up to three
uses; the effect halves every 30 days since the last use and ends below a fifth of a use. A
demoted reading costs 3000 more, a blocked one is never offered, a pinned one always leads. At
most 2000 readings are kept; the weakest unranked ones go first. Nothing is recorded in a
sensitive field, a field that asks for no personalized learning, incognito mode or before the
first unlock, and there the engine ignores what was learned. Backspace right after a commit takes
its learning back with it. Privacy → Learned readings lists them with Rank lower, Never offer,
Keep first, Reset and Delete; Privacy → Clear learning data empties the file. The file is plain
JSON in the app's private storage and never leaves the device.

With `privacy.learned_encrypted` on (off by default, ADR-013) the same JSON text is kept sealed in
`files/keyboard/learned.kenc` instead, and `learned.json` is deleted:

```text
offset  size  content
0       4     magic "KLRN"
4       1     container version (1)
5       12    AES-GCM IV, fresh for every write
17      n     AES-256-GCM ciphertext of the JSON text, 128-bit tag; AAD = bytes 0..4
```

- The key is the Android Keystore key `kotomo_learned` (AES-256, GCM, no padding); it never
  leaves the Keystore, so the keyboard reads the file without asking for anything.
- The form on disk is the one found when the process first reads the readings; the switch (and
  the stored setting, whenever the keyboard reads it) changes it. Either way the new file is
  written first, through a temporary file and an atomic replace, and the other one is deleted
  only after that write succeeded; if both are found, the newer one is read. Turning the switch
  off writes `learned.json` back and deletes `learned.kenc`.
- A sealed file with a newer container version is left untouched: nothing is read from it and
  nothing is written for that run. A sealed file that cannot be decrypted or parsed (a lost key,
  damage) is moved aside to `learned.kenc.unreadable` and the readings start empty. If the
  Keystore gives no key when a write is due, that write is skipped and the file on disk stays.

Privacy → Learned readings also exports and imports. An export is the JSON text above, never
sealed (the key cannot leave the device), in a file the person chooses. An import reads such a
file (at most 4 MB) and merges it: a reading this device does not have is added, one both have
keeps the copy used later, and at most 2000 stay; a file that is not this format changes
nothing.

## Direct Boot

Before the first unlock the keyboard cannot read these files (credential-protected storage) and
neither reads nor writes the keyboard memory. It then uses the defaults, kept in a throwaway file
in device-protected storage that is deleted each
time, and switches to the person's settings once the device is unlocked
([platform-notes.md](platform-notes.md)).

## Privacy

The file holds only choices made in the settings app: no typed text, candidates or clipboard
content. The manifest disables Android backup (`allowBackup="false"`), so the file stays on the
device.
