package com.yuanzhe.kotomo.dictionary

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * Opens a URL for reading: the online client's only contact with the network, so tests answer
 * with a fake (by the full URL) and no real transport exists yet.
 *
 * Notes: Kotomo has no transport of its own: the first network feature Kotomo itself starts
 * waits for the user's decision on where packs are hosted and how the first use is disclosed
 * (PLAN §2.5 decisions 2 and 10, §14). Until then [Online.CATALOG_URL] is null and nothing calls it.
 */
fun interface Transport {
    /** Opens [url]; throws [IOException] when it cannot be reached. */
    fun open(url: String): InputStream
}

/** One dictionary pack as the catalog lists it; every member is required, others are kept in [extra]. */
data class CatalogPack(
    val id: String,
    val titles: Map<String, String>,
    val lang: Lang,
    val category: String,
    val license: String,
    val attribution: String,
    val version: String,
    val size: Long,
    val url: String,
    val sha256: String,
    val format: String,
    val extra: Map<String, Any?> = emptyMap(),
) {
    /** The pack's title in the interface [language] (`en`, `zh-CN`, `zh-TW`, `ja`), else English, else any. */
    fun title(language: String): String = titles[language] ?: titles["en"] ?: titles.values.first()

    /** The pack as the catalog's JSON object, unknown members included. */
    fun toJson(): Map<String, Any?> = LinkedHashMap<String, Any?>(extra).also {
        it["id"] = id; it["titles"] = titles; it["language"] = lang.code; it["category"] = category
        it["license"] = license; it["attribution"] = attribution; it["version"] = version; it["size"] = size
        it["url"] = url; it["sha256"] = sha256; it["format"] = format
    }
}

/** A catalog: its packs, how many listed packs were refused, and its unknown members. */
data class Catalog(val packs: List<CatalogPack>, val refused: Int, val extra: Map<String, Any?> = emptyMap())

/** Why a catalog or a pack could not be used. */
enum class OnlineProblem { CATALOG, CATALOG_VERSION, NETWORK, SIZE, HASH, UNPACK }

