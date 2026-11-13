package com.yuanzhe.kotomo.settings

import android.content.Context
import com.yuanzhe.kotomo.dictionary.Json
import com.yuanzhe.kotomo.dictionary.list
import com.yuanzhe.kotomo.dictionary.obj
import com.yuanzhe.kotomo.dictionary.str
import com.yuanzhe.kotomo.ime.Mode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.UUID

/** One theme the person saved, under its [id] (`saved.<uuid>`). */
data class SavedTheme(val id: String, val theme: Theme)

/**
 * Names a group of built-in themes (PLAN §11.4: built-in themes sorted by city and region).
 */
enum class PresetGroup { BASIC, CITY }

/** One built-in theme: its [id] (`builtin.<name>`), its group and the theme itself. */
data class Preset(val id: String, val group: PresetGroup, val theme: Theme)

/**
 * Holds the saved themes and the theme each input mode uses (PLAN §11.4: different languages can
 * use different themes), as the file `files/themes/library.json` stores them.
 *
 * Notes: [modes] maps a mode to a saved or built-in theme id; a mode without one uses the current
 * theme. [extra] keeps unknown members.
 */
data class ThemeLibraryData(
    val saved: List<SavedTheme> = emptyList(),
    val modes: Map<Mode, String> = emptyMap(),
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** Returns the theme [id] names (saved or built-in), or null when it is gone. */
    fun find(id: String): Theme? = saved.firstOrNull { it.id == id }?.theme ?: ThemePresets.all.firstOrNull { it.id == id }?.theme

    /** Returns the theme [mode] uses: its own one when set and still there, otherwise [current]. */
    fun forMode(mode: Mode, current: Theme): Theme = modes[mode]?.let(::find) ?: current
}

/**
 * Kotomo's built-in themes: the three plain ones and original palettes named after cities
 * (PLAN §11.4). The colors are Kotomo's own; no other keyboard's theme is copied.
 */
object ThemePresets {
    private fun theme(name: String, base: KeyboardTheme, vararg colors: Pair<ColorRole, Long>) =
        Theme(base = base, name = name, colors = colors.associate { (r, c) -> r to c.toInt() })

    /** Every built-in theme, plain ones first. */
    val all: List<Preset> = listOf(
        Preset("builtin.light", PresetGroup.BASIC, theme("Light", KeyboardTheme.LIGHT)),
        Preset("builtin.dark", PresetGroup.BASIC, theme("Dark", KeyboardTheme.DARK)),
        Preset("builtin.dynamic", PresetGroup.BASIC, theme("Material You", KeyboardTheme.DYNAMIC)),
        Preset(
            "builtin.tokyo_night", PresetGroup.CITY,
            theme(
                "Tokyo Night", KeyboardTheme.DARK,
                ColorRole.BACKGROUND to 0xFF141726, ColorRole.KEY to 0xFF232842, ColorRole.FUNCTION_KEY to 0xFF30365A,
                ColorRole.PRESSED_KEY to 0xFF434B7A, ColorRole.TEXT to 0xFFE8EAF6, ColorRole.SECONDARY_TEXT to 0xFF9AA0C8,
                ColorRole.ACCENT to 0xFFFF6FA3, ColorRole.ON_ACCENT to 0xFF2A0716, ColorRole.CANDIDATE to 0xFF4A2340,
                ColorRole.CANDIDATE_TEXT to 0xFFFFD6E6,
            ),
        ),
        Preset(
            "builtin.kyoto", PresetGroup.CITY,
            theme(
                "Kyoto", KeyboardTheme.LIGHT,
                ColorRole.BACKGROUND to 0xFFF3EDE2, ColorRole.KEY to 0xFFFFFBF4, ColorRole.FUNCTION_KEY to 0xFFE4D9C6,
                ColorRole.PRESSED_KEY to 0xFFD3C4AA, ColorRole.TEXT to 0xFF2B2118, ColorRole.SECONDARY_TEXT to 0xFF6E5E4C,
                ColorRole.ACCENT to 0xFFB33A2B, ColorRole.ON_ACCENT to 0xFFFFFFFF, ColorRole.CANDIDATE to 0xFFF4D9D2,
                ColorRole.CANDIDATE_TEXT to 0xFF7A2116,
            ),
        ),
        Preset(
            "builtin.shanghai", PresetGroup.CITY,
            theme(
                "Shanghai", KeyboardTheme.DARK,
                ColorRole.BACKGROUND to 0xFF10171C, ColorRole.KEY to 0xFF1D2A31, ColorRole.FUNCTION_KEY to 0xFF28404A,
                ColorRole.PRESSED_KEY to 0xFF35596A, ColorRole.TEXT to 0xFFE3F1F4, ColorRole.SECONDARY_TEXT to 0xFF8FB0BA,
                ColorRole.ACCENT to 0xFFF2B84B, ColorRole.ON_ACCENT to 0xFF2B1E00, ColorRole.CANDIDATE to 0xFF3E3418,
                ColorRole.CANDIDATE_TEXT to 0xFFFFE3A8,
            ),
        ),
        Preset(
            "builtin.taipei", PresetGroup.CITY,
            theme(
                "Taipei", KeyboardTheme.LIGHT,
                ColorRole.BACKGROUND to 0xFFE8F1EC, ColorRole.KEY to 0xFFFFFFFF, ColorRole.FUNCTION_KEY to 0xFFCFE2D7,
                ColorRole.PRESSED_KEY to 0xFFB5D1C1, ColorRole.TEXT to 0xFF13261C, ColorRole.SECONDARY_TEXT to 0xFF4D6A5B,
                ColorRole.ACCENT to 0xFF1F7A55, ColorRole.ON_ACCENT to 0xFFFFFFFF, ColorRole.CANDIDATE to 0xFFCDEBDC,
                ColorRole.CANDIDATE_TEXT to 0xFF0E4A31,
            ),
        ),
        Preset(
            "builtin.sapporo", PresetGroup.CITY,
            theme(
                "Sapporo Snow", KeyboardTheme.LIGHT,
                ColorRole.BACKGROUND to 0xFFEDF2F8, ColorRole.KEY to 0xFFFFFFFF, ColorRole.FUNCTION_KEY to 0xFFD8E2EE,
                ColorRole.PRESSED_KEY to 0xFFC2D1E3, ColorRole.TEXT to 0xFF15202E, ColorRole.SECONDARY_TEXT to 0xFF56677D,
                ColorRole.ACCENT to 0xFF2F6FD0, ColorRole.ON_ACCENT to 0xFFFFFFFF, ColorRole.CANDIDATE to 0xFFDCE8FA,
                ColorRole.CANDIDATE_TEXT to 0xFF173E7A,
            ),
        ),
    )
}

