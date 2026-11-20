package com.yuanzhe.kotomo.ime.clipboard

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.LruCache
import android.view.inputmethod.EditorInfo
import com.yuanzhe.kotomo.ime.Fields
import com.yuanzhe.kotomo.settings.ClipboardSettings
import com.yuanzhe.kotomo.settings.Settings
import com.yuanzhe.kotomo.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Sorts a clip for its tag in the panel: a link, an address, a phone number, a code, text or an image. */
enum class ClipKind {
    TEXT, LINK, ADDRESS, PHONE, CODE, IMAGE;

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
 * Describes a stored image clip: its encrypted file name under `files/clipboard/images/`,
 * normalized MIME type, and pixel dimensions.
 */
data class ClipImage(
    val file: String,
    val mime: String,
    val width: Int,
    val height: Int,
)

/**
 * Holds one item of the clipboard history (either text or an image).
 *
 * Notes: [timeUtcMs] is when it was copied (UTC milliseconds). Pinned items and quick phrases
 * never expire and are never dropped by the count caps. [extra] keeps fields written by a newer
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
    val image: ClipImage? = null,
) {
    /** Whether this clip holds an image rather than text. */
    val isImage: Boolean get() = image != null

    /** The tag the panel shows. */
    val kind: ClipKind = if (image != null) ClipKind.IMAGE else ClipKind.of(text)

    /** The likeliest verification code, for code items. */
    val code: VerificationCode? = if (kind == ClipKind.CODE) VerificationCodes.find(text).firstOrNull() else null

    /** Whether the item is kept until the person removes it. */
    val kept: Boolean get() = pinned || phrase
}

/**
 * Holds an image just copied, for the quick-paste strip in memory: raw encoded bytes, normalized
 * MIME type, pixel dimensions, SHA-256 hex digest, and optional stored encrypted file name.
 */
data class CopiedImage(
    val bytes: ByteArray,
    val mime: String,
    val width: Int,
    val height: Int,
    val sha256: String,
    val file: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is CopiedImage &&
            sha256 == other.sha256 &&
            mime == other.mime &&
            width == other.width &&
            height == other.height &&
            file == other.file)

    override fun hashCode(): Int {
        var result = sha256.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + (file?.hashCode() ?: 0)
        return result
    }
}

/**
 * Holds text or image just copied, for the quick-paste strip: the text, the codes found in it,
 * when it was copied (UTC milliseconds), and an optional copied image.
 */
data class Copied(
    val text: String,
    val codes: List<VerificationCode>,
    val atUtcMs: Long,
    val image: CopiedImage? = null,
)

/**
 * Reads and writes the history file and encrypted image files: an encrypted container around a list
 * of tagged records (`KCLP`), plus per-image AES-GCM containers (`KCLI`).
 *
 * Notes: the layout is documented in `doc/en-us/clipboard.md`. History container: the magic
 * `KCLP`, one container-version byte, a 12-byte GCM IV, then the AES-GCM ciphertext and tag; the
 * magic and version are the associated data. Plaintext: a record count, then each record as a
 * field count and key/value pairs (key: modified UTF-8 via writeUTF; value: length and UTF-8 bytes).
 * The first record is the header. Image container: the magic `KCLI`, one version byte (1), a
 * 12-byte GCM IV, then AES-GCM ciphertext and tag of the encoded image bytes with AAD = bytes 0..4.
 * Pure JVM code, unit-tested with a software key.
 */
internal object ClipFile {
    /** The container layout this build reads and writes. */
    const val CONTAINER = 1
    /** The record format this build writes (the header's `format_version`). */
    const val VERSION = 2
    private val MAGIC = "KCLP".toByteArray(Charsets.US_ASCII)
    private val IMAGE_MAGIC = "KCLI".toByteArray(Charsets.US_ASCII)
    private const val IMAGE_CONTAINER = 1
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

    /**
     * Encrypts and frames raw image [plain] bytes in a `KCLI` container with a fresh random IV.
     */
    fun sealBytes(plain: ByteArray, key: SecretKey): ByteArray {
        val head = IMAGE_MAGIC + byteArrayOf(IMAGE_CONTAINER.toByte())
        val cipher = Cipher.getInstance(CIPHER).apply { init(Cipher.ENCRYPT_MODE, key) }
        cipher.updateAAD(head)
        return head + cipher.iv + cipher.doFinal(plain)
    }

