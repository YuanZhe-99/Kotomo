package com.yuanzhe.kotomo.ime

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.yuanzhe.kotomo.dictionary.Downloader
import com.yuanzhe.kotomo.dictionary.HttpsTransport
import com.yuanzhe.kotomo.dictionary.Json
import com.yuanzhe.kotomo.dictionary.long
import com.yuanzhe.kotomo.dictionary.str
import com.yuanzhe.kotomo.settings.readVersioned
import com.yuanzhe.kotomo.settings.writeAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Names how the keyboard draws emoji (PLAN §11.5): the system's font, the Noto Color Emoji pack
 * downloaded on request, or a font the person imported.
 * Notes: only the keyboard's own drawing changes; the app being typed in shows emoji with its own
 * font. The names are stored, so renaming one is a format change.
 */
enum class EmojiStyle { SYSTEM, NOTO, CUSTOM }

/**
 * The emoji style setting as `files/emoji/style.json` stores it: the style in use and the name of
 * the imported font. Notes: [extra] keeps unknown members.
 */
data class EmojiStyleConfig(val style: EmojiStyle = EmojiStyle.SYSTEM, val customName: String = "", val extra: Map<String, Any?> = emptyMap())

/** Maps [EmojiStyleConfig] to and from JSON (pure Kotlin; unit-tested). */
internal object EmojiStyleFormat {
    const val VERSION = 1
    private val KNOWN = setOf("format_version", "updated_utc_ms", "style", "custom_name")

    /** Reads the config; null for text that is not a JSON object. */
    fun read(text: String): EmojiStyleConfig? {
        @Suppress("UNCHECKED_CAST")
        val o = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: return null
        return EmojiStyleConfig(
            style = o.str("style")?.let { s -> EmojiStyle.entries.firstOrNull { it.name.lowercase() == s } } ?: EmojiStyle.SYSTEM,
            customName = o.str("custom_name")?.take(NAME_MAX).orEmpty(),
            extra = o.filterKeys { it !in KNOWN || it == "format_version" },
        )
    }

    /** Writes the config. */
    fun write(c: EmojiStyleConfig, nowUtcMs: Long): String {
        val out = LinkedHashMap<String, Any?>()
        out["format_version"] = maxOf((c.extra["format_version"] as? Long)?.toInt() ?: 0, VERSION)
        out["updated_utc_ms"] = nowUtcMs
        out["style"] = c.style.name.lowercase()
        if (c.customName.isNotEmpty()) out["custom_name"] = c.customName
        c.extra.forEach { (k, v) -> if (k !in KNOWN) out[k] = v }
        return Json.write(out, indent = true)
    }

    /** Whether [head] (a file's first bytes) starts like a TrueType or OpenType font. */
    fun looksLikeFont(head: ByteArray): Boolean {
        if (head.size < 4) return false
        val tag = head.copyOf(4)
        return tag.contentEquals(byteArrayOf(0, 1, 0, 0)) || String(tag, Charsets.ISO_8859_1) in setOf("OTTO", "true", "ttcf")
    }

    const val NAME_MAX = 60
}

/**
 * Keeps the emoji style (PLAN §11.5): the setting, the Noto Color Emoji pack (downloaded only
 * when the person asks, from the address and SHA-256 pinned here), an imported font, and the
 * typeface the keyboard draws emoji with.
 *
 * Notes: one instance per process ([get]); [typeface] never touches the disk once loaded. The font
 * files live in `files/emoji/`. A font that cannot be loaded falls back to the system's emoji.
 */
class EmojiStyles private constructor(private val dir: File) {
    private val file = File(dir, CONFIG)
    private val _config = MutableStateFlow(readVersioned(file, EmojiStyleFormat::read) ?: EmojiStyleConfig())
    private var loaded: Pair<File, Typeface?>? = null

    /** The setting as last read or written. */
    val config: StateFlow<EmojiStyleConfig> = _config

    /** The downloaded Noto Color Emoji font. */
    val noto: File get() = File(dir, NOTO_FILE)

    /** The imported font. */
    val custom: File get() = File(dir, CUSTOM_FILE)

    /** Chooses [style] (a pack that is not on the device falls back to the system's emoji when drawn). */
    fun choose(style: EmojiStyle) = save(_config.value.copy(style = style))

    private fun save(c: EmojiStyleConfig) {
        writeAtomically(file, EmojiStyleFormat.write(c, System.currentTimeMillis()))
        _config.value = c
    }

    /**
     * Returns the typeface to draw emoji with, or null for the system's.
     * Notes: main thread; loads the font file once (a few milliseconds), never on a key.
     * Android 10 and later keep the system font as fallback for anything the pack lacks.
     */
    fun typeface(): Typeface? {
        val f = when (_config.value.style) {
            EmojiStyle.SYSTEM -> return null
            EmojiStyle.NOTO -> noto
            EmojiStyle.CUSTOM -> custom
        }
        if (!f.isFile) return null
        loaded?.takeIf { it.first == f }?.let { return it.second }
        val t = runCatching { load(f) }.getOrNull()
        loaded = f to t
        return t
    }

