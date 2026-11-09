# Kotomo privacy policy

Kotomo is an input method (keyboard) for Chinese, English and Japanese. This policy describes what
the app stores, learns and sends, as built. It changes in the same commit as anything that changes
those (AGENTS.md, "Documentation maintenance").

## In short

- Typing works offline, and what you type is never sent over the network. The app holds the
  internet permission, which the ML Kit library for on-device AI declares; Kotomo itself uses the
  network only for downloads you start (downloadable dictionaries, when they arrive), disclosed
  before the first one.
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
| Clipboard history: what you copy while Kotomo is the keyboard, encrypted with a key kept in Android's keystore, removed after the retention you set (7 days by default) unless you pin it; can be turned off and cleared in the settings (turning it off keeps what is already saved until it expires or you delete it). Kotomo cannot tell where text was copied from: a password copied from an app that does not mark it sensitive is kept like any other text if you then use the keyboard in an ordinary field | Pasting it back from the keyboard, and the quick-paste strip for something just copied (60 s) | Password fields, fields that ask not to be learned from, incognito mode, clips that apps mark sensitive |
| Recently used emoji (up to 32) | The Recents group of the emoji panel | Sensitive fields, incognito mode |
| The last input mode used in each app (the app's package name and the mode only) | The keyboard comes back in that mode | Sensitive fields, incognito mode |
| Writing help (off by default): the text you hand to it, held in memory only while its screen is open and dropped when the screen closes; runs on the device with Gemini Nano, and Android's AICore downloads the model from Google when you tap Download | Proofreading and rewriting you ask for | Never stored or logged; never in sensitive fields or incognito mode |
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

Translation, downloadable (online) dictionaries and
sync are planned. Each will be described here, and disclosed in the app before its first use, in the same
change that adds it; none of them will send what you type anywhere without your explicit choice.

## Contact

Kotomo is open source under GPL-3.0. Questions and reports go to the project's public issue
tracker.