    /**
     * Decrypts a `KCLI` image container.
     * Returns: the plaintext encoded image bytes.
     * Notes: throws IOException on bad header/version, GeneralSecurityException on wrong key or tampering.
     */
    fun openBytes(bytes: ByteArray, key: SecretKey): ByteArray {
        val headSize = IMAGE_MAGIC.size + 1
        if (bytes.size <= headSize + IV || !bytes.copyOfRange(0, IMAGE_MAGIC.size).contentEquals(IMAGE_MAGIC)) {
            throw IOException("not an image clip file")
        }
        if (bytes[IMAGE_MAGIC.size].toInt() != IMAGE_CONTAINER) {
            throw IOException("unsupported image clip container")
        }
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, headSize, IV))
        cipher.updateAAD(bytes, 0, headSize)
        return cipher.doFinal(bytes, headSize + IV, bytes.size - headSize - IV)
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
    /**
     * The clipboard timestamp of the last clip refused by [onCopy], or 0. Written on the main
     * thread, kept in [refusedFile] (the timestamp only, never the text) so a restart of the
     * keyboard's process does not forget it.
     */
    @Volatile private var refusedAt = 0L
    private val refusedFile = File(file.parentFile, "refused")
    /** Folder holding encrypted image clip containers (`<sha256>.kci`). */
    private val imagesDir = File(file.parentFile, "images")
    /** Main thread: clipboard timestamp of the last image clip handed to background capture. */
    @Volatile private var lastImageAt = 0L
    /** Lazy so JVM unit tests never instantiate Android LruCache unless thumbnails are requested. */
    private val thumbCache by lazy {
        object : LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount.coerceAtLeast(1)
        }
    }
    /** Monotonic suffix for temporary share files created for `commitContent`. */
    private var shareSeq = 0L
    /** Background thread only: the pending expiry of the oldest unpinned item. */
    private var timer: Job? = null

    /** The history, newest first; empty until the file is read (a few milliseconds after creation). */
    val items: StateFlow<List<Clip>> = state

    /** The unreadable history file set aside by [load]; at most one is kept. */
    private val leftover = File(file.path + ".unreadable")

    init {
        scope.launch(serial) {
            if (refusedAt == 0L) refusedAt = runCatching { refusedFile.readText().trim().toLong() }.getOrDefault(0L)
            load()
            dropStaleLeftover()
        }
    }

    /**
     * Records the primary clip (plain text or image URI), when allowed, and returns text clips
     * synchronously for the quick-paste strip; image clips are read, validated and encrypted off
     * the main thread on [serial] and delivered through [onImage].
     *
     * Inputs: clip is ClipboardManager.getPrimaryClip(); allowed is [allowedIn] for the current
     * field and settings; resolver opens content URIs for image clips; onImage is called on [serial]
     * once a copied image is ready to offer.
     * Returns: the copied text and its codes, or null when the clip is empty, sensitive, refused,
     * has no text/image, or is an image being processed asynchronously.
     * Side effects: adds text or encrypted image clips when the history is on; records refused
     * timestamps without reading clip content.
     * Notes: main thread. Clips marked `EXTRA_IS_SENSITIVE` are ignored without reading URIs.
     */
    fun onPrimaryClip(
        clip: ClipData?,
        allowed: Boolean,
        resolver: ContentResolver? = null,
        onImage: ((Copied) -> Unit)? = null,
    ): Copied? {
        if (clip == null || clip.itemCount == 0) return null
        val description = clip.description
        if (description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true) return null
        val timestamp = description?.timestamp ?: 0L
        val item = clip.getItemAt(0)
        val uri = item.uri
        val imageMime = if (uri != null && resolver != null) {
            normalizeImageMime(runCatching { resolver.getType(uri) }.getOrNull())
                ?: (0 until (description?.mimeTypeCount ?: 0))
                    .firstNotNullOfOrNull { normalizeImageMime(description?.getMimeType(it)) }
        } else {
            null
        }
        if (uri != null && imageMime != null && resolver != null) {
            if (!allowed) {
                if (timestamp > 0 && timestamp != refusedAt) {
                    refusedAt = timestamp
                    scope.launch(serial) { runCatching { refusedFile.parentFile?.mkdirs(); refusedFile.writeText(timestamp.toString()) } }
                }
                return null
            }
            if (timestamp > 0 && timestamp == refusedAt) return null
            if (timestamp > 0 && timestamp == lastImageAt) return null
            if (timestamp > 0) lastImageAt = timestamp
            val atUtcMs = timestamp.takeIf { it > 0 } ?: clock()
            scope.launch(serial) {
                val raw = runCatching {
                    resolver.openInputStream(uri)?.use { readBounded(it, MAX_IMAGE_BYTES) }
                        ?: resolver.openTypedAssetFileDescriptor(uri, imageMime, null)?.use { afd ->
                            afd.createInputStream().use { readBounded(it, MAX_IMAGE_BYTES) }
                        }
                }.getOrNull() ?: return@launch
                val prepared = prepareCapturedImage(raw, imageMime) ?: return@launch
                val copied = recordImageOnSerial(prepared.bytes, prepared.mime, prepared.width, prepared.height, atUtcMs)
                    ?: return@launch
                onImage?.invoke(copied)
            }
            return null
        }
        return onCopy(timestamp, allowed) { item.text?.toString() }
    }

    /**
     * Applies the recording rules to one clip, given by its clipboard timestamp and its text.
     * Inputs: timestamp is `ClipDescription.getTimestamp` (0 when unknown); text is read only
     * when the clip may be taken.
     * Returns: what [add] returns, or null when the clip is refused now or was refused before.
     * Side effects: a refused clip's timestamp is remembered (in memory only, never its text), so
     * the same clip is neither recorded nor offered when the keyboard is next shown in an ordinary
     * field; a newer clip has another timestamp and is treated normally.
     * Notes: main thread. History switched off is not a refusal ([allowed] is true then).
     */
    internal fun onCopy(timestamp: Long, allowed: Boolean, text: () -> String?): Copied? {
        if (!allowed) {
            if (timestamp > 0 && timestamp != refusedAt) {
                refusedAt = timestamp
                scope.launch(serial) { runCatching { refusedFile.parentFile?.mkdirs(); refusedFile.writeText(timestamp.toString()) } }
            }
            return null
        }
        if (timestamp > 0 && timestamp == refusedAt) return null
        return add(text() ?: return null, timestamp.takeIf { it > 0 } ?: clock())
    }

    /**
     * Adds copied [text] (when the history is on) and returns it with its codes.
     * Returns: null for blank or over-long text.
     * Notes: copying text already in the history moves it to the front, keeping its pin; the same
     * clip seen again (same text, no newer time) changes nothing. With the history off nothing is
     * added, but items already saved stay and still expire: the copy runs [expire] instead.
     */
    internal fun add(text: String, atUtcMs: Long): Copied? {
        if (text.isBlank() || text.length > MAX_TEXT) return null
        if (settings().history) {
            change { list ->
                val old = list.firstOrNull { it.text == text }
                if (old != null && old.timeUtcMs >= atUtcMs) return@change list
                val clip = old?.copy(timeUtcMs = atUtcMs) ?: Clip(nextId(list), text, atUtcMs)
                listOf(clip) + list.filterNot { it === old }
            }
        } else {
            expire()
        }
        return Copied(text, VerificationCodes.find(text), atUtcMs)
    }

    /**
     * Adds an encoded image clip on [serial] and returns its in-memory offer, or null when invalid
     * or duplicate at the same timestamp. Pure JVM helper for tests and callers holding bytes.
     */
    internal suspend fun addImage(
        bytes: ByteArray,
        mime: String,
        width: Int,
        height: Int,
        atUtcMs: Long,
    ): Copied? = withContext(serial) { recordImageOnSerial(bytes, mime, width, height, atUtcMs) }

    /** Validates, encrypts, dedupes and stores one image clip; must run on [serial]. */
    private fun recordImageOnSerial(
        bytes: ByteArray,
        mime: String,
        width: Int,
        height: Int,
        atUtcMs: Long,
    ): Copied? {
        val normMime = normalizeImageMime(mime) ?: return null
        if (bytes.size !in 1..MAX_IMAGE_BYTES) return null
        if (width !in 1..MAX_IMAGE_EDGE || height !in 1..MAX_IMAGE_EDGE) return null
        val hash = sha256Hex(bytes)
        val fileName = "$hash.kci"
        if (!settings().history) {
            expireOnSerial()
            return Copied("", emptyList(), atUtcMs, CopiedImage(bytes, normMime, width, height, hash, file = null))
        }
        val oldList = state.value
        val existing = oldList.firstOrNull { it.image?.file == fileName }
        if (existing != null && existing.timeUtcMs >= atUtcMs) return null
        var storedFile: String? = null
        if (writable) {
            val key = try { secret } catch (_: Exception) { writable = false; null }
            if (key != null) {
                val target = File(imagesDir, fileName)
                val wrote = if (target.isFile) true else writeEncryptedImage(target, bytes, key)
                if (!wrote) return null
                storedFile = fileName
            }
        }
        if (storedFile != null) {
            val clip = existing?.copy(timeUtcMs = atUtcMs)
                ?: Clip(nextId(oldList), "", atUtcMs, image = ClipImage(storedFile, normMime, width, height))
            val updated = trim(listOf(clip) + oldList.filterNot { it === existing })
            schedule(updated)
            if (updated != oldList) {
                state.value = updated
                save(updated)
            } else {
                pruneImages(updated)
            }
        }
        return Copied("", emptyList(), atUtcMs, CopiedImage(bytes, normMime, width, height, hash, storedFile))
    }

    /** Decrypts and returns the encoded image bytes of [clip], or null when missing or damaged. */
    suspend fun readImageBytes(clip: Clip): ByteArray? = withContext(serial) {
        val image = clip.image ?: return@withContext null
        val key = try { secret } catch (_: Exception) { writable = false; return@withContext null }
        val imageFile = File(imagesDir, image.file)
        if (!imageFile.isFile) return@withContext null
        runCatching { ClipFile.openBytes(imageFile.readBytes(), key) }.getOrNull()
    }

    /**
     * Loads a downsampled [Bitmap] thumbnail of [clip] cached by size ([maxPx]), decrypting on
     * [serial] and decoding off the main thread. Returns null if missing, non-image or unreadable.
     */
    suspend fun loadThumbnail(clip: Clip, maxPx: Int): Bitmap? {
        val image = clip.image ?: return null
        val boundedPx = maxPx.coerceIn(1, MAX_IMAGE_EDGE)
        val cacheKey = "${image.file}@$boundedPx"
        thumbCache.get(cacheKey)?.let { return it }
        val bytes = readImageBytes(clip) ?: return null
        val bitmap = withContext(Dispatchers.Default) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return@withContext null
            var sample = 1
            while (maxOf(w, h) / (sample * 2) >= boundedPx) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        thumbCache.put(cacheKey, bitmap)
        return bitmap
    }

    /**
     * Prepares a temporary decrypted share file under `<cacheDir>/clipboard_share/` compatible with
     * [acceptedMimes] for `FileProvider` + `commitContent`, sweeping stale temporary share files
     * (`paste_*`) older than 5 minutes or beyond the newest 3.
     *
     * Returns: `(file, commitMime)` or null when the target accepts none of the compatible MIME
     * types or transcoding fails.
     * Notes: writes original bytes directly when [sourceMime] itself matches [acceptedMimes].
     */
    fun prepareShareFile(
        cacheDir: File,
        bytes: ByteArray,
        sourceMime: String,
        acceptedMimes: Collection<String>,
    ): Pair<File, String>? {
        val normSource = normalizeImageMime(sourceMime) ?: return null
        val commitMime = chooseCommitMime(normSource, acceptedMimes) ?: return null
        val payload = if (commitMime == normSource) bytes else transcodeImage(bytes, commitMime) ?: return null
        val shareDir = File(cacheDir, SHARE_DIR).apply { mkdirs() }
        val now = clock()
        val existing = shareDir.listFiles { f -> f.isFile && f.name.startsWith(SHARE_PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        existing.forEachIndexed { idx, old ->
            if (idx >= MAX_SHARE_FILES || now - old.lastModified() > SHARE_TTL_MS) old.delete()
        }
        val seq = synchronized(this) { ++shareSeq }
        val ext = extensionForMime(commitMime)
        val out = File(shareDir, "$SHARE_PREFIX${now}_$seq.$ext")
        return try {
            FileOutputStream(out).use { it.write(payload); it.fd.sync() }
            out to commitMime
        } catch (_: IOException) {
            out.delete()
            null
        }
    }

    /** Pins or unpins an item; pinned items never expire. */
    fun pin(id: Long, pinned: Boolean) = change { list -> list.map { if (it.id == id) it.copy(pinned = pinned) else it } }

    /** Deletes an item. Side effects: rewrites the file without it. */
    fun delete(id: Long) = change { list -> list.filterNot { it.id == id } }

    /** Deletes the items with [ids] at once (the settings' selection). */
    fun delete(ids: Collection<Long>) {
        val set = ids.toSet()
        change { list -> list.filterNot { it.id in set } }
    }

    /**
     * Replaces an item's text, keeping its time, pin and phrase mark (the settings' editor).
     * Notes: blank, over-long text and image clips are ignored; another text item with the same
     * text is merged into it, keeping either one's pin and phrase mark. The tag and codes follow
     * the new text.
     */
    fun edit(id: Long, text: String) {
        if (text.isBlank() || text.length > MAX_TEXT) return
        change { list ->
            val item = list.firstOrNull { it.id == id } ?: return@change list
            if (item.isImage) return@change list
            val twin = list.firstOrNull { !it.isImage && it.text == text && it.id != id }
            list.filterNot { it === twin }.map {
                if (it !== item) it else it.copy(text = text, pinned = it.pinned || twin?.pinned == true, phrase = it.phrase || twin?.phrase == true)
            }
        }
    }

    /** Marks a text item as a quick phrase (kept until removed), or makes it an ordinary item again. */
    fun setPhrase(id: Long, phrase: Boolean) = change { list ->
        list.map { if (it.id == id && !it.isImage) it.copy(phrase = phrase) else it }
    }

    /**
     * Adds a quick phrase (kept until removed), or marks the same text already in the history as one.
     * Notes: for the settings app's phrase editor; works whether the history is on or not.
     */
    fun addPhrase(text: String) {
        if (text.isBlank() || text.length > MAX_TEXT) return
        val now = clock()
        change { list ->
            val old = list.firstOrNull { !it.isImage && it.text == text }
            if (old != null) list.map { if (it === old) it.copy(phrase = true) else it } else listOf(Clip(nextId(list), text, now, phrase = true)) + list
        }
    }

    /** Deletes every item that is neither pinned nor a quick phrase. */
    fun clearUnpinned() = change { list -> list.filter { it.kept } }

    /**
     * Drops unpinned items older than the retention, and the unreadable leftover once it is older too.
     * Side effects: rewrites the file without the expired items (deletes it when nothing is left).
     * Notes: also runs after the file is read, with every change and with every copy while the
     * history is off; call it when the panel opens so an item that expired while the keyboard sat
     * idle is not shown.
     */
    fun expire() {
        change { it }
        scope.launch(serial) { dropStaleLeftover() }
    }

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
            schedule(new)
            if (new == old) return@launch
            state.value = new
            save(new)
        }
    }

    /**
     * Arranges for the oldest unpinned item to be dropped when it expires, while the process
     * lives, so expired text does not wait for the keyboard's next use.
     * Notes: background thread; replaces the previous arrangement. After a restart the file's
     * first read expires what is due.
     */
    private fun schedule(list: List<Clip>) {
        timer?.cancel()
        val oldest = list.filterNot { it.kept }.minOfOrNull { it.timeUtcMs } ?: return
        val wait = (oldest - cutoff()).coerceAtLeast(0) + EXPIRY_GRACE_MS
        timer = scope.launch(serial) {
            delay(wait)
            change { it }
        }
    }

    /** Keeps pinned items and phrases, unpinned items within the retention, at most [MAX_ITEMS] total and [MAX_IMAGES] images. */
    private fun trim(list: List<Clip>): List<Clip> {
        val cutoff = cutoff()
        var unpinned = 0
        var unpinnedImages = 0
        return list.filter { clip ->
            if (clip.kept) return@filter true
            if (clip.timeUtcMs < cutoff) return@filter false
            if (clip.isImage && unpinnedImages >= MAX_IMAGES) return@filter false
            if (unpinned >= MAX_ITEMS) return@filter false
            unpinned++
            if (clip.isImage) unpinnedImages++
            true
        }
    }

    /** Applies expiration and count caps on [serial] and deletes any unreferenced image files. */
    private fun expireOnSerial() {
        val old = state.value
        val updated = trim(old)
        schedule(updated)
        if (updated == old) {
            pruneImages(updated)
            return
        }
        state.value = updated
        save(updated)
    }

    /** Checks bounds of a captured image clip and downscales oversized static images into PNG. */
    private fun prepareCapturedImage(bytes: ByteArray, mime: String): PreparedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w !in 1..MAX_DECODE_EDGE || h !in 1..MAX_DECODE_EDGE) return null
        if (maxOf(w, h) <= MAX_IMAGE_EDGE) return PreparedImage(bytes, mime, w, h)
        if (mime == "image/gif") return null
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= DOWNSCALE_EDGE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return try {
            val out = ByteArrayOutputStream()
            if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            val png = out.toByteArray()
            if (png.size !in 1..MAX_IMAGE_BYTES || bmp.width !in 1..MAX_IMAGE_EDGE || bmp.height !in 1..MAX_IMAGE_EDGE) {
                null
            } else {
                PreparedImage(png, "image/png", bmp.width, bmp.height)
            }
        } finally {
            bmp.recycle()
        }
    }

    /** Encrypts and atomically writes one image container to [target]; returns false on I/O failure. */
    private fun writeEncryptedImage(target: File, bytes: ByteArray, key: SecretKey): Boolean {
        val sealed = ClipFile.sealBytes(bytes, key)
        return try {
            imagesDir.mkdirs()
            val tmp = File(target.path + ".tmp")
            FileOutputStream(tmp).use { it.write(sealed); it.fd.sync() }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: IOException) {
            false
        }
    }

    /** Deletes every file in [imagesDir] not referenced by [kept], removing [imagesDir] when empty. */
    private fun pruneImages(kept: List<Clip>) {
        if (!writable) return
        val wanted = kept.mapNotNullTo(HashSet()) { it.image?.file }
        val files = imagesDir.listFiles() ?: return
        for (f in files) {
            if (f.name !in wanted) f.delete()
        }
        if (imagesDir.listFiles()?.isEmpty() == true) imagesDir.delete()
    }

    /** Transcodes [bytes] into [targetMime] (`image/png` or `image/jpeg`) for fallback commitContent targets. */
    private fun transcodeImage(bytes: ByteArray, targetMime: String): ByteArray? {
        val format = when (targetMime) {
            "image/png" -> Bitmap.CompressFormat.PNG
            "image/jpeg" -> Bitmap.CompressFormat.JPEG
            else -> return null
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            if (!bmp.compress(format, 92, out)) return null
            out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }

    /** Returns the UTC milliseconds before which unpinned items have expired. */
    private fun cutoff() = clock() - settings().retentionDays.coerceIn(Settings.RETENTION_RANGE) * DAY_MS

    private fun nextId(list: List<Clip>) = (list.maxOfOrNull { it.id } ?: 0) + 1

    /**
     * Reads the file into [items].
     * Side effects: when items in the file have expired (or exceed the cap), rewrites it without
     * them, or deletes it when nothing is left, so expired text does not stay on disk.
     * Notes: a file from a newer container is left untouched: the history starts empty and is
     * never written in this process, so newer data is never overwritten. A file
     * that cannot be decrypted or parsed (a lost key, damage) is moved aside to `.unreadable`,
     * replacing an older one, and the history starts empty. When the Keystore cannot give the
     * key, the history works in memory only for this process.
     */
    private fun load() {
        // A write interrupted by a crash leaves its temporary file; the real file is intact.
        File(file.path + ".tmp").delete()
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
        val read = records.drop(1).mapNotNull(::clipOf).sortedByDescending { it.timeUtcMs }
        val kept = trim(read)
        state.value = kept
        schedule(kept)
        if (kept.size != read.size) save(kept) else pruneImages(kept)
    }

    /**
     * Writes [list] through a temporary file and an atomic replace, and prunes orphaned image files.
     * Side effects: deletes `history.kcb` instead when [list] is empty.
     */
    private fun save(list: List<Clip>) {
        if (!writable) return
        pruneImages(list)
        if (list.isEmpty()) {
            file.delete()
            return
        }
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

    /**
     * Moves the unreadable history file to [leftover], replacing an older leftover, and removes
     * any unreadable image files alongside it.
     * Notes: the move keeps the file's modification time (the history's last write), which
     * [dropStaleLeftover] goes by.
     */
    private fun setAside() {
        try {
            Files.move(file.toPath(), leftover.toPath(), StandardCopyOption.REPLACE_EXISTING)
            pruneImages(emptyList())
        } catch (_: IOException) {
            writable = false
        }
    }

    /**
     * Deletes [leftover] once its modification time is older than the retention.
     * Notes: every unpinned item it holds was copied before that time, so by then all of them
     * would have expired. Background thread.
     */
    private fun dropStaleLeftover() {
        val modified = leftover.lastModified() // 0 when there is none
        if (modified != 0L && modified < cutoff()) leftover.delete()
    }

    /** Prepared static/GIF image validated for storage in [ClipboardHistory]. */
    private class PreparedImage(val bytes: ByteArray, val mime: String, val width: Int, val height: Int)

    companion object {
        /** The extra that marks a sensitive clip (`ClipDescription.EXTRA_IS_SENSITIVE`, API 33; apps set it earlier too). */
        private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
        /** Unpinned items kept at most. */
        const val MAX_ITEMS = 200
        /** Unpinned image clips kept at most. */
        const val MAX_IMAGES = 30
        /** Longer text clips are not recorded. */
        const val MAX_TEXT = 20_000
        /** Larger copied images (bytes) are not recorded. */
        const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
        /** Maximum stored pixel edge; static images larger than this are downscaled to [DOWNSCALE_EDGE]. */
        const val MAX_IMAGE_EDGE = 4096
        private const val MAX_DECODE_EDGE = 16384
        private const val DOWNSCALE_EDGE = 2048
        private const val THUMB_CACHE_BYTES = 4 * 1024 * 1024
        private const val SHARE_DIR = "clipboard_share"
        private const val SHARE_PREFIX = "paste_"
        private const val SHARE_TTL_MS = 5 * 60_000L
        private const val MAX_SHARE_FILES = 3
        private const val DAY_MS = 86_400_000L
        /** How long after an item's expiry the arranged check runs. */
        private const val EXPIRY_GRACE_MS = 500L
        private const val FORMAT = "format_version"
        private const val UPDATED = "updated_utc_ms"
        private const val KEY_ALIAS = "kotomo_clipboard"
        private const val FILE = "clipboard/history.kcb"
        val SUPPORTED_IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
        private val IMAGE_FILE_PATTERN = Regex("^[0-9a-f]{64}\\.kci$")

        /**
         * Normalizes a MIME string (`image/jpg` -> `image/jpeg`, trims `;...` parameters) and
         * returns it iff supported ([SUPPORTED_IMAGE_MIMES]), or null otherwise.
         */
        fun normalizeImageMime(mime: String?): String? {
            val base = mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            val norm = if (base == "image/jpg") "image/jpeg" else base
            return norm.takeIf { it in SUPPORTED_IMAGE_MIMES }
        }

        /**
         * Returns true when concrete [mime] matches target MIME [pattern] (universal wildcard,
         * type wildcard, or exact match, ignoring case and `;...` parameters).
         */
        fun mimeMatches(mime: String, pattern: String): Boolean {
            val m = mime.substringBefore(';').trim().lowercase()
            val p = pattern.substringBefore(';').trim().lowercase()
            if (m.isEmpty() || p.isEmpty()) return false
            if (p == "*/*") return true
            val mSlash = m.indexOf('/')
            val pSlash = p.indexOf('/')
            if (mSlash <= 0 || pSlash <= 0) return false
            if (p.substring(pSlash + 1) == "*") {
                return m.substring(0, mSlash) == p.substring(0, pSlash)
            }
            return m == p
        }

        /**
         * Chooses the MIME type to pass to `InputConnectionCompat.commitContent`: prefers [sourceMime],
         * else for static images falls back to `image/png` or `image/jpeg` when accepted.
         */
        fun chooseCommitMime(sourceMime: String, acceptedMimes: Collection<String>): String? {
            val norm = normalizeImageMime(sourceMime) ?: return null
            if (acceptedMimes.any { mimeMatches(norm, it) }) return norm
            if (norm == "image/gif") return null
            if (acceptedMimes.any { mimeMatches("image/png", it) }) return "image/png"
            if (acceptedMimes.any { mimeMatches("image/jpeg", it) }) return "image/jpeg"
            return null
        }

        /** Returns the lower-case SHA-256 hex digest of [bytes]. */
        fun sha256Hex(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        /**
         * Reads [input] up to [maxBytes] bytes; returns null when empty or when [input] exceeds
         * [maxBytes].
         */
        fun readBounded(input: InputStream, maxBytes: Int): ByteArray? {
            if (maxBytes <= 0) return null
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) return null
                out.write(buf, 0, n)
            }
            return if (total == 0) null else out.toByteArray()
        }

        private fun extensionForMime(mime: String): String = when (mime) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "bin"
        }

        /** Reads one item record; null when its required fields are missing or malformed. */
        private fun clipOf(r: Map<String, String>): Clip? {
            val id = r["id"]?.toLongOrNull() ?: return null
            val time = r["time_utc_ms"]?.toLongOrNull() ?: return null
            val imageFile = r["image"]
            if (imageFile != null) {
                if (!IMAGE_FILE_PATTERN.matches(imageFile)) return null
                val mime = normalizeImageMime(r["mime"]) ?: return null
                val width = r["width"]?.toIntOrNull()?.takeIf { it in 1..MAX_IMAGE_EDGE } ?: return null
                val height = r["height"]?.toIntOrNull()?.takeIf { it in 1..MAX_IMAGE_EDGE } ?: return null
                return Clip(
                    id = id,
                    text = "",
                    timeUtcMs = time,
                    pinned = r["pinned"] == "1",
                    phrase = false,
                    extra = r - KNOWN,
                    image = ClipImage(imageFile, mime, width, height),
                )
            }
            val text = r["text"]?.takeIf { it.isNotBlank() } ?: return null
            return Clip(id, text, time, r["pinned"] == "1", r["phrase"] == "1", r - KNOWN)
        }

        private val KNOWN = setOf("id", "text", "time_utc_ms", "pinned", "phrase", "image", "mime", "width", "height")

        /** Writes one item record, its unknown fields kept. */
        private fun recordOf(c: Clip): Map<String, String> = c.extra + buildMap {
            put("id", c.id.toString())
            put("text", c.text)
            put("time_utc_ms", c.timeUtcMs.toString())
            put("pinned", if (c.pinned) "1" else "0")
            put("phrase", if (c.phrase && !c.isImage) "1" else "0")
            c.image?.let { img ->
                put("image", img.file)
                put("mime", img.mime)
                put("width", img.width.toString())
                put("height", img.height.toString())
            }
        }

        /**
         * Decides whether the keyboard may record or offer clips now.
         * Inputs: info is the current field (null when no field is bound).
         * Returns: false in incognito mode, in sensitive fields ([Fields.sensitive]) and in the
         * fields of an app the person excluded (`clipboard.excluded_apps`); true otherwise (URL
         * and e-mail fields included, unlike direct typing).
         * Notes: the app is the one the keyboard is typing in when it sees the clip; Android does
         * not tell an input method which app the text was copied from.
         */
        fun allowedIn(info: EditorInfo?, settings: Settings): Boolean =
            if (info == null) !settings.incognito else allowedIn(info.inputType, info.imeOptions, info.packageName, settings)

        /** [allowedIn] over the field's input type, options and app (pure, unit-tested). */
        internal fun allowedIn(inputType: Int, imeOptions: Int, packageName: String?, settings: Settings): Boolean =
            !settings.incognito && !Fields.sensitive(inputType, imeOptions) && packageName !in settings.clipboard.excludedApps

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
