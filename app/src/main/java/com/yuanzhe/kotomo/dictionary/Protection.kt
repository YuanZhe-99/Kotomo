package com.yuanzhe.kotomo.dictionary

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Why a protected file could not be opened. */
enum class SealProblem { WRONG_PASSWORD, DAMAGED, NEWER }

/** Thrown by [Protection] with the reason. */
class SealException(val problem: SealProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/**
 * Encrypts the person's dictionary with a key derived from a password they choose (PLAN §14,
 * ADR-012): `user.kenc`, `user.prev.kenc` and protected exports. Pure JVM code, unit-tested.
 *
 * Notes: PBKDF2 with HMAC-SHA256 ([ITERATIONS] rounds, a random 16-byte salt) derives an
 * AES-256 key; each file is AES-256-GCM with a fresh 12-byte IV and a 128-bit tag, the header
 * being the associated data, so a changed header fails like a wrong password. The header carries
 * the salt and the rounds, so a protected export opens anywhere with the password alone. The
 * password is never stored; nothing recovers a forgotten one. Layout in `doc/en-us/dictionaries.md`.
 */
object Protection {
    /** The container version this build writes and reads; a newer one is refused, never overwritten. */
    const val VERSION = 1
    /** PBKDF2 rounds for new protection (OWASP's 2023 figure for PBKDF2-HMAC-SHA256). */
    const val ITERATIONS = 600_000
    private val MAGIC = "KDIC".toByteArray(Charsets.US_ASCII)
    private const val SALT = 16
    private const val IV = 12
    private const val TAG_BITS = 128
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val MIN_ITERATIONS = 1_000
    private const val MAX_ITERATIONS = 10_000_000

    /** What a protected file's header says: its version, the key's salt and rounds, and what it holds. */
    data class Header(val version: Int, val iterations: Int, val salt: ByteArray, val kind: String, val length: Int)

    /** A new random salt. */
    fun salt(): ByteArray = ByteArray(SALT).also { SecureRandom().nextBytes(it) }

    /**
     * Derives the key for [password] and [salt].
     * Notes: slow on purpose (about a second on a phone at [ITERATIONS]); call it off the main thread.
     */
    fun derive(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Whether [bytes] start like a protected file. */
    fun isSealed(bytes: ByteArray): Boolean = bytes.size > MAGIC.size && bytes.copyOf(MAGIC.size).contentEquals(MAGIC)

    /**
     * Encrypts [plain] with [key] (derived from [salt] and [iterations]).
     * Inputs: kind names the content (`user`, `check`, or an export's format extension).
     */
    fun seal(plain: ByteArray, key: SecretKey, salt: ByteArray, iterations: Int, kind: String): ByteArray {
        val header = ByteArrayOutputStream().also { b ->
            DataOutputStream(b).use { d ->
                d.write(MAGIC); d.writeByte(VERSION); d.writeInt(iterations)
                d.writeByte(salt.size); d.write(salt)
                val k = kind.toByteArray(Charsets.UTF_8)
                d.writeByte(k.size); d.write(k)
            }
        }.toByteArray()
        val iv = ByteArray(IV).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(CIPHER).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(header)
        }
        return header + iv + cipher.doFinal(plain)
    }

    /**
     * Reads a protected file's header.
     * Throws [SealException]: DAMAGED when it is not one or is cut short, NEWER for a newer version.
     */
    fun header(bytes: ByteArray): Header {
        if (!isSealed(bytes)) throw SealException(SealProblem.DAMAGED)
        try {
            DataInputStream(bytes.inputStream()).use { d ->
                d.skipBytes(MAGIC.size)
                val version = d.readUnsignedByte()
                if (version > VERSION) throw SealException(SealProblem.NEWER)
                val iterations = d.readInt().takeIf { it in MIN_ITERATIONS..MAX_ITERATIONS } ?: throw SealException(SealProblem.DAMAGED)
                val salt = ByteArray(d.readUnsignedByte()).also { d.readFully(it) }
                val kind = ByteArray(d.readUnsignedByte()).also { d.readFully(it) }.toString(Charsets.UTF_8)
                val length = MAGIC.size + 1 + 4 + 1 + salt.size + 1 + kind.toByteArray(Charsets.UTF_8).size
                if (salt.size != SALT || bytes.size < length + IV + TAG_BITS / 8) throw SealException(SealProblem.DAMAGED)
                return Header(version, iterations, salt, kind, length)
            }
        } catch (e: IOException) {
            throw SealException(SealProblem.DAMAGED, e)
        }
    }

    /**
     * Decrypts a protected file with [key].
     * Throws [SealException]: WRONG_PASSWORD when the tag does not verify (a wrong key, or a
     * changed file: GCM cannot tell them apart), DAMAGED or NEWER from [header].
     */
    fun open(bytes: ByteArray, key: SecretKey): ByteArray {
        val h = header(bytes)
        try {
            val cipher = Cipher.getInstance(CIPHER).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, h.length, IV))
                updateAAD(bytes, 0, h.length)
            }
            return cipher.doFinal(bytes, h.length + IV, bytes.size - h.length - IV)
        } catch (e: GeneralSecurityException) {
            throw SealException(SealProblem.WRONG_PASSWORD, e)
        }
    }

    /** Decrypts a protected file (an export from anywhere) with [password], deriving the key from its header. */
    fun open(bytes: ByteArray, password: CharArray): ByteArray {
        val h = header(bytes)
        return open(bytes, derive(password, h.salt, h.iterations))
    }
}
