# Kotomo privacy policy

Kotomo is an input method (keyboard) for Chinese, English and Japanese. This policy describes what
the app stores, learns and sends, as built. It changes in the same commit as anything that changes
those (AGENTS.md, "Documentation maintenance").

## In short

- Typing works offline, and what you type is never sent over the network. The app holds the
  internet permission, which the ML Kit library for on-device AI declares; Kotomo itself uses the
  network only for dictionary downloads you start, after a notice before the first one (see
  "Downloaded dictionaries").
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
| Mixed mode's learned readings: the letters you typed and the candidate you chose for them, how often and when; listed on Settings → Privacy → See what was learned, where each can be ranked lower, hidden, kept first or deleted; emptied by Clear learning data; fades by half every 30 days without use | The reading you chose comes first next time | Password fields, fields that ask not to be learned from, incognito mode (nothing is recorded there, and what was learned is not used there) |
| Clipboard history: what you copy while Kotomo is the keyboard, encrypted with a key kept in Android's keystore, removed after the retention you set (7 days by default) unless you pin it; can be turned off and cleared in the settings (turning it off keeps what is already saved until it expires or you delete it), searched, edited and deleted item by item or several at once on Settings → Privacy → See, search and edit the history, and turned off for apps you choose ("Not in this app" in the keyboard's clipboard panel). Expired items are removed while the keyboard runs, without waiting for its next use. Kotomo cannot tell where text was copied from: a password copied from an app that does not mark it sensitive is kept like any other text if you then use the keyboard in an ordinary field | Pasting it back from the keyboard, and the quick-paste strip for something just copied (60 s) | Password fields, fields that ask not to be learned from, incognito mode, clips that apps mark sensitive, and apps you excluded (Kotomo can only tell which app you are typing in, not where text was copied from) |
| The time of the last clip refused in a sensitive field or incognito mode (a number only, never the text), so it is not recorded later in another field | Keeping a refusal across a restart of the keyboard | — |
| Your dictionary: the words you add, import or make with an AI model (word, reading, priority, where it came from), the previous version of that list so a change can be undone, the switches of the dictionary page, and librime's tables made from them. Imported files are read only when you pick them and are not kept; exported files go where you save them. With password protection on (off by default), the list, its previous version and exports are encrypted with a key derived from your password, which Kotomo never stores and cannot recover; the tables the keyboard types from stay readable in the app's private storage until you tap Lock now | Your own words among the candidates | — (the words are yours; sensitive fields still learn nothing new) |
| Recently used emoji (up to 32) and how often each was sent (up to 200 counts) | The Frequently used and Recents groups of the emoji panel | Sensitive fields, incognito mode |
| Per app: the last input mode used there, the mode you fixed for it, the Chinese script you chose there, and whether you turned off learning there or made its fields type code (the app's package name and those choices only) | The keyboard comes back in that mode and script | Sensitive fields, incognito mode |
| Writing help (off by default): the text you hand to it, held in memory only while its screen is open and dropped when the screen closes; runs on the device with Gemini Nano, and Android's AICore downloads the model from Google when you tap Download | Proofreading and rewriting you ask for | Never stored or logged; never in sensitive fields or incognito mode |
| Downloaded dictionaries: the words of each dictionary you downloaded, its previous version, where it came from and its license, and whether you accepted the download notice. The downloaded files are converted and then deleted | The downloaded words among the candidates | — (the files hold none of your data) |
| Diagnostics: timings of key presses and drawing, memory use, the state of the dictionaries | The Diagnostics page, to find problems | Never what you type |

Japanese conversion (Mozc) and English suggestions do not learn from what you type.

## What Kotomo never does

- It never sends what you type, your candidates, your composition or your clipboard anywhere.
- It never writes what you type to a log.
- It never shows ads or collects analytics.

## Sensitive fields

Password fields and fields that set `IME_FLAG_NO_PERSONALIZED_LEARNING` are sensitive: nothing
typed in them is learned, no clipboard history is recorded, no emoji recents or per-app settings are
kept, and writing help is not offered. Password fields also get direct typing (every key types its
letter, with no composition or candidates); a field that only asks for no learning still converts
Chinese and Japanese. E-mail and URL fields get direct typing too. Incognito mode (in the settings
and the keyboard's quick settings) turns off learning and history everywhere; "Don't learn in this app"
in the keyboard's More panel does the same for one app. Suggestions from your autofill service
(a password manager, for example) are drawn by Android in Kotomo's bar; Kotomo never sees their text,
and a tap fills the field without passing through the keyboard (Settings → Layout and size → Toolbar
turns them off).

## Deleting your data

Settings → Privacy → Clear learning data deletes the learned words, the emoji history and the
modes and scripts remembered per app (the modes you fixed for apps, your settings and the
clipboard history stay; the clipboard has its own Clear). Uninstalling Kotomo, or clearing its storage in Android's app settings, removes everything listed
above.

## Downloaded dictionaries

Settings → Dictionaries → Get dictionaries lists dictionaries published by their own authors
(rime-ice, THUOCL, fcitx5-pinyin-zhwiki). Kotomo connects to the network only when you tap
Download or Update on one of them, and only after you accepted, once, a notice that names the sites
it connects to: `api.github.com`, `github.com` and `release-assets.githubusercontent.com` (GitHub
releases) and `raw.githubusercontent.com` (files on GitHub). If you do not accept it, nothing is
downloaded.

- A download is an ordinary HTTPS request for a public file. It carries no identifier, no account,
  nothing you typed, none of your words and nothing from your clipboard; like any connection, it
  shows your IP address to the site, under GitHub's own privacy statement.
- Files from GitHub releases are checked against the checksum GitHub publishes for them; files
  whose source publishes no checksum are protected by the secure connection only, and the page says
  so. Only HTTPS is used.
- The files are converted on the device and deleted; the words stay until you remove the
  dictionary. Downloads run in the background and can be cancelled; a failed or cancelled download
  changes nothing.

## Features that are not there yet

Translation and sync are planned. Each will be described here, and disclosed in the app before its
first use, in the same change that adds it; none of them will send what you type anywhere without
your explicit choice.

## Contact

Kotomo is open source under GPL-3.0. Questions and reports go to the project's public issue
tracker.
