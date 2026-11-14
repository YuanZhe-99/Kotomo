# Kotomo real-device checklist (gates G0–G5)

One pass over this list lets us judge every gate. Every line is judged by what the screen shows.
The Chinese copy is `checklist.zh-cn.md` (same section and line numbers).

## The build

| | |
|---|---|
| Commit | `18741a9` (branch `main`) |
| Package | signed release APK, `arm64-v8a` (R8 on) |
| SHA-256 | `0bc4bbd7621b9201e11ef87d2f93968bca3f73299dcb83cff9ec7c21156621ff` (compare it with a checksum app if you have one) |
| Version shown in the app | 0.1.0 |

Install: if an older Kotomo (debug build, or one signed differently) is on the phone, uninstall it
first. Open the APK, allow installing from this source, open Kotomo. On the home card tap "Open
keyboard settings", turn Kotomo on, then tap "Choose keyboard" and pick Kotomo.

## How to record

- Mark each line `PASS`, `FAIL` or `NOT_RUN` (put the mark after the box, for example `- [x] PASS`
  or `- [ ] FAIL: the candidate bar was empty`). For a FAIL write what the screen showed.
- Lines tagged below need special hardware, a service or a network; if you do not have it, mark
  `NOT_RUN` and move on. A line tagged `[INFO]` asks you to write down what you see; it is not
  judged.
- Fill in the sheet at the end: device model, Android version, RAM, date, anything odd.
- Start from the default settings. When a line changes a setting, put it back before the next
  section unless the line says otherwise. Do sections 2–4 before anything else, and section 23's
  airplane-mode lines with the network off.

Tags: `[NET]` needs a network (a download) · `[NANO]` a phone with Gemini Nano · `[PEN]` a stylus
· `[KB]` a physical keyboard (USB or Bluetooth) · `[REMOTE]` a remote-desktop or terminal app ·
`[SPEECH]` Android's on-device speech recognition · `[WEBDAV]` an HTTPS WebDAV server ·
`[FOLD]` a foldable · `[TABLET]` a tablet · `[2DEV]` a second phone · `[AUTOFILL]` a password
manager that fills inline.

## Record these numbers (Settings → Diagnostics)

Tap Clear first. Then type for about a minute in one mode (ordinary words, sentences), tap Refresh and
write the row down. Repeat for each mode, clearing in between, because the page keeps one series.
These numbers are read, not judged line by line; the budgets are `PLAN.md` §13.4.

| Value on the page | Budget | Chinese | English | Japanese | Mixed |
|---|---|---|---|---|---|
| Key typed to candidates drawn (p50 / p95 / max) | p95 ≤ 50 ms on a mid-range phone; the old flagship baseline was 120–130 ms | | | | |
| Engine work per key (p50 / p95 / max) | none set; record | | | | |
| Press to key drawn (p50 / p95 / max) | the key preview shows on the next frame; baseline p95 19–21 ms | | | | |

| Value on the page | Budget | Your number |
|---|---|---|
| Keyboard view to first frame (cold start) | none set; baseline 11–14 ms | |
| Emoji panel first frame | none set | |
| Memory in use (while typing; again after section 29) | none set; baseline 61–80 MB | |
| Chinese engine setup | none set (prebuilt data unpacks in about 120 ms) | |
| Chinese engine check (S2) | shows a passing check, no error | |

The Japanese engine (S3) and the English engine (S4) have no line on that page; they are judged by
typing in lines 4.2, 4.4 and 8.5 (S3) and 3.1 and 3.2 (S4).

## 1. Install and first run (G1)

- [ ] 1.1 The home card moves from the two setup steps to "Kotomo is on"; typing in the "Try it" field shows the keyboard
- [ ] 1.2 Settings → About and acknowledgements: the version reads 0.1.0; Open-source licenses, Data sources and Design acknowledgements each open and list entries
- [ ] 1.3 Settings home lists Input modes, Mixed, Chinese, Japanese, English, Layout and size, Keys and gestures, Emoji, Themes, Dictionaries, Backup and sync, Writing help, Diagnostics, Privacy; every page opens without a crash

## 2. Chinese mode (G1, T01, S2)

- [ ] 2.1 `nihao` + space → 你好; `zhongguo` → 中国 first; `woxiangqubeijing` → 我想去北京 first
- [ ] 2.2 `jin tian tian qi hen hao` (with spaces) and `jintiantianqihenhao` → 今天天气很好 first
- [ ] 2.3 `zg` → 中国 among the first three; `shi` → 是 first, 事 / 时 / 十 / 市 follow. On the full keyboard, while pinyin is composed, Shift becomes the 分词 key: `xi` 分词 `an` → 西安 first, and Shift is back once the composition ends; Keys and gestures → "Shift separates syllables while composing" off: Shift stays Shift
- [ ] 2.4 Spelling slips: `nihoa` still offers 你好, `tain` offers 天
- [ ] 2.5 The letters as typed (`zhongguo`) appear after the two best candidates, never first; no Japanese kana appears for `nihao`
- [ ] 2.6 The arrow at the end of the bar shows all candidates; picking one returns to the keys, a partial pick too (你, then 好 the same way); "Back to the keyboard" closes it without a pick. In Mixed mode the arrow opens the segment panel: picking the sentence, or a reading for one piece, also returns to the keys
- [ ] 2.7 Long-press a candidate: its reading and source ("Chinese dictionary") appear; tapping elsewhere closes it
- [ ] 2.8 Pick a lower candidate for the same pinyin a few times: it moves up. Long-press a learned word: "Forget" appears and works
- [ ] 2.9 A long candidate never fills the whole bar: type a long sentence such as `jintiantianqihenhaowomenqugongyuansanbuba`; the first candidate ends in "…" and the next candidate and the arrow stay visible; tapping it types the whole sentence

