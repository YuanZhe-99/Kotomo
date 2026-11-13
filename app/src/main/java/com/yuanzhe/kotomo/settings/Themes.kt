package com.yuanzhe.kotomo.settings

import com.yuanzhe.kotomo.dictionary.Json
import com.yuanzhe.kotomo.dictionary.long
import com.yuanzhe.kotomo.dictionary.obj
import com.yuanzhe.kotomo.dictionary.str
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Names the colors a theme can set (PLAN §11.4), each with the member name its JSON uses.
 * Notes: the names are stored, so renaming one is a format change; a role the theme leaves out
 * takes its base's color.
 */
enum class ColorRole(val key: String) {
    BACKGROUND("background"),
    KEY("key"),
    FUNCTION_KEY("key_function"),
    PRESSED_KEY("key_pressed"),
    TEXT("text"),
    SECONDARY_TEXT("text_secondary"),
    ACCENT("accent"),
    ON_ACCENT("on_accent"),
    CANDIDATE("candidate"),
    CANDIDATE_TEXT("candidate_text"),
}

/**
 * Holds one keyboard theme as its JSON file stores it (PLAN §8.6, §11.4): the colors it starts
 * from, the person's own colors over them, the key corner radius, a background image and its
 * opacity, the pressed-key highlight and a name.
 *
 * Notes: [extra] keeps every member this version does not know (a newer format), so rewriting the
 * file never drops them. [image] is a file name under `files/themes/images/`, never a path.
 */
data class Theme(
    val base: KeyboardTheme = KeyboardTheme.SYSTEM,
    val keyRadiusDp: Int = Settings().keyRadiusDp,
    val name: String = "",
    val colors: Map<ColorRole, Int> = emptyMap(),
    val image: String? = null,
    val imageOpacity: Int = DEFAULT_OPACITY,
    val pressHighlight: Boolean = true,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** Whether two themes look the same (everything but the name and unknown members). */
    fun looksLike(other: Theme) = copy(name = "", extra = emptyMap()) == other.copy(name = "", extra = emptyMap())

    companion object {
        /** The background image's opacity when none was chosen, in percent. */
        const val DEFAULT_OPACITY = 60
        /** The opacities the editor offers, in percent. */
        val OPACITY_RANGE = 0..100
    }
}

/**
 * Maps a [Theme] to and from its JSON form, used by the current theme file, the saved themes and
 * exported theme files.
 *
 * Notes: the layout is documented in `doc/en-us/settings-format.md`. Pure Kotlin; unit-tested.
 * Version 2 added the name, colors, image and press highlight; version 1 files read unchanged.
 */
internal object ThemeFormat {
    /** The format version this build writes. */
    const val VERSION = 2
    private const val FORMAT = "format_version"
    private const val UPDATED = "updated_utc_ms"
    private const val BASE = "base"
    private const val RADIUS = "key_radius_dp"
    private const val NAME = "name"
    private const val COLORS = "colors"
    private const val IMAGE = "background_image"
    private const val OPACITY = "background_opacity"
    private const val PRESS = "press_highlight"
    private val KNOWN = setOf(FORMAT, UPDATED, BASE, RADIUS, NAME, COLORS, IMAGE, OPACITY, PRESS)
    /** The longest name kept. */
    const val NAME_MAX = 40
    private val IMAGE_NAME = Regex("[A-Za-z0-9_-]{1,64}\\.png")

    /**
     * Reads a theme.
     * Returns: the theme, with defaults for missing, unknown or out-of-range values (a color that
     * is not `#RRGGBB` or `#AARRGGBB` is left out, an image name that is not a plain `.png` file
     * name is dropped); null when the text is not a JSON object (a damaged file).
     * Notes: also reads files from other people (imports): nothing in them is trusted.
     */
    fun read(text: String): Theme? {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return null
        val d = Theme()
        val colors = o.obj(COLORS).orEmpty().mapNotNull { (k, v) ->
            val role = ColorRole.entries.firstOrNull { it.key == k } ?: return@mapNotNull null
            parseColor(v as? String)?.let { role to it }
        }.toMap()
        return Theme(
            base = o.str(BASE)?.let { raw -> KeyboardTheme.entries.firstOrNull { it.name.lowercase() == raw } } ?: d.base,
            keyRadiusDp = o.long(RADIUS)?.takeIf { it in Settings.RADIUS_RANGE.first..Settings.RADIUS_RANGE.last }?.toInt() ?: d.keyRadiusDp,
            name = o.str(NAME)?.trim()?.take(NAME_MAX).orEmpty(),
            colors = colors,
            image = o.str(IMAGE)?.takeIf { IMAGE_NAME.matches(it) },
            imageOpacity = o.long(OPACITY)?.takeIf { it in Theme.OPACITY_RANGE.first..Theme.OPACITY_RANGE.last }?.toInt() ?: d.imageOpacity,
            pressHighlight = o[PRESS] as? Boolean ?: d.pressHighlight,
            extra = o.filterKeys { it !in KNOWN || it == FORMAT },
        )
    }