/**
 * Maps the theme library and exported theme files to and from JSON.
 * Notes: pure Kotlin; unit-tested. The layout is documented in `doc/en-us/settings-format.md`.
 */
internal object ThemeLibraryFormat {
    /** The library file's format version this build writes. */
    const val VERSION = 1
    private const val FORMAT = "format_version"
    private const val UPDATED = "updated_utc_ms"
    private const val THEMES = "themes"
    private const val MODES = "modes"
    private const val ID = "id"
    private val KNOWN = setOf(FORMAT, UPDATED, THEMES, MODES)
    /** The export-only member holding the background image, base64-encoded PNG. */
    const val IMAGE_DATA = "background_image_png"
    /** The largest export or import read, in bytes (a theme with its picture). */
    const val EXPORT_MAX = 12L shl 20
    /** The most themes kept. */
    const val SAVED_MAX = 100
    private val SAVED_ID = Regex("saved\\.[0-9a-f-]{36}")

    /**
     * Reads the library file.
     * Returns: the library (unreadable themes and unknown modes left out); null for text that is
     * not a JSON object.
     */
    fun read(text: String): ThemeLibraryData? {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return null
        val saved = o.list(THEMES).orEmpty().mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            val id = m.str(ID)?.takeIf { SAVED_ID.matches(it) } ?: return@mapNotNull null
            ThemeFormat.read(Json.write(m - ID))?.let { SavedTheme(id, it) }
        }.distinctBy { it.id }.take(SAVED_MAX)
        val modes = o.obj(MODES).orEmpty().mapNotNull { (k, v) ->
            val mode = Mode.entries.firstOrNull { it.name.lowercase() == k } ?: return@mapNotNull null
            (v as? String)?.let { mode to it }
        }.toMap()
        return ThemeLibraryData(saved, modes, o.filterKeys { it !in KNOWN || it == FORMAT })
    }

    /** Writes the library; the format version never goes below one a newer build wrote. */
    fun write(lib: ThemeLibraryData, nowUtcMs: Long): String {
        val out = LinkedHashMap<String, Any?>()
        out[FORMAT] = maxOf((lib.extra[FORMAT] as? Long)?.toInt() ?: 0, VERSION)
        out[UPDATED] = nowUtcMs
        out[THEMES] = lib.saved.map { linkedMapOf<String, Any?>(ID to it.id) + ThemeFormat.toMap(it.theme, nowUtcMs) }
        out[MODES] = lib.modes.entries.associate { (m, id) -> m.name.lowercase() to id }
        lib.extra.forEach { (k, v) -> if (k !in KNOWN) out[k] = v }
        return Json.write(out, indent = true)
    }

    /**
     * Writes [theme] as a file to share: its JSON with the picture's PNG bytes [image] inside.
     */
    fun export(theme: Theme, image: ByteArray?, nowUtcMs: Long): String {
        val m = LinkedHashMap(ThemeFormat.toMap(theme.copy(image = null), nowUtcMs))
        if (image != null) m[IMAGE_DATA] = Base64.getEncoder().encodeToString(image)
        return Json.write(m, indent = true)
    }

    /**
     * Reads a shared theme file.
     * Returns: the theme (without an image name) and the picture's bytes when it has one (the
     * caller checks they decode as an image), or null when the text is not a theme.
     */
    fun import(text: String): Pair<Theme, ByteArray?>? {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return null
        if (o["format_version"] == null || o["base"] == null) return null
        val theme = ThemeFormat.read(text)?.copy(image = null) ?: return null
        val bytes = o.str(IMAGE_DATA)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
        return theme.copy(extra = theme.extra - IMAGE_DATA) to bytes
    }
}