## 3. English mode (G1, T02, S4)

- [ ] 3.1 `helo` offers hello (top three); `teh` offers the; `beautif` offers beautiful; `Helo` offers Hello
- [ ] 3.2 Negative: `the` and `hell` stay as typed and first; `qxzjvk` stays as typed
- [ ] 3.3 Space keeps what you typed; after a word, next-word suggestions appear; after `the` + space, words like first / same appear
- [ ] 3.4 A sloppy tap on a neighbouring key (`hwllo`, `thr`) still offers hello / the
- [ ] 3.5 Pick a suggestion, then type "." → "word. " (no space before the dot); a capital starts each sentence (Settings → English → Auto-capitalize; off → no capital)

## 4. Japanese mode (G1, T03, S3)

- [ ] 4.1 `nihon` + space → 日本 (space converts; another space shows the next candidate); Enter keeps にほん
- [ ] 4.2 `watashiwanihonjin` → 私は日本人 first (S3); `kyouhaiitenkidesune` → 今日はいい天気ですね; `toukyou` → 東京
- [ ] 4.3 `shi` and `si` both give し; `tsuki` and `tuki` both offer 月; `kitte` offers 切手 (doubled consonant)
- [ ] 4.4 Segments (a pinned piece, S3): the arrow at the end of the bar opens them; Shorter on 私は gives 私 + は, Longer joins them; another word for one segment leaves the others; Enter commits what is shown
- [ ] 4.5 Japanese mode reads other languages: `dongjing` offers 東京, `coffee` offers コーヒー, `chidai` offers 池袋 (first three); `nihon` keeps 日本 first
- [ ] 4.6 Negative: Settings → Japanese → switch off Pinyin → Japanese kanji, English → Japanese kanji and English → Japanese katakana: `dongjing` and `coffee` are no longer converted; `nihon` still is. Switch them back on
- [ ] 4.7 A Japanese candidate shows its reading; long-press it: reading, source, dictionary form and part of speech. "Readings on candidates" off → no reading under candidates

## 5. Modes and switching (G1, PLAN §8.2)

- [ ] 5.1 Long-press Emoji or the space bar: the modes appear in the order set under Input modes; a hidden mode is not offered; the last shown mode cannot be hidden. The space bar names the mode
- [ ] 5.2 Type `nihon` in Chinese, switch to Japanese: にほん stays uncommitted and converts; switch back: the letters return to pinyin
- [ ] 5.3 More → "Fix this mode here": that app always opens in the fixed mode, even after using another mode there; Input modes lists it under "Apps with their own settings"; Remove releases it
- [ ] 5.4 Other apps come back in the mode you last used in them; a code or command field starts in English
- [ ] 5.5 Settings → Input modes → "Start in": choose Japanese. Then the phone's Settings → Apps → Kotomo → Force stop. Open an app you have not typed in with Kotomo before (for example the Contacts search) and tap its text field: the space bar shows the Japanese mode. (Apps you have typed in keep the mode you last used there, 5.4.) Set "Start in" back afterwards
- [ ] 5.6 A browser's address bar (a web address field): it starts in the mode you last used in that browser, Chinese and Japanese convert, and the bottom row keeps / and .com. Input modes → "Web addresses start in English" on: the address bar types English directly. E-mail and password fields always type directly

## 6. Space and Enter per mode (ADR-010)

- [ ] 6.1 Defaults while typing: Chinese space commits the first candidate, Enter commits the letters as typed; Japanese space converts / moves to the next candidate, Enter keeps the kana; Mixed space only inserts a break, Enter commits the first candidate
- [ ] 6.2 Settings → Chinese → "Space key while typing" = "Insert a break, keep typing": `nihao` + space no longer commits. "Enter while typing" = "Commit the first candidate": Enter then commits 你好
- [ ] 6.3 Settings → Mixed → "Space key while typing" = "Commit the first candidate" and then "Convert, then show the next candidate": each behaves as its label says. Restore the defaults

## 7. Mixed mode: words and sentences (G2, T04–T07)