    /**
     * Downloads the Noto Color Emoji pack.
     * Inputs: [progress] receives (bytes so far, total).
     * Side effects: connects to raw.githubusercontent.com over HTTPS (only because the person tapped
     * Download after the notice naming it); the file is checked against the pinned size and SHA-256
     * before it replaces anything.
     * Throws [com.yuanzhe.kotomo.dictionary.DownloadException] when it cannot be fetched or does not match.
     */
    suspend fun downloadNoto(progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val tmp = File(dir, "$NOTO_FILE.part")
        try {
            Downloader(HttpsTransport()).fetch(NOTO_URL, tmp, NOTO_SIZE, NOTO_SHA256, progress)
            if (!tmp.renameTo(noto)) throw com.yuanzhe.kotomo.dictionary.DownloadException(com.yuanzhe.kotomo.dictionary.DownloadProblem.STORAGE)
            loaded = null
        } finally {
            tmp.delete()
        }
    }

    /** Deletes the Noto pack; the system's emoji are used again when it was chosen. */
    fun deleteNoto() {
        noto.delete()
        loaded = null
        if (_config.value.style == EmojiStyle.NOTO) choose(EmojiStyle.SYSTEM)
    }

    /**
     * Imports the font in [input] (a file the person picked) as the custom pack named [name].
     * Returns: false when it is larger than [CUSTOM_MAX], not a TrueType or OpenType font, or does
     * not load; nothing changes then.
     * Notes: IO thread; the file is untrusted (true boundary).
     */
    suspend fun importCustom(input: InputStream, name: String): Boolean = withContext(Dispatchers.IO) {
        val tmp = File(dir, "$CUSTOM_FILE.part")
        try {
            dir.mkdirs()
            var total = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > CUSTOM_MAX) return@withContext false
                    out.write(buf, 0, n)
                }
            }
            val head = ByteArray(4).also { h -> tmp.inputStream().use { it.read(h) } }
            if (!EmojiStyleFormat.looksLikeFont(head) || runCatching { load(tmp) }.getOrNull() == null) return@withContext false
            if (!tmp.renameTo(custom)) return@withContext false
            loaded = null
            save(_config.value.copy(style = EmojiStyle.CUSTOM, customName = name.take(EmojiStyleFormat.NAME_MAX)))
            true
        } finally {
            tmp.delete()
        }
    }

    /** Deletes the imported font; the system's emoji are used again when it was chosen. */
    fun deleteCustom() {
        custom.delete()
        loaded = null
        save(_config.value.copy(style = if (_config.value.style == EmojiStyle.CUSTOM) EmojiStyle.SYSTEM else _config.value.style, customName = ""))
    }

    /** Loads [f] as a typeface, with the system font behind it on Android 10 and later. */
    private fun load(f: File): Typeface? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val family = FontFamily.Builder(Font.Builder(f).build()).build()
            return Typeface.CustomFallbackBuilder(family).setSystemFallback("sans-serif").build()
        }
        return Typeface.createFromFile(f).takeIf { it != Typeface.DEFAULT }
    }

    companion object {
        private const val CONFIG = "style.json"
        private const val NOTO_FILE = "noto-color-emoji.ttf"
        private const val CUSTOM_FILE = "custom.ttf"
        /** Noto Color Emoji, release v2026-09-24-unicode18_0 (OFL-1.1; `third-party.md`). */
        const val NOTO_URL = "https://raw.githubusercontent.com/googlefonts/noto-emoji/v2026-09-24-unicode18_0/2D/fonts/NotoColorEmoji.ttf"
        const val NOTO_SIZE = 10_730_124L
        const val NOTO_SHA256 = "15671215ab769fdc7162a045d56fd7d7e477c51b04e6b3c761d914d8fdd6cc44"
        /** The host the Noto download connects to, named in the notice before it. */
        const val NOTO_HOST = "raw.githubusercontent.com"
        /** The largest font imported. */
        const val CUSTOM_MAX = 40L shl 20
        private const val BUFFER = 64 * 1024

        @Volatile private var instance: EmojiStyles? = null

        /** Returns the process's emoji styles, reading the small setting file on first use. */
        fun get(context: Context): EmojiStyles = instance ?: synchronized(this) {
            instance ?: EmojiStyles(File(context.applicationContext.filesDir, "emoji")).also { instance = it }
        }
    }
}

/**
 * Draws [content] (the emoji panel) with [typeface] as its text font, or unchanged when null.
 */
@Composable
fun EmojiFont(typeface: Typeface?, content: @Composable () -> Unit) {
    if (typeface == null) return content()
    CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = androidx.compose.ui.text.font.FontFamily(typeface)), content = content)
}
