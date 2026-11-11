package com.yuanzhe.kotomo.ime

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.inputmethodservice.InputMethodService
import android.os.IBinder
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.TextAttribute
import android.content.ClipData
import com.yuanzhe.kotomo.ai.AiActivity
import com.yuanzhe.kotomo.ai.AiHandoff
import com.yuanzhe.kotomo.ai.AiLanguage
import com.yuanzhe.kotomo.ai.AiRequest
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.yuanzhe.kotomo.settings.MainActivity
import android.content.ClipboardManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.yuanzhe.kotomo.ime.clipboard.ClipboardHistory
import com.yuanzhe.kotomo.ime.clipboard.ClipboardPanel
import com.yuanzhe.kotomo.ime.clipboard.QuickPaste
import com.yuanzhe.kotomo.ime.clipboard.QuickPasteStrip
import com.yuanzhe.kotomo.settings.ChineseScript
import com.yuanzhe.kotomo.settings.Settings
import com.yuanzhe.kotomo.settings.SettingsStore
import com.yuanzhe.kotomo.settings.SplitLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
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
import com.yuanzhe.kotomo.dictionary.DictionaryStore
import com.yuanzhe.kotomo.engine.English
import com.yuanzhe.kotomo.engine.Mozc
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import java.io.File
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
 * In Japanese mode Mozc converts the romaji: the composition shows kana, the first candidate is the
 * whole sentence, Enter keeps the kana.
 * In Chinese mode librime owns the composition: every key goes to the engine thread in order,
 * librime decides whether it belongs to the composition, and the main thread applies the result;
 * librime opens on the data already deployed; when the bundled data changed, [RimeDeployService]
 * deploys it in its own process and the new build replaces the old one when it is done, so typing
 * goes on meanwhile. Only before the very first deploy has finished do letters type directly. Mixed mode uses librime too until
 * the mixed coordinator (S6) exists. Each update carries a
 * generation number and a stale engine result never replaces a newer one. Password, e-mail, URL
 * and raw-key fields get direct typing with no composition and no candidates; sensitive fields
 * ([Fields.sensitive]: passwords and fields asking for no personalized learning) learn and record
 * nothing, while a field that only asks for no learning still converts Chinese and Japanese. The service is its own LifecycleOwner and SavedStateRegistryOwner
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
    /** Engine thread only: Mozc, opened on first use of Japanese mode (null until then or without data). */
    private var mozc: Mozc? = null
    private var mozcTried = false
    /** Engine thread only: librime deployed and its session open. */
    private var rimeReady = false
    /** Main thread: librime was opened after the first unlock, so its build can be checked ([syncRimeBuild]). */
    private val rimeChecked = MutableStateFlow(false)
    /** Serializes [syncRimeBuild]: the settings watch and a finished deploy both call it. */
    private val rimeBuildLock = Mutex()

    private lateinit var keyboard: KeyboardView
    private lateinit var candidateBar: CandidateBar
    private lateinit var panelHost: FrameLayout
    private lateinit var content: LinearLayout
    private var emojiPanel: ComposeView? = null
    private var candidatesPanel: ComposeView? = null
    private var segmentPanel: ComposeView? = null
    private var quickPanel: ComposeView? = null
    private var clipboardPanel: ComposeView? = null
    /** The quick-paste strip above the keys (shown in the candidate bar's place). */
    private lateinit var quickStrip: ComposeView
    private lateinit var clipboard: ClipboardManager
    private lateinit var history: ClipboardHistory
    private val quickPaste = QuickPaste()
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onClip() }
    /** Hides the strip when the offer runs out; one pending at a time. */
    private val quickStripTimeout = Runnable { updateQuickStrip() }
    /** Main thread: librime has a session (mirrors the engine thread's rimeReady). */
    private var rimeOpen = false
    /** Main thread: a toolbar notice shown for a moment, or null. */
    private var notice: String? = null
    /** Main thread: the last AI replacement, offered as Undo until the next key, or null. */
    private var aiUndo: AiUndo? = null

    private val composing = StringBuilder()
    private var candidates = emptyList<String>()
    private var generation = 0
    /** Main thread: the emoji index (loaded off the main thread at start), for emoji suggestions. */
    private var emojiIndex: EmojiIndex? = null
    /** Main thread: the emoji among the shown candidates (picked as emoji, not as words). */
    private var emojiShown = emptySet<String>()
    /** Main thread: for each shown Chinese candidate, librime's index, or -1 for an emoji. */
    private var rimeIndexes = emptyList<Int>()
    /** Main thread: the kana reading Mozc gave for the current Japanese composition. */
    private var japaneseReading = ""
    /** Main thread: the current Japanese conversion's segments (bunsetsu), as Mozc gave them. */
    private var japaneseSegments = emptyList<Mozc.Segment>()
    /** Main thread: the Japanese candidates shown, each with its own reading (candidate details). */
    private var japaneseCandidates = emptyList<Candidates.Japanese>()
    /** Main thread: the segment being edited in the segment panel, or null for the whole sentence. */
    private var segmentFocus: Int? = null
    /** Main thread: Mozc could not open (no data in this build); Japanese mode says so once typing. */
    private var japaneseUnavailable = false
    /** librime's latest preedit and candidates, as last applied on the main thread. */
    private var chinesePreedit = ""
    /** Main thread: librime's raw input (the letters typed) as last applied, for switching modes. */
    private var chineseInput = ""
    /** Main thread: the mode fixed for the current app, or null (Compose state for the quick panel). */
    private val pinnedHere = androidx.compose.runtime.mutableStateOf<Mode?>(null)
    private var chineseCandidates = emptyList<String>()
    /** The last word committed in English mode, for predictions and bigram context. */
    private var previousWord: String? = null
    /**
     * Main thread: where each letter of [composing] was tapped, in the English engine's layout
     * space ([English.touchPoint]); null for a letter typed without a position.
     */
    private val taps = ArrayList<Pair<Int, Int>?>()
    private var mode = Mode.MIXED
    private var symbols = false
    private var symbolPage = 0
    private var shift = Shift.OFF
    /** Main thread: the field gets direct typing ([Fields.direct]). */
    private var direct = false
    /** Main thread: the field is sensitive ([Fields.sensitive]): nothing is learned or recorded. */
    private var sensitive = false
    /** Main thread: what the current field is for (e-mail, web address, phone, code, …). */
    private var field = Field.TEXT
    /** Main thread: the Chinese script remembered for the current app, or null to follow the settings (Compose state, so the quick panel follows it). */
    private var appScript by androidx.compose.runtime.mutableStateOf<ChineseScript?>(null)
    /** Main thread: when the last plain space was typed (uptime), for the double-space full stop; 0 after other keys. */
    private var lastSpaceAt = 0L
    /** Main thread: a space was typed after a picked English word, which smart space may move. */
    private var autoSpaced = false
    /** Main thread: the details shown after a long press on a candidate, or null. */
    private var details: CandidateDetails? = null
    /** Main thread: librime's latest candidates with their spelling and type. */
    private var rimeCandidates = emptyList<Rime.Candidate>()
    /** Main thread: the window's fold (hinge position and state), or null on devices without one. */
    private var fold: FoldingFeature? = null
    private var createdAt = 0L
    private lateinit var store: SettingsStore
    /** The job applying the store's changes; replaced when the store changes at the first unlock. */
    private var settingsWatch: Job? = null
    /**
     * Before the first unlock (Direct Boot, PLAN §8.1): only read-only built-in data, no personal
     * data read or written. Set on the main thread, read on the engine thread too.
     */
    @Volatile private var locked = false
    /** The theme and key radius the current views were built with. */
    private var appliedLook: Pair<com.yuanzhe.kotomo.settings.KeyboardTheme, Int>? = null
    /** Main thread: the settings in force (the store's latest value; no I/O to read). */
    private val settings: Settings get() = store.current

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        locked = !getSystemService(UserManager::class.java).isUserUnlocked
        store = if (locked) SettingsStore.forDirectBoot(this) else SettingsStore.get(this)
        lifecycleScope.launch { emojiIndex = runCatching { EmojiIndex.load(this@KotomoService) }.getOrNull() }
        clipboard = getSystemService(ClipboardManager::class.java)
        if (locked) {
            ContextCompat.registerReceiver(this, unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
        } else {
            history = ClipboardHistory.get(this)
            clipboard.addPrimaryClipChangedListener(clipListener)
            LearningData.attach(::clearLearning)
        }
        // Folding, unfolding and half-folding relayout at once; the composition stays as it is.
        lifecycleScope.launch {
            runCatching {
                WindowInfoTracker.getOrCreate(this@KotomoService).windowLayoutInfo(this@KotomoService).collect { info ->
                    fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    refresh(0)
                }
            }
        }
        settingsWatch = watchSettings()
        mode = settings.usable(memory()?.current?.lastMode)
        ContextCompat.registerReceiver(
            this, deployDone, IntentFilter(RimeDeployService.ACTION_DONE), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        engine.post {
            english = openEnglish()
            openRime()
        }
    }

    /**
     * Returns the keyboard's own memory (the last mode, the mode and script per app, the emoji
     * history), or null before the first unlock, when credential-protected storage cannot be read
     * (Direct Boot).
     */
    private fun memory() = if (locked) null else KeyboardMemory.get(this)

    /** Receives the first unlock and leaves Direct Boot. */
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = leaveDirectBoot()
    }

    /**
     * Switches from Direct Boot to the person's data after the first unlock: their settings, the
     * clipboard history and their mode, and librime reopened on their user directory.
     * Notes: main thread; the cached panels are rebuilt, since they were made without personal data.
     */
    private fun leaveDirectBoot() {
        if (!locked) return
        locked = false
        unregisterReceiver(unlocked)
        settingsWatch?.cancel()
        store = SettingsStore.get(this)
        settingsWatch = watchSettings()
        history = ClipboardHistory.get(this)
        clipboard.addPrimaryClipChangedListener(clipListener)
        LearningData.attach(::clearLearning)
        mode = settings.usable(memory()?.current?.lastMode ?: mode)
        emojiPanel = null
        quickPanel = null
        clipboardPanel = null
        engine.post {
            if (rimeReady) Rime.shutdown()
            rimeReady = false
            openRime()
        }
    }

    /**
     * Clears the learning data while the keyboard runs (Privacy → Clear learning data,
     * [LearningData]): on the engine thread librime and Mozc close, their files go, and librime
     * opens again on its deployed data (Mozc opens on the next Japanese key).
     * Side effects: keeps the composition as typed; [done] runs on the main thread.
     */
    private fun clearLearning(done: () -> Unit) {
        main.post { commitComposition() }
        engine.post {
            if (rimeReady) Rime.shutdown()
            rimeReady = false
            mozc?.close()
            mozc = null
            mozcTried = false
            LearningData.deleteFiles(this)
            openRime()
            main.post(done)
        }
    }

    /**
     * Opens librime on the engine thread and reports the result to the main thread.
     * Notes: the app ships compiled data; it only needs unpacking (once per version). Without it
     * (a build made without the Linux prebuild) the phone compiles in RimeDeployService. Every
     * step is recorded for the diagnostics page: what was unpacked, whether librime opened, and
     * what it does with a fixed probe. Before the first unlock librime opens on the
     * device-protected shared data with a throwaway user directory, records nothing and starts
     * no deploy.
     */
    private fun openRime() {
        val files = RimeFiles(this)
        val beforeUnlock = locked
        val check = StringBuilder()
        try {
            val unpackMs = files.unpack(this)
            check.append("unpack=").append(if (unpackMs > 0) "${unpackMs}ms" else "kept")
            val user = if (beforeUnlock) files.lockedUser.apply { deleteRecursively() } else files.user
            user.mkdirs()
            check.append(" prebuilt=").append(files.prebuilt).append(" userBuild=").append(files.hasBuild)
            if (files.prebuilt) {
                rimeReady = Rime.initialize(files.shared.path, user.path)
                if (unpackMs > 0 && !beforeUnlock) {
                    files.writeReport(unpackMs, 0, rimeReady)
                    files.writeStatus(if (rimeReady) RimeFiles.Status.Prebuilt else RimeFiles.Status.Failed("prebuilt data did not open"))
                }
            } else if (files.hasBuild && !beforeUnlock) {
                rimeReady = Rime.initialize(files.shared.path, user.path)
            }
            check.append(" open=").append(rimeReady)
            if (rimeReady) check.append(" | ").append(Rime.selfCheck())
        } catch (e: Throwable) {
            rimeReady = false
            check.append(" error=").append(e.javaClass.simpleName).append(": ").append(e.message.orEmpty())
        }
        if (!beforeUnlock) runCatching { files.writeCheck(check.toString()) }
        val open = rimeReady
        main.post {
            rimeOpen = open
            applyEngineSettings()
            // The settings watch then deploys if this version's data or the fuzzy rules need it.
            if (!beforeUnlock) rimeChecked.value = true
            refresh(0)
        }
    }

    /**
     * Applies changes made in the settings app at once: relayout and the engines' options.
     * Returns: the collecting job (cancelled when the store changes after the first unlock).
     */
    private fun watchSettings() = lifecycleScope.launch {
        // The fuzzy-pinyin rules decide which librime build the keyboard needs; act only on the
        // stored values (not the defaults before the first read) and never before the first unlock.
        launch {
            combine(rimeChecked, store.loaded.filterNotNull().map { it.chineseFuzzy }.distinctUntilChanged(), ::Pair)
                .collect { (checked, rules) -> if (checked && !locked) syncRimeBuild(rules) }
        }
        // The person's dictionaries rewrote librime's tables: re-selecting the schema reloads them
        // (doc/en-us/dictionaries.md). Their files are personal data, never read before the first unlock.
        if (!locked) {
            launch {
                DictionaryStore.get(this@KotomoService).state.map { it.generation }.distinctUntilChanged()
                    .collect { if (it > 0) applyEngineSettings() }
            }
        }
        store.flow.collect {
            // A new theme repaints everything: rebuild the views and drop the cached panels.
            if (appliedLook != null && appliedLook != (it.theme to it.keyRadiusDp)) {
                emojiPanel = null
                candidatesPanel = null
                segmentPanel = null
                quickPanel = null
                clipboardPanel = null
                setInputView(onCreateInputView())
            }
            // The mode in use was just hidden: move to one the language choice still offers.
            if (::keyboard.isInitialized && mode !in it.modes) switchMode(it.usable(mode), 0)
            refresh(0)
            applyEngineSettings()
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
            reopenRime { it.promote() || it.hasBuild }
            // The rules may have changed while it deployed.
            lifecycleScope.launch { store.loaded.value?.let { syncRimeBuild(it.chineseFuzzy) } }
        }
    }

    /**
     * Restarts librime on the engine thread after its build changed, then applies the settings
     * and drops the composition on the main thread.
     * Inputs: prepare runs with librime shut down and returns whether a build is there to open.
     */
    private fun reopenRime(prepare: (RimeFiles) -> Boolean) {
        engine.post {
            val files = RimeFiles(this@KotomoService)
            if (rimeReady) Rime.shutdown()
            rimeReady = false
            if (prepare(files)) rimeReady = Rime.initialize(files.shared.path, files.user.path)
            val open = rimeReady
            main.post {
                rimeOpen = open
                applyEngineSettings()
                clearChinese()
                refresh(0)
            }
        }
    }

    /**
     * Brings librime's build in line with this version's data and the fuzzy-pinyin [rules]
     * (PLAN §9.6): writes or removes the rule file, then deploys in the deploy process when the
     * build is not current, or, with prebuilt data and no rule on, drops the deployed build so
     * librime reads the prebuilt data again (nothing compiles on the phone then).
     * Notes: main thread, files on the IO dispatcher; the keyboard keeps typing on the previous
     * build until the new one is swapped in. Never before the first unlock.
     */
    private suspend fun syncRimeBuild(rules: Set<FuzzyRule>) = rimeBuildLock.withLock {
        val files = RimeFiles(this)
        val (drop, deploy) = withContext(Dispatchers.IO) {
            files.writeFuzzy(Fuzzy.yaml(rules))
            if (files.prebuilt && rules.isEmpty()) files.hasBuild to false else false to !files.current
        }
        if (drop) reopenRime { it.dropBuild(); true }
        if (deploy) startDeploy()
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
        KeyboardColors.apply(this, settings.theme)
        appliedLook = settings.theme to settings.keyRadiusDp
        createdAt = SystemClock.uptimeMillis()
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        val popups = PopupLayer(this)
        keyboard = KeyboardView(this, popups, ::onKey)
        candidateBar = CandidateBar(this, ::pick, ::toggleCandidates, ::onTool, ::showDetails, ::onDetails) { leaveSearch(toPanel = true) }
        panelHost = FrameLayout(this).apply { addView(keyboard) }
        keyboard.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                if (Probe.coldStartMs == null) Probe.coldStartMs = SystemClock.uptimeMillis() - createdAt
                keyboard.post { keyboard.viewTreeObserver.removeOnDrawListener(this) }
            }
        })
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        quickStrip = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                quickPaste.offer.collectAsState().value?.let { copied ->
                    QuickPasteStrip(
                        copied,
                        numberField = (currentInputEditorInfo?.inputType ?: 0) and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER,
                        onPaste = { text ->
                            currentInputConnection?.commitText(text, 1)
                            quickPaste.done()
                            updateQuickStrip()
                        },
                        onClose = {
                            quickPaste.done()
                            updateQuickStrip()
                        },
                    )
                }
            }
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(candidateBar)
            addView(quickStrip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(panelHost)
        }
        val root = InputRoot(this, content, popups).apply { setBackgroundColor(KeyboardColors.color(this@KotomoService, R.color.kb_background)) }
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

    /**
     * Reads the window's size and the fold's position for the layout (PLAN §8.10).
     * Notes: main thread; the fold comes from the WindowManager flow collected in onCreate, in
     * the same window coordinates as the window metrics [bounds] (read once per refresh).
     */
    private fun posture(bounds: android.graphics.Rect): Posture {
        val density = resources.displayMetrics.density
        val f = fold
        val hinge = f?.bounds
        return Posture(
            bounds.width() / density, bounds.height() / density,
            hingeX = if (hinge != null && f.orientation == FoldingFeature.Orientation.VERTICAL && f.isSeparating) {
                hinge.left.toFloat()..hinge.right.toFloat()
            } else null,
            tabletopBottom = if (hinge != null && f.orientation == FoldingFeature.Orientation.HORIZONTAL && f.state == FoldingFeature.State.HALF_OPENED) {
                hinge.bottom.toFloat()
            } else null,
        )
    }

    /**
     * Passes the settings the engines hold to the engine thread: the Chinese script (the app's
     * remembered one first) and no learning in incognito mode, in sensitive fields and before the
     * first unlock (librime's private schema; T12).
     * Notes: setLearning re-selects the schema, which resets its options, so the script options
     * are set after it; exactly one of them is on, or none for OpenCC standard Traditional.
     */
    private fun applyEngineSettings() {
        val script = appScript ?: settings.chineseScript
        val learning = !settings.incognito && !locked && !sensitive
        engine.post {
            if (rimeReady) {
                Rime.setLearning(learning)
                Rime.setOption("zh_hans", script == ChineseScript.SIMPLIFIED)
                Rime.setOption("zh_hant_tw", script == ChineseScript.TAIWAN)
                Rime.setOption("zh_hant_hk", script == ChineseScript.HONG_KONG)
            }
        }
    }

    /**
     * Changes the Chinese script from the quick panel: for every app, and remembered for the
     * current one (not in incognito mode, sensitive or direct fields).
     */
    private fun setScript(script: ChineseScript) {
        val app = currentInputEditorInfo?.packageName
        if (app != null && remembers) {
            appScript = script
            memory()?.update { m -> m.withApp(app) { it.copy(script = script) } }
        }
        store.set { it.copy(chineseScript = script) }
        applyEngineSettings()
    }

    /** Opens or closes the page of every candidate in the keyboard's place. */
    private fun toggleCandidates() {
        if (panelHost.getChildAt(0) === candidatesPanel || panelHost.getChildAt(0) === segmentPanel) {
            showKeyboard()
            return
        }
        if (mode == Mode.JAPANESE && !direct && japaneseSegments.isNotEmpty()) return showSegments()
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

    /**
     * Records a new clip when the field allows it (not sensitive, not incognito, history on) and
     * offers it on the quick-paste strip (main thread).
     */
    private fun onClip() {
        if (!::history.isInitialized) return
        val copied = history.onPrimaryClip(clipboard.primaryClip, ClipboardHistory.allowedIn(currentInputEditorInfo, settings)) ?: return
        if (settings.clipboard.quickPaste) {
            quickPaste.offer(copied)
            updateQuickStrip()
        }
    }

    /**
     * Shows the quick-paste strip in the candidate bar's place while a fresh clip is on offer and
     * nothing is being composed, and hides it when the offer runs out.
     */
    private fun updateQuickStrip() {
        if (!::quickStrip.isInitialized) return
        val now = System.currentTimeMillis()
        val composingNow = composing.isNotEmpty() || chinesePreedit.isNotEmpty()
        val show = quickPaste.current(now) != null && !composingNow && panelHost.getChildAt(0) === keyboard
        quickStrip.visibility = if (show) View.VISIBLE else View.GONE
        candidateBar.visibility = if (show) View.GONE else View.VISIBLE
        main.removeCallbacks(quickStripTimeout)
        if (show) main.postDelayed(quickStripTimeout, quickPaste.remainingMs(now))
    }

    /** Opens the clipboard history in the keyboard's place (the toolbar's clipboard button). */
    private fun showClipboard() {
        if (locked) return showNotice(getString(R.string.locked_notice))
        commitComposition()
        history.expire()
        val height = keyboard.height
        val panel = clipboardPanel ?: ComposeView(this).also { view ->
            clipboardPanel = view
            view.setContent {
                val c = store.flow.collectAsState().value.clipboard
                ClipboardPanel(
                    history.items.collectAsState(), c.retentionDays, c.history,
                    onPaste = {
                        currentInputConnection?.commitText(it, 1)
                        showKeyboard()
                    },
                    onPin = { clip, on -> history.pin(clip.id, on) },
                    onDelete = { history.delete(it.id) },
                    onBack = ::showKeyboard,
                    onManage = { onTool(Tool.SETTINGS) },
                )
            }
        }
        panelHost.removeAllViews()
        panelHost.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        updateQuickStrip()
    }

    /** Main thread: one-handed mode before the first unlock, when nothing may be written (null: none chosen). */
    private var lockedOneHanded: OneHandedMemory? = null
    /** Main thread: the one-handed side in force, for the quick panel. */
    private val quickOneHanded = androidx.compose.runtime.mutableStateOf(com.yuanzhe.kotomo.settings.OneHanded.OFF)

    /** Names the current screen and orientation (PLAN §8.10: one-handed mode is kept per device and orientation). */
    private fun screenKey(): String = resources.configuration.let {
        Posture.screenKey(it.smallestScreenWidthDp, it.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }

    /**
     * Returns one-handed mode on this screen in this orientation: as last chosen there, or, on a
     * screen never set, the side the settings chose before it was kept per screen.
     */
    private fun oneHandedHere(): OneHandedMemory =
        lockedOneHanded ?: memory()?.current?.oneHandedOn(screenKey(), settings.oneHanded) ?: OneHandedMemory(settings.oneHanded)

    /**
     * Changes one-handed mode on this screen in this orientation (the side buttons, the grip, the
     * quick panel); before the first unlock only until the keyboard closes.
     */
    private fun setOneHanded(change: (OneHandedMemory) -> OneHandedMemory) {
        val m = memory()
        if (m == null) lockedOneHanded = change(oneHandedHere()) else m.update { it.withOneHanded(screenKey(), settings.oneHanded, change) }
        refresh(0)
    }

    /** Main thread: the mode the quick panel shows (set each time it opens). */
    private val quickMode = androidx.compose.runtime.mutableStateOf(Mode.MIXED)

    /** Opens or closes the quick settings (the toolbar's first button) in the keyboard's place. */
    private fun toggleQuickPanel() {
        if (panelHost.getChildAt(0) === quickPanel) {
            showKeyboard()
            return
        }
        quickMode.value = mode
        val height = keyboard.height
        val panel = quickPanel ?: ComposeView(this).also { view ->
            quickPanel = view
            view.setContent {
                QuickPanel(
                    store = store,
                    script = appScript,
                    onScript = ::setScript,
                    mode = quickMode,
                    oneHanded = quickOneHanded,
                    onOneHanded = { side -> setOneHanded { it.copy(side = side) } },
                    pinned = pinnedHere,
                    onPin = ::pinHere,
                    onOpenSettings = { onTool(Tool.SETTINGS) },
                    onBack = ::showKeyboard,
                )
            }
        }
        panelHost.removeAllViews()
        panelHost.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    }

    private val shownCandidates = androidx.compose.runtime.mutableStateOf(emptyList<String>())

    /**
     * Shows candidate [index]'s details in the bar (a long press on it): its reading and where it
     * comes from, with Forget for a word librime learned (PLAN §8.5).
     * Notes: main thread, from what the last engine update carried; no engine call.
     */
    private fun showDetails(index: Int) {
        val text = candidates.getOrNull(index) ?: return
        val rimeIndex = rimeIndexes.getOrElse(index) { index }
        val chinese = usesRime && !direct
        val rime = if (chinese && rimeIndex >= 0) rimeCandidates.getOrNull(rimeIndex) else null
        val emoji = text in emojiShown || (chinese && rimeIndex == Candidates.EMOJI)
        val reading = when {
            emoji -> ""
            rime != null -> rime.comment.trim()
            mode == Mode.JAPANESE -> japaneseCandidates.firstOrNull { it.text == text }?.reading ?: japaneseReading
            else -> ""
        }
        val source = getString(
            when {
                emoji -> R.string.source_emoji
                rime != null -> when (rime.type) {
                    "user_phrase" -> R.string.source_learned
                    "user_table" -> R.string.source_your_dictionaries
                    "sentence" -> R.string.source_sentence
                    "completion" -> R.string.source_prediction
                    "punct" -> R.string.source_symbol
                    else -> R.string.source_chinese
                }
                mode == Mode.JAPANESE -> R.string.source_japanese
                mode == Mode.ENGLISH && index == 0 && text == composing.toString() -> R.string.source_typed
                mode == Mode.ENGLISH -> R.string.source_english
                else -> R.string.source_typed
            },
        )
        val forget = if (rime?.type == "user_phrase") getString(R.string.candidate_forget) else null
        details = CandidateDetails(index, if (reading.isEmpty()) source else getString(R.string.candidate_details, reading, source), forget)
        candidateBar.details = details
    }

    /** Closes the details, or forgets the learned word they show when [forget] is its index. */
    private fun onDetails(forget: Int?) {
        details = null
        candidateBar.details = null
        if (forget != null) forget(forget)
    }

    /**
     * Forgets what librime learned about Chinese candidate [index] (Forget in its details); other
     * modes learn nothing to forget. A short notice confirms it.
     */
    private fun forget(index: Int) {
        if (!usesRime || direct) return
        val rimeIndex = rimeIndexes.getOrElse(index) { index }
        if (rimeIndex < 0) return
        engine.post {
            val removed = rimeReady && Rime.deleteCandidate(rimeIndex)
            val state = rimeState(true)
            main.post {
                applyChinese(state, 0)
                if (removed) showNotice(getString(R.string.bar_forgotten))
            }
        }
    }

    /** Shows [text] in the candidate bar for a moment. */
    private fun showNotice(text: String) {
        notice = text
        refresh(0)
        main.postDelayed({
            notice = null
            refresh(0)
        }, NOTICE_MS)
    }

    /** Runs a toolbar button: settings opens the app; the rest arrive with later phases. */
    private fun onTool(tool: Tool) {
        when (tool) {
            Tool.SETTINGS -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Tool.MORE -> toggleQuickPanel()
            Tool.CLIPBOARD -> showClipboard()
            Tool.AI -> startAi()
            Tool.TRANSLATE -> showNotice(getString(R.string.bar_not_yet))
        }
    }

    /**
     * Hands the selected text, or the whole field when nothing is selected, to the AI screen.
     * Side effects: commits the composition; starts [AiActivity] in its own task.
     * Notes: refuses (with a notice) while AI is off, in sensitive and direct fields and in
     * incognito mode; Chinese-only text is refused because
     * Gemini Nano's writing tools do not take Chinese (PLAN §11.1).
     */
    private fun startAi() {
        val info = currentInputEditorInfo
        when {
            locked -> return showNotice(getString(R.string.locked_notice))
            !settings.ai.enabled -> return showNotice(getString(R.string.ai_off_notice))
            info == null || direct || sensitive || settings.incognito -> return showNotice(getString(R.string.ai_not_here))
        }
        commitComposition()
        val ic = currentInputConnection ?: return
        val extracted = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = AI_MAX_CHARS + 1 }, 0)
            ?: return showNotice(getString(R.string.ai_not_here))
        val all = extracted.text?.toString().orEmpty()
        val selStart = minOf(extracted.selectionStart, extracted.selectionEnd).coerceIn(0, all.length)
        val selEnd = maxOf(extracted.selectionStart, extracted.selectionEnd).coerceIn(0, all.length)
        val (from, to) = when {
            selEnd > selStart -> selStart to selEnd
            extracted.startOffset == 0 && all.length <= AI_MAX_CHARS -> 0 to all.length
            else -> return showNotice(getString(R.string.ai_select_shorter))
        }
        val text = all.substring(from, to)
        when {
            text.isBlank() -> return showNotice(getString(R.string.ai_empty))
            text.length > AI_MAX_CHARS -> return showNotice(getString(R.string.ai_select_shorter))
        }
        val kana = text.any { it in '\u3040'..'\u30ff' }
        val latin = text.any { it in 'a'..'z' || it in 'A'..'Z' }
        val han = text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        val language = when {
            kana || (mode == Mode.JAPANESE && !latin) -> AiLanguage.JAPANESE
            han && !latin -> return showNotice(getString(R.string.ai_chinese))
            else -> AiLanguage.ENGLISH
        }
        AiHandoff.begin(
            AiRequest(info.packageName.orEmpty(), info.fieldId, text, extracted.startOffset + from, extracted.startOffset + to, language),
        )
        startActivity(Intent(this, AiActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Puts the suggestion the person confirmed in the AI screen into the field it came from.
     * Side effects: replaces the original range and offers Undo; when the field is another one or
     * its text changed meanwhile, copies the suggestion to the clipboard instead and says so.
     */
    private fun applyAiResult(info: EditorInfo) {
        val (request, result) = AiHandoff.take() ?: return
        val ic = currentInputConnection ?: return
        val same = info.packageName == request.app && info.fieldId == request.fieldId &&
            textAt(request.start, request.end) == request.text
        if (!same) {
            clipboard.setPrimaryClip(ClipData.newPlainText(null, result))
            return showNotice(getString(R.string.ai_changed_copied))
        }
        ic.beginBatchEdit()
        ic.setSelection(request.start, request.end)
        ic.commitText(result, 1)
        ic.endBatchEdit()
        previousWord = null
        aiUndo = AiUndo(request.start, request.start + result.length, request.text, result)
        showNotice(getString(R.string.ai_replaced))
    }

    /** Restores the text an AI replacement took away, if the replacement is still there unchanged. */
    private fun undoAi(undo: AiUndo) {
        aiUndo = null
        val ic = currentInputConnection ?: return
        if (textAt(undo.start, undo.end) == undo.replacement) {
            ic.beginBatchEdit()
            ic.setSelection(undo.start, undo.end)
            ic.commitText(undo.original, 1)
            ic.endBatchEdit()
            showNotice(getString(R.string.ai_undone))
        } else {
            refresh(0)
        }
    }

    /** Reads the field's text between absolute offsets [start] and [end], or null if out of reach. */
    private fun textAt(start: Int, end: Int): String? {
        val extracted = currentInputConnection?.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = end + 1 }, 0) ?: return null
        val text = extracted.text?.toString() ?: return null
        val from = start - extracted.startOffset
        val to = end - extracted.startOffset
        return if (from >= 0 && to <= text.length && from <= to) text.substring(from, to) else null
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        direct = Fields.direct(info.inputType)
        sensitive = Fields.sensitive(info.inputType, info.imeOptions)
        field = Fields.of(info.inputType, info.hintText ?: info.label)
        symbolPage = 0
        symbols = false
        // The emoji panel is built per field, since whether it may record recents depends on it.
        emojiPanel = null
        emojiSearch = null
        if (::candidateBar.isInitialized) candidateBar.search = null
        lastSpaceAt = 0L
        autoSpaced = false
        details = null
        shift = Shift.OFF
        composing.clear()
        taps.clear()
        clearChinese()
        previousWord = null
        // An app fixed to a mode always starts in it; otherwise each app comes back in the mode
        // last used in it, and code-like fields start in English (only the package name is stored).
        val app = info.packageName?.takeIf { !direct && !sensitive }
        val remembered = app?.let { memory()?.current?.app(it) }
        mode = when {
            remembered?.pinned != null -> settings.usable(remembered.pinned)
            field == Field.CODE -> Mode.ENGLISH
            else -> settings.usable(remembered?.mode)
        }
        pinnedHere.value = remembered?.pinned
        appScript = remembered?.script
        applyEngineSettings()
        showKeyboard()
        updateAutoCaps()
        aiUndo = null
        refresh(0)
        // Clips copied while the keyboard was away; the clip's timestamp makes a seen one a no-op.
        onClip()
        // Back from the AI screen with a confirmed suggestion; after the field's connection settles.
        main.post { applyAiResult(info) }
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
        if (!locked) Probe.save(this)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        currentInputConnection?.finishComposingText()
        composing.clear()
        taps.clear()
        clearChinese()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // The cursor left the composition (a tap elsewhere in the field): keep the text as typed.
        if ((composing.isNotEmpty() || chinesePreedit.isNotEmpty()) && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            composing.clear()
            taps.clear()
            clearChinese()
            previousWord = null
            refresh(0)
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        LearningData.attach(null)
        unregisterReceiver(deployDone)
        if (locked) unregisterReceiver(unlocked)
        clipboard.removePrimaryClipChangedListener(clipListener)
        if (deployBound) unbindService(deployConnection)
        engine.post {
            english?.close()
            mozc?.close()
            if (rimeReady) Rime.shutdown()
        }
        engineThread.quitSafely()
        super.onDestroy()
    }

    private fun onKey(action: KeyAction, longPress: Boolean, downTime: Long) {
        val ic = currentInputConnection ?: return
        aiUndo = null
        details = null
        emojiSearch?.let { if (searchKey(action, it)) return }
        if (action != KeyAction.Space) lastSpaceAt = 0L
        val spaced = autoSpaced
        autoSpaced = false
        if (action is KeyAction.SetMode) {
            switchMode(action.mode, downTime)
            val app = currentInputEditorInfo?.packageName
            memory()?.update { m ->
                val last = m.copy(lastMode = mode)
                if (app != null && remembers) last.withApp(app) { it.copy(mode = mode) } else last
            }
            return
        }
        if (usesRime && !direct && onChineseKey(action, longPress, downTime)) {
            refresh(0)
            return
        }
        when (action) {
            is KeyAction.Text -> {
                val letter = action.text.length == 1 && action.text[0].let { it in 'a'..'z' || it in 'A'..'Z' }
                if (letter && !direct && !longPress) {
                    composing.append(action.text)
                    taps += keyboard.lastTap?.let { (dx, dy) -> English.touchPoint(action.text[0], dx, dy) }
                    ic.setComposingText(composing, 1)
                } else if (spaced && composing.isEmpty() && settings.gestures.smartSpace && Fields.attaches(action.text) &&
                    ic.getTextBeforeCursor(1, 0)?.toString() == " "
                ) {
                    // Smart space: "word ." becomes "word. ", ready for the next word.
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(action.text + " ", 1)
                    autoSpaced = true
                    previousWord = null
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
                typeSpace(downTime)
                previousWord = null
            }
            KeyAction.Backspace -> if (composing.isNotEmpty()) {
                composing.setLength(composing.length - 1)
                if (taps.isNotEmpty()) taps.removeAt(taps.size - 1)
                if (composing.isEmpty()) ic.commitText("", 1) else ic.setComposingText(composing, 1)
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                previousWord = null
            }
            KeyAction.Enter -> {
                previousWord = null
                if (composing.isNotEmpty() && mode == Mode.JAPANESE && japaneseReading.isNotEmpty()) {
                    // Enter keeps the kana as typed, as Japanese keyboards do; after segments were
                    // chosen it commits the conversion as shown.
                    ic.commitText(if (japaneseSegments.any { it.pinned }) japaneseCandidates.firstOrNull()?.text ?: japaneseReading else japaneseReading, 1)
                    composing.clear()
                    taps.clear()
                    clearJapanese()
                } else if (composing.isNotEmpty()) {
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
            is KeyAction.SetOneHanded -> setOneHanded { it.copy(side = action.side) }
            is KeyAction.SetOneHandedWidth -> setOneHanded { it.copy(widthPercent = action.percent) }
            is KeyAction.MoveCursor -> {
                commitComposition()
                val code = if (action.steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                repeat(kotlin.math.abs(action.steps)) { sendDownUpKeyEvents(code) }
            }
            is KeyAction.DeleteWords -> {
                commitComposition()
                deleteWordsBefore(action.count)
                previousWord = null
            }
        }
        // Not after Shift itself: that would undo the Shift the person just set.
        if (composing.isEmpty() && action != KeyAction.Shift) updateAutoCaps()
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
            KeyAction.Space -> rimeKey(SPACE, downTime) { typeSpace(downTime) }
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
            val started = SystemClock.uptimeMillis()
            // Only keys that commit the top candidate first ask whether anything is composed.
            val skip = onlyIfComposing && !(rimeReady && Rime.getComposition().isNotEmpty())
            val handled = rimeReady && !skip && Rime.processKey(keycode, 0)
            val state = rimeState(handled)
            val work = SystemClock.uptimeMillis() - started
            main.post {
                if (downTime != 0L) Probe.engineWork.add(work)
                applyChinese(state, downTime)
                if (!state.handled && !skip) fallback()
            }
        }
    }

    /** librime's state after one call, read on the engine thread. */
    private class RimeState(val handled: Boolean, val commit: String?, val preedit: String, val input: String, val candidates: List<Rime.Candidate>)

    private fun rimeState(handled: Boolean) = if (rimeReady) {
        RimeState(handled, Rime.getCommit(), Rime.getComposition(), Rime.getInput(), Rime.getCandidates(Candidates.CHINESE_LIMIT))
    } else {
        RimeState(false, null, "", "", emptyList())
    }

    /** Applies a librime result to the field and the candidate bar (main thread). */
    private fun applyChinese(state: RimeState, downTime: Long) {
        val ic = currentInputConnection ?: return
        when {
            state.commit != null -> ic.commitText(state.commit, 1)
            state.preedit.isEmpty() && chinesePreedit.isNotEmpty() -> ic.commitText("", 1)
        }
        if (state.preedit.isNotEmpty()) {
            // API 37: the field learns the conversion candidates and that the composition shows the
            // reading, not a selected conversion (PLAN §8.2); segments that hold a chosen
            // conversion arrive with segment editing (P2).
            if (Build.VERSION.SDK_INT >= API_TEXT_SUGGESTION_SELECTED) {
                val attribute = TextAttribute.Builder()
                    .setTextConversionSuggestions(state.candidates.take(CONVERSION_HINTS).map { it.text })
                    .setTextSuggestionSelected(false)
                    .build()
                ic.setComposingText(state.preedit, 1, attribute)
            } else {
                ic.setComposingText(state.preedit, 1)
            }
        }
        chinesePreedit = state.preedit
        chineseInput = if (state.preedit.isEmpty()) "" else state.input
        rimeCandidates = state.candidates
        val emoji = emojiFor(state.preedit)
        emojiShown = emoji.toSet()
        val entries = Candidates.chinese(state.candidates.map { it.text }, state.input, emoji)
        chineseCandidates = entries.map { it.first }
        rimeIndexes = entries.map { it.second }
        if (usesRime) {
            candidates = chineseCandidates
            candidateBar.show(candidates, downTime)
            shownCandidates.value = candidates
        }
    }

    /** Drops librime's composition without committing (the field keeps what it shows). */
    private fun clearChinese() {
        if (chinesePreedit.isEmpty() && chineseCandidates.isEmpty()) return
        chinesePreedit = ""
        chineseInput = ""
        chineseCandidates = emptyList()
        engine.post { if (rimeReady) Rime.clear() }
    }

    /**
     * Deletes [count] words before the cursor (dragging left from backspace).
     * Notes: ICU's word iterator also finds words in Chinese and Japanese text without spaces;
     * whitespace before a word goes with it. Reads at most 256 characters of the field.
     */
    private fun deleteWordsBefore(count: Int) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(256, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val words = android.icu.text.BreakIterator.getWordInstance().apply { setText(before) }
        var pos = before.length
        repeat(count) {
            while (pos > 0 && before[pos - 1].isWhitespace()) pos--
            if (pos == 0) return@repeat
            pos = words.preceding(pos).coerceAtLeast(0)
        }
        ic.deleteSurroundingText(before.length - pos, 0)
    }

    /** Performs the field's editor action, or types a newline when it has none. */
    private fun editorEnter() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        if (Fields.enter(info.imeOptions) == EnterStyle.NEWLINE) sendKeyChar('\n')
        else ic.performEditorAction(info.imeOptions and EditorInfo.IME_MASK_ACTION)
    }

    /**
     * Types a space, or turns a second quick space after a word into a full stop (". " or "。")
     * when that gesture is on.
     * Inputs: downTime is the touch's uptime.
     */
    private fun typeSpace(downTime: Long) {
        val ic = currentInputConnection ?: return
        if (settings.gestures.doubleSpacePeriod && lastSpaceAt != 0L && downTime - lastSpaceAt < DOUBLE_SPACE_MS) {
            Fields.period(ic.getTextBeforeCursor(2, 0), mode)?.let {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(it, 1)
                lastSpaceAt = 0L
                return
            }
        }
        ic.commitText(" ", 1)
        lastSpaceAt = downTime
    }

    /**
     * Commits candidate [index] in place of the composition (or a predicted word).
     * Side effects: in English mode also types a space and asks for next-word predictions.
     */
    private fun pick(index: Int) {
        aiUndo?.let { return undoAi(it) }
        if (emojiSearch != null) return pickFound(index)
        if (usesRime && !direct) {
            val rimeIndex = rimeIndexes.getOrElse(index) { index }
            if (rimeIndex < 0) {
                // An emoji or the letters as typed: either replaces the whole composition.
                val text = candidates.getOrNull(index) ?: return
                engine.post {
                    if (rimeReady) Rime.clear()
                    main.post {
                        currentInputConnection?.commitText(text, 1)
                        chinesePreedit = ""
                        chineseCandidates = emptyList()
                        rimeIndexes = emptyList()
                        refresh(0)
                    }
                }
                return
            }
            engine.post {
                val handled = rimeReady && Rime.selectCandidate(rimeIndex)
                val state = rimeState(handled)
                main.post { applyChinese(state, 0) }
            }
            return
        }
        val text = candidates.getOrNull(index) ?: return
        val ic = currentInputConnection ?: return
        if (text in emojiShown) {
            ic.commitText(text, 1)
            previousWord = null
        } else if (mode == Mode.ENGLISH) {
            ic.commitText("$text ", 1)
            previousWord = text
            autoSpaced = true
        } else {
            ic.commitText(text, 1)
        }
        composing.clear()
        taps.clear()
        clearJapanese()
        updateAutoCaps()
        refresh(0)
    }

    /**
     * Sets a one-letter Shift where the field asks for a capital (sentence or word start) in
     * English mode, and clears one the field no longer asks for; a locked Shift stays.
     * Notes: one InputConnection call, made only after commits, not per letter.
     */
    private fun updateAutoCaps() {
        if (mode != Mode.ENGLISH || symbols || shift == Shift.LOCKED || !settings.englishAutoCapitalize) return
        val info = currentInputEditorInfo ?: return
        val caps = (currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0) != 0
        shift = if (caps) Shift.ONCE else if (shift == Shift.ONCE) Shift.OFF else shift
    }

    private fun commitComposition() {
        if (chinesePreedit.isNotEmpty()) {
            currentInputConnection?.finishComposingText()
            clearChinese()
        }
        if (composing.isEmpty()) return
        currentInputConnection?.finishComposingText()
        composing.clear()
        taps.clear()
        clearJapanese()
    }

    /**
     * Redraws the keyboard page and the candidates after a change.
     * Inputs: [downTime] is the touch that caused the change (0 for none), for [Probe].
     * Notes: the first candidates are drawn at once; engine results follow as a second update
     * that is dropped if anything changed in between.
     */
    private fun refresh(downTime: Long) {
        if (!::keyboard.isInitialized) return
        val pad = field == Field.PHONE || field == Field.NUMBER || field == Field.DATETIME
        val s = settings
        val window = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(this).bounds
        val posture = posture(window)
        val split = posture.split(s.split) && !pad
        val density = resources.displayMetrics.density
        keyboard.hinge = posture.hingeX?.takeIf { split }?.let { h ->
            val at = IntArray(2).also(keyboard::getLocationInWindow)[0]
            (h.start - at)..(h.endInclusive - at)
        }
        keyboard.maxWidth = if (posture.wide && !split) resources.getDimension(R.dimen.kb_max_width) else 0f
        keyboard.heightLimit = posture.heightLimit(
            window.height(),
            (candidateBar.height.takeIf { it > 0 } ?: resources.getDimensionPixelSize(R.dimen.bar_height)) + content.paddingBottom,
            density, ResourcesCompat.getFloat(resources, R.dimen.kb_short_window_share), s.tabletopLowerHalf,
        )
        keyboard.split = split
        keyboard.heightScale = s.heightPercent / 100f
        keyboard.thumbHalf = s.thumbZoneDp * resources.displayMetrics.density
        val hand = oneHandedHere()
        keyboard.oneHanded = hand.side
        keyboard.oneHandedShare = hand.widthPercent / 100f
        quickOneHanded.value = hand.side
        keyboard.hints = s.keyHints
        keyboard.haptics = s.haptics
        keyboard.longPressSymbols = s.longPressSymbols
        keyboard.radius = s.keyRadiusDp * resources.displayMetrics.density
        keyboard.sound = s.keySound
        keyboard.spaceCursor = s.gestures.spaceCursor
        keyboard.deleteSwipe = s.gestures.deleteSwipe
        keyboard.spaceLanguage = s.gestures.spaceLanguage
        keyboard.enterStyle = Fields.enter(currentInputEditorInfo?.imeOptions ?: 0)
        // E-mail, web-address and code-like fields take ASCII symbols whatever the mode.
        val symbolMode = if (field == Field.EMAIL || field == Field.URL || field == Field.CODE) Mode.ENGLISH else mode
        keyboard.layout = when {
            pad -> Layout.pad(field)
            symbols -> Layout.symbols(symbolMode, split, symbolPage, hinge = keyboard.hinge != null)
            else -> Layout.letters(mode, shift != Shift.OFF, split, field, hinge = keyboard.hinge != null)
        }
        keyboard.shift = shift
        keyboard.mode = mode
        keyboard.modes = s.modes
        emojiSearch?.let { query ->
            // The keys type into the emoji search: its results replace the candidates.
            ++generation
            candidates = Candidates.emojiSearch(emojiIndex, query.toString())
            emojiShown = candidates.toSet()
            candidateBar.search = query.toString()
            candidateBar.show(candidates, downTime)
            candidateBar.details = null
            candidateBar.hint = null
            updateQuickStrip()
            return
        }
        val raw = composing.toString()
        val gen = ++generation
        candidates = when {
            direct -> emptyList()
            aiUndo != null && raw.isEmpty() && chinesePreedit.isEmpty() -> listOf(getString(R.string.ai_undo))
            usesRime -> chineseCandidates
            mode == Mode.ENGLISH -> if (raw.isEmpty()) emptyList() else listOf(raw)
            mode == Mode.JAPANESE -> if (raw.isEmpty()) emptyList() else listOf(raw)
            raw.isEmpty() -> emptyList()
            else -> listOf(raw, raw.replaceFirstChar(Char::uppercase), raw.uppercase()).distinct()
        }
        // Chinese candidates are librime's answer, drawn by applyChinese; what is shown here is the
        // previous key's, so the probe waits for that answer.
        candidateBar.show(candidates, if (usesRime && !direct) 0 else downTime)
        shownCandidates.value = candidates
        candidateBar.details = details
        candidateBar.hint = notice ?: chineseHint() ?: japaneseHint(raw)
        updateQuickStrip()
        if (raw.isEmpty()) {
            clearJapanese()
            if (::panelHost.isInitialized && panelHost.getChildAt(0) === segmentPanel) showKeyboard()
        }
        if (!direct && mode == Mode.ENGLISH && s.englishSuggestions && (raw.isNotEmpty() || previousWord != null)) suggestEnglish(raw, previousWord, gen)
        if (!direct && mode == Mode.JAPANESE && raw.isNotEmpty()) suggestJapanese(raw, gen)
    }

    /**
     * Opens Mozc with its data set, copied from the APK to private storage once per app version
     * (engine thread).
     * Returns: Mozc, or null when the APK carries no Mozc data (a build without the CI libraries).
     */
    private fun openMozc(): Mozc? = runCatching {
        val dir = File(filesDir, "mozc").apply { mkdirs() }
        val data = File(dir, MOZC_DATA)
        val stamp = File(dir, ".stamp")
        val version = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        if (!data.isFile || !stamp.isFile || stamp.readText() != version) {
            assets.open(MOZC_DATA).use { input -> data.outputStream().use { input.copyTo(it) } }
            stamp.writeText(version)
        }
        Mozc.open(data.path, File(filesDir, LearningData.MOZC_PROFILE).apply { mkdirs() }.path)
    }.getOrNull()

    /**
     * Converts the romaji composition with Mozc on the engine thread and shows the result if still
     * current: the composition becomes the kana reading; candidates are the whole sentence, the
     * first segment's alternatives with the rest of the sentence, words predicted from the
     * reading, then hiragana and katakana (PLAN §8.2).
     * Notes: segments the person already chose in the segment panel stay pinned while more is
     * typed; a deletion converts afresh. Nothing is learned (Mozc never commits).
     */
    private fun suggestJapanese(typed: String, gen: Int) {
        val incognito = settings.incognito || sensitive
        val chosen = japaneseSegments
        engine.post {
            if (!mozcTried && !locked) {
                mozcTried = true
                mozc = openMozc()
            }
            val started = SystemClock.uptimeMillis()
            val m = mozc
            val session = m?.sessionConvert(typed, incognito = incognito).orEmpty()
            val reading = session.joinToString("") { it.reading }
            val segments = Candidates.extend(chosen, reading)?.let { m?.pinnedConvert(it, incognito) } ?: session
            val predicted = if (reading.isEmpty()) emptyList() else m?.predict(reading, incognito).orEmpty()
            val unavailable = m == null
            val work = SystemClock.uptimeMillis() - started
            main.post {
                Probe.engineWork.add(work)
                if (gen != generation) return@post
                japaneseUnavailable = unavailable
                if (segments.isEmpty()) {
                    if (unavailable) candidateBar.hint = japaneseHint(typed)
                    return@post
                }
                showJapanese(segments, predicted, typed)
            }
        }
    }

    /**
     * Shows a Japanese conversion: the composition as its reading (as the chosen conversion once
     * a segment was chosen), marked with the conversion
     * candidates (API 37), and the candidate list with emoji.
     * Notes: main thread.
     */
    private fun showJapanese(segments: List<Mozc.Segment>, predicted: List<Mozc.Candidate>, typed: String) {
        japaneseSegments = segments
        val reading = segments.joinToString("") { it.reading }
        japaneseReading = reading
        japaneseCandidates = Candidates.japanese(segments, predicted)
        // Once a segment is chosen the field shows the conversion (design: PhoneSegment), so what
        // Enter commits is what the person sees; until then it shows the reading.
        val chosen = segments.any { it.pinned }
        val shown = if (chosen) japaneseCandidates.firstOrNull()?.text ?: reading else reading
        setComposition(shown, japaneseCandidates.map { it.text }, chosen)
        candidates = withEmoji(japaneseCandidates.map { it.text }, emojiFor(typed))
        candidateBar.show(candidates, 0)
        shownCandidates.value = candidates
        updateSegmentPanel()
    }

    /**
     * Sets the composition text with, on API 37, the conversion candidates and whether the shown
     * text holds a chosen conversion (TextAttribute, PLAN §8.2).
     */
    private fun setComposition(text: String, conversions: List<String>, selected: Boolean) {
        val ic = currentInputConnection ?: return
        if (Build.VERSION.SDK_INT >= API_TEXT_SUGGESTION_SELECTED) {
            val attribute = TextAttribute.Builder()
                .setTextConversionSuggestions(conversions.take(CONVERSION_HINTS))
                .setTextSuggestionSelected(selected)
                .build()
            ic.setComposingText(text, 1, attribute)
        } else {
            ic.setComposingText(text, 1)
        }
    }

    /** Forgets the Japanese conversion (main thread). */
    private fun clearJapanese() {
        japaneseReading = ""
        japaneseSegments = emptyList()
        japaneseCandidates = emptyList()
        segmentFocus = null
    }

    /** Says that this build cannot convert Japanese while Japanese mode has letters, or null. */
    private fun japaneseHint(raw: String): String? =
        if (mode == Mode.JAPANESE && !direct && japaneseUnavailable && raw.isNotEmpty()) getString(R.string.bar_japanese_unavailable) else null

    private val segmentView = androidx.compose.runtime.mutableStateOf<SegmentView?>(null)

    /** Rebuilds what the segment panel shows from the current conversion. */
    private fun updateSegmentPanel() {
        val segments = japaneseSegments
        val focus = segmentFocus?.takeIf { it in segments.indices }
        segmentView.value = if (segments.isEmpty()) null else SegmentView(
            texts = segments.map { it.candidates.firstOrNull()?.value ?: it.reading },
            readings = segments.map { it.reading },
            focus = focus,
            candidates = focus?.let { Candidates.segment(segments[it]).map { c -> c.text } } ?: japaneseCandidates.map { it.text },
            shorter = focus != null && Candidates.resize(segments, focus, -1) != null,
            longer = focus != null && Candidates.resize(segments, focus, 1) != null,
        )
    }

    /** Opens the segment panel in the keyboard's place (Japanese mode's expand arrow). */
    private fun showSegments() {
        val height = keyboard.height
        val panel = segmentPanel ?: ComposeView(this).also { view ->
            segmentPanel = view
            view.setContent {
                SegmentPanel(
                    view = segmentView,
                    onFocus = { segmentFocus = it; updateSegmentPanel() },
                    onPick = ::pickSegment,
                    onResize = ::resizeSegment,
                    onBack = ::showKeyboard,
                )
            }
        }
        segmentFocus = segmentFocus?.takeIf { it in japaneseSegments.indices }
        updateSegmentPanel()
        panelHost.removeAllViews()
        panelHost.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    }

    /**
     * Chooses candidate [index] in the segment panel: for the whole sentence it commits like the
     * candidate bar; for a segment it pins that segment and converts the rest again around it.
     */
    private fun pickSegment(index: Int) {
        val focus = segmentFocus ?: return run { showKeyboard(); pick(index) }
        val segment = japaneseSegments.getOrNull(focus) ?: return
        val surface = Candidates.segment(segment).getOrNull(index)?.text ?: return
        reconvert(Candidates.pick(japaneseSegments, focus, surface) ?: return, focus + 1)
    }

    /** Shrinks (−1) or grows (+1) the segment being edited by one character and converts again. */
    private fun resizeSegment(delta: Int) {
        val focus = segmentFocus ?: return
        reconvert(Candidates.resize(japaneseSegments, focus, delta) ?: return, focus)
    }

    /**
     * Converts [spans] with Mozc on the engine thread and shows the result, with segment [focus]
     * (clamped to the result) chosen in the segment panel.
     */
    private fun reconvert(spans: List<Mozc.Span>, focus: Int) {
        val incognito = settings.incognito || sensitive
        val typed = composing.toString()
        val gen = ++generation
        engine.post {
            val segments = mozc?.pinnedConvert(spans, incognito).orEmpty()
            main.post {
                if (gen != generation || segments.isEmpty()) return@post
                segmentFocus = focus.coerceIn(0, segments.size - 1)
                showJapanese(segments, emptyList(), typed)
            }
        }
    }

    /**
     * Switches to mode [to], keeping an open composition (PLAN §2.3): its letters move to the new
     * mode's engine uncommitted, so "nihao" typed in Chinese becomes にはお in Japanese and the
     * letters in English. Mixed and Chinese share librime, so between them nothing moves.
     * Inputs: [downTime] is the touch that chose the mode (0 for none), for [Probe].
     * Side effects: replaces the composing text in the field; segment choices and the English tap
     * positions of the old mode are dropped. When librime is not open, letters moving into Chinese
     * are committed as typed.
     */
    private fun switchMode(to: Mode, downTime: Long) {
        val fromRime = usesRime
        previousWord = null
        mode = to
        val ic = currentInputConnection
        when {
            direct || ic == null -> Unit
            fromRime && !usesRime && chinesePreedit.isNotEmpty() -> {
                val letters = chineseInput.filter { it in 'a'..'z' || it in 'A'..'Z' }
                clearChinese()
                rimeIndexes = emptyList()
                composing.clear().append(letters)
                taps.clear()
                repeat(letters.length) { taps += null }
                if (letters.isEmpty()) ic.commitText("", 1) else ic.setComposingText(composing, 1)
            }
            !fromRime && usesRime && composing.isNotEmpty() -> {
                val letters = composing.toString().lowercase()
                composing.clear()
                taps.clear()
                clearJapanese()
                engine.post {
                    val state = if (rimeReady) {
                        Rime.clear()
                        rimeState(Rime.setInput(letters))
                    } else {
                        rimeState(false)
                    }
                    main.post {
                        if (state.preedit.isEmpty()) currentInputConnection?.commitText(letters, 1)
                        applyChinese(state, 0)
                        refresh(0)
                    }
                }
            }
            !usesRime && composing.isNotEmpty() -> {
                // English and Japanese keep the romaji; the field shows the letters again until
                // the new mode's engine answers.
                clearJapanese()
                ic.setComposingText(composing, 1)
            }
        }
        updateAutoCaps()
        refresh(downTime)
    }

    /**
     * Fixes the current mode for the current app (the quick panel's switch), or releases it when
     * [on] is false; nothing is kept where the field may not leave a trace.
     */
    private fun pinHere(on: Boolean) {
        val app = currentInputEditorInfo?.packageName ?: return
        if (!remembers) return showNotice(getString(R.string.pin_not_here))
        val pin = if (on) mode else null
        pinnedHere.value = pin
        memory()?.update { m -> m.withApp(app) { it.copy(pinned = pin) } }
    }

    /**
     * Whether pinyin goes through librime: Chinese mode, and for now Mixed mode too, until the
     * mixed coordinator (spike S6) handles pinyin, romaji and English in one string.
     */
    private val usesRime get() = mode == Mode.CHINESE || mode == Mode.MIXED

    /**
     * Whether this field may leave a trace: the per-app mode and script, emoji recents. False in
     * incognito mode and in sensitive and direct fields.
     */
    private val remembers get() = !direct && !sensitive && !settings.incognito

    /**
     * Finds emoji for what is being typed (English words, pinyin, romaji; CLDR keywords), when
     * emoji suggestions are on and the field is not sensitive.
     * Returns: up to three emoji, best first.
     */
    private fun emojiFor(typed: String): List<String> =
        if (direct || !settings.mixed.cross.emoji) emptyList() else Candidates.emoji(emojiIndex, typed)

    /** Puts [emoji] into [list] at the third place (after the most likely words), marking them. */
    private fun withEmoji(list: List<String>, emoji: List<String>): List<String> {
        emojiShown = emoji.toSet()
        return Candidates.withEmoji(list, emoji)
    }

    /** Says why Chinese mode cannot convert yet (dictionary being prepared or failed), or null. */
    private fun chineseHint(): String? {
        if (!usesRime || direct || rimeOpen) return null
        return when (RimeFiles(this).readStatus()) {
            is RimeFiles.Status.Failed -> getString(R.string.bar_rime_failed)
            else -> getString(R.string.bar_rime_preparing)
        }
    }

    /**
     * Asks the engine thread for English suggestions and shows them if still current.
     * Notes: the letters' tap positions go along when every letter has one, so the engine weighs
     * a tap near a neighbouring key (key correction, PLAN §9.7); otherwise each letter counts as
     * a tap on its key's centre.
     */
    private fun suggestEnglish(typed: String, previous: String?, gen: Int) {
        val points = taps.takeIf { it.size == typed.length && it.all { p -> p != null } }?.map { it!! }
        val xs = points?.map { it.first }?.toIntArray()
        val ys = points?.map { it.second }?.toIntArray()
        engine.post {
            val started = SystemClock.uptimeMillis()
            val words = english?.suggestions(typed, xs, ys, previous)?.map { it.word }.orEmpty()
            val work = SystemClock.uptimeMillis() - started
            main.post {
                Probe.engineWork.add(work)
                if (gen != generation) return@post
                candidates = withEmoji(Candidates.english(typed, words), emojiFor(typed))
                candidateBar.show(candidates, 0)
                shownCandidates.value = candidates
            }
        }
    }

    /** Main thread: the emoji search's query while the keys type into it (PLAN §8.6), or null. */
    private var emojiSearch: StringBuilder? = null

    /**
     * Starts the emoji search (the emoji panel's Search tab): the letter keys come back and type
     * into the query shown in the candidate bar, never into the field.
     */
    private fun startSearch() {
        emojiSearch = StringBuilder()
        symbols = false
        shift = Shift.OFF
        showKeyboard()
        refresh(0)
    }

    /**
     * Handles a key while the emoji search is open: letters, digits and space extend [query],
     * backspace shortens it (and leaves the search when it is empty), Enter sends the first
     * result, the Emoji key returns to the panel and choosing a mode leaves the search.
     * Returns: true when the key was the search's; false lets page and Shift keys work as usual.
     */
    private fun searchKey(action: KeyAction, query: StringBuilder): Boolean {
        when (action) {
            is KeyAction.Text -> query.append(action.text.lowercase()).also { if (shift == Shift.ONCE) shift = Shift.OFF }
            KeyAction.Space -> if (query.isNotEmpty() && query.last() != ' ') query.append(' ')
            KeyAction.Backspace -> if (query.isEmpty()) return true.also { leaveSearch(toPanel = true) } else query.setLength(query.length - 1)
            KeyAction.Enter -> return true.also { if (candidates.isNotEmpty()) pickFound(0) else leaveSearch(toPanel = false) }
            KeyAction.Emoji -> return true.also { leaveSearch(toPanel = true) }
            is KeyAction.SetMode -> {
                leaveSearch(toPanel = false)
                return false
            }
            is KeyAction.MoveCursor, is KeyAction.DeleteWords -> Unit
            else -> return false
        }
        refresh(0)
        return true
    }

    /** Sends search result [index] to the field and leaves the search, counting it as used. */
    private fun pickFound(index: Int) {
        val emoji = candidates.getOrNull(index) ?: return
        currentInputConnection?.commitText(emoji, 1)
        if (!locked && remembers) memory()?.update { it.copy(emoji = it.emoji.sent(emoji)) }
        leaveSearch(toPanel = false)
    }

    /** Leaves the emoji search, back to the emoji panel ([toPanel]) or to the keys. */
    private fun leaveSearch(toPanel: Boolean) {
        emojiSearch = null
        candidateBar.search = null
        candidates = emptyList()
        if (toPanel) showEmoji()
        refresh(0)
    }

    private fun showKeyboard() {
        if (!::panelHost.isInitialized || panelHost.getChildAt(0) === keyboard) return
        panelHost.removeAllViews()
        panelHost.addView(keyboard)
        updateQuickStrip()
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
                    onBackspace = { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL) },
                    onSearch = ::startSearch,
                    onBack = ::showKeyboard,
                    // Sensitive fields record nothing, recent emoji included.
                    recordRecents = !locked && currentInputEditorInfo != null && remembers,
                    memory = memory(),
                )
            }
        }
        panelHost.removeAllViews()
        panelHost.addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    }

    private companion object {
        const val ENGLISH_DICT = "main_en_us.dict"
        const val MOZC_DATA = "mozc.data"
        const val NOTICE_MS = 1500L
        /** The API level that added TextAttribute.Builder.setTextSuggestionSelected. */
        const val API_TEXT_SUGGESTION_SELECTED = 37
        /** Conversion candidates passed to the field with the composition. */
        const val CONVERSION_HINTS = 5
        /** Two spaces closer than this make a full stop. */
        const val DOUBLE_SPACE_MS = 400L
        /** The most text handed to the AI screen at once. */
        const val AI_MAX_CHARS = 2000
        // X11 keysyms that librime takes.
        const val SPACE = 0x20
        const val BACKSPACE = 0xff08
        const val RETURN = 0xff0d
    }
}

/** One AI replacement that Undo can take back: the range it now fills and both texts. */
private data class AiUndo(val start: Int, val end: Int, val original: String, val replacement: String)
