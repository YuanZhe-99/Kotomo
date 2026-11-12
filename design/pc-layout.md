# PC layout (PLAN §10.11)

Text description of the PC / remote-control layout, written from the user's remote-control keyboard
design draft (2026-11-11); the draft itself is not part of this repository. Colors and shapes come
from Kotomo's theme tokens: modifiers and other non-character keys use the function key color, a
latched or held modifier the accent color, a locked one the accent color with a short underline.

## Purpose and limits

- Sends PC keys to apps such as remote desktops, VNC and SSH terminals. Every key, letters and digits
  included, goes out as an Android key event with its key code and meta state, so Ctrl+C or Alt+Tab
  reach the app as combinations. Nothing is converted, learned or recorded, and no candidates show.
- An input method can send Android key events only: no scan codes or keysyms, no choice of remote
  protocol. Whether the app forwards the keys to the remote computer is the app's choice.
- No touchpad (the draft's split layout has one between the halves): a keyboard cannot move another
  app's pointer.

## Phone

A strip above every page: `Esc` `Tab` `Ctrl` `Win` `Alt` `←` `↑` `↓` `→` `Fn` (modifier names
follow the host system, below).

| Page | Row 1 | Row 2 | Row 3 | Bottom row |
|---|---|---|---|---|
| Letters | `q`–`p` | `a`–`l` | `⇧` `z`–`m` `⌫` | `?123` `,` hide keyboard, space, `.` Enter |
| Symbols | `1`–`0` | `@#$_&-+()/` | `=\<` `*"':;!?` `⌫` | `ABC` `,` hide, space, `.` Enter |
| More symbols | ``~`\|\^%=<>`` | `[]{}` | `?123` `*"':;!?` `⌫` | as above |
| Fn | `F1`–`F6` `Ins` `Home` `PgUp` | `F7`–`F12` `Del` `End` `PgDn` | shortcuts (below) | `ABC` `PrtSc` security combination, `Menu`, Enter |

The `Fn` key in the strip opens the Fn page and goes back to letters.

## Wide screen (tablet, unfolded foldable)

- **Full keyboard:** a strip `Esc` `F1`–`F12` `PrtSc` `Ins` `Del` `Home` `End` `PgUp` `PgDn`, then the
  five PC rows: `` ` `` `1`–`0` `-` `=` `⌫`; `Tab` `q`–`p` `[` `]` `\`; `Caps` `a`–`l` `;` `'` Enter;
  `⇧ Shift` `z`–`m` `,` `.` `/` `⇧ Shift` `↑`; the three modifiers, space, Alt and Ctrl, `←` `↓` `→`.
- **Split:** the phone pages in two phone-sized halves at the two sides, for both thumbs; the middle
  stays empty.
- Settings → Input modes → PC keys → On a large screen chooses between them.

## Modifiers

| Gesture | Effect | Drawn as |
|---|---|---|
| Tap | on for the next key, then off | accent |
| Two quick taps | on until tapped again | accent, underlined |
| Hold while another finger types | on while held | accent |

## Host system

| Host | Modifiers | Shortcuts row | Security combination |
|---|---|---|---|
| Windows | Ctrl · Win · Alt | Ctrl+C, Ctrl+V, Ctrl+X, Ctrl+Z, Ctrl+A, Alt+Tab, Win+D | Ctrl+Alt+Del |
| macOS | ⌃Ctrl · ⌥Opt · ⌘Cmd | ⌘C, ⌘V, ⌘X, ⌘Z, ⌘A, ⌘Tab, ⌘Space | ⌥⌘Esc |
| Linux | Ctrl · Super · Alt | Ctrl+C, Ctrl+V, Ctrl+X, Ctrl+Z, Ctrl+A, Alt+Tab, Super | Ctrl+Alt+Del |

Win, ⌘ and Super send the same key (Meta); only names and shortcuts change.

## Wording

Settings and the language choice call it "PC keys" (PC 按键 / PC 按鍵 / PC キー); the space bar
reads "PC". The layout is off until the person turns it on in Settings → Input modes, and is meant
to be fixed to the apps that need it with "Fix this mode here".
