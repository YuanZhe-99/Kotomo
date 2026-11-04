package com.yuanzhe.kotomo.ime

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.inputmethodservice.InputMethodService
import android.os.IBinder
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
import com.yuanzhe.kotomo.settings.MainActivity
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.view.WindowInsetsCompat.Type
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
import com.yuanzhe.kotomo.engine.Rime
import java.io.IOException

/**
 * Runs the Kotomo keyboard: spike S1's input method skeleton with the S2 Chinese and S4 English
 * engines.
 *
 * Notes: UI work runs on the main thread; the native engines run only on the single engine
 * thread ([engine]). Letters build a composition shown as composing text. In English mode the
 * candidates are the typed word first (space commits it; nothing is replaced automatically), then
 * the engine's corrections and completions, and after a committed word its next-word predictions.
 * In Chinese mode librime owns the composition: every key goes to the engine thread in order,
 * librime decides whether it belongs to the composition, and the main thread applies the result;
 * librime opens on the data already deployed; when the bundled data changed, [RimeDeployService]
 * deploys it in its own process and the new build replaces the old one when it is done, so typing
 * goes on meanwhile. Only before the very first deploy has finished do letters type directly. Mixed and Japanese modes still
 * show placeholder candidates until their engines arrive. Each update carries a
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
    /** Engine thread only: librime deployed and its session open. */
    private var rimeReady = false

    private lateinit var keyboard: KeyboardView
    private lateinit var candidateBar: CandidateBar
    private lateinit var panelHost: FrameLayout
    private lateinit var content: LinearLayout
    private var emojiPanel: ComposeView? = null
    private var candidatesPanel: ComposeView? = null
    /** Main thread: librime has a session (mirrors the engine thread's rimeReady). */
    private var rimeOpen = false
    /** Main thread: a toolbar notice shown for a moment, or null. */
    private var notice: String? = null

    private val composing = StringBuilder()
    private var candidates = emptyList<String>()
    private var generation = 0
    /** librime's latest preedit and candidates, as last applied on the main thread. */
    private var chinesePreedit = ""
    private var chineseCandidates = emptyList<String>()
    /** The last word committed in English mode, for predictions and bigram context. */
    private var previousWord: String? = null
    private var mode = Mode.MIXED
    private var symbols = false
    private var symbolPage = 0
    private var shift = Shift.OFF
    private var direct = false
    private var createdAt = 0L

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        mode = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_MODE, null)
            ?.let { name -> Mode.entries.firstOrNull { it.name == name } } ?: Mode.MIXED
        ContextCompat.registerReceiver(
            this, deployDone, IntentFilter(RimeDeployService.ACTION_DONE), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        engine.post {
            english = openEnglish()
            val files = RimeFiles(this)
            // The app ships compiled data; it only needs unpacking (once per version). Without it
            // (a build made without the Linux prebuild) the phone compiles in RimeDeployService.
            val unpackMs = runCatching { files.unpack(this) }.getOrDefault(-1L)
            files.user.mkdirs()
            if (files.prebuilt) {
                rimeReady = Rime.initialize(files.shared.path, files.user.path)
                if (unpackMs > 0) {
                    files.writeReport(unpackMs, 0, rimeReady)
                    files.writeStatus(if (rimeReady) RimeFiles.Status.Prebuilt else RimeFiles.Status.Failed("prebuilt data did not open"))
                }
            } else if (files.hasBuild) {
                rimeReady = Rime.initialize(files.shared.path, files.user.path)
            }
            val open = rimeReady
            val deploy = !files.prebuilt && !files.current
            main.post {
                rimeOpen = open
                if (deploy) startDeploy()
                refresh(0)
            }
        }
    }

    private val deployConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {}
        override fun onServiceDisconnected(name: ComponentName) {}
    }
    private var deployBound = false

    /** Binds to [RimeDeployService], which deploys on bind (main thread). */
    private fun startDeploy() {
        if (deployBound) return
        deployBound = bindService(Intent(this, RimeDeployService::class.java), deployConnection, BIND_AUTO_CREATE)
    }

    /** Receives the deploy process's result and swaps the new build in on the engine thread. */
    private val deployDone = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (deployBound) {
                unbindService(deployConnection)
                deployBound = false
            }
            if (!intent.getBooleanExtra(RimeDeployService.EXTRA_OK, false)) {
                refresh(0)
                return
            }
            engine.post {
                val files = RimeFiles(this@KotomoService)
                if (rimeReady) Rime.shutdown()
                rimeReady = false
                if (files.promote() || files.hasBuild) rimeReady = Rime.initialize(files.shared.path, files.user.path)
                val open = rimeReady
                main.post {
                    rimeOpen = open
                    clearChinese()
                    refresh(0)
                }
            }
        }
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
        createdAt = SystemClock.uptimeMillis()
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        val popups = PopupLayer(this)
        keyboard = KeyboardView(this, popups, ::onKey)
        candidateBar = CandidateBar(this, ::pick, ::toggleCandidates, ::onTool)
        panelHost = FrameLayout(this).apply { addView(keyboard) }
        keyboard.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (Probe.coldStartMs == null) Probe.coldStartMs = SystemClock.uptimeMillis() - createdAt
                keyboard.post { keyboard.viewTreeObserver.removeOnDrawListener(this) }
            }
        })
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(candidateBar)
            addView(panelHost)
        }
        val root = InputRoot(this, content, popups).apply { setBackgroundColor(getColor(R.color.kb_background)) }
        // The window reaches under the navigation bar, where the system draws its own keyboard
        // buttons; the keys stay above it on every device and posture. The insets are read on the
        // window's decor view, since the input view's parents do not pass them on.
        window.window?.decorView?.let { decor ->
            ViewCompat.setOnApplyWindowInsetsListener(decor) { v, insets ->
                content.updatePadding(bottom = bottomInset(insets))
                ViewCompat.onApplyWindowInsets(v, insets)
            }
            ViewCompat.getRootWindowInsets(decor)?.let { content.updatePadding(bottom = bottomInset(it)) }
            ViewCompat.requestApplyInsets(decor)
        }
        return root
    }

    /** The bottom space the system keeps for itself: the navigation bar or its tappable buttons. */
    private fun bottomInset(insets: WindowInsetsCompat) = maxOf(
        insets.getInsets(Type.navigationBars()).bottom,
        insets.getInsets(Type.tappableElement()).bottom,
    )

    /** Whether the window is wide enough for the split layout (unfolded foldables, tablets). */
    private fun isSplit() =
        resources.configuration.screenWidthDp * resources.displayMetrics.density >= resources.getDimension(R.dimen.kb_split_min_width)

    /** Opens or closes the page of every candidate in the keyboard's place. */
    private fun toggleCandidates() {
        if (panelHost.getChildAt(0) === candidatesPanel) {
            showKeyboard()
            return
        }
        val height = keyboard.height
        val panel = candidatesPanel ?: ComposeView(this).also { view ->
            candidatesPanel = view
            view.setContent {
                CandidatesPanel(
                    candidates = shownCandidates,
                    onPick = { index ->
                        showKeyboard()
                        pick(index)
                    },
                    onBack = ::showKeyboard,
                )
            }
        }
        shownCandidates.value = candidates
        panelHost.removeAllViews()
        panelHost.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    }

    private val shownCandidates = androidx.compose.runtime.mutableStateOf(emptyList<String>())

    /** Runs a toolbar button: settings opens the app; the rest arrive with later phases. */
    private fun onTool(tool: Tool) {
        when (tool) {
            Tool.SETTINGS -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Tool.MORE, Tool.CLIPBOARD, Tool.AI, Tool.TRANSLATE -> {
                notice = getString(R.string.bar_not_yet)
                refresh(0)
                main.postDelayed({
                    notice = null
                    refresh(0)
                }, NOTICE_MS)
            }
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        direct = isDirect(info)
        symbolPage = 0
        symbols = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }
        shift = Shift.OFF
        composing.clear()
        clearChinese()
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
        clearChinese()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // The cursor left the composition (a tap elsewhere in the field): keep the text as typed.
        if ((composing.isNotEmpty() || chinesePreedit.isNotEmpty()) && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            composing.clear()
            clearChinese()
            previousWord = null
            refresh(0)
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        unregisterReceiver(deployDone)
        if (deployBound) unbindService(deployConnection)
        engine.post {
            english?.close()
            if (rimeReady) Rime.shutdown()
        }
        engineThread.quitSafely()
        super.onDestroy()
    }

    private fun onKey(action: KeyAction, longPress: Boolean, downTime: Long) {
        val ic = currentInputConnection ?: return
        if (action is KeyAction.SetMode) {
            commitComposition()
            previousWord = null
            mode = action.mode
            getSharedPreferences(PREFS, MODE_PRIVATE).edit { putString(KEY_MODE, mode.name) }
            refresh(downTime)
            return
        }
        if (mode == Mode.CHINESE && !direct && onChineseKey(action, longPress, downTime)) {
            refresh(0)
            return
        }
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
                    editorEnter()
                }
            }
            KeyAction.Shift -> shift = when (shift) {
                Shift.OFF -> Shift.ONCE
                Shift.ONCE -> Shift.LOCKED
                Shift.LOCKED -> Shift.OFF
            }
            is KeyAction.Symbols -> {
                symbols = true
                symbolPage = action.page
            }
            KeyAction.Letters -> symbols = false
            KeyAction.Emoji -> {
                commitComposition()
                previousWord = null
                showEmoji()
            }
            is KeyAction.SetMode -> Unit
        }
        refresh(downTime)
    }

    /**
     * Sends a Chinese-mode key to librime on the engine thread.
     * Returns: true when the key went to librime (the result arrives later), false for keys the
     * normal path handles (shift, page switches, Emoji).
     */
    private fun onChineseKey(action: KeyAction, longPress: Boolean, downTime: Long): Boolean {
        val ic = currentInputConnection ?: return false
        when (action) {
            is KeyAction.Text -> {
                val c = action.text.singleOrNull()
                if (c != null && c in 'a'..'z' && !longPress) {
                    rimeKey(c.code, downTime) { ic.commitText(action.text, 1) }
                } else {
                    // Punctuation, digits and capitals commit the top candidate first.
                    rimeKey(SPACE, downTime, onlyIfComposing = true) {}
                    engine.post { main.post { ic.commitText(action.text, 1) } }
                }
                if (shift == Shift.ONCE) shift = Shift.OFF
            }
            KeyAction.Space -> rimeKey(SPACE, downTime) { ic.commitText(" ", 1) }
            KeyAction.Backspace -> rimeKey(BACKSPACE, downTime) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) }
            KeyAction.Enter -> rimeKey(RETURN, downTime) { editorEnter() }
            else -> return false
        }
        return true
    }

    /**
     * Runs [keycode] through librime on the engine thread and applies the result on the main thread;
     * [fallback] runs when librime did not take the key (or is not ready yet).
     * Inputs: [onlyIfComposing] sends the key only while librime has a composition, and then skips
     * the fallback otherwise.
     * Notes: results are applied in key order and never dropped, since they can carry commits.
     */
    private fun rimeKey(keycode: Int, downTime: Long, onlyIfComposing: Boolean = false, fallback: () -> Unit) {
        engine.post {
            val composingNow = rimeReady && Rime.getComposition().isNotEmpty()
            val skip = onlyIfComposing && !composingNow
            val handled = rimeReady && !skip && Rime.processKey(keycode, 0)
            val state = rimeState(handled)
            main.post {
                applyChinese(state, downTime)
                if (!state.handled && !skip) fallback()
            }
        }
    }

    /** librime's state after one call, read on the engine thread. */
    private class RimeState(val handled: Boolean, val commit: String?, val preedit: String, val candidates: List<String>)

    private fun rimeState(handled: Boolean) = if (rimeReady) {
        RimeState(handled, Rime.getCommit(), Rime.getComposition(), Rime.getCandidates(CHINESE_CANDIDATES).map { it.text })
    } else {
        RimeState(false, null, "", emptyList())
    }

    /** Applies a librime result to the field and the candidate bar (main thread). */
    private fun applyChinese(state: RimeState, downTime: Long) {
        val ic = currentInputConnection ?: return
        when {
            state.commit != null -> ic.commitText(state.commit, 1)
            state.preedit.isEmpty() && chinesePreedit.isNotEmpty() -> ic.commitText("", 1)
        }
        if (state.preedit.isNotEmpty()) ic.setComposingText(state.preedit, 1)
        chinesePreedit = state.preedit
        chineseCandidates = state.candidates
        if (mode == Mode.CHINESE) {
            candidates = chineseCandidates
            candidateBar.show(candidates, downTime)
            shownCandidates.value = candidates
        }
    }

    /** Drops librime's composition without committing (the field keeps what it shows). */
    private fun clearChinese() {
        if (chinesePreedit.isEmpty() && chineseCandidates.isEmpty()) return
        chinesePreedit = ""
        chineseCandidates = emptyList()
        engine.post { if (rimeReady) Rime.clear() }
    }

    /** Performs the field's editor action, or types a newline when it has none. */
    private fun editorEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val editorAction = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val useAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0 &&
            editorAction != EditorInfo.IME_ACTION_NONE && editorAction != EditorInfo.IME_ACTION_UNSPECIFIED
        if (useAction) ic.performEditorAction(editorAction) else sendKeyChar('\n')
    }

    /**
     * Commits candidate [index] in place of the composition (or a predicted word).
     * Side effects: in English mode also types a space and asks for next-word predictions.
     */
    private fun pick(index: Int) {
        if (mode == Mode.CHINESE && !direct) {
            engine.post {
                val handled = rimeReady && Rime.selectCandidate(index)
                val state = rimeState(handled)
                main.post { applyChinese(state, 0) }
            }
            return
        }
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
        if (chinesePreedit.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            clearChinese()
        }
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
        val split = isSplit()
        keyboard.split = split
        keyboard.layout = if (symbols) Layout.symbols(mode, split, symbolPage) else Layout.letters(mode, shift != Shift.OFF, split)
        keyboard.shift = shift
        keyboard.mode = mode
        val raw = composing.toString()
        val gen = ++generation
        candidates = when {
            direct -> emptyList()
            mode == Mode.CHINESE -> chineseCandidates
            mode == Mode.ENGLISH -> if (raw.isEmpty()) emptyList() else listOf(raw)
            raw.isEmpty() -> emptyList()
            else -> listOf(raw, raw.replaceFirstChar(Char::uppercase), raw.uppercase()).distinct()
        }
        candidateBar.show(candidates, downTime)
        shownCandidates.value = candidates
        candidateBar.hint = notice ?: chineseHint()
        if (!direct && mode == Mode.ENGLISH && (raw.isNotEmpty() || previousWord != null)) suggestEnglish(raw, previousWord, gen)
    }

    /** Says why Chinese mode cannot convert yet (dictionary being prepared or failed), or null. */
    private fun chineseHint(): String? {
        if (mode != Mode.CHINESE || direct || rimeOpen) return null
        return when (RimeFiles(this).readStatus()) {
            is RimeFiles.Status.Failed -> getString(R.string.bar_rime_failed)
            else -> getString(R.string.bar_rime_preparing)
        }
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
                shownCandidates.value = candidates
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
        const val NOTICE_MS = 1500L
        const val CHINESE_CANDIDATES = 30
        // X11 keysyms that librime takes.
        const val SPACE = 0x20
        const val BACKSPACE = 0xff08
        const val RETURN = 0xff0d

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
