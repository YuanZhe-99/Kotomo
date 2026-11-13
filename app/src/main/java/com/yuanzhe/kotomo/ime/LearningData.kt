package com.yuanzhe.kotomo.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * Clears what Kotomo learned from typing (PLAN §8.8, Privacy → Clear learning data): librime's
 * learned words and frequencies, Mozc's conversion history, Mixed mode's learned readings
 * ([MixedLearning]), the emoji history, and the mode and script remembered per app. Modes the person fixed for an app, the settings, the clipboard
 * history (cleared on its own) and dictionaries the person added stay.
 *
 * Notes: the engines own their files while open, so when the keyboard is running it clears them on
 * its engine thread (closing and reopening the engines, [attach]); otherwise the files are deleted
 * on a background thread. Both run in the app's one process.
 */
object LearningData {
    /** The running keyboard's way to clear on its engine thread, or null when it is not running. */
    @Volatile private var keyboard: ((done: () -> Unit) -> Unit)? = null
    private val background = Executors.newSingleThreadExecutor { Thread(it, "kotomo-clear").apply { isDaemon = true } }

    /** Called by the keyboard service when it starts (after the first unlock) and with null when it stops. */
    fun attach(clear: ((done: () -> Unit) -> Unit)?) {
        keyboard = clear
    }

    /**
     * Clears the learning data.
     * Side effects: deletes librime's user dictionaries and Mozc's profile, empties Mixed mode's
     * learned readings, rewrites the keyboard memory; [done] runs on the main thread afterwards.
     */
    fun clear(context: Context, done: () -> Unit) {
        val app = context.applicationContext
        KeyboardMemory.get(app).update(::forget)
        MixedLearning.get(app).clear()
        val main = Handler(Looper.getMainLooper())
        keyboard?.let { return it(done) }
        background.execute {
            deleteFiles(app)
            main.post(done)
        }
    }

    /** Deletes the engines' learning files (the engines must be closed). */
    internal fun deleteFiles(context: Context) =
        clearFiles(RimeFiles(context).user, File(context.filesDir, MOZC_PROFILE))

    /**
     * Deletes librime's learned data in [rimeUser] (every `*.userdb` database, its `*.userdb.txt`
     * snapshot and the `sync` directory) and empties Mozc's [mozcProfile]; librime's deployed
     * `build/` and its settings files stay.
     * Notes: pure file work; unit-tested.
     */
    internal fun clearFiles(rimeUser: File, mozcProfile: File) {
        rimeUser.listFiles().orEmpty()
            .filter { it.name.endsWith(".userdb") || it.name.endsWith(".userdb.txt") || it.name == "sync" }
            .forEach { it.deleteRecursively() }
        mozcProfile.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    /**
     * Returns [memory] without what was learned: no emoji history, no last mode, and per app only
     * the fixed mode (and members a newer version keeps).
     */
    internal fun forget(memory: Memory): Memory = memory.copy(
        lastMode = null,
        apps = memory.apps.mapValues { (_, a) -> a.copy(mode = null, script = null) }.filterValues { !it.empty },
        emoji = EmojiMemory(extra = memory.emoji.extra),
    )

    /** Mozc's profile directory under `files/` (KotomoService.openMozc). */
    const val MOZC_PROFILE = "mozc/profile"
}