/** Thrown by [Online] with the reason. */
class OnlineException(val problem: OnlineProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/**
 * The online dictionary client (PLAN §10.5): the catalog format, downloads checked against the
 * catalog's size and SHA-256 with progress, and pack files read like an import. Pure Kotlin over
 * a [Transport], unit-tested with a fake one.
 *
 * Notes: signatures are not checked yet; the catalog's SHA-256 is the only integrity check (PLAN
 * §2.5 decision 9). Everything from the network is untrusted: the catalog's members are checked
 * one by one, a pack's id must be a safe directory name, its URL must use HTTPS, and a pack never
 * unpacks to more than [MAX_UNPACKED] bytes.
 */
object Online {
    /**
     * Where the catalog lives: not decided (PLAN §2.5 decisions 2 and 10). While it is null the
     * settings say that online dictionaries are not available and nothing is downloaded.
     */
    val CATALOG_URL: String? = null

    /** The catalog format version this build reads. */
    const val CATALOG_VERSION = 1
    /** The only pack format so far: gzip-compressed TSV with the header [Formats.COLUMNS]. */
    const val PACK_FORMAT = "tsv.gz"
    /** The largest pack accepted, compressed and unpacked. */
    const val MAX_SIZE = 64L * 1024 * 1024
    const val MAX_UNPACKED = 256L * 1024 * 1024

    private val ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private val SHA256 = Regex("[0-9a-f]{64}")

    /**
     * Reads a catalog.
     * Returns: the packs that carry every required member with a valid value (id, titles,
     * language, category, license, attribution, version, size, url, sha256, format); the others
     * are counted in [Catalog.refused]. Throws [OnlineException]: CATALOG when the text is not a
     * catalog, CATALOG_VERSION when its `format_version` is missing or not this build's.
     */
    fun catalog(text: String): Catalog {
        @Suppress("UNCHECKED_CAST")
        val root = runCatching { Json.parse(text) }.getOrNull() as? Map<String, Any?> ?: throw OnlineException(OnlineProblem.CATALOG)
        if (root.long("format_version") != CATALOG_VERSION.toLong()) throw OnlineException(OnlineProblem.CATALOG_VERSION)
        val listed = root.list("packs") ?: throw OnlineException(OnlineProblem.CATALOG)
        @Suppress("UNCHECKED_CAST")
        val packs = listed.map { (it as? Map<String, Any?>)?.let(::pack) }
        val ids = HashSet<String>()
        val unique = packs.filterNotNull().filter { ids.add(it.id) }
        return Catalog(unique, listed.size - unique.size, root.filterKeys { it != "packs" })
    }

    /** Reads one catalog pack, or null when a required member is missing or invalid. */
    fun pack(o: Map<String, Any?>): CatalogPack? {
        val titles = o.obj("titles")?.mapNotNull { (k, v) -> (v as? String)?.takeIf { it.isNotBlank() }?.let { k to it } }?.toMap()
        return CatalogPack(
            id = o.str("id")?.takeIf { ID.matches(it) } ?: return null,
            titles = titles?.takeIf { it.isNotEmpty() } ?: return null,
            lang = Lang.of(o.str("language")) ?: return null,
            category = o.str("category")?.takeIf { it.isNotBlank() } ?: return null,
            license = o.str("license")?.takeIf { it.isNotBlank() } ?: return null,
            attribution = o.str("attribution")?.takeIf { it.isNotBlank() } ?: return null,
            version = o.str("version")?.takeIf { it.isNotBlank() } ?: return null,
            size = o.long("size")?.takeIf { it in 1..MAX_SIZE } ?: return null,
            url = o.str("url")?.takeIf { it.startsWith("https://") } ?: return null,
            sha256 = o.str("sha256")?.takeIf { SHA256.matches(it) } ?: return null,
            format = o.str("format")?.takeIf { it == PACK_FORMAT } ?: return null,
            extra = o.filterKeys { it !in KNOWN },
        )
    }

    private val KNOWN = setOf("id", "titles", "language", "category", "license", "attribution", "version", "size", "url", "sha256", "format")

    /**
     * Downloads [pack] and checks it.
     * Inputs: progress receives the bytes read so far and the catalog's size, after each chunk.
     * Returns: the compressed bytes, exactly the catalog's size with its SHA-256. Throws
     * [OnlineException]: NETWORK when the transport fails, SIZE when more or fewer bytes arrive
     * than listed (reading stops as soon as there are too many), HASH when the digest differs.
     * Notes: blocking; call it on the IO dispatcher.
     */
    fun download(pack: CatalogPack, transport: Transport, progress: (Long, Long) -> Unit): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val out = ByteArrayOutputStream()
        try {
            transport.open(pack.url).use { input ->
                val buffer = ByteArray(CHUNK)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > pack.size) throw OnlineException(OnlineProblem.SIZE)
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                    progress(total, pack.size)
                }
                if (total != pack.size) throw OnlineException(OnlineProblem.SIZE)
            }
        } catch (e: IOException) {
            throw OnlineException(OnlineProblem.NETWORK, e)
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != pack.sha256) throw OnlineException(OnlineProblem.HASH)
        return out.toByteArray()
    }

    /**
     * Unpacks and reads a downloaded pack.
     * Returns: the preview of its lines read like an imported TSV in the pack's language, every
     * entry marked [Source.DOWNLOAD]; lines that fail a check, or name another language, are
     * dropped and counted. Throws [OnlineException] UNPACK when it is not gzip or unpacks to more
     * than [MAX_UNPACKED] bytes, and for an undecodable file.
     */
    fun read(pack: CatalogPack, compressed: ByteArray): ImportPreview {
        val bytes = try {
            GZIPInputStream(compressed.inputStream()).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(CHUNK)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_UNPACKED) throw OnlineException(OnlineProblem.UNPACK)
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            throw OnlineException(OnlineProblem.UNPACK, e)
        }
        val read = try {
            Formats.read(bytes, Format.TSV, pack.lang, generated = false)
        } catch (e: ImportException) {
            throw OnlineException(OnlineProblem.UNPACK, e)
        }
        val (ours, others) = read.entries.partition { it.lang == pack.lang }
        return read.copy(entries = ours.map { it.copy(source = Source.DOWNLOAD) }, refused = read.refused + others.size)
    }

    private const val CHUNK = 64 * 1024
}
