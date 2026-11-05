package com.yuanzhe.kotomo.ime.clipboard

import android.content.ClipData
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.yuanzhe.kotomo.settings.ClipboardSettings
import com.yuanzhe.kotomo.settings.Settings
import com.yuanzhe.kotomo.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Sorts a clip for its tag in the panel: a link, an address, a phone number, a code or text. */
enum class ClipKind {
    TEXT, LINK, ADDRESS, PHONE, CODE;

    companion object {
        private val link = Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE)
        private val phone = Regex("^\\+?[0-9][0-9 ()\\-.]{5,22}[0-9]$")
        // Explicit ranges (CJK ideographs, kana) rather than script classes, which differ between
        // the JVM and Android's ICU-based regex.
        private const val HAN = "\\u3400-\\u4DBF\\u4E00-\\u9FFF"
        private val addressJa = Regex("[$HAN]{1,4}[都道府県][$HAN\\u3040-\\u30FF々]{1,10}[市区町村郡]|\\d+丁目|\\d+番地")
        private val addressZh = Regex("[$HAN]{1,6}[省市][$HAN]{1,8}[区區县縣]|[$HAN]{1,10}(路|街|大道|巷|弄)\\s*\\d+\\s*[号號]")
        private val addressEn = Regex(
            "\\b\\d{1,6}\\s+(\\w+\\s+){1,4}(street|st|avenue|ave|road|rd|boulevard|blvd|lane|ln|drive|dr|way|court|ct|place|pl)\\b\\.?",
            RegexOption.IGNORE_CASE,
        )
        private val sentenceEnd = Regex("[。！？!?]")

        /** Codes in a message this short and this likely make the whole clip a code. */
        private const val CODE_MESSAGE = 160
        private const val CODE_SCORE = 70

        /**
         * Classifies a clip with simple local rules.
         * Notes: a bare code, then a link (one URL and nothing else), a phone number (7–15
         * digits, a `+`, separators or nine digits and more), a short message with a likely code,
         * an address (Japanese, Chinese or English street shapes, no sentence punctuation), else text.
         */
        fun of(text: String): ClipKind {
            val t = text.trim()
            val digits = t.count { it in '0'..'9' }
            return when {
                VerificationCodes.bare(t) != null -> CODE
                link.matches(t) -> LINK
                phone.matches(t) && digits in 7..15 && (digits >= 9 || t.any { it !in '0'..'9' }) -> PHONE
                t.length <= CODE_MESSAGE && (VerificationCodes.find(t).firstOrNull()?.score ?: 0) >= CODE_SCORE -> CODE
                t.length <= 120 && !sentenceEnd.containsMatchIn(t) &&
                    (addressJa.containsMatchIn(t) || addressZh.containsMatchIn(t) || addressEn.containsMatchIn(t)) -> ADDRESS
                else -> TEXT
            }
        }
    }
}

/**
 * Holds one item of the clipboard history.
 *
 * Notes: [timeUtcMs] is when it was copied (UTC milliseconds). Pinned items and quick phrases
 * never expire and are never dropped by the count cap. [extra] keeps fields written by a newer
 * format so they survive this version rewriting the file. [kind] and [code] are derived when the
 * item is made (on the history's I/O thread), so the panel never classifies while drawing.
 */
data class Clip(
    val id: Long,
    val text: String,
    val timeUtcMs: Long,
    val pinned: Boolean = false,
    val phrase: Boolean = false,
    val extra: Map<String, String> = emptyMap(),
) {
    /** The tag the panel shows. */
    val kind: ClipKind = ClipKind.of(text)

    /** The likeliest verification code, for code items. */
    val code: VerificationCode? = if (kind == ClipKind.CODE) VerificationCodes.find(text).firstOrNull() else null

    /** Whether the item is kept until the person removes it. */
    val kept: Boolean get() = pinned || phrase
}

/**
 * Holds text just copied, for the quick-paste strip: the text, the codes found in it, and when it
 * was copied (UTC milliseconds).
 */
data class Copied(val text: String, val codes: List<VerificationCode>, val atUtcMs: Long)

/**
 * Reads and writes the history file: an encrypted container around a list of tagged records.
 *
 * Notes: the layout is documented in `doc/en-us/clipboard.md`. Container: the magic `KCLP`, one
 * container-version byte, a 12-byte GCM IV, then the AES-GCM ciphertext and tag; the magic and
 * version are the associated data. Plaintext: a record count, then each record as a field count
 * and key/value pairs (key: modified UTF-8 via writeUTF; value: length and UTF-8 bytes). The first
 * record is the header. Pure JVM code, unit-tested with a software key.
 */
