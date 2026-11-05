package com.yuanzhe.kotomo.settings

import com.yuanzhe.kotomo.dictionary.Json
import com.yuanzhe.kotomo.dictionary.long
import com.yuanzhe.kotomo.dictionary.str
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Holds the keyboard theme as its JSON file stores it (PLAN §8.6): the colors it follows and the
 * key corner radius.
 *
 * Notes: [extra] keeps every member this version does not know (a newer format, the theme
 * editor's colors), so rewriting the file never drops them.
 */
data class Theme(
    val base: KeyboardTheme = KeyboardTheme.SYSTEM,
    val keyRadiusDp: Int = Settings().keyRadiusDp,
    val extra: Map<String, Any?> = emptyMap(),
)

/**
 * Maps a [Theme] to and from its JSON file.
 *
 * Notes: the layout is documented in `doc/en-us/settings-format.md`. Pure Kotlin; unit-tested.
 */
internal object ThemeFormat {
    /** The format version this build writes. */
    const val VERSION = 1
    private const val FORMAT = "format_version"
    private const val UPDATED = "updated_utc_ms"
    private const val BASE = "base"
    private const val RADIUS = "key_radius_dp"
    private val KNOWN = setOf(FORMAT, UPDATED, BASE, RADIUS)

    /**
     * Reads a theme file.
     * Returns: the theme, with defaults for missing, unknown or out-of-range values; null when the
     * text is not a JSON object (a damaged file).
     */
    fun read(text: String): Theme? {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return null
        val d = Theme()
        return Theme(
            base = o.str(BASE)?.let { raw -> KeyboardTheme.entries.firstOrNull { it.name.lowercase() == raw } } ?: d.base,
            keyRadiusDp = o.long(RADIUS)?.takeIf { it in Settings.RADIUS_RANGE.first..Settings.RADIUS_RANGE.last }?.toInt() ?: d.keyRadiusDp,
            extra = o.filterKeys { it !in KNOWN || it == FORMAT },
        )
    }

    /**
     * Writes [theme] as JSON.
     * Notes: the format version never goes below one a newer build wrote; [nowUtcMs] stamps it.
     */
    fun write(theme: Theme, nowUtcMs: Long): String {
        val format = maxOf((theme.extra[FORMAT] as? Long)?.toInt() ?: 0, VERSION)
        val out = LinkedHashMap<String, Any?>()
        out[FORMAT] = format
        out[UPDATED] = nowUtcMs
        out[BASE] = theme.base.name.lowercase()
        out[RADIUS] = theme.keyRadiusDp
        theme.extra.forEach { (k, v) -> if (k !in KNOWN) out[k] = v }
        return Json.write(out, indent = true)
    }
}

/**
 * Keeps the current theme in `files/themes/current.json`.
 *
 * Notes: background thread only (called from [SettingsStore]'s writes). A damaged file is moved
 * aside to `.unreadable` and the theme starts from the settings' defaults.
 */
class ThemeFile(private val file: File) {
    /** Reads the file; null when there is none yet (the theme still lives in the settings) or it was damaged. */
    fun load(): Theme? {
        if (!file.isFile) return null
        val text = try { file.readText() } catch (_: IOException) { return null }
        return ThemeFormat.read(text) ?: run {
            runCatching { Files.move(file.toPath(), File(file.path + ".unreadable").toPath(), StandardCopyOption.REPLACE_EXISTING) }
            null
        }
    }

    /** Writes [theme] through a temporary file and an atomic replace. */
    fun save(theme: Theme, nowUtcMs: Long) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            FileOutputStream(tmp).use { it.write(ThemeFormat.write(theme, nowUtcMs).toByteArray()); it.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            // The next change writes again; the theme in memory stays right meanwhile.
        }
    }
}