/**
 * Keeps the theme library in `files/themes/library.json` and the background pictures in
 * `files/themes/images/`, for the settings app and the keyboard in the same process.
 *
 * Notes: one instance per process ([get]); [state] never touches the disk. Writes run one at a
 * time on the IO dispatcher through a temporary file and an atomic replace. A damaged file is set
 * aside (`.unreadable`) and the library starts empty.
 */
class ThemeLibrary private constructor(private val dir: File) {
    private val file = File(dir, FILE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _state = MutableStateFlow(ThemeLibraryData())

    /** The library as last read or written. */
    val state: StateFlow<ThemeLibraryData> = _state

    init {
        scope.launch { lock.withLock { _state.value = readVersioned(file, ThemeLibraryFormat::read) ?: ThemeLibraryData() } }
    }

    /** The folder of the background pictures. */
    val images: File get() = File(dir, IMAGES)

    /**
     * Applies [transform] and writes the result.
     * Side effects: rewrites the library file; [state] emits.
     */
    suspend fun update(transform: (ThemeLibraryData) -> ThemeLibraryData) = withContext(Dispatchers.IO) {
        lock.withLock {
            val next = transform(_state.value)
            if (next != _state.value) {
                writeAtomically(file, ThemeLibraryFormat.write(next, System.currentTimeMillis()))
                _state.value = next
            }
        }
    }

    /** Saves [theme] as a new library theme. Returns: its id. */
    suspend fun save(theme: Theme): String {
        val id = "saved." + UUID.randomUUID()
        update { it.copy(saved = (it.saved + SavedTheme(id, theme)).takeLast(ThemeLibraryFormat.SAVED_MAX)) }
        return id
    }

    /** Deletes saved theme [id] and releases the modes that used it. */
    suspend fun delete(id: String) = update { lib ->
        lib.copy(saved = lib.saved.filter { it.id != id }, modes = lib.modes.filterValues { it != id })
    }

    /** Stores [png] as a new background picture. Returns: its file name. */
    suspend fun addImage(png: ByteArray): String = withContext(Dispatchers.IO) {
        val name = UUID.randomUUID().toString().replace("-", "").take(IMAGE_ID) + ".png"
        writeAtomically(File(images, name), png)
        name
    }

    /** Returns the bytes of background picture [name], or null. */
    fun image(name: String?): File? = name?.let { File(images, it) }?.takeIf { it.isFile }

    /**
     * Deletes the pictures no theme uses any more.
     * Inputs: [current] is the current theme, whose picture stays.
     */
    suspend fun dropUnused(current: Theme) = withContext(Dispatchers.IO) {
        val used = (_state.value.saved.map { it.theme.image } + current.image).filterNotNull().toSet()
        images.listFiles()?.filter { it.name !in used }?.forEach { it.delete() }
    }

    companion object {
        private const val FILE = "library.json"
        private const val IMAGES = "images"
        private const val IMAGE_ID = 24

        @Volatile private var instance: ThemeLibrary? = null

        /** Returns the process's library, reading its file on first use (background thread). */
        fun get(context: Context): ThemeLibrary = instance ?: synchronized(this) {
            instance ?: ThemeLibrary(File(context.applicationContext.filesDir, "themes")).also { instance = it }
        }
    }
}