internal object ClipFile {
    /** The container layout this build reads and writes. */
    const val CONTAINER = 1
    /** The record format this build writes (the header's `format_version`). */
    const val VERSION = 1
    private val MAGIC = "KCLP".toByteArray(Charsets.US_ASCII)
    private const val IV = 12
    private const val TAG_BITS = 128
    private const val CIPHER = "AES/GCM/NoPadding"
    /** More records or fields than this means the file is damaged. */
    private const val MAX_COUNT = 100_000

    /** Reads the container version, or null when the bytes are not a history file. */
    fun container(bytes: ByteArray): Int? =
        if (bytes.size > MAGIC.size + 1 + IV && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) bytes[MAGIC.size].toInt() else null

    /**
     * Encrypts and frames [records].
     * Notes: the cipher picks a fresh random IV each time (required by the Keystore key).
     */
    fun seal(records: List<Map<String, String>>, key: SecretKey): ByteArray {
        val head = MAGIC + byteArrayOf(CONTAINER.toByte())
        val cipher = Cipher.getInstance(CIPHER).apply { init(Cipher.ENCRYPT_MODE, key) }
        cipher.updateAAD(head)
        val body = cipher.doFinal(encode(records))
        return head + cipher.iv + body
    }

    /**
     * Decrypts and parses a container of [CONTAINER] layout.
     * Returns: the records, header first.
     * Notes: throws GeneralSecurityException when the key does not fit or the bytes were changed,
     * IOException when the plaintext is malformed.
     */
    fun open(bytes: ByteArray, key: SecretKey): List<Map<String, String>> {
        val headSize = MAGIC.size + 1
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, headSize, IV))
        cipher.updateAAD(bytes, 0, headSize)
        return decode(cipher.doFinal(bytes, headSize + IV, bytes.size - headSize - IV))
    }

    private fun encode(records: List<Map<String, String>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(records.size)
            for (r in records) {
                out.writeInt(r.size)
                for ((k, v) in r) {
                    out.writeUTF(k)
                    val b = v.toByteArray(Charsets.UTF_8)
                    out.writeInt(b.size)
                    out.write(b)
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): List<Map<String, String>> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val count = input.readInt()
        if (count !in 1..MAX_COUNT) throw IOException("bad record count")
        return List(count) {
            val fields = input.readInt()
            if (fields !in 0..MAX_COUNT) throw IOException("bad field count")
            buildMap {
                repeat(fields) {
                    val k = input.readUTF()
                    val size = input.readInt()
                    if (size !in 0..input.available()) throw IOException("bad value size")
                    put(k, String(ByteArray(size).also(input::readFully), Charsets.UTF_8))
                }
            }
        }
    }
}

/**
 * Keeps the clipboard history (PLAN §10.7): records what is copied while the keyboard is allowed
 * to, dedupes, pins, keeps quick phrases, expires unpinned items after the retention and caps
 * their count, and stores everything in an app-private file encrypted with a key in the Android
 * Keystore.
 *
 * Inputs: file is the history file; key returns the AES key (the Keystore key in the app, a
 * software key in tests); scope runs the file work; settings returns the clipboard settings in
 * force; clock returns UTC milliseconds.
 * Notes: [items] is an in-memory snapshot the keyboard reads on the main thread without I/O; every
 * change runs in order on one background thread and rewrites the file. Clip text never reaches a
 * log. One instance per process ([get]).
 */
