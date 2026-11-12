# Kotomo design drafts

The keyboard, candidate bar, panels and settings screens from the design agent (AGENTS.md,
"Division of work"), frozen into the repository on 2026-11-04 from the design canvas
"Kotomo Keyboard & Settings" (version 1793842804-3671). They are the reference for layout, wording
and tokens; the app's own resources (`app/src/main/res/values*/`) stay authoritative where the
app has deliberately moved on.

Start at `index.html`: it lays out every artboard in the canvas's rows, scaled, with a link to each
at full size. Serve the folder over HTTP, since the artboards load the shared keyboard component:

```bash
jwebserver -p 8000 -d "$PWD/design"   # JDK 18+; then open http://127.0.0.1:8000/
```

Each `*.dc.html` file is one artboard. `support.js` is Kotomo's own small renderer for their
template format (`{{expression}}`, `sc-for`, `sc-if`, `dc-import`, event handlers and `setState`);
the design tool's runtime is not part of this repository. `Keyboard.dc.html` is the shared
key-area component the other artboards import, with its parameters (`w`, `keyH`, `mode`, `split`,
`half`, `side`). `canvas.json` places the artboards and gives their titles and sizes; `index.html`
reads it, so a new artboard needs only its file and an entry there.

| Artboard | Title on the canvas | Size (dp) |
|---|---|---|
| `Main.dc.html` | 手机 · 混合整句 | 412×915 |
| `PhoneSegment.dc.html` | 手机 · 修改片段 | 412×915 |
| `PhoneChinese.dc.html` | 手机 · 中文整句与简繁 | 412×915 |
| `PhoneJapanese.dc.html` | 手机 · 日文变形与敬语 | 412×915 |
| `PhoneOneHanded.dc.html` | 手机 · 单手模式（右手） | 412×915 |
| `PhoneQuickPaste.dc.html` | 手机 · 快捷输入（刚复制与验证码） | 412×915 |
| `PhoneClipboard.dc.html` | 手机 · 剪贴板历史 | 412×915 |
| `FoldCover.dc.html` | Fold 8 · 外屏 | 475×751 |
| `FoldMain.dc.html` | Fold 8 · 展开横向 | 933×704 |
| `FoldMainPortrait.dc.html` | Fold 8 · 展开竖向 | 704×933 |
| `FoldTabletop.dc.html` | Fold 8 · 半折叠桌面姿态 | 704×933 |
| `Tablet.dc.html` | 平板 · 横屏 | 1280×800 |
| `SettingsHome.dc.html` | 设置 · 首页 | 412×1360 |
| `SettingsMixed.dc.html` | 设置 · 混合模式 | 412×1480 |
| `SettingsTablet.dc.html` | 设置 · 平板双栏 · 键盘布局与尺寸 | 1280×800 |
| `Keyboard.dc.html` | 组件 · 键盘按键区 | 412×248 |

`pc-layout.md` describes the PC / remote-control layout (PLAN §10.11) in text and key tables, written
from the user's remote-control keyboard design draft (2026-11-11); it has no artboard.
`toolbar.md` describes the toolbar's order and hidden tools, the More panel's tool grid and the edit
panel (PLAN §10.10); it has no artboard. `layouts.md` describes the 12-key flick layout, the floating
keyboard and the key customizations (PLAN §10.9).

Rules (AGENTS.md): no screenshots, icons or assets of other keyboards here; Gboard and MyApps-UI
inspire the interaction and the settings presentation only. Fonts are loaded from Google Fonts
(Noto Sans SC and JP, SIL Open Font License). A change to these drafts replaces the files here in
the same commit as the matching app or doc change.
