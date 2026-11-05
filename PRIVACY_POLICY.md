# Kotomo privacy policy

Kotomo is an input method (keyboard) for Chinese, English and Japanese. This policy describes what
the app stores, learns and sends, as built. It changes in the same commit as anything that changes
those (AGENTS.md, "Documentation maintenance").

## In short

- Typing works offline. This version has no internet permission. When downloadable dictionaries
  arrive, the network will be used only for downloads you start, disclosed before the first one,
  and never to send what you type.
- What you type stays on your device. Kotomo has no analytics, no advertising, no crash upload and
  no account.
- In password fields, in fields that ask not to be learned from, and in incognito mode, Kotomo
  learns nothing and keeps no history.

## What Kotomo keeps on the device

All of it lives in the app's private storage, which other apps cannot read; Android's cloud backup
and device-to-device transfer are turned off for it.

| Data | Why | Where it is not kept |
|---|---|---|
| Your settings | So the keyboard behaves as you chose | — |
| Chinese learning data (librime's user dictionary: words and how often you chose them) | Better candidates over time | Sensitive fields, incognito mode |
| Recently used emoji (up to 32) | The Recents group of the emoji panel | Sensitive fields, incognito mode |
| The last input mode used in each app (the app's package name and the mode only) | The keyboard comes back in that mode | Sensitive fields, incognito mode |
| Diagnostics: timings of key presses and drawing, memory use, the state of the dictionaries | The Diagnostics page, to find problems | Never what you type |

Japanese conversion (Mozc) and English suggestions do not learn from what you type.

## What Kotomo never does

- It never sends what you type, your candidates, your composition or your clipboard anywhere.
- It never writes what you type to a log.
- It never shows ads or collects analytics.

## Sensitive fields

Password fields, fields that set `IME_FLAG_NO_PERSONALIZED_LEARNING`, e-mail and URL fields get
direct typing: no composition, no candidates, no learning, no history. Incognito mode (in the
settings and the keyboard's quick settings) turns off learning and history everywhere.

## Deleting your data

Uninstalling Kotomo, or clearing its storage in Android's app settings, removes everything listed
above.

## Features that are not there yet

Clipboard history, on-device AI (Gemini Nano), translation, downloadable (online) dictionaries and
sync are planned; online dictionaries will add the internet permission. Each will be described here, and disclosed in the app before its first use, in the same
change that adds it; none of them will send what you type anywhere without your explicit choice.

## Contact

Kotomo is open source under GPL-3.0. Questions and reports go to the project's public issue
tracker.
