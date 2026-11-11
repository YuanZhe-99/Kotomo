# Kotomo P1 device checklist (G1; release build of 2026-11-11)

Install the arm64 release APK handed over on 2026-11-11 (`platform-notes.md` names the commit and
its SHA-256; debug builds check functions only, latency and memory need the release build). Open
Kotomo, enable it and select it on the home card. The lines below were first written for
`21cd65c`; those marked (new) cover what Q2 added. On the emulator every (new) line was checked by
an instrumentation test or a screenshot; the phone is what G1 needs.

Mark each line PASS / FAIL, and note the device, Android version and anything odd. Every line is
judged by what the screen shows. Two exceptions: the "nothing learned" half of the privacy line
cannot be seen yet (the screen that lists learned items arrives with P2, PLAN §9.9), and this
checklist makes no claim about latency or memory (the diagnostics page shows those numbers, but
they are read there, not judged here).

## Typing in each mode (T01–T03)
- [ ] Chinese: `nihao` + space → 你好; `zhongguo` → 中国 first
- [ ] English: `helo` offers hello; space keeps what you typed; next-word suggestions after a word
- [ ] Japanese: `nihon` + space → 日本; Enter keeps にほん; `watashihanihonjin` → 私は日本人
- [ ] (new) Japanese segments: the arrow at the end of the bar opens the segments; Shorter on 私は
      gives 私 + は, Longer joins them; choosing another word for one segment leaves the others; Enter
      commits what is shown

## Modes (new; Settings → Input modes)
- [ ] Long-press Emoji or the space bar: the modes appear in the order set here; a hidden mode is
      not offered; the last shown mode cannot be hidden
- [ ] Type `nihon` in Chinese, switch to Japanese: にほん stays uncommitted and converts; switch back:
      the letters return to pinyin
- [ ] Quick settings (toolbar ⋯) → "Fix this mode here": that app always opens in the fixed mode, even
      after using another mode there; Settings → Input modes lists it, Remove releases it
- [ ] Other apps come back in the mode last used in them; a code field starts in English

## Fuzzy pinyin and dictionaries (new, Q3; part of G2/G3, checked here while the APK is at hand)
- [ ] Settings → Chinese → z ↔ zh on: after a few seconds `zongguo` offers 中国; all switches off:
      `zongguo` no longer does; typing keeps working while the dictionary is prepared
- [ ] Settings → Dictionaries → Add a word (Chinese, a made-up word with its pinyin): typing that
      pinyin offers it; switch "Your dictionary" off: it is gone
- [ ] Import a TSV file: the preview counts the words before anything is added; Restore the
      previous version undoes the import

## Emoji (new parts)
- [ ] Emoji panel → magnifier: the keys type a search in the bar (`cat`, `mao`, `neko`), nothing reaches
      the field until you pick; Enter sends the first result
- [ ] Send one emoji twice: "Frequently used" appears above Recent
- [ ] In a password field or incognito, sent emoji do not appear in Recent or Frequently used

## Gestures (Settings → Keys and gestures; each has its own switch)
- [ ] Two quick spaces after an English word type ". "; after Chinese text "。"; off → two spaces
- [ ] Key sound: off by default; on → clicks (respects the media volume)
- [ ] Long-press the space bar opens the language choice; switch off → no popup
- [ ] Slide on the space bar moves the cursor; switch off → nothing
- [ ] Slide left from delete removes words; switch off → nothing
- [ ] English mode: pick a suggestion, then type "." → "word. " (no space before the dot)

## Fields
- [ ] E-mail field: @ and . on the bottom row, half-width
- [ ] Web address field (browser address bar): / and .com; long-press .com for .net .org …
- [ ] Search box: Enter shows a magnifier and searches; chat apps with Send: the send arrow
- [ ] Phone number field: phone pad (long-press 0 for +); number field: digit pad with . and ,
- [ ] A code or command field starts in English

## Chinese script
- [ ] Settings → Chinese: Simplified, Traditional, Taiwan, Hong Kong
- [ ] Taiwan: `ruanjian` → 軟體; Hong Kong: `ruanjian` → 軟件; Simplified: 软件
- [ ] Traditional stays Traditional after switching apps and fields (the old reset bug)
- [ ] Quick panel (toolbar ⋯) choice is remembered per app

## Candidates
- [ ] Long-press a Chinese candidate: reading and source in the bar; tap elsewhere closes it
- [ ] Long-press a learned word: "Forget" appears and forgetting works

## One-handed (new)
- [ ] Quick settings → Right hand; drag the bar beside the keys: the width follows and stays after
      closing the keyboard; ⇆ swaps the side, ⤢ returns to full width
- [ ] Rotate to landscape: one-handed there is set on its own; back in portrait the old width returns
- [ ] Fold: the cover screen and the inner screen each keep their own one-handed setting

## Window sizes
- [ ] Fold unfolded: split halves, no key on the hinge, two space bars if the hinge divides the screen
- [ ] Half-folded (tabletop): keys stay below the hinge; switch in Layout turns it off
- [ ] Landscape on the cover screen: keyboard not taller than about half the screen
- [ ] Fold/unfold while composing: the composition and candidates survive

## Direct Boot
- [ ] Restart the phone; before the first unlock, use Kotomo for the PIN or an emergency note:
      English and Chinese type; Japanese, clipboard and AI say to unlock first
- [ ] After unlocking, your settings, theme and clipboard are back

## Theme
- [ ] Change theme and key radius; after a restart of the phone they stay

## Privacy (T12)
- [ ] Password field: no candidates, nothing learned; incognito: nothing learned or recorded
- [ ] (new) Settings → Privacy → Clear learning data: after confirming, a word you taught Chinese mode is
      no longer first, Recent emoji is empty, fixed modes stay, typing still works at once

## About
- [ ] About, open-source licenses and data sources open; the version reads 0.1.0

## Numbers to report (read from Settings → Diagnostics after a few minutes of typing; not judged here)
- [ ] Press to key drawn, Key typed to candidates drawn, Engine work per key: p50 / p95 / max
- [ ] Keyboard view to first frame, Emoji panel first frame, Memory in use