- [ ] 7.1 `diannao` → 电脑 (first three); `nihon` → 日本 first; `neko` → 猫 (first three); `sakura` → 桜 or さくら (first three)
- [ ] 7.2 Original language first: `coffee` is first as typed, コーヒー within the first three; `apple` first, アップル within five
- [ ] 7.3 `ai` offers 爱 (first three), AI, and 愛 or あい (first five)
- [ ] 7.4 Ambiguous words keep both readings: `kai` → 开 first, a Japanese reading (回 / 海 / 会 / 貝) still listed; `ni` → 你 first, に or 二 listed; `change` near the top as English, 嫦娥 listed; `hang` → 行 / 航
- [ ] 7.5 `mianjie` → 面接 (first three); `chidai` → 池袋 (first three) with the reading いけぶくろ; `dongjing` → 东京 first, 東京 in the first three; `computer` → コンピューター (first three)
- [ ] 7.6 `chidai ni kai ni ikou` → 池袋に買いに行こう as the first whole-sentence candidate (at `chidai ni kai` the Japanese candidates read に and かい apart, such as 池袋二回 or 池袋に回, never joined as 二階; the pieces offer the other readings of `kai`); `chidai nikainiikou` and `chidainikainiikou` offer it in the first three
- [ ] 7.7 `zailiucard` → 在留カード (first three); `chocolateokainiikou` → チョコレートを買いに行こう (first three)
- [ ] 7.8 English inside a sentence stays English: `wo xihuan ai` offers 我喜欢AI; `aishishenme` offers AI是什么
- [ ] 7.9 Negative: `nihao` does not put Japanese first; `coffee` does not put a pinyin split first
- [ ] 7.10 Typing 30 or more letters in one go (`chidainikainiikouchidainikainiikou`) never stalls the keys; more candidates arrive a moment later
- [ ] 7.11 Mixed page → Level: Conservative keeps the language typed first for `coffee` and `nihon`; Aggressive brings Japanese conversions and whole sentences earlier; set it back to Balanced
- [ ] 7.12 The list is complete (feedback A9): type `yu`, expand the candidates and scroll to the end: 语, 余, 鱼, 雨 and rare characters are there, as many single characters as Chinese mode offers for `yu`; 与 is still first and the Japanese candidates (湯, 油) still stand among the first few; the same for `shi` and `yi`

## 8. Mixed mode: switches, editing, correction (G2, T08–T10)

