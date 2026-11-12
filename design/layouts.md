# Flick, floating keyboard and key customization (PLAN §10.9)

Written by the app agent from PLAN §10.9 on 2026-11-11; it has no artboard. Keys keep the tokens of
`Keyboard.dc.html` (key and function colors, radius, label sizes).

## 12-key flick (Japanese)

Settings → Japanese → 12-key flick. Four rows of five keys:

| | | | | |
|---|---|---|---|---|
| ?123 | あ | か | さ | ⌫ |
| ☺ | た | な | は | Space |
| ABC | ま | や | ら | ⏎ |
| ー | ゛゜小 | わ | 、 | 。 |

- A tap types the key's あ-column kana; a flick left, up, right or down types the い, う, え, お
  kana (や: 「 ゆ 」 よ; わ: を ん ー; 、: ， ・ ： ；; 。: ？ ！ …).
- ゛゜小 turns the last kana typed into its next form (か→が, は→ば→ぱ, つ→っ→づ).
- The kana compose as romaji does: Space converts, Enter keeps the kana. ABC switches to English.
- The side columns are function keys; "Function keys on the other side" swaps them.

## Floating keyboard

The Floating keyboard tool (under More) or Settings → Keys → Keys and rows → Floating keyboard.
The keys, at most 380 dp or 80% of the screen wide, float over the app with a grip bar above the
candidate bar; dragging the grip moves them (kept on screen). The app keeps its whole window and
stays usable around the keyboard. A floating keyboard is never split; the PC layout never floats.

## Keys and rows

Settings → Keys → Keys and rows:

- Gap between keys (2–12 dp, 6 by default), label size (80–140%), space bar width (60–140%).
- Number row: the digits above the letters.
- Function keys on the other side: the bottom row and the ⇧ … ⌫ row mirrored, for the left thumb.
- Your long-press symbols: `letter=symbols`, one per line; the first symbol is drawn in the
  corner, the letter's accents follow in the popup.
