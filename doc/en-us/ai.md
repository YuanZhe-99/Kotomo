# Writing help (on-device AI)

Kotomo's writing help proofreads and rewrites text with Gemini Nano through ML Kit GenAI (AICore).
It follows `PLAN.md` §11 and the behavior contract's AI rule.

## Rules

| Rule | How it is built |
|---|---|
| Off by default | `ai.enabled` is `false` ([settings-format.md](settings-format.md)); while off, the AI button only shows a notice and no GenAI client is created |
| Only when asked | Nothing runs until the person taps the AI button and then picks an action; a model download starts only from its Download button |
| Never on the key path | The keyboard only hands text over; inference runs in `AiActivity`, a foreground screen, because GenAI serves only the foreground app |
| Never in sensitive fields | Refused in sensitive fields (`Fields.sensitive`: passwords, `IME_FLAG_NO_PERSONALIZED_LEARNING`), in direct-typing fields (e-mail, URL) and in incognito mode |
| Preview, confirm, undo | Suggestions are shown first; the field changes only on Replace, and the candidate bar then offers Undo until the next key |
| Nothing leaves the device | Kotomo sends no text (ML Kit's own diagnostics are in `upstream.md` and the privacy policy). AICore downloads the model through the system; the network permission (merged from ML Kit, and used by Kotomo only for the downloads the person starts) carries no typed text |
| Nothing is logged or stored | Neither the text nor the results are logged; the hand-off lives only in memory, and the selected text is dropped from it when the screen closes without Replace (a configuration change such as a rotation keeps it) |

## The hand-off

1. The AI button reads the field through `getExtractedText`: the selection, or the whole field when
   nothing is selected and it is at most 2,000 characters. Longer text needs a selection.
2. The language is Japanese when the text has kana (or in Japanese mode without Latin letters),
   English otherwise; text with Chinese characters and no Latin letters is refused, because the
   GenAI writing APIs take English and Japanese only. The screen can switch the language.
3. `AiActivity` opens in its own task (`taskAffinity=""`), so closing it returns to the app being
   typed in.
4. On Replace, the keyboard checks, when the field comes back, that it is the same field
   (package and field ID) and that the original range still holds the original text. Then it
   replaces the range; otherwise it copies the suggestion to the clipboard and says so. That copy
   is an ordinary clip: the clipboard history and the quick-paste strip treat it like any other
   ([clipboard.md](clipboard.md)), and so they do the screen's Copy button.
5. Undo restores the original only while the replacement is still there unchanged.

## Actions

| Action | API |
|---|---|
| Proofread | Proofreading, input type keyboard |
| Rephrase, More formal, Friendlier, Longer, Shorter | Rewriting: `REPHRASE`, `PROFESSIONAL`, `FRIENDLY`, `ELABORATE`, `SHORTEN` |

The APIs take under 256 tokens. Text over 400 characters is split at sentence ends and line breaks,
each part converted alone and joined back. A single sentence longer than that fails with "too
long".

## Failures

| Failure | When |
|---|---|
| Unavailable | No Gemini Nano, an incompatible AICore, a needed system update, no disk space |
| Background | AICore refused a request from the background |
| Busy, quota | AICore is busy, or Android's per-app battery quota ran out |
| Too long | The request was over the token limit |
| Filtered | AICore declined the text (read from its message; beta1 has no code for it) |
| Failed | Anything else |

ML Kit GenAI proofreading and rewriting are at `1.0.0-beta1`, their latest release; the
background and quota cases have no error code there and are read from the message.
