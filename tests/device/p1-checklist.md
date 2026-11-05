# Kotomo P1 device checklist (G1; release build of 21cd65c)

Install the arm64 release APK built from the commit above (debug builds check functions only;
latency and memory need the release build). Open Kotomo, enable it and select it on the home card.

Mark each line PASS / FAIL, and note the device, Android version and anything odd.

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
