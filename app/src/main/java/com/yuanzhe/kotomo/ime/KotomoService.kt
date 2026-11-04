package com.yuanzhe.kotomo.ime

import android.inputmethodservice.InputMethodService
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.yuanzhe.kotomo.R
import com.yuanzhe.kotomo.engine.English
import java.io.IOException

/**
 * Runs the Kotomo keyboard: spike S1's input method skeleton with the S4 English engine.
 *
 * Notes: UI work runs on the main thread; the native engines run only on the single engine
 * thread ([engine]). Letters build a composition shown as composing text. In English mode the
 * candidates are the typed word first (space commits it; nothing is replaced automatically), then
 * the engine's corrections and completions, and after a committed word its next-word predictions.
 * Other modes still show placeholder candidates until their engines arrive. Each update carries a
 * generation number and a stale engine result never replaces a newer one. Sensitive fields
 * (passwords, no-personalized-learning, e-mail and URL fields) get direct typing with no
 * composition and no candidates. The service is its own LifecycleOwner and SavedStateRegistryOwner
 * so Compose panels can run in its window.
 */
class KotomoService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val engineThread = HandlerThread("kotomo-engine").also { it.start() }
    /** The engine thread's queue; the only place native engines are called. */
    private val engine = Handler(engineThread.looper)
    private val main = Handler(Looper.getMainLooper())
    /** Engine thread only. */
    private var english: English? = null

    private lateinit var keyboard: KeyboardView
    private lateinit var candidateBar: CandidateBar
    private lateinit var panelHost: FrameLayout
    private var emojiPanel: ComposeView? = null

    private val composing = StringBuilder()
    private var candidates = emptyList<String>()
    private var generation = 0
    /** The last word committed in English mode, for predictions and bigram context. */
    private var previousWord: String? = null
    private var mode = Mode.MIXED
    private var symbols = false
    private var shift = Shift.OFF
    private var direct = false
    private var createdAt = 0L

    override fun onCreate() {
        super.onCreate()
        createdAt = SystemClock.uptimeMillis()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MODE, null)
            ?.let { name -> Mode.entries.firstOrNull { it.name == name } } ?: Mode.MIXED
        engine.post { english = openEnglish() }
    }

    /**
     * Opens the English dictionary in place from the APK (engine thread).
     * Returns: the engine, or null when the asset is missing or unreadable; English mode then
     * falls back to the typed word alone.
     */
    private fun openEnglish(): English? = try {
        assets.openFd(ENGLISH_DICT).use { English.open(applicationInfo.sourceDir, it.startOffset, it.length) }
    } catch (_: IOException) {
        null
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        keyboard = KeyboardView(this, ::onKey)
        candidateBar = CandidateBar(this, ::pick)
        panelHost = FrameLayout(this).apply { addView(keyboard) }
        keyboard.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (Probe.coldStartMs == null) Probe.coldStartMs = SystemClock.uptimeMillis() - createdAt
                keyboard.post { keyboard.viewTreeObserver.removeOnDrawListener(this) }
            }
        })
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.kb_background))
            addView(candidateBar)
            addView(panelHost)
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
                insets
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        direct = isDirect(info)
        symbols = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }
        shift = Shift.OFF
        composing.clear()
        previousWord = null
        showKeyboard()
        refresh(0)
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        Thread {
            val info = Debug.MemoryInfo()
            Debug.getMemoryInfo(info)
            Probe.pssKb = info.totalPss.toLong()
        }.start()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        Probe.save(this)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        currentInputConnection?.finishComposingText()
        composing.clear()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // The cursor left the composition (a tap elsewhere in the field): keep the text as typed.
        if (composing.isNotEmpty() && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            composing.clear()
            previousWord = null
            refresh(0)
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        engine.post { english?.close() }
        engineThread.quitSafely()
        super.onDestroy()
    }

    private fun onKey(action: KeyAction, longPress: Boolean, downTime: Long) {
        val ic = currentInputConnection ?: return
        when (action) {
            is KeyAction.Text -> {
                val letter = action.text.length == 1 && action.text[0].let { it in 'a'..'z' || it in 'A'..'Z' }
                if (letter && !direct && !longPress) {
                    composing.append(action.text)
                    ic.setComposingText(composing, 1)
                } else {
                    commitComposition()
                    ic.commitText(action.text, 1)
                    previousWord = null
                }
                if (shift == Shift.ONCE) shift = Shift.OFF
            }
            KeyAction.Space -> if (composing.isNotEmpty()) {
                pick(0)
                return
            } else {
                ic.commitText(" ", 1)
                previousWord = null
            }
            KeyAction.Backspace -> if (composing.isNotEmpty()) {
                composing.setLength(composing.length - 1)
                if (composing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(composing, 1)
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                previousWord = null
            }
            KeyAction.Enter -> {
                previousWord = null
                if (composing.isNotEmpty()) {
                    commitComposition()
                } else {
                    val info = currentInputEditorInfo
                    val editorAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
                    val useAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
                        editorAction != EditorInfo.IME_ACTION_NONE && editorAction != EditorInfo.IME_ACTION_UNSPECIFIED
                    if (useAction) ic.performEditorAction(editorAction) else sendKeyChar('\n')
                }
            }
            KeyAction.Shift -> shift = when (shift) {
                Shift.OFF -> Shift.ONCE
                Shift.ONCE -> Shift.LOCKED
                Shift.LOCKED -> Shift.OFF
            }
            KeyAction.Symbols -> symbols = true
            KeyAction.Letters -> symbols = false
            KeyAction.Emoji -> {
                commitComposition()
                previousWord = null
                if (longPress) {
                    mode = Mode.entries[(mode.ordinal + 1) % Mode.entries.size]
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit { putString(KEY_MODE, mode.name) }
                } else {
                    showEmoji()
                }
            }
        }
        refresh(downTime)
    }

    /**
     * Commits candidate [index] in place of the composition (or a predicted word).
     * Side effects: in English mode also types a space and asks for next-word predictions.
     */
    private fun pick(index: Int) {
        val text = candidates.getOrNull(index) ?: return
        val ic = currentInputConnection ?: return
        if (mode == Mode.ENGLISH) {
            ic.commitText("$text ", 1)
            previousWord = text
        } else {
            ic.commitText(text, 1)
        }
        composing.clear()
        refresh(0)
    }

    private fun commitComposition() {
        if (composing.isEmpty()) return
        currentInputConnection?.finishComposingText()
        composing.clear()
    }

    /**
     * Redraws the keyboard page and the candidates after a change.
     * Inputs: [downTime] is the touch that caused the change (0 for none), for [Probe].
     * Notes: the first candidates are drawn at once; engine results follow as a second update
     * that is dropped if anything changed in between.
     */
    private fun refresh(downTime: Long) {
        if (!::keyboard.isInitialized) return
        keyboard.layout = if (symbols) Layout.symbols(mode) else Layout.letters(mode, shift != Shift.OFF)
        keyboard.shift = shift
        keyboard.modeBadge = getString(
            when (mode) {
                Mode.MIXED -> R.string.mode_badge_mixed
                Mode.CHINESE -> R.string.mode_badge_chinese
                Mode.JAPANESE -> R.string.mode_badge_japanese
                Mode.ENGLISH -> R.string.mode_badge_english
            },
        )
        keyboard.spaceLabel = getString(
            when (mode) {
                Mode.MIXED -> R.string.mode_mixed
                Mode.CHINESE -> R.string.mode_chinese
                Mode.JAPANESE -> R.string.mode_japanese
                Mode.ENGLISH -> R.string.mode_english
            },
        )
        val raw = composing.toString()
        val gen = ++generation
        candidates = when {
            direct -> emptyList()
            mode == Mode.ENGLISH -> if (raw.isEmpty()) emptyList() else listOf(raw)
            raw.isEmpty() -> emptyList()
            else -> listOf(raw, raw.replaceFirstChar(Char::uppercase), raw.uppercase()).distinct()
        }
        candidateBar.show(candidates, downTime)
        if (!direct && mode == Mode.ENGLISH && (raw.isNotEmpty() || previousWord != null)) suggestEnglish(raw, previousWord, gen)
    }

    /** Asks the engine thread for English suggestions and shows them if still current. */
    private fun suggestEnglish(typed: String, previous: String?, gen: Int) {
        engine.post {
            val words = english?.suggestions(typed, previous = previous)?.map { it.word }.orEmpty()
            main.post {
                if (gen != generation) return@post
                val capital = typed.firstOrNull()?.isUpperCase() == true
                val shown = words.map { if (capital) it.replaceFirstChar(Char::uppercase) else it }
                candidates = if (typed.isEmpty()) shown.distinct() else (listOf(typed) + shown).distinct()
                candidateBar.show(candidates, 0)
            }
        }
    }

    private fun showKeyboard() {
        if (!::panelHost.isInitialized || panelHost.getChildAt(0) === keyboard) return
        panelHost.removeAllViews()
        panelHost.addView(keyboard)
    }

    private fun showEmoji() {
        val height = keyboard.height
        val panel = emojiPanel ?: ComposeView(this).also { view ->
            emojiPanel = view
            val attachedAt = SystemClock.uptimeMillis()
            view.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                override fun onDraw() {
                    if (Probe.composeFirstFrameMs == null) Probe.composeFirstFrameMs = SystemClock.uptimeMillis() - attachedAt
                    view.post { view.viewTreeObserver.removeOnDrawListener(this) }
                }
            })
            view.setContent {
                EmojiPanel(
                    onEmoji = { currentInputConnection?.commitText(it, 1) },
                    onBack = ::showKeyboard,
                )
            }
        }
        panelHost.removeAllViews()
        panelHost.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    }

    private companion object {
        const val PREFS = "ime"
        const val KEY_MODE = "mode"
        const val ENGLISH_DICT = "main_en_us.dict"

        /**
         * Decides whether a field gets direct typing: no composition, no candidates, no learning.
         *
         * Notes: the behavior contract's sensitive fields (passwords, IME_FLAG_NO_PERSONALIZED_LEARNING),
         * plus fields where a composition gets in the way (e-mail, URL, raw key input).
         */
        fun isDirect(info: EditorInfo): Boolean {
            if (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
            val type = info.inputType
            val variation = type and InputType.TYPE_MASK_VARIATION
            return when (type and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_NULL -> true
                InputType.TYPE_CLASS_TEXT -> variation in setOf(
                    InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                    InputType.TYPE_TEXT_VARIATION_URI,
                )
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }
    }
}
