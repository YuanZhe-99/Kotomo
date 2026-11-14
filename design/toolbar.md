# Toolbar, More and the edit panel (PLAN §10.10)

Written by the app agent from PLAN §10.10 on 2026-11-11; it has no artboard. The bar keeps the
look of the candidate bar in `Main.dc.html` when nothing is being typed.

## Toolbar

- More always comes first. After it come the person's tools, in their order, spread from one end of
  the bar to the other; a tool the bar has no room for waits under More.
- Tools: Clipboard, Writing help, Translate, Settings (on the bar by default, as in the design),
  then Edit text, Emoji, Next language, Dictionaries and Themes (under More by default).
- Settings → Layout and size → Toolbar: every tool with Up, Down and a switch that puts it on the bar
  or under More.

## More

The panel opens in the keyboard's place. At the top, "More tools": the tools not on the bar, four
to a row, each an icon over its label. Below them the quick settings as before (one-handed side,
split layout, Chinese script, fix the mode for this app, incognito, All settings), and the Back
bar at the bottom.

## Edit panel (Edit text)

In the keyboard's place, at the keyboard's height:

| Left (three fifths) | | | Right (two fifths) | |
|---|---|---|---|---|
| ⇤ Line start | ↑ | ⇥ Line end | Select all | |
| ← | Select | → | Cut | Copy |
| | ↓ | | Paste | ⌫ Delete |

- Select is a switch (highlighted with the accent container while on): the arrows and line start
  and end then extend the selection. Cut, Copy and Paste turn it off.
- The composition is committed when the panel opens, so the keys work on finished text.
- The Back bar returns to the keys.

## Autofill suggestions (Android 11 and later)

When an autofill service (a password manager) has suggestions for the field, they replace the idle
toolbar as a row of pill chips at the candidate pill's height, in the key color; Android draws
their content. Typing brings the candidates back; the chips return when the bar is idle again.
The tools stay one tap away: the strip starts with a More-shaped button that puts the suggestions
aside for this field and shows the toolbar, and while they are aside the toolbar starts with a key
icon that brings them back. A new field shows them again.
Settings → Layout and size → Toolbar → Autofill suggestions turns them off.
