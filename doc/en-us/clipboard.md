# Clipboard

Kotomo keeps its own clipboard history, because Android clears the system clipboard at most 60
minutes after the last access (Android 13+) and keeps only one clip. The code lives in
`app/src/main/java/com/yuanzhe/kotomo/ime/clipboard/`: `ClipboardHistory.kt` (recording, storage,
classification), `VerificationCodes.kt` (code extraction), `ClipboardPanel.kt` (the panel in the
keyboard's place) and `QuickPaste.kt` (the strip above the keys).

## What is recorded

The keyboard listens with `ClipboardManager.OnPrimaryClipChangedListener` (only the default input
method and the focused app may read the clipboard, Android 10+) and hands each primary clip to
`ClipboardHistory.onPrimaryClip` with `allowed = ClipboardHistory.allowedIn(field, settings)`.

| Case | History | Quick-paste strip |
|---|---|---|
| Incognito mode | nothing | nothing |
| Password fields (`TYPE_TEXT_VARIATION_PASSWORD`, `…_VISIBLE_PASSWORD`, `…_WEB_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`) and fields with `IME_FLAG_NO_PERSONALIZED_LEARNING` | nothing | nothing |
| Clip marked `ClipDescription.EXTRA_IS_SENSITIVE` (`android.content.extra.IS_SENSITIVE`, also set by password managers before Android 13) | never | never |
| History switched off (`clipboard.history`) | nothing | offered (memory only) |
| Quick paste switched off (`clipboard.quick_paste`) | recorded | nothing |
| Anything else (URL and e-mail fields included) | recorded | offered |

- Only the first item's plain text is taken; URIs, intents and blank text are ignored, and text
  longer than 20,000 characters is not recorded.
- The time of a clip is the clipboard's own timestamp (`ClipDescription.getTimestamp`, UTC
  milliseconds), so reading the same clip again when the keyboard opens changes nothing.
- Copying text already in the history moves it to the front and keeps its pin.
- Clips from another device (`EXTRA_IS_REMOTE_DEVICE`) are treated like local ones.

## Pins, quick phrases, expiry and the cap

- Pinned items and quick phrases are kept until the person removes them.
- Other items expire after the retention (`clipboard.retention_days`, 7 by default, 1–90) and at
  most 200 of them are kept, newest first. Expiry runs when the file is read, with every change and
  through `ClipboardHistory.expire()`, which the keyboard calls when the panel opens.
- `ClipboardHistory.clearUnpinned()` deletes every item that is neither pinned nor a phrase (the
  Privacy page's Clear button).

## Classification

`ClipKind.of` sorts each clip with local rules, in this order: a bare code (4–8 digits, `123-456`,
or 4–8 capitals and digits with at least two digits), a link (one URL and nothing else), a phone
number (7–15 digits with a `+`, separators, or nine digits and more), a message of at most 160
characters whose likeliest code scores 70 or more, an address (Japanese prefecture and city shapes,
丁目 and 番地; Chinese province, city and district or road and number shapes; an English number and
street word; never with sentence punctuation), else plain text. A quick phrase shows the phrase
tag whatever its kind.

## Verification codes

`VerificationCodes.find` returns every plausible code in a text, likeliest first, each with a
score and a context snippet of about 12 characters on each side, cut with "…". When several are
plausible they are all shown, the first marked as likelier (PLAN §10.7).

- A text with no code keyword yields nothing, unless the whole text is a code. Keywords include
  code, verification, OTP, passcode, PIN, one-time, 2FA; 验证码, 驗證碼, 校验码, 动态码, 动态密码,
  确认码, 安全码, 认证码, 口令; 認証コード, 確認コード, 認証番号, 暗証番号, ワンタイム, パスコード.
  English keywords match between non-letters, so `您的OTP为…` is found.
- Candidates: one group of 4–8 digits; two equal groups of 3 or 4 digits joined by a space or a
  hyphen (pasted joined); 4–8 capitals and digits with at least two digits. Full-width digits and
  letters are read as ASCII; full-width punctuation is kept.
- Not codes: digit groups joined by `.`, `,`, `:` or `/` (amounts, times, dates), three or more
  groups or nine digits and more (phone numbers, dates, IDs), a leading `+`, a currency sign or
  code before, and a unit after (年, 月, 日, 分, 元, 円, %, yen, dollars, …).
- Score (points): 50 for six digits, 40 for four, 35 for five, seven or eight, 30 for letters and
  digits; plus 40 when a keyword is within 12 characters, 20 within 40; minus 30 after an account
  tail (尾号, ending in), 50 after an order word (订单, order, #), 40 after a phone word, 15 for a
  year from 1900 to 2099. Codes under 30 points are dropped; a bare code scores 100.

## Quick-paste strip

`QuickPaste` offers what was just copied for 60 seconds after copying, once per clip: pasting it,
closing the strip or copying something newer removes the offer, and the same clip (same text and
time) is not offered again. The strip shows a chip per code, the likeliest highlighted, then the
copied text; in number fields, or when the whole clip is the code, only the codes. Nothing of the
strip is written to disk.

## Storage format

The history lives in `files/clipboard/history.kcb` in the app's credential-protected storage.
Android backup and device transfer exclude it (`data_extraction_rules.xml`, `allowBackup="false"`).

```
offset  size  content
0       4     magic "KCLP"
4       1     container version (1)
5       12    AES-GCM IV, fresh for every write
17      n     AES-256-GCM ciphertext of the record list, 128-bit tag; AAD = bytes 0..4

record list (big-endian, java.io.DataOutputStream):
int     record count (header first, then one record per item)
per record:
  int   field count
  per field: writeUTF(key), int length, UTF-8 bytes of the value
```

- The key is the Android Keystore key `kotomo_clipboard` (AES-256, GCM, no padding); it never
  leaves the Keystore.
- Header fields: `format_version` (this build writes `1` and never lowers a newer value) and
  `updated_utc_ms`.
- Item fields: `id`, `text`, `time_utc_ms` (UTC milliseconds), `pinned` and `phrase` (`1` or `0`).
  Kinds and codes are derived when read, never stored.
- Fields this version does not know, in the header or in an item, are kept when the file is
  rewritten. A newer format version adds fields only; a change that is not compatible raises the
  container version.
- A file with a newer container version is left untouched: the history starts empty for that run
  and is never written, so newer data is not overwritten. A file that cannot be decrypted or
  parsed (a lost key, damage) is moved aside to `history.kcb.unreadable` and the history starts
  empty. When the Keystore cannot give the key, the history works in memory only.
- Every change rewrites the file through `history.kcb.tmp` and an atomic replace, on one background
  thread in submission order. The keyboard reads only the in-memory snapshot
  (`ClipboardHistory.items`).

## Privacy

- Nothing copied is logged, sent or used for learning; the code has no log calls with clip text.
- Sensitive fields, sensitive clips and incognito mode record nothing (table above).
- The history is encrypted at rest with a Keystore key and excluded from Android backup.