    /**
     * Writes [theme] as JSON.
     * Notes: the format version never goes below one a newer build wrote; [nowUtcMs] stamps it.
     */
    fun write(theme: Theme, nowUtcMs: Long): String = Json.write(toMap(theme, nowUtcMs), indent = true)

    /** The JSON object of [theme], for files that hold several themes. */
    fun toMap(theme: Theme, nowUtcMs: Long): Map<String, Any?> {
        val format = maxOf((theme.extra[FORMAT] as? Long)?.toInt() ?: 0, VERSION)
        val out = LinkedHashMap<String, Any?>()
        out[FORMAT] = format
        out[UPDATED] = nowUtcMs
        out[BASE] = theme.base.name.lowercase()
        out[RADIUS] = theme.keyRadiusDp
        if (theme.name.isNotEmpty()) out[NAME] = theme.name
        if (theme.colors.isNotEmpty()) out[COLORS] = ColorRole.entries.filter { it in theme.colors }.associate { it.key to formatColor(theme.colors.getValue(it)) }
        theme.image?.let { out[IMAGE] = it }
        out[OPACITY] = theme.imageOpacity
        out[PRESS] = theme.pressHighlight
        theme.extra.forEach { (k, v) -> if (k !in KNOWN) out[k] = v }
        return out
    }

    /** Reads `#RRGGBB` (opaque) or `#AARRGGBB`. Returns: the ARGB color, or null. */
    fun parseColor(text: String?): Int? {
        val hex = text?.removePrefix("#") ?: return null
        if (hex.length != 6 && hex.length != 8 || hex.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) return null
        val v = hex.toLong(16).toInt()
        return if (hex.length == 6) v or (0xFF shl 24) else v
    }

    /** Writes an ARGB color as `#RRGGBB` when opaque, `#AARRGGBB` otherwise. */
    fun formatColor(argb: Int): String =
        if (argb ushr 24 == 0xFF) "#%06X".format(argb and 0xFFFFFF) else "#%08X".format(argb)
}

/**
 * Keeps the current theme in `files/themes/current.json`.
 *
 * Notes: background thread only (called from [SettingsStore]'s writes). A damaged file is moved
 * aside to `.unreadable` and the theme starts from the settings' defaults.
 */
class ThemeFile(private val file: File) {
    /** Reads the file; null when there is none yet (the theme still lives in the settings) or it was damaged. */
    fun load(): Theme? = readVersioned(file, ThemeFormat::read)

    /**
     * Writes [theme] through a temporary file and an atomic replace.
     * Notes: on an I/O failure the next change writes again; the theme in memory stays right meanwhile.
     */
    fun save(theme: Theme, nowUtcMs: Long) {
        writeAtomically(file, ThemeFormat.write(theme, nowUtcMs))
    }
}

/**
 * Reads one of Kotomo's versioned JSON files (the theme, the keyboard's memory).
 * Inputs: [parse] maps the text to its model, or null for text that is not the format.
 * Returns: the model, or null when the file is missing, unreadable or damaged.
 * Side effects: a damaged file is moved aside to `<name>.unreadable` so the next write does not
 * lose it silently.
 */
internal fun <T> readVersioned(file: File, parse: (String) -> T?): T? {
    if (!file.isFile) return null
    val text = try { file.readText() } catch (_: IOException) { return null }
    return parse(text) ?: run {
        runCatching { Files.move(file.toPath(), File(file.path + ".unreadable").toPath(), StandardCopyOption.REPLACE_EXISTING) }
        null
    }
}

/**
 * Writes [text] to [file] through a temporary file, a sync and an atomic replace, so a crash
 * leaves either the old file or the new one.
 * Returns: false when the write failed (the old file is then untouched).
 */
internal fun writeAtomically(file: File, text: String): Boolean = writeAtomically(file, text.toByteArray())

/** Writes [bytes] to [file] as [writeAtomically] does for text. */
internal fun writeAtomically(file: File, bytes: ByteArray): Boolean = try {
    file.parentFile?.mkdirs()
    val tmp = File(file.path + ".tmp")
    FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    true
} catch (_: IOException) {
    false
}
