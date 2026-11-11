package com.yuanzhe.kotomo.ime

import android.content.Context
import com.yuanzhe.kotomo.dictionary.Json
import com.yuanzhe.kotomo.dictionary.list
import com.yuanzhe.kotomo.dictionary.long
import com.yuanzhe.kotomo.dictionary.obj
import com.yuanzhe.kotomo.dictionary.str
import com.yuanzhe.kotomo.settings.ChineseScript
import com.yuanzhe.kotomo.settings.OneHanded
import com.yuanzhe.kotomo.settings.readVersioned
import com.yuanzhe.kotomo.settings.writeAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

/**
 * Holds what the keyboard remembers for one app (PLAN §2.3, §8.4): the mode last used there, the
 * mode fixed for it, and the Chinese script chosen there.
 *
 * Notes: only the package name is stored, never anything typed. [extra] keeps members this version
 * does not know.
 */
data class AppMemory(
    val mode: Mode? = null,
    val pinned: Mode? = null,
    val script: ChineseScript? = null,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** Whether nothing is remembered (the app's entry can go). */
    val empty get() = mode == null && pinned == null && script == null && extra.isEmpty()
}

/**
 * Holds the emoji the person sent from the panel or its search: the most recent first, and how
 * often each was sent (PLAN §8.6, "Frequently used").
 * Notes: [extra] keeps members this version does not know.
 */
data class EmojiMemory(
    val recent: List<String> = emptyList(),
    val uses: Map<String, Int> = emptyMap(),
    val extra: Map<String, Any?> = emptyMap(),
) {
    /**
     * Returns the emoji sent at least twice, most often first (the more recent first on a tie),
     * at most [limit].
     */
    fun frequent(limit: Int = Memory.MAX_FREQUENT): List<String> {
        val order = recent.withIndex().associate { (i, e) -> e to i }
        return uses.filterValues { it >= 2 }.keys
            .sortedWith(compareByDescending<String> { uses.getValue(it) }.thenBy { order[it] ?: Int.MAX_VALUE })
            .take(limit)
    }

    /**
     * Returns a copy with [emoji] sent once more: first in [recent] and counted in [uses].
     * Notes: keeps at most [Memory.MAX_RECENT] recent emoji and [Memory.MAX_USES] counts,
     * dropping the least used (the least recent on a tie).
     */
    fun sent(emoji: String): EmojiMemory {
        val nextRecent = (listOf(emoji) + recent.filter { it != emoji }).take(Memory.MAX_RECENT)
        var nextUses = uses + (emoji to (uses[emoji] ?: 0) + 1)
        if (nextUses.size > Memory.MAX_USES) {
            val order = nextRecent.withIndex().associate { (i, e) -> e to i }
            val drop = nextUses.keys.filter { it != emoji }
                .sortedWith(compareBy<String> { nextUses.getValue(it) }.thenByDescending { order[it] ?: Int.MAX_VALUE })
                .take(nextUses.size - Memory.MAX_USES)
            nextUses = nextUses - drop.toSet()
        }
        return copy(recent = nextRecent, uses = nextUses)
    }
}

/**
 * Holds one-handed mode as chosen on one screen in one orientation (PLAN §8.10): the side the
 * keys sit on (or off) and their width in percent of the full keyboard.
 * Notes: [extra] keeps members this version does not know.
 */
data class OneHandedMemory(
    val side: OneHanded = OneHanded.OFF,
    val widthPercent: Int = DEFAULT_WIDTH,
    val extra: Map<String, Any?> = emptyMap(),
) {
    companion object {
        /** The width the one-handed keys take until the person changes it, in percent. */
        const val DEFAULT_WIDTH = 82
        /** The widths the one-handed keys may take, in percent. */
        val WIDTH_RANGE = 66..92
    }
}

/**
 * Holds the keyboard's own memory, as `files/keyboard/memory.json` stores it: the mode last used
 * anywhere, what is remembered per app, the emoji history, and one-handed mode per screen and
 * orientation (keys from [Posture.screenKey]).
 *
 * Notes: the layout is documented in `doc/en-us/settings-format.md`; [extra] keeps unknown members
 * (a newer format) so rewriting never drops them.
 */
data class Memory(
    val lastMode: Mode? = null,
    val apps: Map<String, AppMemory> = emptyMap(),
    val emoji: EmojiMemory = EmojiMemory(),
    val oneHanded: Map<String, OneHandedMemory> = emptyMap(),
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** Returns [app]'s memory (empty when nothing is remembered). */
    fun app(app: String): AppMemory = apps[app] ?: AppMemory()

    /**
     * Returns one-handed mode on [screen] ([Posture.screenKey]); a screen never set follows
     * [fallback], the side the settings chose before it was remembered per screen.
     */
    fun oneHandedOn(screen: String, fallback: OneHanded): OneHandedMemory = oneHanded[screen] ?: OneHandedMemory(fallback)

    /** Returns a copy with one-handed mode on [screen] changed by [change] (see [oneHandedOn]). */
    fun withOneHanded(screen: String, fallback: OneHanded, change: (OneHandedMemory) -> OneHandedMemory): Memory =
        copy(oneHanded = oneHanded + (screen to change(oneHandedOn(screen, fallback))))

    /** Returns a copy with [app]'s memory changed by [change]; an emptied entry is removed. */
    fun withApp(app: String, change: (AppMemory) -> AppMemory): Memory {
        val next = change(app(app))
        return copy(apps = if (next.empty) apps - app else apps + (app to next))
    }

    companion object {
        /** The most emoji kept in the history (four rows of a phone-width grid). */
        const val MAX_RECENT = 32
        /** The most emoji whose use is counted. */
        const val MAX_USES = 200
        /** The most emoji under "Frequently used" (two rows of a phone-width grid). */
        const val MAX_FREQUENT = 16
        /** Longer than any emoji sequence; anything longer in the file is not an emoji. */
        const val MAX_EMOJI_LENGTH = 32
    }
}

/**
 * Maps [Memory] to and from its JSON text, and reads what older builds kept in SharedPreferences.
 *
 * Notes: enum values are stored as their lowercase constant names; unknown or invalid values read
 * as absent. Pure Kotlin; unit-tested (MemoryFormatTest).
 */
internal object MemoryFormat {
    /** The format version this build writes. */
    const val VERSION = 1
    private const val FORMAT = "format_version"
    private const val UPDATED = "updated_utc_ms"
    private const val LAST_MODE = "last_mode"
    private const val APPS = "apps"
    private const val EMOJI = "emoji"
    private const val ONE_HANDED = "one_handed"
    private const val SIDE = "side"
    private const val WIDTH = "width_percent"
    private const val MODE = "mode"
    private const val PINNED = "pinned_mode"
    private const val SCRIPT = "script"
    private const val RECENT = "recent"
    private const val USES = "uses"
    private val KNOWN = setOf(FORMAT, UPDATED, LAST_MODE, APPS, EMOJI, ONE_HANDED)

    /**
     * Reads a memory file.
     * Returns: the memory with absent values for anything missing or unknown; null when the text
     * is not a JSON object (a damaged file).
     * Notes: a value this version cannot read stays in the entry's [AppMemory.extra] (or the
     * file's [Memory.extra]) and is written back unchanged until a new value replaces it.
     */
    fun read(text: String): Memory? {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return null
        val apps = o.obj(APPS).orEmpty().mapNotNull { (app, value) ->
            @Suppress("UNCHECKED_CAST")
            val a = value as? Map<String, Any?> ?: return@mapNotNull null
            val mode = mode(a.str(MODE))
            val pinned = mode(a.str(PINNED))
            val script = a.str(SCRIPT)?.let { raw -> ChineseScript.entries.firstOrNull { it.name.lowercase() == raw } }
            // A value this version cannot read (a newer mode) stays as it was.
            val read = setOfNotNull(MODE.takeIf { mode != null }, PINNED.takeIf { pinned != null }, SCRIPT.takeIf { script != null })
            app to AppMemory(mode, pinned, script, a - read)
        }.toMap()
        val e = o.obj(EMOJI).orEmpty()
        val recent = e.list(RECENT).orEmpty().filterIsInstance<String>()
            .filter { it.isNotBlank() && it.length <= Memory.MAX_EMOJI_LENGTH }.distinct().take(Memory.MAX_RECENT)
        val lastMode = mode(o.str(LAST_MODE))
        val uses = e.obj(USES).orEmpty().mapNotNull { (emoji, n) ->
            val count = (n as? Long)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            if (count == null || emoji.isBlank() || emoji.length > Memory.MAX_EMOJI_LENGTH) null else emoji to count
        }.toMap()
        val oneHanded = o.obj(ONE_HANDED).orEmpty().mapNotNull { (screen, value) ->
            @Suppress("UNCHECKED_CAST")
            val h = value as? Map<String, Any?> ?: return@mapNotNull null
            val side = h.str(SIDE)?.let { raw -> OneHanded.entries.firstOrNull { it.name.lowercase() == raw } }
            val width = h.long(WIDTH)?.takeIf { it in OneHandedMemory.WIDTH_RANGE.first..OneHandedMemory.WIDTH_RANGE.last }?.toInt()
            val read = setOfNotNull(SIDE.takeIf { side != null }, WIDTH.takeIf { width != null })
            screen to OneHandedMemory(side ?: OneHanded.OFF, width ?: OneHandedMemory.DEFAULT_WIDTH, h - read)
        }.toMap()
        return Memory(
            lastMode = lastMode,
            apps = apps,
            emoji = EmojiMemory(recent, uses, e - setOf(RECENT, USES)),
            oneHanded = oneHanded,
            extra = o.filterKeys { it !in KNOWN || it == FORMAT || (it == LAST_MODE && lastMode == null) },
        )
    }

    /**
     * Writes [memory] as JSON.
     * Notes: the format version never goes below one a newer build wrote; [nowUtcMs] stamps it.
     */
    fun write(memory: Memory, nowUtcMs: Long): String {
        val out = LinkedHashMap<String, Any?>()
        out[FORMAT] = maxOf((memory.extra[FORMAT] as? Long)?.toInt() ?: 0, VERSION)
        out[UPDATED] = nowUtcMs
        (memory.lastMode?.key ?: memory.extra[LAST_MODE])?.let { out[LAST_MODE] = it }
        out[APPS] = memory.apps.toSortedMap().mapValues { (_, a) ->
            LinkedHashMap<String, Any?>(a.extra).apply {
                a.mode?.let { put(MODE, it.key) }
                a.pinned?.let { put(PINNED, it.key) }
                a.script?.let { put(SCRIPT, it.name.lowercase()) }
            }
        }
        out[EMOJI] = LinkedHashMap<String, Any?>().apply {
            put(RECENT, memory.emoji.recent)
            put(USES, memory.emoji.uses)
            putAll(memory.emoji.extra)
        }
        out[ONE_HANDED] = memory.oneHanded.toSortedMap().mapValues { (_, h) ->
            LinkedHashMap<String, Any?>(h.extra).apply {
                put(SIDE, h.side.name.lowercase())
                put(WIDTH, h.widthPercent)
            }
        }
        memory.extra.forEach { (k, v) -> if (k !in KNOWN) out[k] = v }
        return Json.write(out, indent = true)
    }

    /**
     * Builds the memory from what builds before format 1 kept in SharedPreferences: the `ime` file
     * (`mode`, `mode.<app>`, `script.<app>`, each the constant name) and the `emoji_recents` file
     * (`items`, the emoji joined by newlines).
     * Inputs: [ime] and [emoji] are the two files' entries (empty when absent).
     */
    fun fromLegacy(ime: Map<String, *>, emoji: Map<String, *>): Memory {
        fun legacyMode(v: Any?) = (v as? String)?.let { name -> Mode.entries.firstOrNull { it.name == name } }
        var memory = Memory(lastMode = legacyMode(ime[LEGACY_MODE]))
        for ((key, value) in ime) {
            when {
                key.startsWith("$LEGACY_MODE.") -> legacyMode(value)?.let { m ->
                    memory = memory.withApp(key.removePrefix("$LEGACY_MODE.")) { it.copy(mode = m) }
                }
                key.startsWith("$LEGACY_SCRIPT.") -> (value as? String)?.let { name -> ChineseScript.entries.firstOrNull { it.name == name } }?.let { s ->
                    memory = memory.withApp(key.removePrefix("$LEGACY_SCRIPT.")) { it.copy(script = s) }
                }
            }
        }
        val recent = (emoji[LEGACY_ITEMS] as? String).orEmpty().split('\n')
            .filter { it.isNotBlank() && it.length <= Memory.MAX_EMOJI_LENGTH }.distinct().take(Memory.MAX_RECENT)
        return memory.copy(emoji = EmojiMemory(recent))
    }

    private fun mode(raw: String?) = raw?.let { r -> Mode.entries.firstOrNull { it.key == r } }
    private val Mode.key get() = name.lowercase()

    /** The SharedPreferences files and keys of builds before format 1. */
    const val LEGACY_IME = "ime"
    const val LEGACY_EMOJI = "emoji_recents"
    private const val LEGACY_MODE = "mode"
    private const val LEGACY_SCRIPT = "script"
    private const val LEGACY_ITEMS = "items"
}

/**
 * Keeps the keyboard's [Memory] in memory and in `files/keyboard/memory.json`, shared by the
 * keyboard and the settings app (one instance per process, [get]).
 *
 * Notes: [current] never touches the disk, so the keyboard reads it on the main thread. Each
 * change writes the whole file on one background thread, in order, through an atomic replace.
 * Never used before the first unlock (credential-protected storage). On first use without the
 * file, the SharedPreferences of older builds are read once and left as they are, so an older
 * build installed over this one still finds its values.
 */
class KeyboardMemory internal constructor(private val file: File, legacy: () -> Memory?) {
    private val state = MutableStateFlow(readVersioned(file, MemoryFormat::read) ?: legacy() ?: Memory())
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "kotomo-memory").apply { isDaemon = true } }

    /** Emits the memory after every change (for the settings pages). */
    val flow: StateFlow<Memory> get() = state

    /** Returns the memory as it is now, without I/O. */
    val current: Memory get() = state.value

    /**
     * Applies [transform] and writes the result when it changed.
     * Side effects: queues a write of the whole file on the memory thread.
     */
    fun update(transform: (Memory) -> Memory) {
        var changed: Memory? = null
        state.update { old -> transform(old).also { if (it != old) changed = it } }
        changed?.let { snapshot ->
            val now = System.currentTimeMillis()
            writer.execute { writeAtomically(file, MemoryFormat.write(snapshot, now)) }
        }
    }

    companion object {
        /** The memory file, under `files/`. */
        private const val FILE = "keyboard/memory.json"

        @Volatile private var instance: KeyboardMemory? = null

        /**
         * Returns the process's memory, reading the file (or the older preferences) on first use.
         * Notes: the first call reads one small file on the calling thread; the keyboard makes it
         * when the service starts, never on a key.
         */
        fun get(context: Context): KeyboardMemory = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                KeyboardMemory(File(app.filesDir, FILE)) {
                    val ime = app.getSharedPreferences(MemoryFormat.LEGACY_IME, Context.MODE_PRIVATE).all
                    val emoji = app.getSharedPreferences(MemoryFormat.LEGACY_EMOJI, Context.MODE_PRIVATE).all
                    if (ime.isEmpty() && emoji.isEmpty()) null else MemoryFormat.fromLegacy(ime, emoji)
                }.also { instance = it }
            }
        }
    }
}