- [ ] 8.1 Settings → Mixed → "Pinyin → Japanese kanji" off: `chidai` no longer offers 池袋; on again: it does
- [ ] 8.2 "English → Japanese katakana" off: `computer` and `coffee` lose their katakana; "English → Japanese kanji" off: `school` loses 学校
- [ ] 8.3 "Mixed-language sentences" off: `chidai ni kai ni ikou` is no longer assembled into 池袋に買いに行こう across languages
- [ ] 8.4 "Readings on candidates" off: no reading under Japanese candidates; "Emoji suggestions" off: `neko` / `mao` / `cat` show no 🐱. Switch both back on. (The "Pinyin → Japanese katakana" switch has no data behind it yet; ignore it)
- [ ] 8.5 Piece editing: after `chidai ni kai ni ikou` open the arrow; tap the piece 池袋: its other readings (地代) are listed; choose one: the keys come back; open the arrow again: the other pieces stayed as they were; commit
- [ ] 8.6 Backspace right after that commit deletes one character, as anywhere else (the default). Keys and gestures → "Backspace restores what was just committed" on: Backspace right after the commit puts the letters back with the chosen piece still chosen; Enter commits it again
- [ ] 8.7 In the piece list the letters (`chidai`) are offered as a choice (keeps the English); "Restore input" drops the choice and the sentence converts afresh
- [ ] 8.8 "Commit up to here" on 池袋: it goes into the field, the rest stays composed and can be chosen next
- [ ] 8.9 Key correction: type `boo`, then tap `l` in its left quarter (the side next to `k`, no further from the key's edge than a quarter of its width): `book` is offered, never first. Tap `l` in the middle: `book` is not offered

## 9. Fuzzy pinyin (G2, T08)

- [ ] 9.1 Settings → Chinese → z ↔ zh on: after a few seconds `zongguo` offers 中国 (first three); `zhongguo` and `nihao` are unchanged; typing works while "Preparing…" shows; all switches off: `zongguo` no longer does
- [ ] 9.2 Only rules that are on apply: with z ↔ zh on and c ↔ ch off, `cifan` does not offer 吃饭; turn c ↔ ch on and it does; `sijian` → 时间 with s ↔ sh; `yingwei` → 因为 with in ↔ ing
- [ ] 9.3 Rules sharing a letter do not chain: n ↔ l and l ↔ r on: `lihao` offers 你好 and `luguo` offers 如果, but `rihao` does not offer 你好 and `niben` does not offer 日本
- [ ] 9.4 With every rule on, `zg` still gives 中国 and `nihao` still gives 你好 first
- [ ] 9.5 With z ↔ zh on, Mixed mode also offers 中国 for `zongguo`

## 10. Learning (G2, T10, ADR-013)

- [ ] 10.1 In Mixed mode pick a lower candidate for one input (for example 愛 for `ai`) up to three times: it moves up. Settings → Privacy → "See what was learned" lists `ai` → 愛
- [ ] 10.2 In that list: "Rank lower", "Never offer", "Keep first", "Reset", "Delete" each change what `ai` offers next time as the label says
- [ ] 10.3 In Mixed mode type `dajia`, pick 大家 and press Backspace at once: 大 is left and nothing is being composed (the default). With "Backspace restores what was just committed" on (Keys and gestures): the same Backspace brings `dajia` and its candidates back, and nothing new is listed as learned
- [ ] 10.4 Settings → Privacy → "Encrypt what was learned" on: typing is the same and the list still shows the items; off: the items are still there
- [ ] 10.5 On that list's page: Export saves a file ("Exported"); Clear learning data; Import the file: the items come back; Import a photo or other unrelated file: "That is not a file of learned readings"
- [ ] 10.6 Settings → Privacy → Clear learning data (confirm): a word you taught Chinese mode is no longer first, Recent emoji is empty, fixed modes stay, typing still works at once

## 11. Chinese characters and Chinese input extras (G1, G3, T11)

- [ ] 11.1 Settings → Chinese → Characters: Simplified, Traditional, Taiwan, Hong Kong all select; Simplified `ruanjian` → 软件, Taiwan → 軟體, Hong Kong → 軟件
- [ ] 11.2 Taiwan words by their own pinyin (first few): `huashu` 滑鼠, `wanglu` 網路, `yinbiaoji` 印表機, `jichengche` 計程車, `jieyun` 捷運, `zixun` 資訊. Hong Kong: `dianyou` 電郵. [INFO] `dishi` 的士 may sit around fifth (known gap)
- [ ] 11.3 Negative: in Simplified, `ruanti` does not offer 軟體; Traditional stays Traditional after switching apps and fields; the More panel's "Characters" choice is remembered per app
- [ ] 11.4 Double pinyin (Chinese → Double pinyin): Xiaohe `nihc` → 你好; Microsoft `nihk` → 你好; Ziranma `vsgo` → 中国; with one selected, full `nihao` is no longer read as full pinyin; back to "Full pinyin"
- [ ] 11.5 Quick input: `v1234` → 一千二百三十四 and the capital-money form; `vrq` date, `vsj` time, `vxq` weekday; `vu4f60` → 你; `v1+2*3` → 7; space types the first, Enter types the letters
- [ ] 11.6 "Your quick replacements": add `dz=my address`; `vdz` offers it
- [ ] 11.7 Strokes: with nothing composing type `u` then `hs` → 十 among the candidates
- [ ] 11.8 9-key layout (Chinese → 9-key layout): keys 2–9 show letters; `64426` → 你好; `94664486` → 中国 (first three); `96`, the 分词 key, then `744` → 我是; the bottom row is Chinese punctuation; off: letter keys return
- [ ] 11.9 Idioms [NET]: after downloading "Idioms" (14.5), commit 画蛇 (or another idiom's first characters): the bar offers the rest; "Idiom completion" off: nothing offered

## 12. Japanese extras (G3, T16)

- [ ] 12.1 Settings → Japanese → "12-key flick": the pad shows kana; tap = あ column, flick left / up / right / down = い う え お; ゛゜ cycles voiced and small kana; typing か + ゛ gives が
- [ ] 12.2 Forms: in Japanese mode type `iku`, convert to 行く, long-press it: dictionary form and part of speech (verb), and a "Forms" button
- [ ] 12.3 The Forms panel offers Polite, -masu, -te, -ta, -nai, Potential, Passive, Causative, Volitional, Imperative, Honorific, Humble; choosing one replaces the composition (行きます, 行って, 行った, 行かない, 行ける, 参ります)

## 13. Emoji (G1, G3, G4)

- [ ] 13.1 Suggestions on the bar: `neko` / `mao` / `cat` → 🐱; `apple` / `pingguo` → 🍎; `sakura` / `yinghua` → 🌸; `birthday` / `shengri` → 🎂; `coffee` / `kafei` → ☕; `computer` / `diannao` → 💻 (after the text candidates)
- [ ] 13.2 The Emoji panel has categories, a skin-tone choice, Recent and, after sending one twice, "Frequently used" above Recent
- [ ] 13.3 Magnifier: the keys type a search in the bar (`cat`, `mao`, `neko`), nothing reaches the field until you pick; Enter sends the first result
- [ ] 13.4 Long-press an emoji: skin tone and a favourite switch; Favorites lists it; the Combinations tab and the Kaomoji tab insert what you tap
- [ ] 13.5 Settings → Emoji: "Your emoji for words" `neko=🐈`: typing `neko` shows 🐈 first among the emoji; "Your combinations" adds a line to the Combinations tab; "Clear favorites" empties Favorites
- [ ] 13.6 Mixed page → Candidate weights → Emoji: raising it moves emoji suggestions up, lowering it moves them down; back to the default 30

## 14. Dictionaries (G3, T17)

- [ ] 14.1 Settings → Dictionaries → Add a word (Chinese, a made-up word with its pinyin): typing that pinyin offers it; switch "Your dictionary" off: it is gone; on again: back. Search, edit and delete the word work
- [ ] 14.2 Export, then "Import a file" with that exported file: the preview counts the words, shows duplicates with "Keep mine" / "Use the file's", nothing is added until "Add"
- [ ] 14.3 Import a photo or PDF: it is refused with a clear reason and nothing is added. "Restore the previous version" undoes the last change; tapping again redoes it
- [ ] 14.4 "Make a dictionary with an AI model": Show the prompt, Copy the prompt ("Copied.")
- [ ] 14.5 [NET] Get dictionaries: the first notice names the host; "Not now" downloads nothing; "Allow downloads" then Download a small pack (for example "Idioms"): progress, then installed with version and word count; the source line says whether a checksum was checked
- [ ] 14.6 [NET] A word from the installed pack types on the keyboard; "Previous version" and "Remove" work; the pack is gone after Remove
- [ ] 14.7 Password protection: turn on (at least 6 characters; a mismatch is refused); "Lock now" locks the page; wrong password → "Wrong password."; the right one unlocks; export is encrypted ("Open an encrypted file" asks for the password); turn protection off
- [ ] 14.8 [INFO] Protected and locked, restart the phone: do your own words still type before you unlock them? Write what you see

## 15. Clipboard (G3, PLAN §14)

- [ ] 15.1 Copy text in another app and open the keyboard: the strip "Just copied" offers it; tapping types it; it disappears after a minute or after use
- [ ] 15.2 Clipboard tool: tabs All / Pinned and phrases / Codes list the copied text newest first with tags (Link, Phone, Address); the footer says it is only on this phone, encrypted
- [ ] 15.3 Pin a clip: it stays under Pinned and phrases and survives "Clear unpinned items" (Settings → Privacy); Unpin and Delete work; "Paste the whole text" works
- [ ] 15.4 Copy a message such as "Your verification code is 482915": the strip says a verification code was found and offers 482915; tapping types only the code; the Codes tab lists it
- [ ] 15.5 Negative: copy "Total 482915 yen", a time (12:30) or a date: no code is offered. Copy "Code 123456, order #987654": both are listed, the likelier marked "more likely"
- [ ] 15.6 Incognito on, copy something: it is not recorded and not offered; same while a password field is focused
- [ ] 15.7 [INFO] Copy a password from a password manager that marks it sensitive: it is not recorded and not offered. Write the app
- [ ] 15.8 Settings → Privacy → Clipboard history off: new copies are not kept and the footer says so; Quick paste off: no strip; the retention choice offers 1, 7 and 30 days
- [ ] 15.9 History page (See, search and edit the history): search, the categories (Codes, Links, Phone numbers, Addresses, Text), edit an item, "Add a quick phrase" (it appears under Pinned and phrases), select all shown and delete
- [ ] 15.10 In the clipboard panel tap "Not in this app": the footer says it is not kept while you type there; the app appears under "Apps where nothing is kept"; Remove brings it back
- [ ] 15.11 Long-press a clip card: a menu over the panel shows the clip and Pin (Unpin when pinned), Delete and Cancel (Paste the whole text for a verification-code message); each line is easy to hit with a finger and its text is as large as the other panel menus; Pin pins the clip

## 16. Fields, gestures, punctuation (G1, T13)

- [ ] 16.1 E-mail field: @ and . on the bottom row, half-width. Web address field: / and .com, long-press .com for .net .org. Phone field: phone pad (long-press 0 for +). Number field: digit pad with . and ,
- [ ] 16.2 Search box: Enter shows a magnifier and searches; a chat box with Send shows the send arrow; Next / Done actions show where the app asks
- [ ] 16.3 Two quick spaces after an English word type ". ", after Chinese text "。"; Settings → Keys and gestures → "Double-space full stop" off → two spaces
- [ ] 16.4 Slide on the space bar moves the cursor; slide left from delete removes words; long-press space opens the language choice; each switch off → nothing happens
- [ ] 16.5 Key sound off by default, on → clicks; "Vibrate on key press" works; "Key corner hints" shows the long-press symbols; long-press a key types its symbol
- [ ] 16.6 Smart quotes on: the " and ' keys type curly quotes; "Close brackets and quotes" on: ( adds ) and Backspace right away removes both; neither acts in code, web address or e-mail fields
- [ ] 16.7 More → "Code in this app": text fields there start in English with plain quotes and no automatic pairs

## 17. Toolbar, layout and key customisation (G3, T14)

- [ ] 17.1 The bar shows More, Clipboard, Writing help, Translate, Settings; More lists Edit text, Emoji, Next language, Dictionaries, Themes, Floating keyboard, Undo, Redo, Handwriting, Voice typing
- [ ] 17.2 Settings → Layout and size → Toolbar: move a tool up, hide another; the bar follows and a hidden tool waits under More
- [ ] 17.3 Edit text: arrows, line start / end, Select (then arrows extend), Select all, Cut, Copy, Paste, Delete all work in a note; Undo and Redo undo and redo typing
- [ ] 17.4 More panel: Incognito, Characters, One-handed, Split keyboard, "Fix this mode here", "Don't learn in this app" (nothing typed there is learned, copied text is not kept), "All settings"
- [ ] 17.5 Floating keyboard: the keys float over the app, narrower; dragging the bar above them moves it; the app is not pushed up; switching it off returns the normal keyboard
- [ ] 17.6 Settings → Keys and gestures → Keys and rows: gap between keys, label size, space bar width, Number row, "Function keys on the other side", "Your long-press symbols" (`a=@á`) each change the keyboard at once
- [ ] 17.7 Layout and size: Keyboard height changes the height; landscape works and keeps the composition when you rotate

## 18. Foldable, tablet, one-handed (G1, T14)

- [ ] 18.1 One-handed: More → Right hand, then drag the bar beside the keys: the width follows and stays after closing the keyboard; ⇆ swaps the side, ⤢ returns to full width
- [ ] 18.2 Rotate to landscape: one-handed there is set on its own; back in portrait the old width returns
- [ ] 18.3 [FOLD] The cover screen and the inner screen each keep their own one-handed setting
- [ ] 18.4 [FOLD] Unfolded: split halves, no key on the hinge, two space bars if the hinge divides the screen; Split keyboard (By screen width / Always / Never) behaves as labelled
- [ ] 18.5 [FOLD] Half-folded (tabletop): keys stay below the hinge; the "Move to the lower half when half-folded" switch turns that off
- [ ] 18.6 [FOLD] Fold and unfold while composing (`nihao` shown): the composition and candidates survive
- [ ] 18.7 [FOLD] Landscape on the cover screen: the keyboard is not taller than about half the screen
- [ ] 18.8 [TABLET] Large screen: the keyboard is capped in width (centred or at the side), more candidates fit in the bar, the thumb-zone width setting changes the gap in the middle

## 19. PC / remote layout (G3, T20)

- [ ] 19.1 Settings → Input modes → "PC keys" on: it appears in the language choice; "Computer you type to" offers Windows, macOS, Linux; "On a large screen" offers Full keyboard and Split
- [ ] 19.2 [REMOTE] Fix PC keys for the remote app (More → "Fix this mode here"): the keyboard shows Esc, Tab, modifiers, arrows and Fn on top, no candidates
- [ ] 19.3 [REMOTE] Tap Ctrl once (it highlights), then `c`: the remote gets Ctrl+C (a running command stops in a terminal); Ctrl lets go afterwards; a following `q` is plain
- [ ] 19.4 [REMOTE] Double-tap Ctrl (highlight and underline = locked): `a` then `c` both carry Ctrl; tap Ctrl to release. Holding a modifier with one finger while tapping with another works
- [ ] 19.5 [REMOTE] Arrows, Esc, Tab move / act; the Fn page sends F1–F12, Home, End, PgUp, PgDn, Ins, Del and the combination keys (Ctrl+C, Ctrl+V, Alt+Tab …)
- [ ] 19.6 With macOS chosen the modifiers read ⌃ ⌥ ⌘ and the Fn page's safe combination changes
- [ ] 19.7 In any notes app, PC mode letters type, arrows move the cursor, Ctrl then `a` selects all (the remote app decides the rest; mark `FAIL` only if the key does not leave Kotomo)
- [ ] 19.8 PC mode shows no candidates and learns nothing: Privacy's learned list is unchanged after typing in it

## 20. Physical keyboard (G5, T21)

- [ ] 20.1 [KB] Chinese mode: letters start a composition, the bar shows candidates, the on-screen keys stay hidden; digits pick a candidate; Space, Enter and Backspace act as in 6.1; Esc discards
- [ ] 20.2 [KB] Shift+Space switches mode; Ctrl+C / Ctrl+V pass through; English mode and PC mode type directly; with the 9-key layout on, letters are still pinyin

## 21. Handwriting, stylus, voice (G5, T22, T23)

- [ ] 21.1 [NET] More → Handwriting: the first time the panel names the download (about 20 MB per language, from Google) and has Download; after it, writing 你 and 好 offers them; tapping one commits; Space and the delete key work
- [ ] 21.2 The model follows the mode and characters: Chinese (mainland), Taiwan, Hong Kong, Japanese (write ひ / あ). Settings → Keys and gestures → "Handwriting models" lists them with Delete
- [ ] 21.3 In a password field Handwriting says it is off in this field
- [ ] 21.4 [PEN] With a model downloaded, write with the stylus directly in a text field: after a short pause (about 0.7 s) the text commits; a password field refuses; without a model the hint says to download it in the Handwriting tool
- [ ] 21.5 [SPEECH] More → Voice typing: allow the microphone when asked; speak "你好" in Chinese mode, a sentence in English and in Japanese: the text arrives; not offered in a password field
- [ ] 21.6 Without the recognizer the panel says the device has none (mark 21.5 `NOT_RUN`); a missing language model says Android can download it; this line passes if the message is clear and typing is unaffected

## 22. Autofill (G3)

- [ ] 22.1 [AUTOFILL] In a login form the password manager's suggestions appear in the bar; tapping one fills the form
- [ ] 22.2 [AUTOFILL] Settings → Layout and size → Toolbar → "Autofill suggestions" off: they no longer appear in the bar
- [ ] 22.3 [AUTOFILL] While suggestions show, the button at the strip's start ("Show the toolbar") brings the toolbar back; a key icon at the bar's start ("Show the autofill suggestions") shows them again; in the next field they show again by themselves

## 23. Privacy and offline (G1, T12, PLAN §14)

- [ ] 23.1 Password field (the "Try it" password box): no candidates, letters type directly, nothing learned (the learned list is unchanged)
- [ ] 23.2 Incognito (home switch): typing works; nothing learned, no Recent / Frequently used emoji, nothing recorded from the clipboard, Writing help refuses
- [ ] 23.3 A field that asks for no personalised learning (for example a private browser tab): Chinese and Japanese still convert, nothing is learned
- [ ] 23.4 Settings → Apps → Kotomo → Permissions: nothing is granted before you use voice typing
- [ ] 23.5 Airplane mode on: Chinese `nihao`, English `helo`, Japanese `nihon`, Mixed `chidai ni kai ni ikou` all convert as in sections 2–7
- [ ] 23.6 Airplane mode on: a download (a dictionary, a translation model) fails with a clear message and nothing else breaks. Turn airplane mode off
- [ ] 23.7 The Privacy page says typing works offline and nothing is sent; Diagnostics says it keeps only timings and memory

## 24. Writing help and translation (G0 S7, G4, T18)

- [ ] 24.1 Settings → Writing help is off at first; with it off the AI button says to turn it on in Kotomo settings and Translate is refused
- [ ] 24.2 [NANO] Turn it on, select "i has went to the store yesterday" in English and tap the AI button: Kotomo's own screen opens with your text, Language and "What to do" (Proofread, Rephrase, More formal, Friendlier, Longer, Shorter); nothing runs until you choose
- [ ] 24.3 [NANO] [NET] The first time it asks to download the Gemini Nano model; after it, Proofread shows suggestions; Replace puts the text back in the original field (exactly the selected part) and the keyboard offers "↶ Undo", which restores it
- [ ] 24.4 [NANO] Edit the field text before tapping Replace: the suggestion is copied instead ("The text changed, so the suggestion was copied")
- [ ] 24.5 [NANO] A casual Japanese sentence with More formal gives a more polite one; several suggestions can be chosen between
- [ ] 24.6 [NANO] [INFO] If the screen says "AI runs only while this screen is open." or any other error, write it down (S7)
- [ ] 24.7 Chinese text selected: the AI screen says writing help works in English and Japanese; in a password field it says it is off in this field
- [ ] 24.8 Phone without Gemini Nano: with Writing help on, the AI screen says Gemini Nano is not available and that every typing feature still works; all typing lines in this list pass anyway (mark 24.2–24.5 `NOT_RUN` here)
- [ ] 24.9 [NET] Translate (toolbar): choose From / To, type "Good morning, see you tomorrow" and translate to Japanese; the first time it names the Google models (about 30 MB per language) and has Download; the translation shows under your text; Replace swaps it in, Undo restores
- [ ] 24.10 [NET] Chinese → English and English → Chinese work (Chinese comes out in Simplified); Settings → Writing help lists "Translation models" with Delete; in a password field Translate is refused

## 25. Themes and emoji styles (G4)

- [ ] 25.1 Settings → Themes → Colors: Follow the system, Light, Dark, Material You (wallpaper) each change the keyboard at once; "Key corners" changes the key radius
- [ ] 25.2 Built-in themes: Tokyo Night, Kyoto, Shanghai, Taipei, Sapporo Snow each apply
- [ ] 25.3 Editor: tap a colour role (Background, Keys, Function keys …), enter `#RRGGBB`: the keyboard changes; the preview tabs (中文, 日本語, English, Emoji, Landscape) switch; "Reset all colors" restores
- [ ] 25.4 "Choose a picture" sets a background that shows behind the keys; the opacity slider changes it; "Remove picture" removes it; keys stay readable
- [ ] 25.5 "Pressed-key highlight" off: keys no longer change colour while pressed
- [ ] 25.6 "Save this theme" with a name; it appears under Saved themes; Use and Delete work; Export saves a file; Import of that file previews it ("Use this theme?"); a non-theme file is refused
- [ ] 25.7 "Theme per input mode": give Japanese its own theme; switching to Japanese changes the colours and switching back restores them
- [ ] 25.8 [NET] Emoji style: System / Noto Color Emoji (Download 10.7 MB, names the host) / Your font; with Noto the emoji panel redraws in that style; the note says apps show their own emoji font; delete Noto goes back
- [ ] 25.9 Change the theme and key radius, restart the phone: they stay

## 26. Backup and sync (G4, T19)

- [ ] 26.1 Settings → Backup and sync → Back up now: tick Settings, Themes, Your dictionary, Learned readings, Keyboard memory; the clipboard is not offered; "Save backup…" to a folder shows "Backup saved"
- [ ] 26.2 Encrypt with a password (at least 8 characters) and save a second backup
- [ ] 26.3 Change a theme and add a word, then "Restore from a file…" with the encrypted backup: a wrong password → "Wrong password"; the preview shows when and by which version it was made, the parts, and marks parts where this phone is newer (unticked); Restore → "Restored" and the changes are gone
- [ ] 26.4 "Undo the last restore" brings the changes back; restoring turns automatic sync off (the page says so)
- [ ] 26.5 Automatic backups: choose a folder, Daily or Weekly, Backups kept; "Back up now" updates "Last backup" and a file appears in that folder (check in a file manager); Stop automatic backups
- [ ] 26.6 Sync (off at first): Folder → pick a folder; "What is synced" ticks Settings, Themes, Your dictionary by default and not Learned readings or Keyboard memory; the clipboard is never listed; "Sync now" shows "Sent …, received …, conflicts …" and files appear in the folder
- [ ] 26.7 [2DEV] With the folder synced to another phone: a change on one arrives on the other; a part changed on both is listed as a conflict and waits for "Keep this device's" or "Use the synced copy"
- [ ] 26.8 [WEBDAV] Choose WebDAV, enter an https:// folder, user and password, "Use this server": a dialog names the host and asks to connect; Sync now works; a wrong password says the server refused it

## 27. Appearance (G1, G5)

- [ ] 27.1 System dark mode: keyboard and every settings page switch to dark and stay legible; back to light
- [ ] 27.2 Interface language: Settings → Interface language (Android 13 and later), choose English, 简体中文, 繁體中文 and 日本語 in turn: the settings pages change at once, the keyboard (key descriptions, tool names, hints, the More panel) the next time it opens; no cut-off text. Then "Follow the system" and set the phone to Chinese: both follow it
- [ ] 27.3 Largest font scale (Android Settings → Display → Font size, and Display size): key labels, candidate bar, the More panel and every settings page stay readable with no overlapping; the PC layout too
- [ ] 27.4 Rotate to landscape in a text field: the keyboard works and is not taller than needed

## 28. Direct Boot and first unlock (G1)

- [ ] 28.1 Restart the phone; before the first unlock use Kotomo for the PIN or an emergency note: English and Chinese type; Japanese, clipboard and AI say to unlock first
- [ ] 28.2 After unlocking, your settings, theme and clipboard are back

## 29. Stability and speed (G1, G2, T15)

- [ ] 29.1 Type quickly in each mode for a minute: no letter is lost or doubled, the key preview shows at once, nothing feels late
- [ ] 29.2 Switch between five apps ten times, opening the keyboard each time: it appears every time, no crash dialog, the composition is handled sensibly
- [ ] 29.3 Open and close the keyboard twenty times; then read Memory in use (Diagnostics) and compare it with the first reading in the numbers block: no steady climb
- [ ] 29.4 While a dictionary downloads or a backup runs, keep typing: the keys stay responsive
- [ ] 29.5 Quick repeated mode switching (ten times) while composing: no crash, no stuck letters

## 30. Release-build sanity (G5)

- [ ] 30.1 Settings → Apps → Kotomo → Force stop; open any text field: Chinese `nihao` → 你好, English `helo` → hello, Japanese `nihon` → 日本, Mixed `chidai ni kai ni ikou` → 池袋に買いに行こう
- [ ] 30.2 After that, [NET] Translate still works (24.9) and Handwriting still recognises (21.1): the release build (R8) did not break either library
- [ ] 30.3 No "keeps stopping" or crash dialog appeared anywhere in this pass; Diagnostics shows no failed step ("Chinese engine check" is fine)

## Result sheet

| | |
|---|---|
| Device model | |
| Android version / patch level | |
| RAM | |
| Date | |
| Network used (Wi-Fi / mobile) | |
| Hardware you had (stylus, keyboard, second phone, foldable, WebDAV, Gemini Nano) | |
| Lines FAIL (numbers) | |
| Lines NOT_RUN (numbers) | |
| Anything odd | |

## Which lines serve which gate

| Gate | Lines |
|---|---|
| G0 (S2, S3, S4, S7) | numbers block, 2.1, 3.1, 3.2, 4.2, 4.4, 8.5, 24.2–24.8 |
| G1 | 1–6, 11.1–11.3, 13.1–13.3, 16, 18, 23, 27, 28, 29, numbers block |
| G2 | 7–10, 29.1 |
| G3 | 11–12, 14, 15, 17, 19, 22 |
| G4 | 24, 25, 26 |
| G5 | 20, 21, 27, 29, 30 |