class ClipboardHistory internal constructor(
    private val file: File,
    private val key: () -> SecretKey,
    private val scope: CoroutineScope,
    private val settings: () -> ClipboardSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Runs every load, change and write in submission order. */
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private val state = MutableStateFlow<List<Clip>>(emptyList())
    /** Background thread only: the header record as read, unknown fields included. */
    private var header: Map<String, String> = emptyMap()
    /** Background thread only: false when the file is from a newer container or the key is unavailable. */
    private var writable = true
    private val secret by lazy { key() }

    /** The history, newest first; empty until the file is read (a few milliseconds after creation). */
    val items: StateFlow<List<Clip>> = state

    init {
        scope.launch(serial) { load() }
    }

    /**
     * Records the primary clip, when allowed, and returns it for the quick-paste strip.
     * Inputs: clip is ClipboardManager.getPrimaryClip(); allowed is [allowedIn] for the current
     * field and settings.
     * Returns: the copied text and its codes, or null when nothing may be offered: not allowed, no
     * text, or a clip marked sensitive.
     * Side effects: adds it to the history when the history is on.
     * Notes: main thread. Clips marked `EXTRA_IS_SENSITIVE` (Android 13+, and the same key set by
     * password managers on older versions) are neither recorded nor returned. Only the first
     * item's plain text is taken; URIs and intents are ignored.
     */
    fun onPrimaryClip(clip: ClipData?, allowed: Boolean): Copied? {
        if (!allowed || clip == null || clip.itemCount == 0) return null
        val description = clip.description
        if (description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true) return null
        val text = clip.getItemAt(0).text?.toString() ?: return null
        return add(text, description?.timestamp?.takeIf { it > 0 } ?: clock())
    }

    /**
     * Adds copied [text] (when the history is on) and returns it with its codes.
     * Returns: null for blank or over-long text.
     * Notes: copying text already in the history moves it to the front, keeping its pin; the same
     * clip seen again (same text, no newer time) changes nothing.
     */
    internal fun add(text: String, atUtcMs: Long): Copied? {
        if (text.isBlank() || text.length > MAX_TEXT) return null
        if (settings().history) change { list ->
            val old = list.firstOrNull { it.text == text }
            if (old != null && old.timeUtcMs >= atUtcMs) return@change list
            val clip = old?.copy(timeUtcMs = atUtcMs) ?: Clip(nextId(list), text, atUtcMs)
            listOf(clip) + list.filterNot { it === old }
        }
        return Copied(text, VerificationCodes.find(text), atUtcMs)
    }

    /** Pins or unpins an item; pinned items never expire. */
    fun pin(id: Long, pinned: Boolean) = change { list -> list.map { if (it.id == id) it.copy(pinned = pinned) else it } }

    /** Deletes an item. Side effects: rewrites the file without it. */
    fun delete(id: Long) = change { list -> list.filterNot { it.id == id } }

    /**
     * Adds a quick phrase (kept until removed), or marks the same text already in the history as one.
     * Notes: for the settings app's phrase editor; works whether the history is on or not.
     */
    fun addPhrase(text: String) {
        if (text.isBlank() || text.length > MAX_TEXT) return
        val now = clock()
        change { list ->
            val old = list.firstOrNull { it.text == text }
            if (old != null) list.map { if (it === old) it.copy(phrase = true) else it } else listOf(Clip(nextId(list), text, now, phrase = true)) + list
        }
    }

    /** Deletes every item that is neither pinned nor a quick phrase. */
    fun clearUnpinned() = change { list -> list.filter { it.kept } }

    /**
     * Drops unpinned items older than the retention.
     * Notes: also runs after the file is read and with every change; call it when the panel opens
     * so an item that expired while the keyboard sat idle is not shown.
     */
    fun expire() = change { it }

    /** Waits until every change submitted so far is applied and written. For tests. */
    internal suspend fun flush() = withContext(serial) {}

    /**
     * Applies [transform] to the list on the background thread, then the retention and the cap,
     * publishes the result and writes the file when anything changed.
     */
    private fun change(transform: (List<Clip>) -> List<Clip>) {
        scope.launch(serial) {
            val old = state.value
            val new = trim(transform(old))
            if (new == old) return@launch
            state.value = new
            save(new)
        }
    }

    /** Keeps pinned items and phrases, unpinned items within the retention, at most [MAX_ITEMS] of them. */
    private fun trim(list: List<Clip>): List<Clip> {
        val cutoff = clock() - settings().retentionDays.coerceIn(Settings.RETENTION_RANGE) * DAY_MS
        var unpinned = 0
        return list.filter { it.kept || (it.timeUtcMs >= cutoff && ++unpinned <= MAX_ITEMS) }
    }

    private fun nextId(list: List<Clip>) = (list.maxOfOrNull { it.id } ?: 0) + 1

    /**
     * Reads the file into [items].
     * Notes: a file from a newer container is left untouched: the history starts empty and is
     * never written in this process, so newer data is never overwritten. A file
     * that cannot be decrypted or parsed (a lost key, damage) is moved aside to `.unreadable` and
     * the history starts empty. When the Keystore cannot give the key, the history works in
     * memory only for this process.
     */
    private fun load() {
        if (!file.isFile) return
        val bytes = try { file.readBytes() } catch (_: IOException) { writable = false; return }
        val container = ClipFile.container(bytes)
        if (container != null && container > ClipFile.CONTAINER) {
            writable = false
            return
        }
        val key = try { secret } catch (_: Exception) { writable = false; return }
        val records = try {
            if (container == null) throw IOException("not a history file")
            ClipFile.open(bytes, key)
        } catch (_: GeneralSecurityException) {
            setAside(); return
        } catch (_: IOException) {
            setAside(); return
        }
        header = records.first()
        state.value = trim(records.drop(1).mapNotNull(::clipOf).sortedByDescending { it.timeUtcMs })
    }

    /** Writes [list] through a temporary file and an atomic replace. */
    private fun save(list: List<Clip>) {
        if (!writable) return
        val key = try { secret } catch (_: Exception) { writable = false; return }
        val format = maxOf(header[FORMAT]?.toIntOrNull() ?: 0, ClipFile.VERSION)
        header = header + mapOf(FORMAT to format.toString(), UPDATED to clock().toString())
        val bytes = ClipFile.seal(listOf(header) + list.map(::recordOf), key)
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            // The next change writes again; the snapshot in memory stays right meanwhile.
        }
    }

    private fun setAside() {
        try {
            Files.move(file.toPath(), File(file.path + ".unreadable").toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            writable = false
        }
    }

    companion object {
        /** The extra that marks a sensitive clip (`ClipDescription.EXTRA_IS_SENSITIVE`, API 33; apps set it earlier too). */
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        /** Unpinned items kept at most. */
        const val MAX_ITEMS = 200
        /** Longer clips are not recorded. */
        const val MAX_TEXT = 20_000
        private const val DAY_MS = 86_400_000L
        private const val FORMAT = "format_version"
        private const val UPDATED = "updated_utc_ms"
        private const val KEY_ALIAS = "kotomo_clipboard"
        private const val FILE = "clipboard/history.kcb"

        /** Reads one item record; null when its required fields are missing or malformed. */
        private fun clipOf(r: Map<String, String>): Clip? {
            val id = r["id"]?.toLongOrNull() ?: return null
            val text = r["text"]?.takeIf { it.isNotBlank() } ?: return null
            val time = r["time_utc_ms"]?.toLongOrNull() ?: return null
            return Clip(id, text, time, r["pinned"] == "1", r["phrase"] == "1", r - KNOWN)
        }

        private val KNOWN = setOf("id", "text", "time_utc_ms", "pinned", "phrase")

        /** Writes one item record, its unknown fields kept. */
        private fun recordOf(c: Clip): Map<String, String> = c.extra + mapOf(
            "id" to c.id.toString(),
            "text" to c.text,
            "time_utc_ms" to c.timeUtcMs.toString(),
            "pinned" to if (c.pinned) "1" else "0",
            "phrase" to if (c.phrase) "1" else "0",
        )

        /**
         * Decides whether the keyboard may record or offer clips now.
         * Inputs: info is the current field (null when no field is bound).
         * Returns: false in incognito mode, in password fields and in fields with
         * IME_FLAG_NO_PERSONALIZED_LEARNING; true otherwise (URL and e-mail fields included,
         * unlike direct typing).
         */
        fun allowedIn(info: EditorInfo?, settings: Settings): Boolean =
            !settings.incognito && (info == null || !sensitiveField(info.inputType, info.imeOptions))

        /** Decides whether a field's input type and options mark it sensitive (PLAN §14). */
        internal fun sensitiveField(inputType: Int, imeOptions: Int): Boolean {
            if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }

        @Volatile private var instance: ClipboardHistory? = null

        /**
         * Returns the process's history, creating it on first use.
         * Side effects: the first call starts reading `files/clipboard/history.kcb` on a background
         * thread and, on first save, creates the Keystore key `kotomo_clipboard`.
         */
        fun get(context: Context): ClipboardHistory = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val store = SettingsStore.get(app)
                ClipboardHistory(
                    File(app.filesDir, FILE),
                    ::keystoreKey,
                    CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    { store.current.clipboard },
                ).also { instance = it }
            }
        }

        /**
         * Returns the history's AES-256-GCM key from the Android Keystore, creating it when missing.
         * Notes: background thread; the key never leaves the Keystore.
         */
        private fun keystoreKey(): SecretKey {
            val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
